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
    fun `LIST returns schemas including PUBLIC`() = runBlocking {
        val result = SchemaHandler.list(config, JsonObject(emptyMap()))
        val arr = result.jsonArray
        assertTrue(arr.size >= 1)
        assertTrue(arr.any { it.jsonPrimitive.content.equals("PUBLIC", ignoreCase = true) })
    }

    @Test
    fun `CREATE schema then DELETE schema`() = runBlocking {
        SchemaHandler.create(config, buildJsonObject { put("name", "test_schema") })
        assertTrue(schemaExists("test_schema"))

        SchemaHandler.delete(config, buildJsonObject { put("name", "test_schema") })
        assertFalse(schemaExists("test_schema"))
    }
}