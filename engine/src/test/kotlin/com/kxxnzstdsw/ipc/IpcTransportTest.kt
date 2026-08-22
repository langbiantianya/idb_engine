package com.kxxnzstdsw.ipc

import com.kxxnzstdsw.ipc.impl.NamedPipeIpcTransport
import com.kxxnzstdsw.ipc.impl.TcpIpcTransport
import com.kxxnzstdsw.ipc.impl.UnixSocketIpcTransport
import com.kxxnzstdsw.testutil.TestIds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [IpcTransport] 各实现的轻量构造测试 — 验证 builder 创建不会抛错。
 * 实际绑定测试在 [TcpIpcTransportIntegrationTest] / [UnixSocketIpcTransportIntegrationTest] 中。
 */
class IpcTransportTest {

    @Test
    fun `TcpIpcTransport exposes tcp scheme and 50051 default`() {
        val t = TcpIpcTransport(IpcConfig(kind = IpcKind.TCP, tcpPort = 50051))
        assertEquals("tcp", t.scheme())
        assertEquals("50051", t.displayTarget())
        t.prepare()
        assertNotNull(t.serverBuilder())
        assertNotNull(t.channelBuilder())
        // cleanup 不应抛错
        t.cleanup()
    }

    @Test
    fun `TcpIpcTransport with port 0 still constructs builder`() {
        val t = TcpIpcTransport(IpcConfig(kind = IpcKind.TCP, tcpPort = 0))
        assertNotNull(t.serverBuilder())
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC, OS.FREEBSD)
    fun `UnixSocketIpcTransport exposes unix scheme on POSIX`() {
        val path = System.getProperty("java.io.tmpdir") + "/" + TestIds.uniqueName("idb-engine-test") + ".sock"
        val t = UnixSocketIpcTransport(IpcConfig(kind = IpcKind.UNIX, udsPath = path))
        try {
            assertEquals("unix", t.scheme())
            assertEquals(path, t.displayTarget())
            t.prepare()
            assertNotNull(t.serverBuilder())
            assertNotNull(t.channelBuilder())
        } finally {
            t.cleanup()
        }
    }

    @Test
    fun `NamedPipeIpcTransport exposes pipe scheme but serverBuilder throws (grpc limitation)`() {
        val t = NamedPipeIpcTransport(
            IpcConfig(kind = IpcKind.PIPE, pipeName = TestIds.uniqueName("test"))
        )
        assertEquals("pipe", t.scheme())
        assertTrue(t.displayTarget().startsWith("pipe:"))
        t.prepare()
        assertNotNull(t.channelBuilder())
        // serverBuilder 抛 UnsupportedOperationException — grpc-java 1.68.0 无公开 pipe server API
        try {
            t.serverBuilder()
            kotlin.test.fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("named pipe"))
        }
    }

    @Test
    fun `NamedPipeIpcTransport rejects invalid pipe names`() {
        val transport = NamedPipeIpcTransport(IpcConfig(kind = IpcKind.PIPE, pipeName = "bad/name"))
        val ex = kotlin.runCatching { transport.prepare() }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "got ${ex?.javaClass?.simpleName}")
    }

    @Test
    fun `IpcTransportRegistry routes TCP choice`() {
        val t = IpcTransportRegistry.resolve(IpcConfig(kind = IpcKind.TCP))
        assertEquals("tcp", t.scheme())
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC, OS.FREEBSD)
    fun `IpcTransportRegistry routes UNIX choice on POSIX`() {
        val t = IpcTransportRegistry.resolve(IpcConfig(kind = IpcKind.UNIX))
        assertEquals("unix", t.scheme())
    }
}