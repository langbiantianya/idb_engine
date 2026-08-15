package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.SystemHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SystemHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `info returns JVM stats without database connection`() {
        val info = SystemHandler.info()
        val obj = info.jsonObject
        assertNotNull(obj["jvmVersion"]?.jsonPrimitive?.content)
        assertNotNull(obj["jvmName"]?.jsonPrimitive?.content)
        assertNotNull(obj["osName"]?.jsonPrimitive?.content)
        assertTrue(obj["availableProcessors"]!!.jsonPrimitive.intOrNull!! >= 1)
        assertNotNull(obj["memory"]?.jsonObject?.get("max"))
    }

    @Test
    fun `testConnection returns ok=true for valid H2 connection`() = runBlocking {
        val result = SystemHandler.testConnection(config)
        val obj = result.jsonObject
        assertEquals(true, obj["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("H2", obj["driver"]?.jsonPrimitive?.content)
    }

    @Test
    fun `serverInfo returns H2 product name and version`() = runBlocking {
        val result = SystemHandler.serverInfo(config)
        val obj = result.jsonObject
        // H2 JDBC 返回 product = "H2 JDBC Driver"
        assertTrue(obj["product"]?.jsonPrimitive?.content!!.contains("H2"))
        assertNotNull(obj["version"]?.jsonPrimitive?.content)
        // driver 是 JDBC 驱动名 "H2 JDBC Driver"（不是方言名）
        assertTrue(obj["driver"]?.jsonPrimitive?.content!!.contains("H2"))
        // url 应包含 jdbc:h2
        assertTrue(obj["url"]?.jsonPrimitive?.content!!.startsWith("jdbc:h2"))
    }
}