package com.kxxnzstdsw.export

import com.kxxnzstdsw.handlers.ExportHandler
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Response
import com.kxxnzstdsw.proto.PayloadValue
import com.kxxnzstdsw.proto.ProtoConverters
import com.kxxnzstdsw.transport.Framing
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

object ExportSubProcess {

    private val logger = LoggerFactory.getLogger(ExportSubProcess::class.java)

    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { encodeDefaults = true }

    // Force stdout to autoflush - critical for pipe-based communication with parent process
    private val out = PrintStream(System.out, true, Charsets.UTF_8)

    // 正在执行的导出任务 ID 集合（仅用于日志/状态查询，ExportEngine.isCancelled 控制实际取消）
    private val activeExports = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var shouldStop = false

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        logger.info("Export SubProcess started, listening on stdin (protobuf frame protocol)...")

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

        val stdin = System.`in`
        val outputChannel = Channel<ByteArray>(Channel.UNLIMITED)

        val outputJob = launch(Dispatchers.IO) {
            for (frame in outputChannel) {
                synchronized(out) {
                    Framing.writeFrame(out, frame)
                    out.flush()
                }
            }
        }

        try {
            while (!shouldStop) {
                val frame = withContext(Dispatchers.IO) {
                    Framing.readFrame(stdin)
                }

                if (frame == null || shouldStop) {
                    logger.info("EOF or stop signal, shutting down")
                    break
                }

                processCommand(frame, outputChannel)
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

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun processCommand(frame: ByteArray, outputChannel: Channel<ByteArray>) {
        try {
            val cmd = proto.decodeFromByteArray(ExportCommand.serializer(), frame)
            logger.debug("Subprocess received command: ${cmd.kind}")

            when (cmd.kind) {
                ExportCommandKind.START_EXPORT -> {
                    if (cmd.id.isBlank()) {
                        sendError("unknown", "Missing export id", outputChannel)
                        return
                    }
                    val payload = ProtoConverters.toJsonObject(cmd.payload)
                    startExport(cmd.id, cmd.connection, payload, outputChannel)
                }

                ExportCommandKind.STOP_EXPORT -> {
                    if (cmd.exportId.isNotBlank()) {
                        stopExport(cmd.exportId)
                    } else {
                        stopAllExports()
                    }
                }

                ExportCommandKind.CMD_EXIT -> {
                    logger.info("Received CMD_EXIT")
                    shouldStop = true
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing command", e)
        }
    }

    private suspend fun startExport(
        exportId: String,
        config: ConnectionConfig,
        payload: kotlinx.serialization.json.JsonObject,
        outputChannel: Channel<ByteArray>
    ) {
        if (activeExports.contains(exportId)) {
            sendError(exportId, "Export $exportId already running", outputChannel)
            return
        }

        activeExports.add(exportId)
        // 启动独立协程执行导出，主循环继续读取其他命令（如 STOP_EXPORT）
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                // 委托给 ExportHandler 统一执行（业务编排 + 进度回报）
                ExportHandler.execute(exportId, config, payload, outputChannel)
            } catch (e: Exception) {
                logger.error("Export failed: $exportId", e)
                @OptIn(ExperimentalSerializationApi::class)
                val errFrame = proto.encodeToByteArray(
                    Response.serializer(),
                    Response(id = exportId, success = false, error = e.message ?: "Export failed")
                )
                outputChannel.send(errFrame)
            } finally {
                activeExports.remove(exportId)
            }
        }
    }

    private fun stopExport(exportId: String) {
        if (activeExports.remove(exportId)) {
            logger.info("Stopping export: $exportId")
            // 设置取消标志，ExportEngine 会在下一次循环检查时抛出 ExportCancelledException
            ExportEngine.isCancelled = true
        }
    }

    private fun stopAllExports() {
        logger.info("Stopping all exports...")
        if (activeExports.isNotEmpty()) {
            ExportEngine.isCancelled = true
            activeExports.clear()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun sendError(id: String, message: String, outputChannel: Channel<ByteArray>) {
        val frame = proto.encodeToByteArray(
            Response.serializer(), Response(id = id, success = false, error = message)
        )
        outputChannel.send(frame)
    }
}