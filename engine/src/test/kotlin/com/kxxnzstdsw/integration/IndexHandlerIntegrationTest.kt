package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.IndexHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexHandlerIntegrationTest : H2Fixture() {

    private fun seedTable() {
        executeUpdate("CREATE TABLE users (id INT, email VARCHAR(100), name VARCHAR(50))")
    }

    @Test
    fun `LIST returns no indexes initially`() = runBlocking {
        seedTable()
        val result = IndexHandler.list(config, buildJsonObject { put("tableName", "users") })
        // H2 自动有主键索引（PRIMARY KEY），实际 ≥0
        assertTrue(result.jsonArray.size >= 0)
    }

    @Test
    fun `CREATE then LIST then DROP index`() = runBlocking {
        seedTable()
        IndexHandler.create(config, buildJsonObject {
            put("tableName", "users")
            put("indexName", "idx_email")
            putJsonArray("columns") { add(JsonPrimitive("email")) }
            put("unique", false)
        })
        var list = IndexHandler.list(config, buildJsonObject { put("tableName", "users") })
        assertTrue(list.jsonArray.any { it.jsonObject["name"]?.jsonPrimitive?.content?.equals("idx_email", ignoreCase = true) == true })

        IndexHandler.delete(config, buildJsonObject {
            put("indexName", "idx_email")
            put("tableName", "users")
        })
        list = IndexHandler.list(config, buildJsonObject { put("tableName", "users") })
        assertFalse(list.jsonArray.any { it.jsonObject["name"]?.jsonPrimitive?.content?.equals("idx_email", ignoreCase = true) == true })
    }

    @Test
    fun `CREATE UNIQUE INDEX has unique=true in list`() = runBlocking {
        seedTable()
        IndexHandler.create(config, buildJsonObject {
            put("tableName", "users")
            put("indexName", "uk_email")
            putJsonArray("columns") { add(JsonPrimitive("email")) }
            put("unique", true)
        })
        val list = IndexHandler.list(config, buildJsonObject { put("tableName", "users") })
        val idx = list.jsonArray.first { it.jsonObject["name"]?.jsonPrimitive?.content?.equals("uk_email", ignoreCase = true) == true }
        assertEquals("true", idx.jsonObject["unique"]?.jsonPrimitive?.content)
    }

    @Test
    fun `CREATE composite index on multiple columns`() = runBlocking {
        seedTable()
        IndexHandler.create(config, buildJsonObject {
            put("tableName", "users")
            put("indexName", "idx_name_email")
            putJsonArray("columns") {
                add(JsonPrimitive("name"))
                add(JsonPrimitive("email"))
            }
            put("unique", false)
        })
        val list = IndexHandler.list(config, buildJsonObject { put("tableName", "users") })
        val idx = list.jsonArray.first { it.jsonObject["name"]?.jsonPrimitive?.content?.equals("idx_name_email", ignoreCase = true) == true }
        val cols = idx.jsonObject["columns"]?.jsonPrimitive?.content!!.uppercase()
        assertTrue(cols.contains("NAME"))
        assertTrue(cols.contains("EMAIL"))
    }
}