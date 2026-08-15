package com.kxxnzstdsw.proto

import kotlinx.serialization.Serializable

/**
 * 通用动态值类型 — wire 层数据结构
 *
 * 模仿 google.protobuf.Value 语义，支持 null / number / string / bool / struct / list 六种值。
 * wire 上以扁平字段编码（kind + 五种 payload 字段），运行期由 [kind] 决定哪个字段有效。
 * 之所以不用 kotlinx-serialization 的 sealed class，是因为 kotlinx-serialization-protobuf
 * 对 sealed class 的多态支持需要 @SerialName + 类 id 注册表，过于繁重。
 *
 * 该类型在 Request / Response 边界使用，业务层（Handler）继续使用 JsonObject / JsonElement，
 * RequestDispatcher 在边界负责双向转换（见 ProtoConverters）。
 */
@Serializable
data class PayloadValue(
    /** 值类型标签（NULL / NUMBER / STRING / BOOL / STRUCT / LIST） */
    val kind: PayloadValueKind = PayloadValueKind.NULL,
    /** 当 kind == NUMBER 时有效（统一用 Double 表示所有数字） */
    val numberValue: Double = 0.0,
    /** 当 kind == STRING 时有效 */
    val stringValue: String = "",
    /** 当 kind == BOOL 时有效 */
    val boolValue: Boolean = false,
    /** 当 kind == STRUCT 时有效（键值对集合） */
    val structValue: Map<String, PayloadValue> = emptyMap(),
    /** 当 kind == LIST 时有效（有序数组） */
    val listValue: List<PayloadValue> = emptyList()
) {
    companion object {
        /** 单例：表示 null */
        val NULL: PayloadValue = PayloadValue()
    }
}

/** PayloadValue 的类型标签 */
@Serializable
enum class PayloadValueKind {
    NULL,
    NUMBER,
    STRING,
    BOOL,
    STRUCT,
    LIST
}