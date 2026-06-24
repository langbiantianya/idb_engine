package com.kxxnzstdsw.export

import com.kxxnzstdsw.models.Response
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 3. 支持停止指定导出任务
 * 4. 主进程关闭时自动停止子进程
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

    // 协程作用域
    private var processScope: CoroutineScope? = null

    // 回调：接收子进程的响应
    private var responseCallback: ((String) -> Unit)? = null

    /**
     * 启动导出子进程
     *
     * @param jarPath idb-engine.jar 的路径
     * @param onResponse 接收子进程响应的回调
     */
    fun start(jarPath: String, onResponse: (String) -> Unit) {
        if (isRunning.value) {
            logger.warn("Export subprocess already running")
            return
        }

        this.responseCallback = onResponse

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
                "-Xmx512m",                      // 限制内存，防止导出任务耗尽主进程内存
                "-Xms64m",
                "-cp", classPath,
                "com.kxxnzstdsw.export.ExportSubProcess"
            )
            builder.directory(libsDir ?: File("."))
            builder.redirectErrorStream(false)

            process = builder.start()
            stdinWriter = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
            stdoutReader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))

            processScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            // 启动读取协程
            processScope?.launch {
                readOutputLoop()
            }

            // 监控进程退出
            processScope?.launch {
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
     * 读取子进程输出循环
     */
    private suspend fun readOutputLoop() {
        val reader = stdoutReader ?: return
        val ctx = currentCoroutineContext()
        try {
            var line: String?
            while (ctx.isActive) {
                line = reader.readLine()
                if (line == null) break
                logger.debug("Subprocess stdout >>> {}", line.take(200))
                responseCallback?.invoke(line)
            }
        } catch (e: Exception) {
            if (ctx.isActive) {
                logger.error("Error reading subprocess output", e)
            }
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
        responseCallback = null

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

        // 取消协程作用域
        processScope?.cancel()
        processScope = null

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
