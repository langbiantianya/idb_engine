package com.kxxnzstdsw.ipc

/** IPC 传输种类。 */
enum class IpcKind {
    /** TCP loopback（保留与旧 Wails 集成的兼容路径）。 */
    TCP,

    /** Unix Domain Socket（Linux / macOS / BSD）。 */
    UNIX,

    /** Windows 命名管道（Windows only）。 */
    PIPE,
}

/**
 * IPC 传输配置 — 由 [fromEnv] 从环境变量构造。
 *
 * - 显式 `IDB_ENGINE_IPC` 优先于 OS 自动检测
 * - 不设置时：Windows 默认 [IpcKind.PIPE]；POSIX 默认 [IpcKind.UNIX]；其他兜底 [IpcKind.TCP]
 */
data class IpcConfig(
    val kind: IpcKind,
    val tcpPort: Int = 50051,
    val udsPath: String = "/tmp/idb-engine.sock",
    val pipeName: String = "idb-engine",
) {
    companion object {
        /**
         * 解析环境变量。允许注入 [env] 函数便于测试。
         */
        fun fromEnv(env: (String) -> String? = System::getenv): IpcConfig {
            val explicit = env("IDB_ENGINE_IPC")?.trim()?.lowercase()
            val os = System.getProperty("os.name", "").lowercase()
            val kind = when (explicit) {
                null, "" -> when {
                    os.contains("win") -> IpcKind.PIPE
                    else -> IpcKind.UNIX   // POSIX 默认走 UDS
                }
                "tcp"  -> IpcKind.TCP
                "unix" -> IpcKind.UNIX
                "pipe" -> IpcKind.PIPE
                else -> error("Invalid IDB_ENGINE_IPC='$explicit' (expected: tcp | unix | pipe)")
            }
            return IpcConfig(
                kind     = kind,
                tcpPort  = env("IDB_ENGINE_PORT")?.toIntOrNull() ?: 50051,
                udsPath  = env("IDB_ENGINE_UDS_PATH") ?: defaultUdsPath(os),
                pipeName = env("IDB_ENGINE_PIPE_NAME") ?: "idb-engine",
            )
        }

        /** POSIX 优先用 XDG_RUNTIME_DIR，否则 /tmp。 */
        private fun defaultUdsPath(os: String): String {
            val xdg = System.getenv("XDG_RUNTIME_DIR")
            return if (!xdg.isNullOrBlank() && !os.contains("win")) {
                "$xdg/idb-engine.sock"
            } else {
                "/tmp/idb-engine.sock"
            }
        }
    }
}