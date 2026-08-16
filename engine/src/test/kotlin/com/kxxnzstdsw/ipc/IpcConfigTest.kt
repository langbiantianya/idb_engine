package com.kxxnzstdsw.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [IpcConfig] 环境变量解析测试 — 使用注入的 env 函数避免污染系统环境。
 */
class IpcConfigTest {

    @Test
    fun `default OS detection chooses pipe on Windows`() {
        val cfg = IpcConfig.fromEnv { null }
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win")) {
            assertEquals(IpcKind.PIPE, cfg.kind)
        } else {
            assertEquals(IpcKind.UNIX, cfg.kind)
        }
    }

    @Test
    fun `explicit tcp overrides OS detection`() {
        val cfg = IpcConfig.fromEnv {
            if (it == "IDB_ENGINE_IPC") "tcp"
            else null
        }
        assertEquals(IpcKind.TCP, cfg.kind)
        assertEquals(50051, cfg.tcpPort)
    }

    @Test
    fun `explicit unix overrides OS detection`() {
        val cfg = IpcConfig.fromEnv {
            if (it == "IDB_ENGINE_IPC") "unix" else null
        }
        assertEquals(IpcKind.UNIX, cfg.kind)
    }

    @Test
    fun `explicit pipe overrides OS detection`() {
        val cfg = IpcConfig.fromEnv {
            if (it == "IDB_ENGINE_IPC") "pipe" else null
        }
        assertEquals(IpcKind.PIPE, cfg.kind)
    }

    @Test
    fun `IDB_ENGINE_PORT overrides default tcp port`() {
        val cfg = IpcConfig.fromEnv {
            when (it) {
                "IDB_ENGINE_IPC" -> "tcp"
                "IDB_ENGINE_PORT" -> "60000"
                else -> null
            }
        }
        assertEquals(60000, cfg.tcpPort)
    }

    @Test
    fun `IDB_ENGINE_UDS_PATH overrides default uds path`() {
        val cfg = IpcConfig.fromEnv {
            when (it) {
                "IDB_ENGINE_IPC" -> "unix"
                "IDB_ENGINE_UDS_PATH" -> "/var/run/idb.sock"
                else -> null
            }
        }
        assertEquals("/var/run/idb.sock", cfg.udsPath)
    }

    @Test
    fun `IDB_ENGINE_PIPE_NAME overrides default pipe name`() {
        val cfg = IpcConfig.fromEnv {
            when (it) {
                "IDB_ENGINE_IPC" -> "pipe"
                "IDB_ENGINE_PIPE_NAME" -> "my-app"
                else -> null
            }
        }
        assertEquals("my-app", cfg.pipeName)
    }

    @Test
    fun `invalid IDB_ENGINE_IPC throws with clear message`() {
        val ex = assertThrows<IllegalStateException> {
            IpcConfig.fromEnv { if (it == "IDB_ENGINE_IPC") "websocket" else null }
        }
        assertTrue(ex.message!!.contains("Invalid IDB_ENGINE_IPC"))
    }

    @Test
    fun `default uds path uses XDG_RUNTIME_DIR when available on POSIX`() {
        val cfg = IpcConfig.fromEnv {
            if (it == "XDG_RUNTIME_DIR") "/run/user/1000" else null
        }
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("win")) {
            assertEquals("/run/user/1000/idb-engine.sock", cfg.udsPath)
        } else {
            assertEquals("/tmp/idb-engine.sock", cfg.udsPath)
        }
    }
}