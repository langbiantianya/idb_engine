package com.kxxnzstdsw.integration

import com.kxxnzstdsw.grpc.ViewCreateRequest
import com.kxxnzstdsw.grpc.ViewDeleteRequest
import com.kxxnzstdsw.grpc.ViewGetDdlRequest
import com.kxxnzstdsw.grpc.ViewListRequest
import com.kxxnzstdsw.handlers.ViewHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns empty initially`() = runBlocking {
        val result = ViewHandler.list(
            config,
            ViewListRequest.newBuilder().setSchema("PUBLIC").build()
        )
        assertEquals(0, result.itemsCount)
    }

    @Test
    fun `CREATE then LIST then GET_DDL then DELETE view`() = runBlocking {
        executeUpdate("CREATE TABLE products (id INT, name VARCHAR(50))")

        ViewHandler.create(
            config,
            ViewCreateRequest.newBuilder()
                .setName("v_products")
                .setDefinition("SELECT id FROM products")
                .build()
        )

        val list = ViewHandler.list(
            config,
            ViewListRequest.newBuilder().setSchema("PUBLIC").build()
        )
        assertTrue(list.itemsList.any { it.name.equals("v_products", ignoreCase = true) })

        val ddl = ViewHandler.getDDL(
            config,
            ViewGetDdlRequest.newBuilder().setName("v_products").setSchema("PUBLIC").build()
        )
        assertTrue(ddl.ddl.contains("CREATE VIEW", ignoreCase = true))

        ViewHandler.delete(
            config,
            ViewDeleteRequest.newBuilder().setName("v_products").setIfExists(true).build()
        )
        val after = ViewHandler.list(
            config,
            ViewListRequest.newBuilder().setSchema("PUBLIC").build()
        )
        assertFalse(after.itemsList.any { it.name.equals("v_products", ignoreCase = true) })
    }

    @Test
    fun `DELETE with ifExists does not throw on missing view`() = runBlocking {
        ViewHandler.delete(
            config,
            ViewDeleteRequest.newBuilder().setName("no_such_view").setIfExists(true).build()
        )
        assertTrue(true)
    }
}