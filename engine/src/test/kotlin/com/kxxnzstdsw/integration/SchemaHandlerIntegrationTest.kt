package com.kxxnzstdsw.integration

import com.kxxnzstdsw.grpc.SchemaCreateRequest
import com.kxxnzstdsw.grpc.SchemaDeleteRequest
import com.kxxnzstdsw.grpc.SchemaListRequest
import com.kxxnzstdsw.handlers.SchemaHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.kxxnzstdsw.grpc.schemaCreateRequest
import com.kxxnzstdsw.grpc.schemaDeleteRequest
import com.kxxnzstdsw.grpc.schemaListRequest

class SchemaHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST defaults to database level — H2 returns current catalog`() = runBlocking {
        // 默认 level=database → H2 listDatabases 返回 [config.database]
        val result = SchemaHandler.list(config, SchemaListRequest.getDefaultInstance())
        assertEquals("database", result.level)
        assertTrue(result.itemsList.size == 1)
        assertTrue(result.itemsList.any { it.equals(dbName, ignoreCase = true) })
    }

    @Test
    fun `LIST level=database returns catalog`() = runBlocking {
        val result = SchemaHandler.list(
            config,
            schemaListRequest { level = "database" }
        )
        assertEquals("database", result.level)
        assertTrue(result.itemsList.any { it.equals(dbName, ignoreCase = true) })
    }

    @Test
    fun `LIST level=schema returns all schemas including PUBLIC`() = runBlocking {
        val result = SchemaHandler.list(
            config,
            schemaListRequest { level = "schema"; database = dbName }
        )
        assertEquals("schema", result.level)
        assertEquals(dbName, result.database)
        assertTrue(result.itemsList.size >= 1)
        assertTrue(result.itemsList.any { it.equals("PUBLIC", ignoreCase = true) })
    }

    @Test
    fun `LIST level=schema without database throws`() = runBlocking {
        val ex = kotlin.runCatching {
            SchemaHandler.list(
                config,
                schemaListRequest { level = "schema" }
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IllegalArgumentException but got ${ex?.javaClass?.simpleName}")
    }

    @Test
    fun `LIST level=invalid throws`() = runBlocking {
        val ex = kotlin.runCatching {
            SchemaHandler.list(
                config,
                schemaListRequest { level = "garbage" }
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `CREATE schema then DELETE schema`() = runBlocking {
        SchemaHandler.create(
            config,
            schemaCreateRequest { name = "test_schema" }
        )
        assertTrue(schemaExists("test_schema"))

        SchemaHandler.delete(
            config,
            schemaDeleteRequest { name = "test_schema" }
        )
        assertFalse(schemaExists("test_schema"))
    }
}