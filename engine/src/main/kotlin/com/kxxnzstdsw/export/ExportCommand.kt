package com.kxxnzstdsw.export

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.proto.PayloadValue
import kotlinx.serialization.Serializable

/**
 * 父进程 → 子进程的指令（wire 层消息）
 *
 * 与主进程的 Request 类似，使用 Map<String, PayloadValue> 作为 payload 以便
 * kotlinx-serialization-protobuf 直接序列化。kind 字段做 union 判别式：
 * - START_EXPORT: id + connection + payload 都有效
 * - STOP_EXPORT: exportId 有效
 * - CMD_EXIT: 全部字段忽略
 *
 * 注意：这些字段必须保留默认值，否则 wire 上未设值的字段会被 ProtoBuf 跳过
 * （但保留默认值后 decode 端能拿到正确的"未设值"语义）。
 */
@Serializable
data class ExportCommand(
    val kind: ExportCommandKind = ExportCommandKind.CMD_EXIT,
    val id: String = "",
    val connection: ConnectionConfig = ConnectionConfig(
        driver = "",
        host = "",
        port = 0,
        user = "",
        password = "",
        database = ""
    ),
    val payload: Map<String, PayloadValue> = emptyMap(),
    val exportId: String = ""
)

/** ExportCommand 的指令类型 */
@Serializable
enum class ExportCommandKind {
    START_EXPORT,
    STOP_EXPORT,
    CMD_EXIT
}