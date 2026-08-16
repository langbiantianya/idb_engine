package com.kxxnzstdsw.ipc

import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.IdbEngineGrpc
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.ipc.impl.NamedPipeIpcTransport
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Windows 命名管道传输集成测试 — 当前仅验证客户端可达性。
 *
 * 由于 grpc-Java 1.68.0（及最新 1.83.0）**不暴露公共 API** 用于将 gRPC Server 绑定到
 * Windows 命名管道，服务端测试需要 JNA + Win32 `CreateNamedPipe` 自实现，标记为未来工作。
 *
 * 客户端 [NamedPipeIpcTransport.channelBuilder] 使用 [io.grpc.Grpc.newChannelBuilder] + `pipe:<name>` URI，
 * 在 Windows 上可用（在其他平台上构造失败但不在此测试范围）。
 */
class NamedPipeIpcTransportIntegrationTest {

    @Test
    fun `client can construct channel builder for pipe name`() {
        val name = "idb-test-${UUID.randomUUID().toString().take(8)}"
        val transport = NamedPipeIpcTransport(IpcConfig(kind = IpcKind.PIPE, pipeName = name))
        transport.prepare()
        val channelBuilder = transport.channelBuilder()
        assertNotNull(channelBuilder)
        assertTrue(transport.displayTarget().startsWith("pipe:"))
    }

    @Test
    fun `server builder throws UnsupportedOperationException documenting grpc limitation`() {
        assertTimeout(Duration.ofSeconds(2)) {
            val name = "idb-test-${UUID.randomUUID().toString().take(8)}"
            val transport = NamedPipeIpcTransport(IpcConfig(kind = IpcKind.PIPE, pipeName = name))
            try {
                transport.serverBuilder()
                fail("expected UnsupportedOperationException")
            } catch (e: UnsupportedOperationException) {
                assertTrue(e.message!!.contains("named pipe"))
            }
        }
    }
}