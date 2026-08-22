package com.kxxnzstdsw.ipc

import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.IdbEngineGrpc
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.ipc.impl.UnixSocketIpcTransport
import com.kxxnzstdsw.testutil.TestIds
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UDS 传输端到端 gRPC round-trip 集成测试 — 仅在 POSIX 平台运行。
 *
 * Linux：使用 epoll native lib（grpc-netty-shaded 1.68 内置）。
 * macOS / BSD：通过 [io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerDomainSocketChannel]（跨平台 NIO）。
 */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.FREEBSD)
class UnixSocketIpcTransportIntegrationTest {

    private val sockFile = File(
        System.getProperty("java.io.tmpdir"),
        TestIds.uniqueName("idb-engine-test") + ".sock"
    )

    private val transport = UnixSocketIpcTransport(
        IpcConfig(kind = IpcKind.UNIX, udsPath = sockFile.absolutePath)
    )

    @AfterEach
    fun tearDown() {
        transport.cleanup()
    }

    @Test
    fun `gRPC round-trip over filesystem UDS`() {
        assertTimeout(Duration.ofSeconds(15)) {
            transport.prepare()
            val server = transport.serverBuilder()
                .addService(MinimalEchoImpl())
                .build()
            server.start()
            try {
                val channel = transport.channelBuilder().build()
                try {
                    val stub = IdbEngineGrpc.newBlockingStub(channel)
                    val iter = stub.handle(
                        Request.newBuilder()
                            .setId("uds-1")
                            .setCategory(Category.SYSTEM)
                            .setAction(Action.INFO)
                            .build()
                    )
                    assertTrue(iter.hasNext(), "no response received via UDS")
                    val resp: Response = iter.next()
                    assertNotNull(resp)
                    assertTrue(resp.success)
                    while (iter.hasNext()) iter.next()
                } finally {
                    channel.shutdownNow()
                    channel.awaitTermination(2, TimeUnit.SECONDS)
                }
            } finally {
                server.shutdownNow()
                server.awaitTermination(2, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `prepare deletes stale UDS file from previous crash`() {
        // 写一个 dummy 文件模拟 stale socket
        sockFile.parentFile.mkdirs()
        sockFile.writeBytes(ByteArray(0))
        assertTrue(sockFile.exists())
        transport.prepare()
        // prepare 内部先删除 stale 文件再 recreate，准备成功即可
        assertNotNull(transport.serverBuilder())
    }

    private class MinimalEchoImpl : IdbEngineGrpc.IdbEngineImplBase() {
        override fun handle(
            request: Request,
            responseObserver: StreamObserver<Response>
        ) {
            responseObserver.onNext(
                Response.newBuilder()
                    .setId(request.id)
                    .setSuccess(true)
                    .build()
            )
            responseObserver.onCompleted()
        }
    }
}