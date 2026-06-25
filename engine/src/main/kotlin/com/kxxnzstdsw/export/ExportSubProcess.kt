package com.kxxnzstdsw.export

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Response
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

object ExportSubProcess {

    private val logger = LoggerFactory.getLogger(ExportSubProcess::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Force stdout to autoflush - critical for pipe-based communication with parent process
    // When stdout is redirected to a pipe (via ProcessBuilder), Java may buffer it.
    // This ensures each println() is immediately flushed to the pipe.
    private val out = PrintStream(System.out, true, Charsets.UTF_8)

    private val activeExports = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    @Volatile
    private var shouldStop = false

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        logger.info("Export SubProcess started, listening on stdin...")

        try {
            val baseDir = findBaseDir()
            com.kxxnzstdsw.loader.DriverLoader.loadFromDir(File(baseDir, "drivers"))
            com.kxxnzstdsw.loader.DialectLoader.loadFromDir(File(baseDir, "dialects"))
            logger.info("Loaded drivers and dialects from $baseDir")
        } catch (e: Exception) {
            logger.warn("Failed to load drivers/dialects from subdirs, trying current dir", e)
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutdown hook triggered")
            stopAllExports()
        })

        val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        val outputChannel = Channel<String>(Channel.UNLIMITED)

        val outputJob = launch(Dispatchers.IO) {
            for (response in outputChannel) {
                logger.info("SUBPROCESS_OUT (len=${response.length}): {}", response)
                out.println(response)
                logger.info("SUBPROCESS_FLUSHED (len=${response.length})")
            }
        }

        try {
            while (!shouldStop) {
                val line = withContext(Dispatchers.IO) {
                    reader.readLine()
                }

                if (line == null || shouldStop) {
                    logger.info("EOF or stop signal, shutting down")
                    break
                }

                if (line.isBlank()) continue

                processCommand(line.trim(), outputChannel)
            }
        } catch (e: Exception) {
            logger.error("Fatal error", e)
        } finally {
            stopAllExports()
            outputChannel.close()
            outputJob.join()
            logger.info("Export SubProcess stopped")
            exitProcess(0)
        }
    }

    private fun findBaseDir(): File {
        val currentDir = File(".").absoluteFile
        val libsDir = File(currentDir, "libs")
        if (libsDir.exists()) {
            return currentDir
        }
        val parent = currentDir.parentFile
        if (parent != null && File(parent, "libs").exists()) {
            return parent
        }
        return currentDir
    }

    private suspend fun processCommand(command: String, outputChannel: Channel<String>) {
        try {
            val jsonObj = json.parseToJsonElement(command).jsonObject
            val cmd = jsonObj["CMD"]?.jsonPrimitive?.content

            when (cmd) {
                "START_EXPORT" -> {
                    val exportId = jsonObj["id"]?.jsonPrimitive?.content ?: return
                    val connectionJson = jsonObj["connection"]?.jsonObject
                    val payload = jsonObj["payload"]?.jsonObject

                    if (connectionJson == null || payload == null) {
                        sendError(exportId, "Missing connection or payload", outputChannel)
                        return
                    }

                    val config = parseConnection(connectionJson)
                    startExport(exportId, config, payload, outputChannel)
                }

                "STOP_EXPORT" -> {
                    val exportId = jsonObj["exportId"]?.jsonPrimitive?.content
                    if (exportId != null) {
                        stopExport(exportId)
                    } else {
                        stopAllExports()
                    }
                }

                "CMD_EXIT" -> {
                    logger.info("Received CMD_EXIT")
                    shouldStop = true
                }

                else -> {
                    logger.debug("Ignoring non-command message")
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing command", e)
        }
    }

    private fun parseConnection(jsonObj: JsonObject): ConnectionConfig {
        return ConnectionConfig(
            driver = jsonObj["driver"]?.jsonPrimitive?.content ?: "",
            host = jsonObj["host"]?.jsonPrimitive?.content ?: "localhost",
            port = jsonObj["port"]?.jsonPrimitive?.intOrNull ?: 0,
            user = jsonObj["user"]?.jsonPrimitive?.content ?: "",
            password = jsonObj["password"]?.jsonPrimitive?.content ?: "",
            database = jsonObj["database"]?.jsonPrimitive?.content ?: ""
        )
    }

    private suspend fun startExport(
        exportId: String,
        config: ConnectionConfig,
        payload: JsonObject,
        outputChannel: Channel<String>
    ) {
        if (activeExports.containsKey(exportId)) {
            sendError(exportId, "Export $exportId already running", outputChannel)
            return
        }

        val job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                ExportEngine.export(config, parseRequest(payload)) { progress ->
                    logger.info("PROGRESS_CALLBACK: completed=${progress.completed}, exportedRows=${progress.exportedRows}, filePath=${progress.filePath}")
                    val progressJson = buildJsonObject {
                        put("exportedRows", progress.exportedRows)
                        put("columnCount", progress.columnCount)
                        put("completed", progress.completed)
                        progress.filePath?.let { put("filePath", it) }
                        progress.error?.let { put("error", it) }
                    }
                    val response = Response(
                        id = exportId,
                        success = true,
                        stream = true,
                        end = progress.completed,
                        data = progressJson
                    )
                    val encoded = json.encodeToString(Response.serializer(), response)
                    logger.info("PROGRESS_ENCODED (len=${encoded.length}): $encoded")
                    // 使用 send (阻塞) 而非 trySend，确保消息不丢失
                    outputChannel.send(encoded)
                    logger.info("PROGRESS_SENT (len=${encoded.length}, end=${progress.completed})")
                }
                logger.info("EXPORT_FINISHED: $exportId")
            } catch (e: ExportEngine.ExportCancelledException) {
                logger.info("Export cancelled: $exportId")
                val errorResponse = Response(
                    id = exportId,
                    success = false,
                    error = e.message ?: "Export cancelled by user"
                )
                outputChannel.send(json.encodeToString(Response.serializer(), errorResponse))
            } catch (e: Exception) {
                logger.error("Export failed: $exportId", e)
                val errorResponse = Response(
                    id = exportId,
                    success = false,
                    error = e.message ?: "Export failed"
                )
                outputChannel.send(json.encodeToString(Response.serializer(), errorResponse))
            } finally {
                activeExports.remove(exportId)
            }
        }

        activeExports[exportId] = job
        logger.info("Started export: $exportId")
    }

    private fun parseRequest(payload: JsonObject): ExportRequest {
        val sql = payload["sql"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'sql'")
        val outputDir = payload["outputDir"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'outputDir'")
        val fileName = payload["fileName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'fileName'")
        val formatStr = payload["format"]?.jsonPrimitive?.content?.uppercase()
            ?: throw IllegalArgumentException("缺少参数 'format'")
        val format = try {
            ExportFormat.valueOf(formatStr)
        } catch (e: Exception) {
            throw IllegalArgumentException("不支持的格式: $formatStr")
        }
        val tableName = payload["tableName"]?.jsonPrimitive?.content
        val fetchSize = payload["fetchSize"]?.jsonPrimitive?.intOrNull ?: 1000

        return ExportRequest(
            sql = sql,
            outputDir = outputDir,
            fileName = fileName,
            format = format,
            tableName = tableName,
            fetchSize = fetchSize
        )
    }

    private fun stopExport(exportId: String) {
        activeExports[exportId]?.let { job ->
            logger.info("Stopping export: $exportId")
            // 设置取消标志，ExportEngine 会在下一次循环检查时抛出 ExportCancelledException
            ExportEngine.isCancelled = true
            activeExports.remove(exportId)
        }
    }

    private fun stopAllExports() {
        logger.info("Stopping all exports...")
        activeExports.forEach { (id, job) ->
            job.cancel()
            logger.info("Cancelled export: $id")
        }
        activeExports.clear()
    }

    private suspend fun sendError(id: String, message: String, outputChannel: Channel<String>) {
        val response = Response(id = id, success = false, error = message)
        outputChannel.send(json.encodeToString(Response.serializer(), response))
    }
}
