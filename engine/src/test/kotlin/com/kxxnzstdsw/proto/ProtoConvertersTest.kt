package com.kxxnzstdsw.proto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ProtoConverters 单元测试
 *
 * 覆盖 JsonElement ↔ PayloadValue 双向转换的所有路径：
 * - null / number (整数 / 浮点 / 负数 / 0) / string / bool
 * - flat struct / nested struct / list / mixed list
 * - asStringOrNull / asIntOrNull / asLongOrNull / asBooleanOrNull 便捷提取
 */
class ProtoConvertersTest {

    // ============ JsonElement → PayloadValue ============

    @Test
    fun `JsonNull converts to PayloadValue NULL`() {
        val payload = ProtoConverters.toPayloadValue(JsonNull)
        assertEquals(PayloadValueKind.NULL, payload.kind)
    }

    @Test
    fun `integer primitive converts to NUMBER (double)`() {
        val payload = ProtoConverters.toPayloadValue(JsonPrimitive(42))
        assertEquals(PayloadValueKind.NUMBER, payload.kind)
        assertEquals(42.0, payload.numberValue)
    }

    @Test
    fun `negative integer primitive converts to NUMBER`() {
        val payload = ProtoConverters.toPayloadValue(JsonPrimitive(-1234))
        assertEquals(PayloadValueKind.NUMBER, payload.kind)
        assertEquals(-1234.0, payload.numberValue)
    }

    @Test
    fun `zero primitive converts to NUMBER`() {
        val payload = ProtoConverters.toPayloadValue(JsonPrimitive(0))
        assertEquals(PayloadValueKind.NUMBER, payload.kind)
        assertEquals(0.0, payload.numberValue)
    }

    @Test
    fun `double primitive converts to NUMBER`() {
        val payload = ProtoConverters.toPayloadValue(JsonPrimitive(3.14159))
        assertEquals(PayloadValueKind.NUMBER, payload.kind)
        assertEquals(3.14159, payload.numberValue)
    }

    @Test
    fun `boolean true converts to BOOL`() {
        val payload = ProtoConverters.toPayloadValue(JsonPrimitive(true))
        assertEquals(PayloadValueKind.BOOL, payload.kind)
        assertEquals(true, payload.boolValue)
    }

    @Test
    fun `boolean false converts to BOOL`() {
        val payload = ProtoConverters.toPayloadValue(JsonPrimitive(false))
        assertEquals(PayloadValueKind.BOOL, payload.kind)
        assertEquals(false, payload.boolValue)
    }

    @Test
    fun `string primitive converts to STRING`() {
        val payload = ProtoConverters.toPayloadValue(JsonPrimitive("hello world"))
        assertEquals(PayloadValueKind.STRING, payload.kind)
        assertEquals("hello world", payload.stringValue)
    }

    @Test
    fun `numeric string stays STRING (not converted to NUMBER)`() {
        // 字符串 "42" 必须保持 STRING，不能误判为 NUMBER
        val payload = ProtoConverters.toPayloadValue(JsonPrimitive("42"))
        assertEquals(PayloadValueKind.STRING, payload.kind)
        assertEquals("42", payload.stringValue)
    }

    @Test
    fun `boolean-like string stays STRING`() {
        val payload = ProtoConverters.toPayloadValue(JsonPrimitive("true"))
        assertEquals(PayloadValueKind.STRING, payload.kind)
        assertEquals("true", payload.stringValue)
    }

    @Test
    fun `flat JsonObject converts to STRUCT`() {
        val obj = buildJsonObject {
            put("name", "alice")
            put("age", 30)
            put("active", true)
        }
        val payload = ProtoConverters.toPayloadValue(obj)
        assertEquals(PayloadValueKind.STRUCT, payload.kind)
        assertEquals(3, payload.structValue.size)

        val nameVal = payload.structValue["name"]!!
        assertEquals(PayloadValueKind.STRING, nameVal.kind)
        assertEquals("alice", nameVal.stringValue)

        val ageVal = payload.structValue["age"]!!
        assertEquals(PayloadValueKind.NUMBER, ageVal.kind)
        assertEquals(30.0, ageVal.numberValue)

        val activeVal = payload.structValue["active"]!!
        assertEquals(PayloadValueKind.BOOL, activeVal.kind)
        assertEquals(true, activeVal.boolValue)
    }

    @Test
    fun `nested JsonObject converts recursively`() {
        val obj = buildJsonObject {
            put("user", buildJsonObject {
                put("name", "bob")
                put("meta", buildJsonObject {
                    put("id", 999)
                    put("verified", true)
                })
            })
        }
        val payload = ProtoConverters.toPayloadValue(obj)
        val user = payload.structValue["user"]!!
        assertEquals(PayloadValueKind.STRUCT, user.kind)
        val meta = user.structValue["meta"]!!
        assertEquals(PayloadValueKind.STRUCT, meta.kind)
        assertEquals(999.0, meta.structValue["id"]!!.numberValue)
        assertEquals(true, meta.structValue["verified"]!!.boolValue)
    }

    @Test
    fun `JsonArray converts to LIST`() {
        val arr = buildJsonArray {
            add(JsonPrimitive(1))
            add(JsonPrimitive("two"))
            add(JsonPrimitive(true))
            add(JsonNull)
        }
        val payload = ProtoConverters.toPayloadValue(arr)
        assertEquals(PayloadValueKind.LIST, payload.kind)
        assertEquals(4, payload.listValue.size)

        assertEquals(PayloadValueKind.NUMBER, payload.listValue[0].kind)
        assertEquals(1.0, payload.listValue[0].numberValue)

        assertEquals(PayloadValueKind.STRING, payload.listValue[1].kind)
        assertEquals("two", payload.listValue[1].stringValue)

        assertEquals(PayloadValueKind.BOOL, payload.listValue[2].kind)
        assertEquals(true, payload.listValue[2].boolValue)

        assertEquals(PayloadValueKind.NULL, payload.listValue[3].kind)
    }

    @Test
    fun `nested array of objects converts to LIST of STRUCT`() {
        val arr = buildJsonArray {
            add(buildJsonObject { put("id", 1) })
            add(buildJsonObject { put("id", 2) })
        }
        val payload = ProtoConverters.toPayloadValue(arr)
        assertEquals(PayloadValueKind.LIST, payload.kind)
        assertEquals(2, payload.listValue.size)
        payload.listValue.forEach {
            assertEquals(PayloadValueKind.STRUCT, it.kind)
        }
        assertEquals(1.0, payload.listValue[0].structValue["id"]!!.numberValue)
        assertEquals(2.0, payload.listValue[1].structValue["id"]!!.numberValue)
    }

    // ============ PayloadValue → JsonElement ============

    @Test
    fun `PayloadValue NULL converts to JsonNull`() {
        assertEquals(JsonNull, ProtoConverters.toJsonElement(PayloadValue.NULL))
    }

    @Test
    fun `PayloadValue NUMBER converts to JsonPrimitive(double)`() {
        val payload = PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 2.71828)
        val json = ProtoConverters.toJsonElement(payload)
        val prim = assertIs<JsonPrimitive>(json)
        assertEquals(2.71828, prim.double)
    }

    @Test
    fun `PayloadValue STRING converts to JsonPrimitive(string)`() {
        val payload = PayloadValue(kind = PayloadValueKind.STRING, stringValue = "kotlin")
        val json = ProtoConverters.toJsonElement(payload)
        val prim = assertIs<JsonPrimitive>(json)
        assertEquals("kotlin", prim.content)
    }

    @Test
    fun `PayloadValue BOOL converts to JsonPrimitive(boolean)`() {
        val payload = PayloadValue(kind = PayloadValueKind.BOOL, boolValue = true)
        val json = ProtoConverters.toJsonElement(payload)
        val prim = assertIs<JsonPrimitive>(json)
        assertEquals(true, prim.boolean)
    }

    @Test
    fun `PayloadValue STRUCT converts to JsonObject`() {
        val payload = PayloadValue(
            kind = PayloadValueKind.STRUCT,
            structValue = mapOf(
                "name" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "carol"),
                "score" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 95.5)
            )
        )
        val json = ProtoConverters.toJsonElement(payload)
        val obj = assertIs<JsonObject>(json)
        assertEquals(2, obj.size)
        assertEquals("carol", obj["name"]!!.jsonPrimitive.content)
        // numberValue 始终是 Double，反向转回 JsonPrimitive 也是 Double
        assertEquals(95.5, obj["score"]!!.jsonPrimitive.double)
    }

    @Test
    fun `PayloadValue LIST converts to JsonArray`() {
        val payload = PayloadValue(
            kind = PayloadValueKind.LIST,
            listValue = listOf(
                PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 1.0),
                PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 2.0),
                PayloadValue(kind = PayloadValueKind.STRING, stringValue = "three")
            )
        )
        val json = ProtoConverters.toJsonElement(payload)
        val arr = assertIs<JsonArray>(json)
        assertEquals(3, arr.size)
        // numberValue 始终是 Double，反向转回也是 double 形式（1.0 / 2.0）
        assertEquals(1.0, arr[0].jsonPrimitive.double)
        assertEquals(2.0, arr[1].jsonPrimitive.double)
        assertEquals("three", arr[2].jsonPrimitive.content)
    }

    // ============ 双向 round-trip ============

    @Test
    fun `round-trip simple JsonObject via toPayloadMap then toJsonObject preserves values`() {
        // 注意：PayloadValue.numberValue 统一用 Double 表示，所以 int 经过 wire 反序列化后是 double
        // 这是 protobuf wire format 的限制（protobuf 没有 int / long 区分，统一 float64）
        // 用 .5 后缀构造以确保 round-trip 后类型一致
        val original = buildJsonObject {
            put("a", 1.0)
            put("b", "two")
            put("c", true)
            put("d", JsonNull)
        }
        val back = ProtoConverters.toJsonObject(ProtoConverters.toPayloadMap(original))
        assertEquals(original, back)
    }

    @Test
    fun `round-trip preserves integer values (1 becomes 1_0 after wire)`() {
        // 整数经 PayloadValue (Double) → JsonPrimitive 后变成 1.0（double 形式）
        // 业务层使用 ProtoConverters.asLongOrNull / asIntOrNull 提取，可以正常得到 Int / Long
        val original = buildJsonObject {
            put("count", 42)
        }
        val payload = ProtoConverters.toPayloadMap(original)
        val back = ProtoConverters.toJsonObject(payload)
        // 反序列化后是 double 形式 42.0
        assertEquals(42.0, back["count"]!!.jsonPrimitive.double)
        // 但通过 ProtoConverters.asIntOrNull 仍可正确取出
        assertEquals(42, ProtoConverters.asIntOrNull(payload["count"]!!))
    }

    @Test
    fun `round-trip complex nested struct is identity`() {
        // 构造时所有数字都用 double 后缀 .0 ，以保持 PayloadValue Double 表示下的 round-trip 恒等
        val original = buildJsonObject {
            put("user", buildJsonObject {
                put("id", 42.0)
                put("name", "alice")
                put("tags", buildJsonArray {
                    add(JsonPrimitive("admin"))
                    add(JsonPrimitive("user"))
                })
                put("profile", buildJsonObject {
                    put("age", 30.0)
                    put("verified", true)
                    put("deletedAt", JsonNull)
                })
            })
            put("count", 1.0)
        }
        val back = ProtoConverters.toJsonObject(ProtoConverters.toPayloadMap(original))
        assertEquals(original, back)
    }

    // ============ 便捷提取函数 ============

    @Test
    fun `asStringOrNull returns string only for STRING kind`() {
        assertEquals("hello", ProtoConverters.asStringOrNull(
            PayloadValue(kind = PayloadValueKind.STRING, stringValue = "hello")
        ))
        assertNull(ProtoConverters.asStringOrNull(
            PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 42.0)
        ))
        assertNull(ProtoConverters.asStringOrNull(PayloadValue.NULL))
    }

    @Test
    fun `asIntOrNull returns int only for integer NUMBER kind`() {
        assertEquals(42, ProtoConverters.asIntOrNull(
            PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 42.0)
        ))
        // 浮点数应返回 null（不是整数）
        assertNull(ProtoConverters.asIntOrNull(
            PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 42.5)
        ))
        assertNull(ProtoConverters.asIntOrNull(
            PayloadValue(kind = PayloadValueKind.STRING, stringValue = "42")
        ))
        assertNull(ProtoConverters.asIntOrNull(PayloadValue.NULL))
    }

    @Test
    fun `asLongOrNull handles values larger than Int`() {
        val big = 5_000_000_000L  // > Int.MAX_VALUE
        val payload = PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = big.toDouble())
        assertEquals(big, ProtoConverters.asLongOrNull(payload))
    }

    @Test
    fun `asBooleanOrNull returns boolean only for BOOL kind`() {
        assertEquals(true, ProtoConverters.asBooleanOrNull(
            PayloadValue(kind = PayloadValueKind.BOOL, boolValue = true)
        ))
        assertEquals(false, ProtoConverters.asBooleanOrNull(
            PayloadValue(kind = PayloadValueKind.BOOL, boolValue = false)
        ))
        assertNull(ProtoConverters.asBooleanOrNull(
            PayloadValue(kind = PayloadValueKind.STRING, stringValue = "true")
        ))
        assertNull(ProtoConverters.asBooleanOrNull(PayloadValue.NULL))
    }

    @Test
    fun `asIntOrNull correctly identifies negative integers`() {
        assertEquals(-100, ProtoConverters.asIntOrNull(
            PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = -100.0)
        ))
    }

    @Test
    fun `PayloadValue NULL singleton is shared (instance identity)`() {
        // NULL 单例 — 多次引用返回同一个对象
        assertTrue(PayloadValue.NULL === PayloadValue.NULL)
        assertEquals(PayloadValueKind.NULL, PayloadValue.NULL.kind)
    }
}