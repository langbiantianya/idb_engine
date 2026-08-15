package com.kxxnzstdsw.proto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 边界转换器 — JsonElement ↔ PayloadValue
 *
 * 设计目标：业务层（Handler）继续使用 kotlinx.serialization.json 的 JsonObject / JsonElement，
 * 因为 Handler 内部逻辑大量依赖 `payload["xxx"]?.jsonPrimitive?.intOrNull` 这类 API。
 * Wire 层（Request / Response 模型）使用 Map<String, PayloadValue> / PayloadValue。
 *
 * RequestDispatcher 负责在边界做双向转换；所有 Handler 不需要改动。
 */
object ProtoConverters {

    // ============ JsonElement → PayloadValue ============

    /**
     * JsonElement → PayloadValue
     *
     * - JsonNull → PayloadValue(kind = NULL)
     * - JsonPrimitive(number | boolean | string) → 对应 kind
     * - JsonObject → PayloadValue(kind = STRUCT, structValue)
     * - JsonArray → PayloadValue(kind = LIST, listValue)
     */
    fun toPayloadValue(element: JsonElement): PayloadValue {
        return when (element) {
            is JsonNull -> PayloadValue.NULL
            is JsonPrimitive -> {
                // 关键：先判断是不是字符串（避免 "42" / "true" 被误判为 NUMBER / BOOL）
                // kotlinx-serialization 的 doubleOrNull / booleanOrNull 会尝试解析字符串内容
                if (element.isString) {
                    PayloadValue(kind = PayloadValueKind.STRING, stringValue = element.content)
                } else {
                    // 非字符串：优先 boolean，其次 number
                    element.booleanOrNull?.let {
                        return PayloadValue(kind = PayloadValueKind.BOOL, boolValue = it)
                    }
                    element.doubleOrNull?.let {
                        return PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = it)
                    }
                    // 兜底：理论上 isString=false 又有数字/布尔值 — 不应到这里
                    PayloadValue.NULL
                }
            }
            is JsonObject -> PayloadValue(
                kind = PayloadValueKind.STRUCT,
                structValue = element.mapValues { (_, v) -> toPayloadValue(v) }
            )
            is JsonArray -> PayloadValue(
                kind = PayloadValueKind.LIST,
                listValue = element.map { toPayloadValue(it) }
            )
            else -> PayloadValue.NULL
        }
    }

    /**
     * JsonObject → Map<String, PayloadValue>（便捷方法）
     */
    fun toPayloadMap(obj: JsonObject): Map<String, PayloadValue> {
        return obj.mapValues { (_, v) -> toPayloadValue(v) }
    }

    // ============ PayloadValue → JsonElement ============

    /**
     * PayloadValue → JsonElement
     *
     * 用于把 Handler 返回的 JsonElement / 内部构造的 JsonObject 转回 PayloadValue 后，
     * 反向转回给需要构造 JsonObject 的场景（理论上 RequestDispatcher 不需要这个方向，
     * 但保留以便后续扩展及测试使用）。
     */
    fun toJsonElement(value: PayloadValue): JsonElement {
        return when (value.kind) {
            PayloadValueKind.NULL -> JsonNull
            PayloadValueKind.NUMBER -> JsonPrimitive(value.numberValue)
            PayloadValueKind.STRING -> JsonPrimitive(value.stringValue)
            PayloadValueKind.BOOL -> JsonPrimitive(value.boolValue)
            PayloadValueKind.STRUCT -> JsonObject(
                value.structValue.mapValues { (_, v) -> toJsonElement(v) }
            )
            PayloadValueKind.LIST -> JsonArray(value.listValue.map { toJsonElement(it) })
        }
    }

    /**
     * Map<String, PayloadValue> → JsonObject
     */
    fun toJsonObject(map: Map<String, PayloadValue>): JsonObject {
        return JsonObject(map.mapValues { (_, v) -> toJsonElement(v) })
    }

    // ============ 便捷：从 PayloadValue 提取基础类型 ============

    /** 取出字符串（如果 kind == STRING），否则返回 null */
    fun asStringOrNull(value: PayloadValue): String? =
        if (value.kind == PayloadValueKind.STRING) value.stringValue else null

    /** 取出 Int（如果 kind == NUMBER 且 numberValue 整数） */
    fun asIntOrNull(value: PayloadValue): Int? =
        if (value.kind == PayloadValueKind.NUMBER && value.numberValue % 1.0 == 0.0)
            value.numberValue.toInt() else null

    /** 取出 Long（如果 kind == NUMBER 且 numberValue 整数） */
    fun asLongOrNull(value: PayloadValue): Long? =
        if (value.kind == PayloadValueKind.NUMBER && value.numberValue % 1.0 == 0.0)
            value.numberValue.toLong() else null

    /** 取出 Boolean（如果 kind == BOOL） */
    fun asBooleanOrNull(value: PayloadValue): Boolean? =
        if (value.kind == PayloadValueKind.BOOL) value.boolValue else null
}