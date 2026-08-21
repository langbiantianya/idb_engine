package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.SystemHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SystemHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `info returns JVM stats without database connection`() {
        val info = SystemHandler.info()
        assertNotNull(info.jvmVersion)
        assertTrue(info.jvmVersion.isNotEmpty())
        assertNotNull(info.jvmName)
        assertTrue(info.availableProcessors >= 1)
        assertTrue(info.memory.max > 0L, "max memory must be > 0")
        assertTrue(info.memory.total >= 0L)
        assertTrue(info.memory.used >= 0L)
        assertTrue(info.memory.free >= 0L)
        assertTrue(info.uptime > 0L)
        assertTrue(info.pid > 0L)
    }

    @Test
    fun `testConnection returns ok=true for valid H2 connection`() = runBlocking {
        val result = SystemHandler.testConnection(config)
        assertTrue(result.ok)
        assertEquals("H2", result.driver)
        assertEquals("", result.error, "no error on success")
    }

    @Test
    fun `serverInfo returns H2 product name and version`() = runBlocking {
        val result = SystemHandler.serverInfo(config)
        // H2 dialect returns: product, version, driver, url, ... (dialect-specific keys)
        // version is on the typed field; extras holds the rest
        assertNotNull(result.version)
        assertTrue(result.version.isNotEmpty())
        assertTrue(result.hasExtras(), "dialect-specific keys packed into extras")
        val extrasObj = result.extras
        assertEquals(com.google.protobuf.Value.KindCase.STRUCT_VALUE, extrasObj.kindCase)
        val struct = extrasObj.structValue.fieldsMap
        assertTrue(struct["product"]?.stringValue?.contains("H2") == true)
        assertTrue(struct["url"]?.stringValue?.startsWith("jdbc:h2") == true)
    }
}