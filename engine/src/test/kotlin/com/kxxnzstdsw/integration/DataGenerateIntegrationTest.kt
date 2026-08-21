package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.DataRequest
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.dataGenerateRequest
import com.kxxnzstdsw.grpc.generateTable
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DATA.GENERATE 路由端到端测试（v2.6 新增）。
 *
 * 覆盖 dispatcher 流式 GENERATE 路径：Lua 脚本执行 → INSERT → 流式进度帧。
 */
class DataGenerateIntegrationTest : H2Fixture() {

    private fun request(
        configure: Request.Builder.() -> Unit
    ): Request = Request.newBuilder()
        .setId("r-gen-${System.nanoTime()}")
        .setCategory(Category.DATA)
        .setAction(Action.GENERATE)
        .setConnection(config)
        .apply(configure)
        .build()

    @Test
    fun `DATA GENERATE inserts rows via Lua script`() = runBlocking {
        val tableName = "gen_${System.nanoTime()}"
        // setup target table
        executeUpdate("CREATE TABLE $tableName (id INT PRIMARY KEY, name VARCHAR(64))")

        val resp = RequestDispatcher.dispatch(
            request {
                setDataRequest(DataRequest.newBuilder()
                    .setGenerate(dataGenerateRequest {
                        tables += generateTable {
                            script = "for i = 1, 5 do insert('$tableName', {id = i, name = 'user_'..i}) end"
                        }
                    })
                    .build())
            }
        ).toList()

        // 最后一条是 terminal 帧（end=true），前面是 5 条 progress 帧
        assertTrue(resp.size >= 6, "expected >=6 frames (5 progress + terminal), got ${resp.size}")
        assertTrue(resp.last().end, "last frame must be terminal (end=true)")
        assertTrue(resp.last().success)
        assertTrue(resp.last().hasGenerateTerminal())
        assertTrue(resp.last().generateTerminal.success)

        // 验证行确实写入
        val rowCount = executeQuerySingle("SELECT COUNT(*) FROM $tableName")?.toInt() ?: 0
        assertEquals(5, rowCount, "expected 5 rows inserted by Lua script")
    }

    @Test
    fun `DATA GENERATE rejects empty tables list`() = runBlocking {
        val resp = RequestDispatcher.dispatch(
            request {
                setDataRequest(DataRequest.newBuilder()
                    .setGenerate(dataGenerateRequest { /* no tables */ })
                    .build())
            }
        ).toList()

        // 空 tables 在 generate 处抛 IllegalArgumentException，dispatcher 包装为 error 帧
        assertEquals(1, resp.size)
        assertEquals(false, resp.single().success)
        assertTrue(resp.single().error.isNotEmpty())
    }
}