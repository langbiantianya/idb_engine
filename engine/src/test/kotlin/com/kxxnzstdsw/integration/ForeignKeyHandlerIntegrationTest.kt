package com.kxxnzstdsw.integration

import com.kxxnzstdsw.grpc.ForeignKeyCreateRequest
import com.kxxnzstdsw.grpc.ForeignKeyDeleteRequest
import com.kxxnzstdsw.grpc.ForeignKeyListRequest
import com.kxxnzstdsw.handlers.ForeignKeyHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.kxxnzstdsw.grpc.foreignKeyListRequest

class ForeignKeyHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns no foreign keys initially`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT PRIMARY KEY)")
        executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY)")
        val result = ForeignKeyHandler.list(
            config,
            foreignKeyListRequest { tableName = "orders" }
        )
        assertEquals(0, result.itemsCount)
    }

    @Test
    fun `CREATE then LIST then DELETE foreign key`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT PRIMARY KEY)")
        executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT)")

        ForeignKeyHandler.create(
            config,
            ForeignKeyCreateRequest.newBuilder()
                .setTableName("orders")
                .setFkName("fk_orders_user")
                .addColumns("user_id")
                .setRefTable("users")
                .addRefColumns("id")
                .setOnDelete("CASCADE")
                .build()
        )

        var fks = ForeignKeyHandler.list(
            config,
            foreignKeyListRequest { tableName = "orders" }
        )
        assertTrue(fks.itemsList.any { it.name.equals("fk_orders_user", ignoreCase = true) }, "FK 应在列表中")

        ForeignKeyHandler.delete(
            config,
            ForeignKeyDeleteRequest.newBuilder()
                .setTableName("orders")
                .setFkName("fk_orders_user")
                .build()
        )

        fks = ForeignKeyHandler.list(
            config,
            foreignKeyListRequest { tableName = "orders" }
        )
        assertFalse(fks.itemsList.any { it.name.equals("fk_orders_user", ignoreCase = true) })
    }

    @Test
    fun `LIST returns on_delete cascade rule`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT PRIMARY KEY)")
        executeUpdate("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT)")

        ForeignKeyHandler.create(
            config,
            ForeignKeyCreateRequest.newBuilder()
                .setTableName("orders")
                .setFkName("fk_orders_user")
                .addColumns("user_id")
                .setRefTable("users")
                .addRefColumns("id")
                .setOnDelete("CASCADE")
                .build()
        )

        val fks = ForeignKeyHandler.list(
            config,
            foreignKeyListRequest { tableName = "orders" }
        )
        val fk = fks.itemsList.first { it.name.equals("fk_orders_user", ignoreCase = true) }
        assertEquals("CASCADE", fk.onDelete)
    }
}