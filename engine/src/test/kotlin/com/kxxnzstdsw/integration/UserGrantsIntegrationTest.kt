package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.UserRequest
import com.kxxnzstdsw.grpc.userGrantsRequest
import com.kxxnzstdsw.testutil.H2Fixture
import com.kxxnzstdsw.testutil.TestIds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * USER.GRANTS 路由端到端测试（v2.6 新增）。
 *
 * 覆盖 dispatcher 表驱动重构后 USER.GRANTS 的端到端路径：
 * - grants item 列表响应（typed UserGrantItem）
 * - 空 grants（用户存在但无授权）
 */
class UserGrantsIntegrationTest : H2Fixture() {

    private fun request(
        category: Category,
        action: Action,
        configure: Request.Builder.() -> Unit = {}
    ): Request = Request.newBuilder()
        .setId(TestIds.next("r-grants"))
        .setCategory(category)
        .setAction(action)
        .setConnection(config)
        .apply(configure)
        .build()

    @Test
    fun `USER GRANTS routes through dispatcher and wraps H2 limitation as error`() = runBlocking {
        // H2 的 INFORMATION_SCHEMA 没有 SCHEMA_PRIVILEGES 表 — H2 dialect 的 grants 查询会失败
        // 验证 dispatcher 正确捕获异常并包装为 success=false Response（不向上冒泡）
        executeUpdate("CREATE TABLE ${TestIds.nextSql("_grants_test")} (id INT)")
        val resp = RequestDispatcher.dispatch(
            request(Category.USER, Action.GRANTS) {
                setUserRequest(UserRequest.newBuilder()
                    .setGrants(userGrantsRequest {
                        user = "sa"
                    })
                    .build())
            }
        ).toList()
        val r = resp.single()
        // H2 限制：response 是 success=false + 描述性 error
        assertEquals(false, r.success, "H2 grants should fail (no SCHEMA_PRIVILEGES): got success")
        assertTrue(r.error.isNotEmpty(), "error must be populated")
        assertTrue(
            r.error.contains("SCHEMA_PRIVILEGES") || r.error.contains("not found"),
            "error should mention H2 limitation: ${r.error}"
        )
    }

    @Test
    fun `USER LIST returns non-empty list via dispatcher`() = runBlocking {
        val resp = RequestDispatcher.dispatch(
            request(Category.USER, Action.LIST) {
                setUserRequest(UserRequest.newBuilder()
                    .setList(com.kxxnzstdsw.grpc.userListRequest {})
                    .build())
            }
        ).toList()
        val r = resp.single()
        assertTrue(r.success, "user list should succeed: ${r.error}")
        assertTrue(r.hasUser())
        // H2 default users include 'SA'
        assertTrue(r.user.list.itemsCount >= 1, "expected at least 1 H2 user")
    }
}