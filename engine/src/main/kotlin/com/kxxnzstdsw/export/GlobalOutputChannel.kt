package com.kxxnzstdsw.export

import kotlinx.coroutines.channels.Channel

/**
 * 全局输出 Channel
 *
 * 子进程管理器（如 ExportProcessManager）需要将子进程响应转发到主进程 stdout。
 * 由于 stdout 是单线程串行化的，需要通过这个全局 Channel 将消息送回主进程的
 * 输出循环，避免多线程同时 println 造成的输出混乱。
 */
object GlobalOutputChannel {

    @Volatile
    var channel: Channel<String>? = null
}
