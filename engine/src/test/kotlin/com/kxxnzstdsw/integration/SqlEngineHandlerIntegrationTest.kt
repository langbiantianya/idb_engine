package com.kxxnzstdsw.integration

import com.kxxnzstdsw.grpc.SqlExecuteRequest
import com.kxxnzstdsw.grpc.SqlExplainRequest
import com.kxxnzstdsw.handlers.SqlEngineHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.kxxnzstdsw.grpc.sqlExecuteRequest
import com.kxxnzstdsw.grpc.sqlExplainRequest

class SqlEngineHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `EXECUTE SELECT streams rows via callback`() = runBlocking<Unit> {
        executeUpdate("CREATE TABLE t (id INT, name VARCHAR(50))")
        executeUpdate("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')")
        var rows = 0
        var firstName: String? = null
        SqlEngineHandler.execute(
            config,
            sqlExecuteRequest { sql = "SELECT * FROM t ORDER BY id" }
        ) { frame ->
            rows++
            if (firstName == null) {
                firstName = frame.row.valuesMap["NAME"]?.stringValue
            }
        }
        assertEquals(3, rows)
        assertEquals("a", firstName)
    }

    @Test
    fun `EXECUTE INSERT returns affectedRows`() = runBlocking<Unit> {
        executeUpdate("CREATE TABLE t (id INT, name VARCHAR(50))")
        val result = SqlEngineHandler.execute(
            config,
            SqlExecuteRequest.newBuilder().setSql("INSERT INTO t VALUES (1, 'a'), (2, 'b')").build()
        )
        assertEquals(2, result.affectedRows)
    }

    @Test
    fun `EXECUTE UPDATE returns affectedRows`() = runBlocking<Unit> {
        executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))")
        executeUpdate("INSERT INTO t VALUES (1, 'old')")
        val result = SqlEngineHandler.execute(
            config,
            sqlExecuteRequest { sql = "UPDATE t SET name='new' WHERE id=1" }
        )
        assertEquals(1, result.affectedRows)
    }

    @Test
    fun `EXECUTE DELETE returns affectedRows`() = runBlocking<Unit> {
        executeUpdate("CREATE TABLE t (id INT PRIMARY KEY)")
        executeUpdate("INSERT INTO t VALUES (1), (2)")
        val result = SqlEngineHandler.execute(
            config,
            sqlExecuteRequest { sql = "DELETE FROM t WHERE id=1" }
        )
        assertEquals(1, result.affectedRows)
    }

    @Test
    fun `EXECUTE DDL returns 0 affectedRows`() = runBlocking<Unit> {
        val result = SqlEngineHandler.execute(
            config,
            sqlExecuteRequest { sql = "CREATE TABLE new_t (id INT)" }
        )
        assertEquals(0, result.affectedRows)
        assertTrue(tableExists("new_t"))
    }

    @Test
    fun `EXPLAIN returns plan rows for a SELECT`() = runBlocking<Unit> {
        executeUpdate("CREATE TABLE t (id INT)")
        val result = SqlEngineHandler.explain(
            config,
            sqlExplainRequest { sql = "SELECT * FROM t" }
        )
        assertTrue(result.rowsCount > 0)
    }

    @Test
    fun `EXPLAIN rejects semicolon`() = runBlocking<Unit> {
        try {
            SqlEngineHandler.explain(
                config,
                sqlExplainRequest { sql = "SELECT 1; DROP TABLE x" }
            )
            error("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("分号"))
        }
    }
}