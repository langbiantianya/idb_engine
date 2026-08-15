package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.SqlEngineHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlEngineHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `EXECUTE SELECT returns rows`() = runBlocking {
        executeUpdate("CREATE TABLE t (id INT, name VARCHAR(50))")
        executeUpdate("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')")
        val result = SqlEngineHandler.execute(config, buildJsonObject {
            put("sql", "SELECT * FROM t ORDER BY id")
        }) as JsonElement
        val arr = (result as JsonArray)
        assertEquals(3, arr.size)
        assertEquals("a", arr[0].jsonObject["NAME"]?.jsonPrimitive?.content)
    }

    @Test
    fun `EXECUTE INSERT returns affectedRows`() = runBlocking {
        executeUpdate("CREATE TABLE t (id INT, name VARCHAR(50))")
        val result = SqlEngineHandler.execute(config, buildJsonObject {
            put("sql", "INSERT INTO t VALUES (1, 'a'), (2, 'b')")
        }) as JsonElement
        assertEquals(2, result.jsonObject["affectedRows"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `EXECUTE UPDATE returns affectedRows`() = runBlocking {
        executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))")
        executeUpdate("INSERT INTO t VALUES (1, 'old')")
        val result = SqlEngineHandler.execute(config, buildJsonObject {
            put("sql", "UPDATE t SET name='new' WHERE id=1")
        }) as JsonElement
        assertEquals(1, result.jsonObject["affectedRows"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `EXECUTE DELETE returns affectedRows`() = runBlocking {
        executeUpdate("CREATE TABLE t (id INT PRIMARY KEY)")
        executeUpdate("INSERT INTO t VALUES (1), (2)")
        val result = SqlEngineHandler.execute(config, buildJsonObject {
            put("sql", "DELETE FROM t WHERE id=1")
        }) as JsonElement
        assertEquals(1, result.jsonObject["affectedRows"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `EXECUTE DDL returns 0 affectedRows`() = runBlocking {
        val result = SqlEngineHandler.execute(config, buildJsonObject {
            put("sql", "CREATE TABLE new_t (id INT)")
        }) as JsonElement
        assertEquals(0, result.jsonObject["affectedRows"]?.jsonPrimitive?.intOrNull)
        assertTrue(tableExists("new_t"))
    }

    @Test
    fun `EXPLAIN returns plan rows for a SELECT`() = runBlocking {
        executeUpdate("CREATE TABLE t (id INT)")
        val result = SqlEngineHandler.explain(config, buildJsonObject {
            put("sql", "SELECT * FROM t")
        })
        assertTrue(result is JsonArray)
        assertTrue(result.jsonArray.isNotEmpty())
    }

    @Test
    fun `EXPLAIN rejects semicolon`() = runBlocking {
        try {
            SqlEngineHandler.explain(config, buildJsonObject {
                put("sql", "SELECT 1; DROP TABLE x")
            })
            error("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("分号"))
        }
    }
}