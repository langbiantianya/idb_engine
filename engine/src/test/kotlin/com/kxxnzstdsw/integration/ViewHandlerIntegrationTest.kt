package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.ViewHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns empty initially`() = runBlocking {
        val result = ViewHandler.list(config, buildJsonObject { put("schema", "PUBLIC") })
        assertEquals(0, result.jsonArray.size)
    }

    @Test
    fun `CREATE then LIST then GET_DDL then DELETE view`() = runBlocking {
        executeUpdate("CREATE TABLE products (id INT, name VARCHAR(50))")

        ViewHandler.create(config, buildJsonObject {
            put("name", "v_products")
            put("definition", "SELECT id FROM products")
        })

        val list = ViewHandler.list(config, buildJsonObject { put("schema", "PUBLIC") })
        assertTrue(list.jsonArray.any {
            it.jsonObject["name"]?.jsonPrimitive?.content?.equals("v_products", ignoreCase = true) == true
        })

        val ddl = ViewHandler.getDDL(config, buildJsonObject {
            put("name", "v_products")
            put("schema", "PUBLIC")
        })
        assertTrue(ddl.jsonPrimitive.content.contains("CREATE VIEW"))

        ViewHandler.delete(config, buildJsonObject {
            put("name", "v_products")
            put("ifExists", true)
        })
        val after = ViewHandler.list(config, buildJsonObject { put("schema", "PUBLIC") })
        assertFalse(after.jsonArray.any {
            it.jsonObject["name"]?.jsonPrimitive?.content?.equals("v_products", ignoreCase = true) == true
        })
    }

    @Test
    fun `DELETE with ifExists does not throw on missing view`() = runBlocking {
        ViewHandler.delete(config, buildJsonObject {
            put("name", "no_such_view")
            put("ifExists", true)
        })
        assertTrue(true) // 不抛错即成功
    }
}