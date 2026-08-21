package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.FunctionRequest
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.functionCreateRequest
import com.kxxnzstdsw.grpc.functionGetDdlRequest
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FUNCTION.GET_DDL 路由端到端测试（v2.6 新增）。
 *
 * 覆盖 dispatcher 表驱动重构后 FUNCTION.GET_DDL 的端到端路径：
 * - dispatcher 收到 GET_DDL 请求 → 路由到 FunctionHandler.getDDL
 * - H2 dialect 不支持从元数据重建函数 DDL — 走 error 路径
 * - dispatcher 把 handler 抛出的异常包装为 success=false Response（不向上冒泡）
 */
class FunctionGetDdlIntegrationTest : H2Fixture() {

    private fun request(
        action: Action,
        configure: Request.Builder.() -> Unit
    ): Request = Request.newBuilder()
        .setId("r-fng-${System.nanoTime()}")
        .setCategory(Category.FUNCTION)
        .setAction(action)
        .setConnection(config)
        .apply(configure)
        .build()

    @Test
    fun `FUNCTION GET_DDL is routed via dispatcher and surfaces error gracefully on H2`() = runBlocking {
        val resp = RequestDispatcher.dispatch(
            request(Action.GET_DDL) {
                setFunctionRequest(FunctionRequest.newBuilder()
                    .setGetDdl(functionGetDdlRequest {
                        name = "anyFunctionName_${System.nanoTime()}"
                    })
                    .build())
            }
        ).toList()
        val r = resp.single()
        // H2 不支持函数 DDL 重建 — handler 抛异常 → dispatcher 包装为 error 帧
        assertEquals(false, r.success, "H2 GET_DDL should fail gracefully")
        assertTrue(r.error.isNotEmpty(), "error must be populated")
        assertTrue(
            r.error.contains("DDL") || r.error.contains("重建") || r.error.contains("保留"),
            "error should mention H2 limitation: ${r.error}"
        )
    }

    @Test
    fun `FUNCTION CREATE returns success for valid H2 alias ddl`() = runBlocking {
        // 单独测 CREATE 路径（不依赖 GET_DDL 回环） — 验证 CREATE 在 dispatcher 中可达
        val funcName = "fn_${System.nanoTime()}"
        val ddl = "CREATE ALIAS $funcName FOR \"java.lang.Integer.parseInt(java.lang.String)\""
        val resp = RequestDispatcher.dispatch(
            request(Action.CREATE) {
                setFunctionRequest(FunctionRequest.newBuilder()
                    .setCreate(functionCreateRequest { this.ddl = ddl })
                    .build())
            }
        ).toList()
        val r = resp.single()
        assertTrue(r.success, "create failed: ${r.error}")
        assertTrue(r.function.create.success)
    }
}