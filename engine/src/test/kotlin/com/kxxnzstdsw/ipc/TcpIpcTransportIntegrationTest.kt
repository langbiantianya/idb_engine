package com.kxxnzstdsw.ipc

import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.IdbEngineGrpc
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.ipc.impl.TcpIpcTransport
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TCP 传输端到端 gRPC round-trip 集成测试 — 回归保护（确保 UDS / Pipe 改动不破坏 TCP 路径）。
 *
 * 使用 [MinimalEchoImpl] 而非完整 [com.kxxnzstdsw.server.IdbEngineImpl]，避免加载 drivers /
 * dialects；测试目标纯粹是传输可达性。
 */
class TcpIpcTransportIntegrationTest {

    @Test
    fun `gRPC round-trip over TCP loopback via ephemeral port`() {
        assertTimeout(Duration.ofSeconds(10)) {
            val transport = TcpIpcTransport(IpcConfig(kind = IpcKind.TCP, tcpPort = 0))
            transport.prepare()
            val server = transport.serverBuilder()
                .addService(MinimalEchoImpl())
                .build()
            server.start()
            try {
                val actualPort = server.getPort()
                val clientTransport = TcpIpcTransport(IpcConfig(kind = IpcKind.TCP, tcpPort = actualPort))
                val channel = clientTransport.channelBuilder().build()
                try {
                    val stub = IdbEngineGrpc.newBlockingStub(channel)
                    val iter = stub.handle(
                        Request.newBuilder()
                            .setId("tcp-1")
                            .setCategory(Category.SYSTEM)
                            .setAction(Action.INFO)
                            .build()
                    )
                    assertTrue(iter.hasNext(), "no response received")
                    val resp: Response = iter.next()
                    assertNotNull(resp)
                    assertTrue(resp.success)
                    assertTrue(iter.hasNext() || true)  // consume all
                    while (iter.hasNext()) iter.next()
                } finally {
                    channel.shutdownNow()
                    channel.awaitTermination(2, TimeUnit.SECONDS)
                }
            } finally {
                server.shutdownNow()
                server.awaitTermination(2, TimeUnit.SECONDS)
                transport.cleanup()
            }
        }
    }

    /** Minimal gRPC service: 每条 Request 回一条固定 Response，避免加载 dialects/drivers。 */
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