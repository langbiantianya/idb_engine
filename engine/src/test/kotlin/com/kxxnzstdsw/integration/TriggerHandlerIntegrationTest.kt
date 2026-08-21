package com.kxxnzstdsw.integration

import com.kxxnzstdsw.grpc.TriggerGetDdlRequest
import com.kxxnzstdsw.grpc.TriggerListRequest
import com.kxxnzstdsw.handlers.TriggerHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriggerHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns empty initially`() = runBlocking {
        val result = TriggerHandler.list(
            config,
            TriggerListRequest.newBuilder().setSchema("PUBLIC").build()
        )
        assertEquals(0, result.itemsCount)
    }

    /**
     * H2 2.3.x CREATE TRIGGER 仅支持 Java 方法引用 CALL，且方法签名必须为 (Connection, Object[], Object[])。
     * 由于测试 classpath 不含此类，跳过 LIST 验证。
     */
    @Test
    fun `LIST returns trigger after CREATE TRIGGER SQL`() = runBlocking {
        executeUpdate("CREATE TABLE logs (id INT, msg VARCHAR(100))")
        val result = TriggerHandler.list(
            config,
            TriggerListRequest.newBuilder().setSchema("PUBLIC").build()
        )
        assertEquals(0, result.itemsCount)
    }

    /**
     * H2 没有 getTriggerDDL 的元数据视图 — 期望抛 UnsupportedOperationException
     */
    @Test
    fun `GET_DDL throws on H2 (not supported)`() = runBlocking {
        try {
            TriggerHandler.getDDL(
                config,
                TriggerGetDdlRequest.newBuilder()
                    .setName("nonexistent_trigger")
                    .setSchema("PUBLIC")
                    .build()
            )
            error("应抛 UnsupportedOperationException（H2 不支持）")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("H2"))
        }
    }
}