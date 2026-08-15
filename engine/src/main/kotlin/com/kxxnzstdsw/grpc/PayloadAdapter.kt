package com.kxxnzstdsw.grpc

import com.google.protobuf.ListValue
import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 边界转换器 — google.protobuf.Value ↔ JsonElement
 *
 * 设计目标：业务层（Handler）继续使用 kotlinx.serialization.json 的 JsonObject / JsonElement，
 * 因为 Handler 内部逻辑大量依赖 `payload["xxx"]?.jsonPrimitive?.intOrNull` 这类 API。
 * Wire 层（Request / Response 模型）使用 google.protobuf.Value，由 grpc-java 直接序列化。
 *
 * RequestDispatcher 负责在边界做双向转换；所有 Handler 不需要改动。
 */
object PayloadAdapter {

    // ============ JsonElement → Value ============

    /**
     * JsonElement → google.protobuf.Value
     *
     * - JsonNull → NULL_VALUE
     * - JsonPrimitive(number | boolean | string) → 对应 kind
     * - JsonObject → STRUCT_VALUE
     * - JsonArray → LIST_VALUE
     */
    fun toValue(element: JsonElement): Value {
        return when (element) {
            is JsonNull -> Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
            is JsonPrimitive -> {
                // 关键：先判断是不是字符串（避免 "42" / "true" 被误判为 NUMBER / BOOL）
                if (element.isString) {
                    Value.newBuilder().setStringValue(element.content).build()
                } else {
                    // 非字符串：优先 boolean，其次 number
                    element.booleanOrNull?.let {
                        return Value.newBuilder().setBoolValue(it).build()
                    }
                    element.doubleOrNull?.let {
                        return Value.newBuilder().setNumberValue(it).build()
                    }
                    // 兜底：理论上 isString=false 又有数字/布尔值 — 不应到这里
                    Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
                }
            }
            is JsonObject -> Value.newBuilder()
                .setStructValue(
                    Struct.newBuilder()
                        .apply {
                            element.forEach { (k, v) -> putFields(k, toValue(v)) }
                        }
                        .build()
                )
                .build()
            is JsonArray -> Value.newBuilder()
                .setListValue(
                    ListValue.newBuilder()
                        .apply {
                            element.forEach { addValues(toValue(it)) }
                        }
                        .build()
                )
                .build()
            else -> Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
        }
    }

    /**
     * JsonObject → Map<String, Value>（便捷方法）
     */
    fun toPayloadMap(obj: JsonObject): Map<String, Value> {
        val map = HashMap<String, Value>(obj.size)
        obj.forEach { (k, v) -> map[k] = toValue(v) }
        return map
    }

    /**
     * 空 JsonObject → NULL Value（语义对齐 google.protobuf.Value 默认构造）
     */
    fun nullValue(): Value =
        Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()

    // ============ Value → JsonElement ============

    /**
     * google.protobuf.Value → JsonElement
     */
    fun toJsonElement(value: Value): JsonElement {
        return when (value.kindCase) {
            Value.KindCase.NULL_VALUE -> JsonNull
            Value.KindCase.NUMBER_VALUE -> JsonPrimitive(value.numberValue)
            Value.KindCase.STRING_VALUE -> JsonPrimitive(value.stringValue)
            Value.KindCase.BOOL_VALUE -> JsonPrimitive(value.boolValue)
            Value.KindCase.STRUCT_VALUE -> {
                val fields = value.structValue.fieldsMap
                val obj = LinkedHashMap<String, JsonElement>(fields.size)
                fields.forEach { (k, v) -> obj[k] = toJsonElement(v) }
                JsonObject(obj)
            }
            Value.KindCase.LIST_VALUE -> {
                val arr = value.listValue.valuesList.map { toJsonElement(it) }
                JsonArray(arr)
            }
            Value.KindCase.KIND_NOT_SET -> JsonNull
            else -> JsonNull
        }
    }

    /**
     * Map<String, Value> → JsonObject
     */
    fun toJsonObject(map: Map<String, Value>): JsonObject {
        val obj = LinkedHashMap<String, JsonElement>(map.size)
        map.forEach { (k, v) -> obj[k] = toJsonElement(v) }
        return JsonObject(obj)
    }

    // ============ 便捷：从 Value 提取基础类型 ============

    /** 取出字符串（如果 kindCase == STRING_VALUE），否则返回 null */
    fun asStringOrNull(value: Value): String? =
        if (value.kindCase == Value.KindCase.STRING_VALUE) value.stringValue else null

    /** 取出 Int（如果 kindCase == NUMBER_VALUE 且 numberValue 整数） */
    fun asIntOrNull(value: Value): Int? =
        if (value.kindCase == Value.KindCase.NUMBER_VALUE && value.numberValue % 1.0 == 0.0)
            value.numberValue.toInt() else null

    /** 取出 Long（如果 kindCase == NUMBER_VALUE 且 numberValue 整数） */
    fun asLongOrNull(value: Value): Long? =
        if (value.kindCase == Value.KindCase.NUMBER_VALUE && value.numberValue % 1.0 == 0.0)
            value.numberValue.toLong() else null

    /** 取出 Boolean（如果 kindCase == BOOL_VALUE） */
    fun asBooleanOrNull(value: Value): Boolean? =
        if (value.kindCase == Value.KindCase.BOOL_VALUE) value.boolValue else null
}