@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kxxnzstdsw

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.export.ExportProcessManager
import com.kxxnzstdsw.export.GlobalOutputChannel
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.loader.DriverLoader
import com.kxxnzstdsw.models.Request
import com.kxxnzstdsw.models.Response
import com.kxxnzstdsw.pool.PoolManager
import com.kxxnzstdsw.transport.Framing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess

fun main() = runBlocking {
    val logger = LoggerFactory.getLogger("Main")
    logger.info("IDB Engine started (async mode), listening on stdin (protobuf frame protocol)...")

    // 动态加载 drivers/ 目录下的 JDBC 驱动
    DriverLoader.loadFromDir(File("drivers"))

    // 动态加载 dialects/ 目录下的方言插件
    DialectLoader.loadFromDir(File("dialects"))

    val stdin = System.`in`
    val stdout = System.out

    // Channel for serializing stdout output (only one output at a time)
    val outputChannel = Channel<ByteArray>(Channel.UNLIMITED)

    // Set global output channel for subprocess managers (export, etc.)
    // They send response frames here to be serialized through the same stdout pipeline
    GlobalOutputChannel.channel = outputChannel

    // Add shutdown hook for graceful cleanup
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutdown hook triggered")
        ExportProcessManager.stop()
        PoolManager.closeAll()
        DriverLoader.closeAll()
        DialectLoader.closeAll()
    })

    // Launch output writer coroutine (serializes all stdout writes — one frame at a time)
    val outputJob = launch(Dispatchers.IO) {
        for (frame in outputChannel) {
            synchronized(stdout) {
                Framing.writeFrame(stdout, frame)
                stdout.flush()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    val proto = ProtoBuf { encodeDefaults = true }

    try {
        logger.info("Entering main loop, waiting for input...")
        while (true) {
            // 阻塞读取一帧（4 字节 BE 长度 + protobuf 字节）
            // readFrame 在读到 EOF 时返回 null，在 header 被截断时抛出 IOException
            val frame: ByteArray? = try {
                withContext(Dispatchers.IO) {
                    Framing.readFrame(stdin)
                }
            } catch (e: Exception) {
                // 截断的 header / 不完整的 payload — 流已损坏，无法恢复，
                // 发送一个错误响应帧（id = "unknown"），然后跳出循环。
                logger.error("Failed to read frame from stdin (malformed input stream)", e)
                @OptIn(ExperimentalSerializationApi::class)
                val errorResp = Response(
                    id = "unknown",
                    success = false,
                    error = "Malformed frame: ${e.message ?: e.javaClass.simpleName}"
                )
                try {
                    outputChannel.send(proto.encodeToByteArray(Response.serializer(), errorResp))
                } catch (_: Exception) {
                    // outputChannel 已关闭 — 忽略
                }
                break
            }

            // EOF reached (stdin closed cleanly)
            if (frame == null) {
                logger.info("EOF detected (stdin closed), shutting down")
                break
            }

            // Process request asynchronously (non-blocking)
            launch {
                try {
                    // 反序列化为 Request（payload 用 Map<String, PayloadValue>）
                    val request = try {
                        proto.decodeFromByteArray(Request.serializer(), frame)
                    } catch (e: Exception) {
                        logger.error("Failed to decode request frame (${frame.size} bytes)", e)
                        // 反序列化失败时构造错误响应帧，id 留空（请求内容不可读）
                        @OptIn(ExperimentalSerializationApi::class)
                        val errorResp = Response(id = "unknown", success = false, error = "Malformed request: ${e.message}")
                        outputChannel.send(proto.encodeToByteArray(Response.serializer(), errorResp))
                        return@launch
                    }

                    // 调试日志：脱敏打印 payload 字段名（避免打印真实密码）
                    val masked = "[id=${request.id}] ${request.category}/${request.action} payloadKeys=${request.payload.keys}"
                    logger.debug("STDIN <<< {}", masked)
                    RequestDispatcher.dispatch(request, outputChannel)
                } catch (e: Exception) {
                    logger.error("Error processing request asynchronously", e)
                }
            }
        }
    } catch (e: Exception) {
        logger.error("Fatal error in main loop", e)
    } finally {
        // Wait for all pending outputs to complete
        outputChannel.close()
        outputJob.join()

        // Cleanup
        PoolManager.closeAll()
        logger.info("IDB Engine stopped")
        exitProcess(0)
    }
}