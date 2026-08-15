package com.kxxnzstdsw

import com.kxxnzstdsw.export.ExportCommand
import com.kxxnzstdsw.export.ExportCommandKind
import com.kxxnzstdsw.models.Action
import com.kxxnzstdsw.models.Category
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Request
import com.kxxnzstdsw.models.Response
import com.kxxnzstdsw.proto.PayloadValue
import com.kxxnzstdsw.proto.PayloadValueKind
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire 格式（protobuf）单元测试
 *
 * 不依赖子进程或真实数据库连接 — 只验证 Request / Response / ExportCommand
 * 通过 kotlinx-serialization-protobuf 编码后能被正确解码，所有字段 round-trip。
 *
 * 这些测试同时验证了 wire 兼容性：
 * - Kotlin 端编出的字节可以被 Kotlin 端正确解码
 * - 与外部 protobuf 实现（如 google.golang.org/protobuf）的兼容性由字节格式保证
 */
@OptIn(ExperimentalSerializationApi::class)
class WireFormatTest {

    private val proto = ProtoBuf { encodeDefaults = true }

    // ============ PayloadValue round-trip ============

    @Test
    fun `PayloadValue NULL round-trips through protobuf`() {
        val original = PayloadValue.NULL
        val bytes = proto.encodeToByteArray(PayloadValue.serializer(), original)
        val decoded = proto.decodeFromByteArray(PayloadValue.serializer(), bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `PayloadValue NUMBER (int) round-trips`() {
        val original = PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 12345.0)
        val decoded = proto.decodeFromByteArray(PayloadValue.serializer(),
            proto.encodeToByteArray(PayloadValue.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun `PayloadValue NUMBER (double) round-trips`() {
        val original = PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 3.14159265358979)
        val decoded = proto.decodeFromByteArray(PayloadValue.serializer(),
            proto.encodeToByteArray(PayloadValue.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun `PayloadValue STRING (unicode) round-trips`() {
        val original = PayloadValue(kind = PayloadValueKind.STRING, stringValue = "你好，世界 🌍")
        val decoded = proto.decodeFromByteArray(PayloadValue.serializer(),
            proto.encodeToByteArray(PayloadValue.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun `PayloadValue BOOL (true and false) round-trips`() {
        for (b in listOf(true, false)) {
            val original = PayloadValue(kind = PayloadValueKind.BOOL, boolValue = b)
            val decoded = proto.decodeFromByteArray(PayloadValue.serializer(),
                proto.encodeToByteArray(PayloadValue.serializer(), original))
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `PayloadValue STRUCT (nested) round-trips`() {
        val original = PayloadValue(
            kind = PayloadValueKind.STRUCT,
            structValue = mapOf(
                "name" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "carol"),
                "age" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 28.0),
                "admin" to PayloadValue(kind = PayloadValueKind.BOOL, boolValue = true),
                "meta" to PayloadValue(
                    kind = PayloadValueKind.STRUCT,
                    structValue = mapOf(
                        "joined" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "2024-01-01"),
                        "score" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 99.9)
                    )
                )
            )
        )
        val decoded = proto.decodeFromByteArray(PayloadValue.serializer(),
            proto.encodeToByteArray(PayloadValue.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun `PayloadValue LIST (heterogeneous) round-trips`() {
        val original = PayloadValue(
            kind = PayloadValueKind.LIST,
            listValue = listOf(
                PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 1.0),
                PayloadValue(kind = PayloadValueKind.STRING, stringValue = "two"),
                PayloadValue.NULL,
                PayloadValue(kind = PayloadValueKind.BOOL, boolValue = false)
            )
        )
        val decoded = proto.decodeFromByteArray(PayloadValue.serializer(),
            proto.encodeToByteArray(PayloadValue.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun `empty map (request with no payload) round-trips`() {
        val original = emptyMap<String, PayloadValue>()
        val mapSerializer = MapSerializer(String.serializer(), PayloadValue.serializer())
        val bytes = proto.encodeToByteArray(mapSerializer, original)
        val decoded = proto.decodeFromByteArray(mapSerializer, bytes)
        assertEquals(0, decoded.size)
    }

    // ============ Request round-trip ============

    @Test
    fun `Request with empty payload round-trips`() {
        val original = Request(
            id = "req-001",
            category = Category.SYSTEM,
            action = Action.INFO,
            connection = ConnectionConfig("Mysql", "127.0.0.1", 3306, "root", "secret", "mysql"),
            payload = emptyMap()
        )
        val decoded = proto.decodeFromByteArray(Request.serializer(),
            proto.encodeToByteArray(Request.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun `Request with full payload struct round-trips`() {
        val original = Request(
            id = "req-002",
            category = Category.DATA,
            action = Action.LIST,
            connection = ConnectionConfig("Postgresql", "db.example.com", 5432, "app", "p@ssw0rd!", "production"),
            payload = mapOf(
                "tableName" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "users"),
                "page" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 1.0),
                "pageSize" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 50.0),
                "where" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "age > 18"),
                "orderBy" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "created_at DESC"),
                "includeArchived" to PayloadValue(kind = PayloadValueKind.BOOL, boolValue = false),
                "tags" to PayloadValue(
                    kind = PayloadValueKind.LIST,
                    listValue = listOf(
                        PayloadValue(kind = PayloadValueKind.STRING, stringValue = "active"),
                        PayloadValue(kind = PayloadValueKind.STRING, stringValue = "verified")
                    )
                )
            )
        )
        val decoded = proto.decodeFromByteArray(Request.serializer(),
            proto.encodeToByteArray(Request.serializer(), original))
        assertEquals(original, decoded)
        assertEquals("users", decoded.payload["tableName"]!!.stringValue)
        assertEquals(50.0, decoded.payload["pageSize"]!!.numberValue)
        assertEquals(false, decoded.payload["includeArchived"]!!.boolValue)
    }

    @Test
    fun `Request enums (Category and Action) preserve values`() {
        // 验证 enum 通过 wire 传输后保持原值
        val categories = Category.entries
        val actions = Action.entries
        for (cat in categories) {
            for (act in actions) {
                val req = Request(
                    id = "enum-test",
                    category = cat,
                    action = act,
                    connection = ConnectionConfig("Mysql", "127.0.0.1", 3306, "u", "p", "d"),
                    payload = emptyMap()
                )
                val decoded = proto.decodeFromByteArray(Request.serializer(),
                    proto.encodeToByteArray(Request.serializer(), req))
                assertEquals(cat, decoded.category, "Category $cat round-trip mismatch")
                assertEquals(act, decoded.action, "Action $act round-trip mismatch")
            }
        }
    }

    @Test
    fun `Request with special chars in string fields round-trips`() {
        val original = Request(
            id = "req-with-\n-newline-and-\"-quote",
            category = Category.SCHEMA,
            action = Action.CREATE,
            connection = ConnectionConfig(
                driver = "Mysql\\driver",
                host = "host with spaces",
                port = 3306,
                user = "user@example.com",
                password = "p@ss\"w0rd",
                database = "db/with/slashes"
            ),
            payload = mapOf(
                "name" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "table\nwith\nnewlines")
            )
        )
        val decoded = proto.decodeFromByteArray(Request.serializer(),
            proto.encodeToByteArray(Request.serializer(), original))
        assertEquals(original, decoded)
    }

    // ============ Response round-trip ============

    @Test
    fun `Response success with empty error and NULL data round-trips`() {
        val original = Response(id = "resp-001", success = true)
        val decoded = proto.decodeFromByteArray(Response.serializer(),
            proto.encodeToByteArray(Response.serializer(), original))
        assertEquals(original, decoded)
        assertTrue(decoded.success)
        assertFalse(decoded.isError)
        assertEquals("", decoded.error)
        assertEquals(PayloadValueKind.NULL, decoded.data.kind)
    }

    @Test
    fun `Response success with STRUCT data (export progress) round-trips`() {
        val original = Response(
            id = "resp-export-001",
            success = true,
            stream = true,
            end = false,
            data = PayloadValue(
                kind = PayloadValueKind.STRUCT,
                structValue = mapOf(
                    "exportedRows" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 5000.0),
                    "columnCount" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 8.0),
                    "completed" to PayloadValue(kind = PayloadValueKind.BOOL, boolValue = false),
                    "filePath" to PayloadValue.NULL,
                    "error" to PayloadValue.NULL
                )
            )
        )
        val decoded = proto.decodeFromByteArray(Response.serializer(),
            proto.encodeToByteArray(Response.serializer(), original))
        assertEquals(original, decoded)
        assertEquals(5000.0, decoded.data.structValue["exportedRows"]!!.numberValue)
    }

    @Test
    fun `Response end-of-stream marker round-trips`() {
        val original = Response(id = "resp-end", success = true, stream = true, end = true)
        val decoded = proto.decodeFromByteArray(Response.serializer(),
            proto.encodeToByteArray(Response.serializer(), original))
        assertEquals(original, decoded)
        assertTrue(decoded.stream)
        assertTrue(decoded.end)
    }

    @Test
    fun `Response error (failure) round-trips`() {
        val original = Response(
            id = "resp-error",
            success = false,
            error = "Connection refused: connect timeout after 5000ms"
        )
        val decoded = proto.decodeFromByteArray(Response.serializer(),
            proto.encodeToByteArray(Response.serializer(), original))
        assertEquals(original, decoded)
        assertFalse(decoded.success)
        assertTrue(decoded.isError)
        assertNotNull(decoded.error)
        assertTrue(decoded.error.contains("timeout"))
    }

    @Test
    fun `Response with affectedRows (UPDATE result) round-trips`() {
        // 模拟 SCHEMA / DATA UPDATE 的响应
        val original = Response(
            id = "resp-update",
            success = true,
            data = PayloadValue(
                kind = PayloadValueKind.STRUCT,
                structValue = mapOf(
                    "affectedRows" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 42.0)
                )
            )
        )
        val decoded = proto.decodeFromByteArray(Response.serializer(),
            proto.encodeToByteArray(Response.serializer(), original))
        assertEquals(original, decoded)
        assertEquals(42.0, decoded.data.structValue["affectedRows"]!!.numberValue)
    }

    @Test
    fun `Response with LIST data (table rows from DATA LIST) round-trips`() {
        // 模拟 DATA LIST 的响应（单页多行）
        val original = Response(
            id = "resp-list",
            success = true,
            data = PayloadValue(
                kind = PayloadValueKind.LIST,
                listValue = listOf(
                    PayloadValue(
                        kind = PayloadValueKind.STRUCT,
                        structValue = mapOf(
                            "id" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 1.0),
                            "name" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "alice")
                        )
                    ),
                    PayloadValue(
                        kind = PayloadValueKind.STRUCT,
                        structValue = mapOf(
                            "id" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 2.0),
                            "name" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "bob")
                        )
                    )
                )
            )
        )
        val decoded = proto.decodeFromByteArray(Response.serializer(),
            proto.encodeToByteArray(Response.serializer(), original))
        assertEquals(original, decoded)
        assertEquals(2, decoded.data.listValue.size)
        assertEquals("alice", decoded.data.listValue[0].structValue["name"]!!.stringValue)
    }

    // ============ ExportCommand round-trip ============

    @Test
    fun `ExportCommand CMD_EXIT round-trips`() {
        val original = ExportCommand(kind = ExportCommandKind.CMD_EXIT)
        val decoded = proto.decodeFromByteArray(ExportCommand.serializer(),
            proto.encodeToByteArray(ExportCommand.serializer(), original))
        assertEquals(original, decoded)
        assertEquals(ExportCommandKind.CMD_EXIT, decoded.kind)
    }

    @Test
    fun `ExportCommand STOP_EXPORT round-trips`() {
        val original = ExportCommand(kind = ExportCommandKind.STOP_EXPORT, exportId = "export-12345")
        val decoded = proto.decodeFromByteArray(ExportCommand.serializer(),
            proto.encodeToByteArray(ExportCommand.serializer(), original))
        assertEquals(original, decoded)
        assertEquals(ExportCommandKind.STOP_EXPORT, decoded.kind)
        assertEquals("export-12345", decoded.exportId)
    }

    @Test
    fun `ExportCommand START_EXPORT with full payload round-trips`() {
        val original = ExportCommand(
            kind = ExportCommandKind.START_EXPORT,
            id = "export-001",
            connection = ConnectionConfig("Mysql", "127.0.0.1", 3306, "root", "secret", "test"),
            payload = mapOf(
                "sql" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "SELECT * FROM users"),
                "outputDir" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "/tmp/exports"),
                "fileName" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "users_2024"),
                "format" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "CSV"),
                "fetchSize" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 1000.0)
            )
        )
        val decoded = proto.decodeFromByteArray(ExportCommand.serializer(),
            proto.encodeToByteArray(ExportCommand.serializer(), original))
        assertEquals(original, decoded)
        assertEquals(ExportCommandKind.START_EXPORT, decoded.kind)
        assertEquals("export-001", decoded.id)
        assertEquals("SELECT * FROM users", decoded.payload["sql"]!!.stringValue)
    }

    // ============ Wire compatibility sanity ============

    @Test
    fun `Request produces non-empty wire bytes (not just length-prefix)`() {
        val req = Request(
            id = "wire-test",
            category = Category.SYSTEM,
            action = Action.INFO,
            connection = ConnectionConfig("Mysql", "127.0.0.1", 3306, "u", "p", "d"),
            payload = emptyMap()
        )
        val bytes = proto.encodeToByteArray(Request.serializer(), req)
        assertTrue(bytes.isNotEmpty(), "Encoded bytes should not be empty")
        assertTrue(bytes.size > 5, "Encoded Request should be at least a few bytes (got ${bytes.size})")
    }

    @Test
    fun `Response for SYSTEM INFO produces STRUCT data with JVM fields`() {
        // 模拟 SystemHandler.info() 的输出
        val original = Response(
            id = "sysinfo-test",
            success = true,
            data = PayloadValue(
                kind = PayloadValueKind.STRUCT,
                structValue = mapOf(
                    "jvmVersion" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "21.0.2"),
                    "jvmVendor" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = "Oracle Corporation"),
                    "availableProcessors" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 8.0),
                    "uptime" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 120000.0),
                    "pid" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 12345.0),
                    "memory" to PayloadValue(
                        kind = PayloadValueKind.STRUCT,
                        structValue = mapOf(
                            "max" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 4294967296.0),
                            "total" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 268435456.0),
                            "used" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 134217728.0),
                            "free" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = 134217728.0)
                        )
                    )
                )
            )
        )
        val decoded = proto.decodeFromByteArray(Response.serializer(),
            proto.encodeToByteArray(Response.serializer(), original))
        assertEquals(original, decoded)
        assertEquals("21.0.2", decoded.data.structValue["jvmVersion"]!!.stringValue)
        assertEquals(4294967296.0, decoded.data.structValue["memory"]!!.structValue["max"]!!.numberValue)
    }

    @Test
    fun `ConnectionConfig toHashKey produces consistent key`() {
        val config = ConnectionConfig("Mysql", "127.0.0.1", 3306, "root", "secret", "test")
        assertEquals("Mysql://root@127.0.0.1:3306/test?password=secret", config.toHashKey())
        // 相同配置不同实例产生相同 hash
        assertEquals(config.toHashKey(), config.copy().toHashKey())
        // password 变化产生不同 hash（pool 失效时强制重建）
        assertNotEquals(config.toHashKey(), config.copy(password = "different").toHashKey())
        // driver 变化产生不同 hash
        assertNotEquals(config.toHashKey(), config.copy(driver = "Postgresql").toHashKey())
        // host 变化产生不同 hash
        assertNotEquals(config.toHashKey(), config.copy(host = "other.host").toHashKey())
        // port 变化产生不同 hash
        assertNotEquals(config.toHashKey(), config.copy(port = 3307).toHashKey())
        // user 变化产生不同 hash
        assertNotEquals(config.toHashKey(), config.copy(user = "other_user").toHashKey())
        // database 变化产生不同 hash
        assertNotEquals(config.toHashKey(), config.copy(database = "other_db").toHashKey())
    }
}