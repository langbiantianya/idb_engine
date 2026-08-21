package com.kxxnzstdsw.server

import com.kxxnzstdsw.ipc.IpcConfig
import com.kxxnzstdsw.ipc.IpcTransportRegistry
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.loader.DriverLoader
import com.kxxnzstdsw.pool.PoolManager
import io.grpc.ServerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * gRPC 服务端入口
 *
 * 通信层：gRPC over HTTP/2 + protobuf；传输层由 [com.kxxnzstdsw.ipc.IpcTransport] SPI 抽象，
 * 支持 TCP（默认 :50051）/ UDS（Linux/macOS/BSD）/ Windows 命名管道（pipe:<name>）。
 *
 * 默认传输由 OS 自动检测（Windows=pipe, POSIX=unix），可通过 CLI 参数 `--ipc` 覆盖。
 * 完整参数列表：`java -jar idb-engine.jar --help`。
 */
object IdbEngineServer {
    private val logger = LoggerFactory.getLogger(IdbEngineServer::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val cfg = IpcConfig.fromArgs(args)
            run(cfg)
        } catch (e: IllegalStateException) {
            System.err.println("idb-engine: ${e.message}")
            System.err.println("Try --help for usage.")
            exitProcess(2)
        }
    }

    /**
     * 启动 gRPC 服务并阻塞至收到终止信号。
     *
     * @param cfg 已解析的 IPC 传输配置
     */
    fun run(cfg: IpcConfig) {
        val transport = IpcTransportRegistry.resolve(cfg)
        transport.prepare()
        logger.info("IDB Engine starting (gRPC mode), transport=${transport.describe()}")

        // 动态加载 drivers/ 目录下的 JDBC 驱动
        DriverLoader.loadFromDir(File("drivers"))

        // 动态加载 dialects/ 目录下的方言插件
        DialectLoader.loadFromDir(File("dialects"))

        // 关闭钩子 — 释放连接池 / 驱动 / 方言资源；transport.cleanup() 在 finally 块中
        // 在 server.shutdown() 之后调用，避免删除 UDS 文件时仍有 in-flight RPC。
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutdown hook triggered")
            PoolManager.closeAll()
            DriverLoader.closeAll()
            DialectLoader.closeAll()
        })

        // 单帧最大 256 MiB（与历史 MAX_FRAME_SIZE 对齐）
        val maxMsgBytes = 256L * 1024L * 1024L

        val server = transport.serverBuilder()
            .addService(IdbEngineImpl().bindService())
            .maxInboundMessageSize(maxMsgBytes.toInt())
            .build()

        try {
            server.start()
            logger.info("IDB Engine gRPC server started, ${transport.describe()}")
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
            transport.cleanup()
            logger.info("IDB Engine stopped")
            exitProcess(0)
        }
    }

    @Suppress("unused")
    private fun serverBuilderForCompatibility(port: Int): ServerBuilder<*> =
        ServerBuilder.forPort(port)
}