package com.kxxnzstdsw.ipc.impl

import com.kxxnzstdsw.ipc.IpcConfig
import com.kxxnzstdsw.ipc.IpcTransport
import io.grpc.Grpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import org.slf4j.LoggerFactory

/**
 * Windows 命名管道传输（gRPC 客户端可达，服务端待启用）。
 *
 * **客户端**：[Grpc.newChannelBuilder] 接受 `pipe:<name>` URI（gRPC-Java 1.61+ 内部支持），
 * 无 native 依赖，可直接使用。
 *
 * **服务端**：grpc-Java 1.68.0（最新 1.83.0）**不暴露公共 API** 用于将 gRPC Server 绑定到
 * Windows 命名管道 —— `ServerBuilder.forPort(int)` 强制 TCP；`NettyServerBuilder.forAddress(SocketAddress)`
 * 接受 `DomainSocketAddress`（POSIX UDS），但没有公开的 `NamedPipeAddress` 子类。
 * grpc 内部的 `NettyTransportFactory.createServerTransport` 支持 pipe，但需要 internal API。
 *
 * 当前实现：客户端可用，服务端在调用 [serverBuilder] 时抛 [UnsupportedOperationException]，
 * 并给出明确错误。后续可通过 JNA + Windows `CreateNamedPipe` Win32 API + 自行实现 gRPC HTTP/2 管道
 * 启用（参见 TODO），或在 grpc-java 上游公开 `forTarget` 后自动启用。
 */
class NamedPipeIpcTransport(private val cfg: IpcConfig) : IpcTransport {
    private val logger = LoggerFactory.getLogger(NamedPipeIpcTransport::class.java)
    private val target: String = "pipe:${cfg.pipeName}"

    override fun scheme(): String = "pipe"
    override fun displayTarget(): String = target

    override fun prepare() {
        require(cfg.pipeName.matches(Regex("^[A-Za-z0-9_.-]{1,64}$"))) {
            "Invalid pipe name: ${cfg.pipeName} (must match ^[A-Za-z0-9_.-]{1,64}$)"
        }
        logger.info("Named pipe target: $target (client-only; server-side pending)")
    }

    override fun serverBuilder(): ServerBuilder<*> {
        throw UnsupportedOperationException(
            "grpc-Java does not expose a public API for binding a gRPC server to a Windows " +
            "named pipe (pipe:<name>). Internal NettyTransportFactory supports it but is not " +
            "in the public surface. Pass --ipc=tcp on Windows, or implement raw Netty " +
            "piping via JNA + CreateNamedPipe."
        )
    }

    override fun channelBuilder(): ManagedChannelBuilder<*> =
        Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())

    override fun cleanup() {
        // Windows OS 在最后一个句柄关闭时回收 pipe，无需额外清理
    }
}