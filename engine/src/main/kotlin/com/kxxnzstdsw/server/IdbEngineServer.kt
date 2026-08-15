package com.kxxnzstdsw.server

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.loader.DriverLoader
import com.kxxnzstdsw.pool.PoolManager
import io.grpc.ServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * gRPC 服务端入口
 *
 * 替代历史 stdin/stdout 长度前缀 Protobuf 帧协议：
 * - HTTP/2 (Netty) over TCP loopback
 * - 默认端口 50051，可通过环境变量 IDB_ENGINE_PORT 覆盖
 * - 单帧最大 256 MiB（与历史 MAX_FRAME_SIZE 对齐）
 */
object IdbEngineServer {
    private val logger = LoggerFactory.getLogger(IdbEngineServer::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        run()
    }

    /**
     * 启动 gRPC 服务并阻塞至收到终止信号
     */
    fun run() {
        val port = System.getenv("IDB_ENGINE_PORT")?.toIntOrNull() ?: 50051
        logger.info("IDB Engine starting (gRPC mode), listening on :$port")

        // 动态加载 drivers/ 目录下的 JDBC 驱动
        DriverLoader.loadFromDir(File("drivers"))

        // 动态加载 dialects/ 目录下的方言插件
        DialectLoader.loadFromDir(File("dialects"))

        // Add shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutdown hook triggered")
            PoolManager.closeAll()
            DriverLoader.closeAll()
            DialectLoader.closeAll()
        })

        // 256 MiB 上限，与历史 MAX_FRAME_SIZE 一致
        val maxMsgBytes = 256L * 1024L * 1024L

        // 优先使用 Netty（更可控的性能参数），回退到 JDK ServerBuilder
        val server = try {
            (NettyServerBuilder.forPort(port)
                .addService(IdbEngineImpl())
                .maxInboundMessageSize(maxMsgBytes.toInt())
                .build())
        } catch (e: Throwable) {
            logger.warn("Failed to build Netty server, falling back to default builder", e)
            ServerBuilder.forPort(port)
                .addService(IdbEngineImpl())
                .maxInboundMessageSize(maxMsgBytes.toInt())
                .build()
        }

        try {
            server.start()
            logger.info("IDB Engine gRPC server started on :$port")
            server.awaitTermination()
        } catch (e: InterruptedException) {
            logger.info("Server interrupted, shutting down")
        } catch (e: Exception) {
            logger.error("Server failed", e)
        } finally {
            try {
                server.shutdown()
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            logger.info("IDB Engine stopped")
            exitProcess(0)
        }
    }
}