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
 * IPC 传输配置 — 由 [fromArgs] 从 CLI 参数构造。
 *
 * - 未传 `--ipc` 时按 OS 自动检测：Windows → [IpcKind.PIPE]；POSIX → [IpcKind.UNIX]
 * - 各字段默认值见 [fromArgs]
 */
data class IpcConfig(
    val kind: IpcKind,
    val tcpPort: Int = 50051,
    val udsPath: String = "/tmp/idb-engine.sock",
    val pipeName: String = "idb-engine",
) {
    companion object {
        val USAGE: String = """
            |IDB Engine — gRPC server
            |
            |Usage: java -jar idb-engine.jar [options]
            |
            |Options:
            |  --ipc <kind>         Transport kind: tcp | unix | pipe
            |                       Default: OS auto-detect (Windows=pipe, POSIX=unix)
            |  --port <int>         TCP port (only when --ipc=tcp). Default: 50051
            |  --uds-path <path>    Unix domain socket path (only when --ipc=unix)
            |                       Default: /tmp/idb-engine.sock
            |  --pipe-name <name>   Windows named pipe name (only when --ipc=pipe)
            |                       Default: idb-engine
            |  --help, -h           Show this help and exit
            |
            |Examples:
            |  java -jar idb-engine.jar
            |  java -jar idb-engine.jar --ipc tcp --port 50051
            |  java -jar idb-engine.jar --ipc unix --uds-path /var/run/idb.sock
            |  java -jar idb-engine.jar --ipc pipe --pipe-name idb-engine
        """.trimMargin()

        /**
         * 解析 CLI 参数。
         *
         * 不抛 checked exception：解析错误统一抛 [IllegalStateException]，
         * 由调用方（[com.kxxnzstdsw.server.IdbEngineServer]）捕获并以非零状态退出。
         */
        fun fromArgs(args: Array<String>): IpcConfig {
            var explicitKind: IpcKind? = null
            var tcpPort = 50051
            var udsPath = "/tmp/idb-engine.sock"
            var pipeName = "idb-engine"

            var i = 0
            while (i < args.size) {
                when (val a = args[i]) {
                    "--help", "-h" -> {
                        print(USAGE)
                        kotlin.system.exitProcess(0)
                    }
                    "--ipc" -> {
                        val v = args.getOrNull(i + 1)
                            ?: error("--ipc requires a value (tcp|unix|pipe)")
                        explicitKind = parseKind(v)
                        i += 2
                    }
                    "--port" -> {
                        val v = args.getOrNull(i + 1)
                            ?: error("--port requires an integer value")
                        val parsed = v.toIntOrNull()
                            ?: error("--port expects an integer, got '$v'")
                        if (parsed !in 0..65535) {
                            error("--port must be in 0..65535, got $parsed")
                        }
                        tcpPort = parsed
                        i += 2
                    }
                    "--uds-path" -> {
                        udsPath = args.getOrNull(i + 1)
                            ?: error("--uds-path requires a path value")
                        i += 2
                    }
                    "--pipe-name" -> {
                        pipeName = args.getOrNull(i + 1)
                            ?: error("--pipe-name requires a name value")
                        i += 2
                    }
                    else -> error("Unknown argument: '$a' (try --help)")
                }
            }

            // OS 自动检测（用户未显式传 --ipc）
            val kind = explicitKind ?: autoDetectKind()

            return IpcConfig(
                kind = kind,
                tcpPort = tcpPort,
                udsPath = udsPath,
                pipeName = pipeName,
            )
        }

        private fun autoDetectKind(): IpcKind {
            val os = System.getProperty("os.name", "").lowercase()
            return if (os.contains("win")) IpcKind.PIPE else IpcKind.UNIX
        }

        private fun parseKind(raw: String): IpcKind = when (raw.trim().lowercase()) {
            "tcp"  -> IpcKind.TCP
            "unix" -> IpcKind.UNIX
            "pipe" -> IpcKind.PIPE
            else   -> error("Invalid --ipc value '$raw' (expected: tcp | unix | pipe)")
        }
    }
}