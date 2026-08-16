package com.kxxnzstdsw.ipc.impl

import com.kxxnzstdsw.ipc.IpcConfig
import com.kxxnzstdsw.ipc.IpcTransport
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.epoll.Epoll
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollServerDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Unix Domain Socket 传输 — Linux / macOS / BSD 通用实现。
 *
 * - Linux：优先使用 [EpollServerDomainSocketChannel] + [EpollEventLoopGroup]
 *          （高性能，依赖 grpc-netty-shaded 内置 native lib）。
 *          native lib 不可用时降级到 NIO。
 * - macOS / BSD：使用 [NioServerDomainSocketChannel] + [NioEventLoopGroup]
 *          （grpc-netty-shaded 1.68 不内置 kqueue native lib，NIO 是唯一公开路径）。
 *
 * EventLoopGroup 由 NettyServerBuilder 在 [io.grpc.Server.shutdown] 时自动释放。
 *
 * 仅使用 filesystem namespace（Linux abstract namespace 与 macOS/BSD 不兼容，故未启用）。
 */
class UnixSocketIpcTransport(private val cfg: IpcConfig) : IpcTransport {
    private val logger = LoggerFactory.getLogger(UnixSocketIpcTransport::class.java)
    private val sock = File(cfg.udsPath)
    private val address = DomainSocketAddress(cfg.udsPath)

    override fun scheme(): String = "unix"
    override fun displayTarget(): String = cfg.udsPath

    override fun prepare() {
        if (sock.exists() && Files.isDirectory(sock.toPath())) {
            throw IllegalStateException("UDS path is a directory: ${sock.absolutePath}")
        }
        if (sock.exists()) {
            logger.info("Removing stale UDS file: ${sock.absolutePath}")
            Files.deleteIfExists(sock.toPath())
        }
        sock.parentFile?.mkdirs()
        try {
            Files.createFile(sock.toPath())
            Files.setPosixFilePermissions(
                sock.toPath(),
                PosixFilePermissions.fromString("rw-------")
            )
        } catch (e: UnsupportedOperationException) {
            logger.debug("POSIX permissions not supported on this FS: ${e.message}")
        } catch (e: Exception) {
            logger.warn("Failed to pre-set UDS permissions (continuing): ${e.message}")
        }
    }

    override fun serverBuilder(): ServerBuilder<*> {
        val sb = NettyServerBuilder.forAddress(address)
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("linux") && Epoll.isAvailable()) {
            sb.channelType(EpollServerDomainSocketChannel::class.java)
            sb.bossEventLoopGroup(EpollEventLoopGroup(1))
            sb.workerEventLoopGroup(EpollEventLoopGroup())
            logger.info("UDS server: epoll + EpollServerDomainSocketChannel (linux, native)")
        } else {
            sb.channelType(NioServerDomainSocketChannel::class.java)
            sb.bossEventLoopGroup(NioEventLoopGroup(1))
            sb.workerEventLoopGroup(NioEventLoopGroup())
            logger.info("UDS server: nio + NioServerDomainSocketChannel ({} fallback)", os)
        }
        return sb
    }

    override fun channelBuilder(): ManagedChannelBuilder<*> {
        val cb = NettyChannelBuilder.forAddress(address).usePlaintext()
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("linux") && Epoll.isAvailable()) {
            cb.channelType(EpollDomainSocketChannel::class.java)
            cb.eventLoopGroup(EpollEventLoopGroup())
        } else {
            cb.channelType(NioDomainSocketChannel::class.java)
            cb.eventLoopGroup(NioEventLoopGroup())
        }
        return cb
    }

    override fun cleanup() {
        try {
            if (sock.exists()) {
                Files.deleteIfExists(sock.toPath())
                logger.info("Deleted UDS file: ${sock.absolutePath}")
            }
        } catch (e: Exception) {
            logger.warn("Failed to delete UDS file: ${e.message}")
        }
    }

    @Suppress("unused")
    private val marker: EventLoopGroup? = null
}