package com.kxxnzstdsw.ipc

import com.kxxnzstdsw.ipc.impl.NamedPipeIpcTransport
import com.kxxnzstdsw.ipc.impl.TcpIpcTransport
import com.kxxnzstdsw.ipc.impl.UnixSocketIpcTransport

/**
 * 按 [IpcConfig] 选择具体 [IpcTransport] 实现。
 *
 * 选择规则：
 *  - [IpcKind.TCP]  任意平台
 *  - [IpcKind.UNIX] 仅 POSIX（Windows 会抛错）
 *  - [IpcKind.PIPE] 仅 Windows（POSIX 会抛错）
 */
object IpcTransportRegistry {

    fun resolve(config: IpcConfig): IpcTransport = when (config.kind) {
        IpcKind.TCP -> TcpIpcTransport(config)
        IpcKind.UNIX -> {
            require(!isWindows()) {
                "Unix domain sockets are not supported on Windows — pass --ipc=tcp or --ipc=pipe"
            }
            UnixSocketIpcTransport(config)
        }
        IpcKind.PIPE -> {
            require(isWindows()) {
                "Named pipes are only supported on Windows — pass --ipc=tcp or --ipc=unix"
            }
            NamedPipeIpcTransport(config)
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")
}