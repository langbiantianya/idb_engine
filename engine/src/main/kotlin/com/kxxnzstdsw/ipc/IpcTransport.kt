package com.kxxnzstdsw.ipc

import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder

/**
 * SPI — 进程间通信传输层抽象
 *
 * 在 gRPC 之上抽象 IPC 传输层，支持：
 *  - [com.kxxnzstdsw.ipc.impl.TcpIpcTransport]        TCP（跨平台默认回退，端口 :50051）
 *  - [com.kxxnzstdsw.ipc.impl.UnixSocketIpcTransport] Unix Domain Socket（Linux / macOS / BSD）
 *  - [com.kxxnzstdsw.ipc.impl.NamedPipeIpcTransport]  Windows 命名管道（pipe:<name>）
 *
 * gRPC 作为统一 wire 协议；本 SPI 仅切换 gRPC ServerBuilder/ChannelBuilder 底层的
 * SocketAddress / 目标 URI（TCP / UDS / Named Pipe）。
 */
interface IpcTransport {

    /** URI scheme: "tcp" / "unix" / "pipe" */
    fun scheme(): String

    /**
     * 服务端启动前的准备（filesystem UDS 删除遗留 socket 文件并设置权限；
     * pipe 名验证；TCP no-op）。
     * 失败抛 [IllegalStateException]；不会启动 server。
     */
    fun prepare()

    /**
     * 创建 gRPC ServerBuilder。各实现内部配置 channel type / target URI，
     * 调用方负责 addService() + maxInboundMessageSize() + build() + start()。
     */
    fun serverBuilder(): ServerBuilder<*>

    /**
     * 创建 gRPC ManagedChannelBuilder（usePlaintext 默认），authority 已设置。
     * 调用方负责 build() + shutdownNow()。
     */
    fun channelBuilder(): ManagedChannelBuilder<*>

    /** 显示给日志与客户端的目标 URI（如 "unix:/tmp/idb-engine.sock" / "pipe:idb-engine"） */
    fun displayTarget(): String

    /**
     * 服务端关闭后的清理（filesystem UDS deleteOnExit + 显式删除；
     * pipe 在 Windows 不需要额外操作；TCP no-op）。
     */
    fun cleanup()

    /** 描述字符串，仅用于日志。 */
    fun describe(): String = "${scheme()}://${displayTarget()}"
}