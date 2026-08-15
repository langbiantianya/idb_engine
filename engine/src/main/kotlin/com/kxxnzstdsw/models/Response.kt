package com.kxxnzstdsw.models

import com.kxxnzstdsw.proto.PayloadValue
import kotlinx.serialization.Serializable

/**
 * 统一响应体（wire 层）
 *
 * data 用 PayloadValue 而非 JsonElement 是为了让 Response 能直接被
 * kotlinx-serialization-protobuf 序列化。流式响应的结束帧 data 默认就是 NULL。
 *
 * error 用空字符串 `""` 表示无错误（kotlinx-serialization-protobuf 不支持
 * 编码 `null` 给 optional property）。Receiver 检查 `error.isNotEmpty()` 判定失败。
 *
 * 业务层（Handler）继续返回 JsonElement，RequestDispatcher 在边界负责转换。
 */
@Serializable
data class Response(
    val id: String,
    val success: Boolean,
    val error: String = "",
    val stream: Boolean = false,
    val end: Boolean = false,
    val data: PayloadValue = PayloadValue.NULL
) {
    /** 是否处于错误状态（error 非空字符串） */
    val isError: Boolean get() = error.isNotEmpty()
}