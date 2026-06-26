package com.kxxnzstdsw.export

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 导出子进程管理器
 *
 * 负责：
 * 1. 启动独立的导出子进程
 * 2. 通过 stdin/stdout 发送命令和接收响应
 * 3. 子进程 stdout 输出通过 responseBuffer Channel 转发到主进程的 GlobalOutputChannel
 * 4. 支持停止指定导出任务
 * 5. 主进程关闭时自动停止子进程
 */
object ExportProcessManager {

    private val logger = LoggerFactory.getLogger(ExportProcessManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // 子进程进程对象
    @Volatile
    private var process: Process? = null

    // 子进程 stdin writer
    @Volatile
    private var stdinWriter: OutputStreamWriter? = null

    // 子进程 stdout reader
    @Volatile
    private var stdoutReader: BufferedReader? = null

    // 子进程是否运行中
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // 内部缓冲 Channel：readOutputLoop 写入，forwardResponses 读取后发送到 GlobalOutputChannel
    private val responseBuffer = Channel<String>(Channel.UNLIMITED)

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
                jars?.joinToString(java.io.File.pathSeparator) { it.absolutePath }
                    ?.let { "$jarPath${java.io.File.pathSeparator}$it" }
                    ?: jarPath
            } else {
                jarPath
            }
        } else {
            jarPath
        }

        logger.info("Starting export subprocess with classpath from: ${libsDir ?: "."}")

        try {
            val builder = ProcessBuilder(
                "java",
                "-Xmx1024m",                      // 限制内存，防止导出任务耗尽主进程内存
                "-Xms256m",
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
            stdinWriter = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
            stdoutReader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))

            // 启动读取协程，读取子进程 stdout 写入 responseBuffer Channel
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
     * 读取子进程 stdout，每行写入 responseBuffer Channel
     */
    private suspend fun readOutputLoop() {
        val reader = stdoutReader ?: return
        try {
            var line: String?
            while (withContext(Dispatchers.IO) {
                    reader.readLine()
                }.also { line = it } != null) {
                line?.let {
                    val len = it.length
                    logger.debug("Subprocess stdout (len=$len): {}", it)
                    responseBuffer.send(it)
                }
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
            for (response: String in responseBuffer) {
                val ch = GlobalOutputChannel.channel
                if (ch != null) {
                    ch.send(response)
                    logger.debug("Forwarded subprocess response to GlobalOutputChannel (len=${response.length})")
                } else {
                    logger.warn("GlobalOutputChannel is null, dropping subprocess response (len=${response.length})")
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
     * @param payload 导出参数
     */
    fun startExport(id: String, connection: JsonObject, payload: JsonObject) {
        val writer = stdinWriter
        if (writer == null || !isRunning.value) {
            logger.error("Cannot start export: subprocess not running")
            return
        }

        val command = buildJsonObject {
            put("CMD", "START_EXPORT")
            put("id", id)
            put("connection", connection)
            put("payload", payload)
        }

        try {
            synchronized(this) {
                writer.write(json.encodeToString(JsonElement.serializer(), command))
                writer.write("\n")
                writer.flush()
            }
            logger.info("Sent START_EXPORT command: $id")
        } catch (e: Exception) {
            logger.error("Failed to send START_EXPORT command", e)
        }
    }

    /**
     * 停止指定的导出任务
     *
     * @param exportId 导出任务 ID
     */
    fun stopExport(exportId: String) {
        val writer = stdinWriter
        if (writer == null || !isRunning.value) {
            logger.warn("Cannot stop export: subprocess not running")
            return
        }

        val command = buildJsonObject {
            put("CMD", "STOP_EXPORT")
            put("exportId", exportId)
        }

        try {
            synchronized(this) {
                writer.write(json.encodeToString(JsonElement.serializer(), command))
                writer.write("\n")
                writer.flush()
            }
            logger.info("Sent STOP_EXPORT command: $exportId")
        } catch (e: Exception) {
            logger.error("Failed to send STOP_EXPORT command", e)
        }
    }

    /**
     * 停止子进程
     */
    fun stop() {
        logger.info("Stopping export subprocess...")

        _isRunning.value = false

        // 关闭缓冲 Channel（如果 readOutputLoop 还未关闭）
        responseBuffer.close()

        try {
            // 发送退出命令
            stdinWriter?.let { writer ->
                try {
                    synchronized(this) {
                        writer.write("{\"CMD\":\"CMD_EXIT\"}\n")
                        writer.flush()
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            logger.warn("Error sending exit command", e)
        }

        // 关闭流
        try { stdinWriter?.close() } catch (e: Exception) { /* ignore */ }
        try { stdoutReader?.close() } catch (e: Exception) { /* ignore */ }
        stdinWriter = null
        stdoutReader = null

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
     * 确保进程已停止
     */
    fun ensureStopped() {
        if (isRunning.value || process?.isAlive == true) {
            stop()
        }
    }
}
