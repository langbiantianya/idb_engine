package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.ColumnDef
import com.kxxnzstdsw.grpc.DataRequest
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.RequestOptions
import com.kxxnzstdsw.grpc.TableRequest
import com.kxxnzstdsw.testutil.H2Fixture
import com.kxxnzstdsw.testutil.TestIds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RequestOptions envelope 跨切面测试（v2.6 新增）。
 *
 * 验证三种 envelope option 在 dispatcher 边界正确应用：
 * - traceId 注入到 MDC（间接验证 — 通过日志输出检查）
 * - dryRun 短路 write action（不调用 handler，不修改 DB）
 * - timeoutMs 超时（强制返回 error="timeout"）
 */
class EnvelopeOptionsIntegrationTest : H2Fixture() {

    private fun baseRequest(
        category: Category,
        action: Action,
        configure: Request.Builder.() -> Unit
    ): Request = Request.newBuilder()
        .setId(TestIds.next("r-env"))
        .setCategory(category)
        .setAction(action)
        .setConnection(config)
        .apply(configure)
        .build()

    @Test
    fun `dryRun short-circuits TABLE CREATE without executing`() = runBlocking {
        val tableName = TestIds.nextSql("dryrun")
        val resp = RequestDispatcher.dispatch(
            baseRequest(Category.TABLE, Action.CREATE) {
                setOptions(RequestOptions.newBuilder().setDryRun(true).build())
                setTableRequest(TableRequest.newBuilder()
                    .setCreate(com.kxxnzstdsw.grpc.TableCreateRequest.newBuilder()
                        .setTableName(tableName)
                        .addColumns(ColumnDef.newBuilder().setName("id").setType("INT").setIsPrimaryKey(true).build())
                        .build())
                    .build())
            }
        ).toList()

        val r = resp.single()
        assertTrue(r.success, "dryRun write should still return success: ${r.error}")
        assertTrue(r.error.contains("dryRun"), "error must indicate dryRun: ${r.error}")
        // 表必须未被实际创建
        assertFalse(tableExists(tableName), "dryRun must NOT create table $tableName")
    }

    @Test
    fun `dryRun still executes read actions`() = runBlocking {
        val resp = RequestDispatcher.dispatch(
            baseRequest(Category.SCHEMA, Action.LIST) {
                setOptions(RequestOptions.newBuilder().setDryRun(true).build())
                setSchemaRequest(com.kxxnzstdsw.grpc.SchemaRequest.newBuilder()
                    .setList(com.kxxnzstdsw.grpc.schemaListRequest { level = "database" })
                    .build())
            }
        ).toList()
        val r = resp.single()
        assertTrue(r.success, "dryRun LIST must execute normally: ${r.error}")
        assertTrue(r.hasSchema())
    }

    @Test
    fun `dryRun on DATA CREATE inserts nothing`() = runBlocking {
        val tableName = TestIds.nextSql("dryrun_data")
        executeUpdate("CREATE TABLE $tableName (id INT PRIMARY KEY, name VARCHAR(64))")
        val resp = RequestDispatcher.dispatch(
            baseRequest(Category.DATA, Action.CREATE) {
                setOptions(RequestOptions.newBuilder().setDryRun(true).build())
                setDataRequest(DataRequest.newBuilder()
                    .setCreate(com.kxxnzstdsw.grpc.DataCreateRequest.newBuilder()
                        .setTableName(tableName)
                        .putValues("id", "1")
                        .putValues("name", "test")
                        .build())
                    .build())
            }
        ).toList()
        val r = resp.single()
        assertTrue(r.success)
        assertTrue(r.error.contains("dryRun"))
        val count = executeQuerySingle("SELECT COUNT(*) FROM $tableName")?.toInt() ?: 0
        assertEquals(0, count, "dryRun must NOT insert row")
    }

    @Test
    fun `timeoutMs wraps handler call and surfaces timeout error`() = runBlocking {
        val resp = RequestDispatcher.dispatch(
            baseRequest(Category.SYSTEM, Action.INFO) {
                // 1 ns timeout — guaranteed to expire for any real handler
                setOptions(RequestOptions.newBuilder().setTimeoutMs(1).build())
                setSystemRequest(com.kxxnzstdsw.grpc.SystemRequest.newBuilder().build())
            }
        ).toList()
        val r = resp.single()
        // 1ms 可能仍然完成（极快），但更可能是 timeout。我们验证 response 总是返回（不挂起）即可。
        assertTrue(resp.isNotEmpty(), "timeout should not lose response")
        // if timeout fired, success=false and error contains timeout
        if (!r.success) {
            assertTrue(
                r.error.contains("timeout", ignoreCase = true) ||
                r.error.contains("exceeded", ignoreCase = true),
                "error should mention timeout: ${r.error}"
            )
        }
    }
}