package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.ColumnDef
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.DataRequest
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.SchemaRequest
import com.kxxnzstdsw.grpc.SqlRequest
import com.kxxnzstdsw.grpc.SystemRequest
import com.kxxnzstdsw.grpc.TableRequest
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.kxxnzstdsw.grpc.schemaListRequest

/**
 * 端到端测试：通过强类型 gRPC [Request] envelope → [RequestDispatcher] → H2 dialect → typed [Response]。
 *
 * 这是少数直接通过 dispatcher 而非 handler 单元测试的场景 — 验证：
 * 1. 生成的 proto wire 类型与 dispatcher 兼容
 * 2. typed per-Category Request 在 handler 边界可直接消费（无需 JsonObject 转换）
 * 3. handler 返回的 typed per-Action 消息被 dispatcher 包到 Response.body 对应 oneof 分支
 *
 * 覆盖 Category/Action 各打一条最有代表性的请求，避免重复 handler integration 已覆盖的深度逻辑。
 */
class TypedRequestEnvelopeIntegrationTest : H2Fixture() {

    private fun request(
        category: Category,
        action: Action,
        configure: Request.Builder.() -> Unit = {}
    ): Request = Request.newBuilder()
        .setId("r-${category}-${action}")
        .setCategory(category)
        .setAction(action)
        .setConnection(config)
        .apply(configure)
        .build()

    // ---------- SYSTEM ----------

    @Test
    fun `SYSTEM INFO returns valid response with no body case`() = runBlocking {
        val resp = RequestDispatcher.dispatch(
            request(Category.SYSTEM, Action.INFO) {
                setSystemRequest(SystemRequest.newBuilder().build())
            }
        ).toList()
        assertEquals(1, resp.size, "non-streaming returns single frame")
        val r = resp.single()
        assertTrue(r.success)
        assertTrue(r.error.isEmpty())
        assertTrue(r.hasSystem())
        val info = r.system.info
        assertEquals(System.getProperty("java.version"), info.jvmVersion)
    }

    @Test
    fun `SYSTEM TEST_CONNECTION returns ok=true for valid H2`() = runBlocking {
        val resp = RequestDispatcher.dispatch(
            request(Category.SYSTEM, Action.TEST_CONNECTION) {
                setSystemRequest(SystemRequest.newBuilder().build())
            }
        ).toList()
        val r = resp.single()
        assertTrue(r.success)
        assertTrue(r.hasSystem())
        assertTrue(r.system.testConnection.ok)
        assertEquals("", r.system.testConnection.error)
    }

    // ---------- SCHEMA ----------

    @Test
    fun `SCHEMA LIST round-trips through typed envelope`() = runBlocking {
        val resp = RequestDispatcher.dispatch(
            request(Category.SCHEMA, Action.LIST) {
                setSchemaRequest(SchemaRequest.newBuilder()
                    .setList(com.kxxnzstdsw.grpc.schemaListRequest { level = "database" })
                    .build())
            }
        ).toList()
        val r = resp.single()
        assertTrue(r.success)
        assertTrue(r.hasSchema())
        val list = r.schema.list
        assertEquals("database", list.level)
        assertTrue(list.itemsCount >= 1, "H2 catalog must be present")
    }

    // ---------- TABLE ----------

    @Test
    fun `TABLE CREATE then LIST and DELETE via typed envelope`() = runBlocking {
        val tableName = "users_${System.nanoTime()}"
        // CREATE
        val createResp = RequestDispatcher.dispatch(
            request(Category.TABLE, Action.CREATE) {
                setTableRequest(TableRequest.newBuilder()
                    .setCreate(com.kxxnzstdsw.grpc.TableCreateRequest.newBuilder()
                        .setTableName(tableName)
                        .addColumns(ColumnDef.newBuilder().setName("id").setType("INT").setIsPrimaryKey(true).build())
                        .addColumns(ColumnDef.newBuilder().setName("name").setType("VARCHAR").setSize(255).build())
                        .build())
                    .build())
            }
        ).toList()
        assertTrue(createResp.single().success)
        assertEquals(tableName, createResp.single().table.create.created)
        assertTrue(tableExists(tableName), "table must exist in H2 after CREATE")

        // LIST columns
        val colsResp = RequestDispatcher.dispatch(
            request(Category.TABLE, Action.LIST) {
                setTableRequest(TableRequest.newBuilder()
                    .setColumnList(com.kxxnzstdsw.grpc.TableColumnListRequest.newBuilder()
                        .setTableName(tableName)
                        .build())
                    .build())
            }
        ).toList()
        val cols = colsResp.single().table.columns
        assertEquals(2, cols.itemsCount, "expected 2 columns")

        // DELETE
        val delResp = RequestDispatcher.dispatch(
            request(Category.TABLE, Action.DELETE) {
                setTableRequest(TableRequest.newBuilder()
                    .setDelete(com.kxxnzstdsw.grpc.TableDeleteRequest.newBuilder()
                        .setTableName(tableName)
                        .build())
                    .build())
            }
        ).toList()
        assertTrue(delResp.single().success)
        assertFalse(tableExists(tableName))
    }

    // ---------- DATA ----------

    @Test
    fun `DATA CREATE then LIST paged via typed envelope`() = runBlocking {
        val tableName = "data_${System.nanoTime()}"
        // CREATE TABLE
        executeUpdate("CREATE TABLE $tableName (id INT PRIMARY KEY, name VARCHAR(255))")
        // DATA CREATE
        val insertResp = RequestDispatcher.dispatch(
            request(Category.DATA, Action.CREATE) {
                setDataRequest(DataRequest.newBuilder()
                    .setCreate(com.kxxnzstdsw.grpc.DataCreateRequest.newBuilder()
                        .setTableName(tableName)
                        .putValues("id", "1")
                        .putValues("name", "Alice")
                        .build())
                    .build())
            }
        ).toList()
        val insert = insertResp.single()
        assertTrue(insert.success)
        assertEquals(1, insert.data.create.affectedRows)

        // DATA LIST paged
        val listResp = RequestDispatcher.dispatch(
            request(Category.DATA, Action.LIST) {
                setDataRequest(DataRequest.newBuilder()
                    .setList(com.kxxnzstdsw.grpc.DataListRequest.newBuilder()
                        .setTableName(tableName)
                        .setPageSize(10)
                        .build())
                    .build())
            }
        ).toList()
        val list = listResp.single().data.list
        assertEquals(1L, list.total)
        assertEquals(1, list.rowsCount)
    }

    // ---------- SQL ----------

    @Test
    fun `SQL EXECUTE UPDATE returns affected_rows via typed envelope`() = runBlocking {
        val resp = RequestDispatcher.dispatch(
            request(Category.SQL, Action.EXECUTE) {
                setSqlRequest(SqlRequest.newBuilder()
                    .setExecute(com.kxxnzstdsw.grpc.SqlExecuteRequest.newBuilder()
                        .setSql("CREATE TABLE t_${System.nanoTime()} (id INT)")
                        .build())
                    .build())
            }
        ).toList()
        assertTrue(resp.single().success)
        assertTrue(resp.single().hasSql())
        assertTrue(resp.single().sql.hasExecute())
    }

    // ---------- Error handling ----------

    @Test
    fun `dispatcher catches exception and surfaces error string on Response`() = runBlocking {
        // No body case set — dispatcher routes to an unsupported path that throws
        val resp = RequestDispatcher.dispatch(
            Request.newBuilder()
                .setId("err")
                .setCategory(Category.SCHEMA)
                .setAction(Action.RUN_EXPORT)  // unsupported for SCHEMA
                .setConnection(config)
                .setSchemaRequest(SchemaRequest.newBuilder()
                    .setList(com.kxxnzstdsw.grpc.SchemaListRequest.newBuilder().build())
                    .build())
                .build()
        ).toList()
        val r = resp.single()
        assertFalse(r.success)
        assertTrue(r.error.isNotEmpty(), "error must be populated")
    }
}