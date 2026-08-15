package com.kxxnzstdsw.export

import kotlinx.coroutines.channels.Channel

/**
 * 全局输出 Channel
 *
 * 子进程管理器（ExportProcessManager）需要将子进程响应转发到主进程 stdout。
 * 由于 stdout 是单线程串行化的，需要通过这个全局 Channel 将消息送回主进程的
 * 输出循环，避免多线程同时写 stdout 造成的帧交错。
 *
 * 元素类型为 ByteArray（已编码的 protobuf 响应帧），由主进程统一写帧 + flush。
 */
object GlobalOutputChannel {

    @Volatile
    var channel: Channel<ByteArray>? = null
}