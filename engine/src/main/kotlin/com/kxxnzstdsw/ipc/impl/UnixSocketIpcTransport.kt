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
import java.util.concurrent.TimeUnit as JucTimeUnit

/**
 * Unix Domain Socket 传输 — Linux / macOS / BSD 通用实现。
 *
 * - Linux：优先使用 [EpollServerDomainSocketChannel] + [EpollEventLoopGroup]
 *          （高性能，依赖 grpc-netty-shaded 内置 native lib）。
 *          native lib 不可用时降级到 NIO。
 * - macOS / BSD：使用 [NioServerDomainSocketChannel] + [NioEventLoopGroup]
 *          （grpc-netty-shaded 1.68 不内置 kqueue native lib，NIO 是唯一公开路径）。
 *
 * EventLoopGroup 由本类持有 — server.shutdown() 之后在 [cleanup] 中显式 shutdownGracefully
 * （NettyServerBuilder 不会关闭外部传入的 group）。
 *
 * 仅使用 filesystem namespace（Linux abstract namespace 与 macOS/BSD 不兼容，故未启用）。
 */
class UnixSocketIpcTransport(private val cfg: IpcConfig) : IpcTransport {
    private val logger = LoggerFactory.getLogger(UnixSocketIpcTransport::class.java)
    private val sock = File(cfg.udsPath)
    private val address = DomainSocketAddress(cfg.udsPath)

    // 由 [serverBuilder] 在启动时创建并缓存，[cleanup] 中关闭。
    // ServerBuilder 不会关闭外部传入的 EventLoopGroup,必须我们自己关闭。
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    // 由 [channelBuilder] 缓存的客户端 EventLoopGroup (一次创建,cleanup 中关闭)
    private var clientGroup: EventLoopGroup? = null

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
            // 缓存 group 引用以便 cleanup() 关闭 — NettyServerBuilder 不会关闭外部传入的 group
            bossGroup = EpollEventLoopGroup(1).also { sb.bossEventLoopGroup(it) }
            workerGroup = EpollEventLoopGroup().also { sb.workerEventLoopGroup(it) }
            logger.info("UDS server: epoll + EpollServerDomainSocketChannel (linux, native)")
        } else {
            sb.channelType(NioServerDomainSocketChannel::class.java)
            bossGroup = NioEventLoopGroup(1).also { sb.bossEventLoopGroup(it) }
            workerGroup = NioEventLoopGroup().also { sb.workerEventLoopGroup(it) }
            logger.info("UDS server: nio + NioServerDomainSocketChannel ({} fallback)", os)
        }
        return sb
    }

    override fun channelBuilder(): ManagedChannelBuilder<*> {
        val cb = NettyChannelBuilder.forAddress(address).usePlaintext()
        val os = System.getProperty("os.name", "").lowercase()
        val group: EventLoopGroup = if (os.contains("linux") && Epoll.isAvailable()) {
            cb.channelType(EpollDomainSocketChannel::class.java)
            EpollEventLoopGroup().also { clientGroup = it }
        } else {
            cb.channelType(NioDomainSocketChannel::class.java)
            NioEventLoopGroup().also { clientGroup = it }
        }
        cb.eventLoopGroup(group)
        return cb
    }

    override fun cleanup() {
        // 关闭所有持有的 EventLoopGroup — NettyServerBuilder 不会自动关闭外部传入的 group
        listOf(bossGroup, workerGroup, clientGroup).forEach { g ->
            try {
                g?.shutdownGracefully(0, 5, JucTimeUnit.SECONDS)?.sync()
            } catch (e: Exception) {
                logger.warn("EventLoopGroup shutdown failed: ${e.message}")
            }
        }
        bossGroup = null
        workerGroup = null
        clientGroup = null

        try {
            if (sock.exists()) {
                Files.deleteIfExists(sock.toPath())
                logger.info("Deleted UDS file: ${sock.absolutePath}")
            }
        } catch (e: Exception) {
            logger.warn("Failed to delete UDS file: ${e.message}")
        }
    }
}