package com.kxxnzstdsw.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [IpcConfig] CLI 参数解析测试。
 */
class IpcConfigTest {

    @Test
    fun `default OS detection chooses pipe on Windows when --ipc omitted`() {
        val cfg = IpcConfig.fromArgs(emptyArray())
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win")) {
            assertEquals(IpcKind.PIPE, cfg.kind)
        } else {
            assertEquals(IpcKind.UNIX, cfg.kind)
        }
    }

    @Test
    fun `empty args keeps default port and uds path and pipe name`() {
        val cfg = IpcConfig.fromArgs(emptyArray())
        assertEquals(50051, cfg.tcpPort)
        assertEquals("/tmp/idb-engine.sock", cfg.udsPath)
        assertEquals("idb-engine", cfg.pipeName)
    }

    @Test
    fun `--ipc tcp overrides auto-detect`() {
        val cfg = IpcConfig.fromArgs(arrayOf("--ipc", "tcp"))
        assertEquals(IpcKind.TCP, cfg.kind)
    }

    @Test
    fun `--ipc unix overrides auto-detect`() {
        val cfg = IpcConfig.fromArgs(arrayOf("--ipc", "unix"))
        assertEquals(IpcKind.UNIX, cfg.kind)
    }

    @Test
    fun `--ipc pipe overrides auto-detect`() {
        val cfg = IpcConfig.fromArgs(arrayOf("--ipc", "pipe"))
        assertEquals(IpcKind.PIPE, cfg.kind)
    }

    @Test
    fun `--ipc value is case-insensitive`() {
        val cfg = IpcConfig.fromArgs(arrayOf("--ipc", "TCP"))
        assertEquals(IpcKind.TCP, cfg.kind)
    }

    @Test
    fun `--port 60000 is honored`() {
        val cfg = IpcConfig.fromArgs(arrayOf("--ipc", "tcp", "--port", "60000"))
        assertEquals(60000, cfg.tcpPort)
    }

    @Test
    fun `--port 0 is accepted for ephemeral binding`() {
        val cfg = IpcConfig.fromArgs(arrayOf("--ipc", "tcp", "--port", "0"))
        assertEquals(0, cfg.tcpPort)
    }

    @Test
    fun `--port out-of-range is rejected`() {
        val ex = assertThrows<IllegalStateException> {
            IpcConfig.fromArgs(arrayOf("--ipc", "tcp", "--port", "70000"))
        }
        assertTrue(ex.message!!.contains("0..65535"))
    }

    @Test
    fun `--port non-integer is rejected`() {
        val ex = assertThrows<IllegalStateException> {
            IpcConfig.fromArgs(arrayOf("--ipc", "tcp", "--port", "notanumber"))
        }
        assertTrue(ex.message!!.contains("integer"))
    }

    @Test
    fun `--uds-path overrides default uds path`() {
        val cfg = IpcConfig.fromArgs(arrayOf("--ipc", "unix", "--uds-path", "/var/run/idb.sock"))
        assertEquals("/var/run/idb.sock", cfg.udsPath)
    }

    @Test
    fun `--pipe-name overrides default pipe name`() {
        val cfg = IpcConfig.fromArgs(arrayOf("--ipc", "pipe", "--pipe-name", "my-app"))
        assertEquals("my-app", cfg.pipeName)
    }

    @Test
    fun `--ipc with no value is rejected`() {
        val ex = assertThrows<IllegalStateException> {
            IpcConfig.fromArgs(arrayOf("--ipc"))
        }
        assertTrue(ex.message!!.contains("requires a value"))
    }

    @Test
    fun `--ipc invalid value is rejected with clear message`() {
        val ex = assertThrows<IllegalStateException> {
            IpcConfig.fromArgs(arrayOf("--ipc", "websocket"))
        }
        assertTrue(ex.message!!.contains("Invalid --ipc"))
        assertTrue(ex.message!!.contains("tcp | unix | pipe"))
    }

    @Test
    fun `unknown flag is rejected with hint to --help`() {
        val ex = assertThrows<IllegalStateException> {
            IpcConfig.fromArgs(arrayOf("--foo-bar"))
        }
        assertTrue(ex.message!!.contains("Unknown argument"))
        assertTrue(ex.message!!.contains("--help"))
    }

    @Test
    fun `multiple flags all parse correctly`() {
        val cfg = IpcConfig.fromArgs(
            arrayOf("--ipc", "tcp", "--port", "12345")
        )
        assertEquals(IpcKind.TCP, cfg.kind)
        assertEquals(12345, cfg.tcpPort)
        assertEquals("/tmp/idb-engine.sock", cfg.udsPath) // default preserved
        assertEquals("idb-engine", cfg.pipeName) // default preserved
    }

    @Test
    fun `USAGE string documents every flag`() {
        assertTrue(IpcConfig.USAGE.contains("--ipc"))
        assertTrue(IpcConfig.USAGE.contains("--port"))
        assertTrue(IpcConfig.USAGE.contains("--uds-path"))
        assertTrue(IpcConfig.USAGE.contains("--pipe-name"))
        assertTrue(IpcConfig.USAGE.contains("--help"))
    }

    @Test
    fun `USAGE string does not reference legacy env vars`() {
        assertTrue(!IpcConfig.USAGE.contains("IDB_ENGINE_IPC"))
        assertTrue(!IpcConfig.USAGE.contains("IDB_ENGINE_PORT"))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `--ipc omitted on Windows yields pipe`() {
        val cfg = IpcConfig.fromArgs(emptyArray())
        assertEquals(IpcKind.PIPE, cfg.kind)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC, OS.FREEBSD)
    fun `--ipc omitted on POSIX yields unix`() {
        val cfg = IpcConfig.fromArgs(emptyArray())
        assertEquals(IpcKind.UNIX, cfg.kind)
    }
}