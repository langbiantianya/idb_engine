package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.SchemaHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST defaults to database level — H2 returns current catalog`() = runBlocking {
        // 默认 level=database → H2 listDatabases 返回 [config.database]
        val result = SchemaHandler.list(config, JsonObject(emptyMap()))
        val obj = result.jsonObject
        assertEquals("database", obj["level"]?.jsonPrimitive?.content)
        val items = obj["items"]?.jsonArray ?: error("missing items array")
        assertTrue(items.size == 1)
        // H2 conn.catalog 通常大写
        assertTrue(items[0].jsonPrimitive.content.equals(dbName, ignoreCase = true))
    }

    @Test
    fun `LIST level=database returns catalog`() = runBlocking {
        val payload = buildJsonObject { put("level", "database") }
        val result = SchemaHandler.list(config, payload)
        val obj = result.jsonObject
        assertEquals("database", obj["level"]?.jsonPrimitive?.content)
        val items = obj["items"]?.jsonArray ?: error("missing items array")
        assertTrue(items.any { it.jsonPrimitive.content.equals(dbName, ignoreCase = true) })
    }

    @Test
    fun `LIST level=schema returns all schemas including PUBLIC`() = runBlocking {
        val payload = buildJsonObject {
            put("level", "schema")
            put("database", dbName)
        }
        val result = SchemaHandler.list(config, payload)
        val obj = result.jsonObject
        assertEquals("schema", obj["level"]?.jsonPrimitive?.content)
        assertEquals(dbName, obj["database"]?.jsonPrimitive?.content)
        val items = obj["items"]?.jsonArray ?: error("missing items array")
        assertTrue(items.size >= 1)
        assertTrue(items.any { it.jsonPrimitive.content.equals("PUBLIC", ignoreCase = true) })
    }

    @Test
    fun `LIST level=schema without database throws`() = runBlocking {
        val payload = buildJsonObject { put("level", "schema") }
        val ex = kotlin.runCatching {
            SchemaHandler.list(config, payload)
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IllegalArgumentException but got ${ex?.javaClass?.simpleName}")
    }

    @Test
    fun `LIST level=invalid throws`() = runBlocking {
        val payload = buildJsonObject { put("level", "garbage") }
        val ex = kotlin.runCatching {
            SchemaHandler.list(config, payload)
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `CREATE schema then DELETE schema`() = runBlocking {
        SchemaHandler.create(config, buildJsonObject { put("name", "test_schema") })
        assertTrue(schemaExists("test_schema"))

        SchemaHandler.delete(config, buildJsonObject { put("name", "test_schema") })
        assertFalse(schemaExists("test_schema"))
    }
}