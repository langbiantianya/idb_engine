package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.ForeignKeyHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForeignKeyHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns no foreign keys initially`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT PRIMARY KEY)")
        executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY)")
        val result = ForeignKeyHandler.list(config, buildJsonObject { put("tableName", "orders") })
        // 一些 H2 元数据视图可能也返回带 FK 性质的默认值，但实际未创建 FK 时应为空
        assertEquals(0, result.jsonArray.size)
    }

    @Test
    fun `CREATE then LIST then DELETE foreign key`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT PRIMARY KEY)")
        executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT)")

        ForeignKeyHandler.create(config, buildJsonObject {
            put("tableName", "orders")
            put("fkName", "fk_orders_user")
            putJsonArray("columns") { add(JsonPrimitive("user_id")) }
            put("refTable", "users")
            putJsonArray("refColumns") { add(JsonPrimitive("id")) }
            put("onDelete", "CASCADE")
        })

        var fks = ForeignKeyHandler.list(config, buildJsonObject { put("tableName", "orders") })
        assertTrue(fks.jsonArray.any {
            it.jsonObject["name"]?.jsonPrimitive?.content?.equals("fk_orders_user", ignoreCase = true) == true
        }, "FK 应在列表中：$fks")

        ForeignKeyHandler.delete(config, buildJsonObject {
            put("tableName", "orders")
            put("fkName", "fk_orders_user")
        })

        fks = ForeignKeyHandler.list(config, buildJsonObject { put("tableName", "orders") })
        assertFalse(fks.jsonArray.any {
            it.jsonObject["name"]?.jsonPrimitive?.content?.equals("fk_orders_user", ignoreCase = true) == true
        })
    }

    @Test
    fun `LIST returns on_delete cascade rule`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT PRIMARY KEY)")
        executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT)")

        ForeignKeyHandler.create(config, buildJsonObject {
            put("tableName", "orders")
            put("fkName", "fk_orders_user")
            putJsonArray("columns") { add(JsonPrimitive("user_id")) }
            put("refTable", "users")
            putJsonArray("refColumns") { add(JsonPrimitive("id")) }
            put("onDelete", "CASCADE")
        })

        val fks = ForeignKeyHandler.list(config, buildJsonObject { put("tableName", "orders") })
        val fk = fks.jsonArray.first {
            it.jsonObject["name"]?.jsonPrimitive?.content?.equals("fk_orders_user", ignoreCase = true) == true
        }
        assertEquals("CASCADE", fk.jsonObject["on_delete"]?.jsonPrimitive?.content)
    }
}