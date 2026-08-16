package com.kxxnzstdsw.ipc.impl

import com.kxxnzstdsw.ipc.IpcConfig
import com.kxxnzstdsw.ipc.IpcTransport
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.slf4j.LoggerFactory

/**
 * TCP 传输 — 与旧 [com.kxxnzstdsw.server.IdbEngineServer] 行为完全一致，
 * 保留与 Wails Go 主进程的兼容路径（默认 :50051）。
 */
class TcpIpcTransport(private val cfg: IpcConfig) : IpcTransport {
    private val logger = LoggerFactory.getLogger(TcpIpcTransport::class.java)

    override fun scheme(): String = "tcp"
    override fun displayTarget(): String = cfg.tcpPort.toString()

    override fun prepare() {
        logger.info("TCP transport: port={}", cfg.tcpPort)
    }

    override fun serverBuilder(): ServerBuilder<*> {
        val max = 256L * 1024L * 1024L
        // 优先 Netty（更可控的性能参数），失败兜底到 JDK ServerBuilder
        return try {
            NettyServerBuilder.forPort(cfg.tcpPort)
        } catch (t: Throwable) {
            logger.warn("Failed to build Netty server, falling back to default builder", t)
            io.grpc.ServerBuilder.forPort(cfg.tcpPort)
        }.also { _ ->
            // maxInboundMessageSize 由调用方在 IdbEngineServer.run() 中设置
            // 这里仅返回 builder，不在此处强行设置避免重复
            @Suppress("UNUSED_EXPRESSION") max
        }
    }

    override fun channelBuilder(): ManagedChannelBuilder<*> =
        ManagedChannelBuilder.forAddress("localhost", cfg.tcpPort).usePlaintext()

    override fun cleanup() { /* no-op */ }
}