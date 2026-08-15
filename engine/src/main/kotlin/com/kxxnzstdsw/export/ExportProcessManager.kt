package com.kxxnzstdsw.export

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.proto.PayloadValue
import com.kxxnzstdsw.transport.Framing
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 导出子进程管理器
 *
 * 负责：
 * 1. 启动独立的导出子进程
 * 2. 通过 stdin/stdout 发送命令帧（protobuf + 长度前缀）和接收响应帧
 * 3. 子进程 stdout 输出通过 responseBuffer Channel 转发到主进程的 GlobalOutputChannel
 * 4. 支持停止指定导出任务
 * 5. 主进程关闭时自动停止子进程
 *
 * wire 格式：4 字节 BE 长度 + protobuf 编码字节（与主进程一致）
 */
object ExportProcessManager {

    private val logger = LoggerFactory.getLogger(ExportProcessManager::class.java)

    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { encodeDefaults = true }

    // 子进程进程对象
    @Volatile
    private var process: Process? = null

    // 子进程 stdin（写字节）
    @Volatile
    private var stdinStream: OutputStream? = null

    // 子进程 stdout（读字节）
    @Volatile
    private var stdoutStream: InputStream? = null

    // 子进程是否运行中
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // 子进程 stdin 互斥锁（writeFrame 不是线程安全，多个 startExport 调用需串行）
    private val stdinLock = Any()

    // 内部缓冲 Channel：readOutputLoop 写入，forwardResponses 读取后发送到 GlobalOutputChannel
    private val responseBuffer = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * 启动导出子进程
     *
     * @param jarPath idb-engine.jar 的路径
     */
    fun start(jarPath: String) {
        if (isRunning.value) {
            logger.warn("Export subprocess already running")
            return
        }

        val libsDir = File(jarPath).parentFile?.absoluteFile
        val classPath = if (libsDir != null) {
            val libs = File(libsDir, "libs")
            if (libs.exists()) {
                val jars = libs.listFiles { f -> f.extension == "jar" }
                jars?.joinToString(File.pathSeparator) { it.absolutePath }
                    ?.let { "$jarPath${File.pathSeparator}$it" }
                    ?: jarPath
            } else {
                jarPath
            }
        } else {
            jarPath
        }

        logger.info("Starting export subprocess with classpath from: ${libsDir ?: "."}")

        try {
            // 子进程复用父进程的 JRE，最大堆与父进程一致（最低 256m）
            val javaHome = System.getProperty("java.home")
            val javaExe: String = File(File(javaHome), "bin/java").takeIf { it.exists() }?.absolutePath
                ?: File(File(javaHome), "bin/java.exe").takeIf { it.exists() }?.absolutePath
                ?: "java"
            val parentMaxMem = Runtime.getRuntime().maxMemory()
            val childMaxMem = maxOf(parentMaxMem, 256L * 1024 * 1024)

            val builder = ProcessBuilder(
                javaExe,
                "-Xmx${formatMem(childMaxMem)}",
                "-Xms${formatMem(childMaxMem)}",
                "-XX:+UseSerialGC",
                "-Dexport.subprocess=true",
                "-cp", classPath,
                "com.kxxnzstdsw.export.ExportSubProcess"
            )
            builder.directory(libsDir ?: File("."))
            builder.redirectErrorStream(false)

            // Windows + Parquet: 设置 HADOOP_HOME 指向 libs/ 目录（其下 bin/winutils.exe 由 Gradle 构建下载）
            if (System.getProperty("os.name").lowercase().contains("win") && libsDir != null) {
                builder.environment()["HADOOP_HOME"] = libsDir.absolutePath
                builder.environment()["hadoop.home.dir"] = libsDir.absolutePath
            }

            process = builder.start()
            stdinStream = process!!.outputStream
            stdoutStream = process!!.inputStream

            // 启动读取协程，读取子进程 stdout 帧写入 responseBuffer Channel
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                readOutputLoop()
            }

            // 启动转发协程，从 responseBuffer Channel 转发到 GlobalOutputChannel
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                forwardResponses()
            }

            // 监控进程退出
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                process?.waitFor()
                logger.info("Export subprocess exited with code: ${process?.exitValue()}")
                stop()
            }

            _isRunning.value = true
            logger.info("Export subprocess started successfully")

        } catch (e: Exception) {
            logger.error("Failed to start export subprocess", e)
            stop()
        }
    }

    /**
     * 读取子进程 stdout，每帧（4B length + protobuf bytes）写入 responseBuffer Channel
     */
    private suspend fun readOutputLoop() {
        val input = stdoutStream ?: return
        try {
            while (true) {
                val frame = withContext(Dispatchers.IO) {
                    Framing.readFrame(input)
                } ?: break  // EOF
                logger.debug("Subprocess stdout frame received (${frame.size} bytes)")
                responseBuffer.send(frame)
            }
        } catch (e: Exception) {
            logger.error("Error reading subprocess output", e)
        } finally {
            // 子进程 stdout 关闭时，关闭 responseBuffer，通知 forwardResponses 结束
            responseBuffer.close()
        }
    }

    /**
     * 从 responseBuffer Channel 读取，转发到主进程的 GlobalOutputChannel
     * 保证子进程响应走主进程统一的 stdout 串行化输出管线
     */
    private suspend fun forwardResponses() {
        try {
            for (frame in responseBuffer) {
                val ch = GlobalOutputChannel.channel
                if (ch != null) {
                    ch.send(frame)
                    logger.debug("Forwarded subprocess frame to GlobalOutputChannel (${frame.size} bytes)")
                } else {
                    logger.warn("GlobalOutputChannel is null, dropping subprocess frame (${frame.size} bytes)")
                }
            }
        } catch (e: Exception) {
            logger.error("Error forwarding responses to GlobalOutputChannel", e)
        }
    }

    /**
     * 发送导出命令到子进程
     *
     * @param id 请求 ID
     * @param connection 数据库连接配置
     * @param payload 导出参数（proto payload：Map<String, PayloadValue>）
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun startExport(id: String, connection: ConnectionConfig, payload: Map<String, PayloadValue>) {
        val stream = stdinStream
        if (stream == null || !isRunning.value) {
            logger.error("Cannot start export: subprocess not running")
            return
        }

        val cmd = ExportCommand(
            kind = ExportCommandKind.START_EXPORT,
            id = id,
            connection = connection,
            payload = payload
        )
        val frame = proto.encodeToByteArray(ExportCommand.serializer(), cmd)

        try {
            synchronized(stdinLock) {
                Framing.writeFrame(stream, frame)
                stream.flush()
            }
            logger.info("Sent START_EXPORT command: $id (${frame.size} bytes)")
        } catch (e: Exception) {
            logger.error("Failed to send START_EXPORT command", e)
        }
    }

    /**
     * 停止指定的导出任务
     *
     * @param exportId 导出任务 ID
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun stopExport(exportId: String) {
        val stream = stdinStream
        if (stream == null || !isRunning.value) {
            logger.warn("Cannot stop export: subprocess not running")
            return
        }

        val cmd = ExportCommand(
            kind = ExportCommandKind.STOP_EXPORT,
            exportId = exportId
        )
        val frame = proto.encodeToByteArray(ExportCommand.serializer(), cmd)

        try {
            synchronized(stdinLock) {
                Framing.writeFrame(stream, frame)
                stream.flush()
            }
            logger.info("Sent STOP_EXPORT command: $exportId")
        } catch (e: Exception) {
            logger.error("Failed to send STOP_EXPORT command", e)
        }
    }

    /**
     * 停止子进程
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun stop() {
        logger.info("Stopping export subprocess...")

        _isRunning.value = false

        // 关闭缓冲 Channel（如果 readOutputLoop 还未关闭）
        responseBuffer.close()

        try {
            // 发送退出命令
            val stream = stdinStream
            if (stream != null) {
                try {
                    val cmd = ExportCommand(kind = ExportCommandKind.CMD_EXIT)
                    val frame = proto.encodeToByteArray(ExportCommand.serializer(), cmd)
                    synchronized(stdinLock) {
                        Framing.writeFrame(stream, frame)
                        stream.flush()
                    }
                } catch (_: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            logger.warn("Error sending exit command", e)
        }

        // 关闭流
        try { stdinStream?.close() } catch (_: Exception) { /* ignore */ }
        try { stdoutStream?.close() } catch (_: Exception) { /* ignore */ }
        stdinStream = null
        stdoutStream = null

        // 销毁进程
        process?.let { p ->
            if (p.isAlive) {
                p.destroyForcibly()
            }
        }
        process = null

        logger.info("Export subprocess stopped")
    }

    /**
     * 将字节数格式化为 -Xmx/-Xms 的参数格式，如 536870912 -> "512m"
     */
    private fun formatMem(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024 * 1024)}g"
            else -> "${bytes / (1024 * 1024)}m"
        }
    }
}