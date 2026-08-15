package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.FunctionHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FunctionHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns empty when no routines exist`() = runBlocking {
        val result = FunctionHandler.list(config, buildJsonObject { put("schema", "PUBLIC") })
        assertTrue(result is JsonArray)
        assertEquals(0, result.jsonArray.size)
    }

    @Test
    fun `CREATE a Java function alias via DDL then LIST shows it`() = runBlocking {
        // H2 函数/别名通过 CREATE ALIAS 注册。使用无重载的方法（Math.sqrt 在 double/int 等多个版本上都有，会冲突）
        val ddl = "CREATE ALIAS my_func FOR \"java.lang.Math.toDegrees\""
        val result = FunctionHandler.create(config, buildJsonObject { put("ddl", ddl) })
        assertTrue(result.jsonObject["success"]?.jsonPrimitive?.booleanOrNull == true)

        val list = FunctionHandler.list(config, buildJsonObject { put("schema", "PUBLIC") })
        assertTrue(list.jsonArray.any {
            it.jsonObject["name"]?.jsonPrimitive?.content?.equals("my_func", ignoreCase = true) == true
        })
    }

    @Test
    fun `CALL a function alias returns result`() = runBlocking {
        FunctionHandler.create(config, buildJsonObject {
            put("ddl", "CREATE ALIAS my_abs FOR \"java.lang.Math.toDegrees\"")
        })
        val result = FunctionHandler.call(config, buildJsonObject {
            put("name", "my_abs")
            put("routineType", "FUNCTION")
            put("schema", "PUBLIC")
            putJsonArray("args") { add(JsonPrimitive("3.141592653589793")) }
        })
        assertNotNull(result.jsonObject["result"])
        // 180 degrees
        assertEquals(180, (result.jsonObject["result"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0).toInt())
    }

    @Test
    fun `INFO returns function info`() = runBlocking {
        FunctionHandler.create(config, buildJsonObject {
            put("ddl", "CREATE ALIAS my_func FOR \"java.lang.Math.toDegrees\"")
        })
        val info = FunctionHandler.info(config, buildJsonObject {
            put("name", "my_func")
            put("schema", "PUBLIC")
        })
        // H2 ALIAS 可能返回大写名
        assertTrue(info.jsonObject["name"]?.jsonPrimitive?.content?.equals("my_func", ignoreCase = true) == true)
        assertNotNull(info.jsonObject["routine_type"])
    }

    @Test
    fun `DELETE drops a function`() = runBlocking {
        FunctionHandler.create(config, buildJsonObject {
            put("ddl", "CREATE ALIAS tmp_func FOR \"java.lang.Math.toDegrees\"")
        })
        val delete = FunctionHandler.delete(config, buildJsonObject {
            put("name", "tmp_func")
            put("routineType", "FUNCTION")
            put("schema", "PUBLIC")
            put("ifExists", true)
        })
        assertTrue(delete.jsonObject["success"]?.jsonPrimitive?.booleanOrNull == true)

        val after = FunctionHandler.list(config, buildJsonObject { put("schema", "PUBLIC") })
        assertFalse(after.jsonArray.any {
            it.jsonObject["name"]?.jsonPrimitive?.content?.equals("tmp_func", ignoreCase = true) == true
        })
    }

    @Test
    fun `VALIDATE returns valid=true for good DDL`() = runBlocking {
        val result = FunctionHandler.validate(config, buildJsonObject {
            put("ddl", "CREATE ALIAS good_func FOR \"java.lang.Math.toDegrees\"")
        })
        assertEquals(true, result.jsonObject["valid"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `DEBUG returns EXPLAIN and INFO for function`() = runBlocking {
        FunctionHandler.create(config, buildJsonObject {
            put("ddl", "CREATE ALIAS debug_func FOR \"java.lang.Math.toDegrees\"")
        })
        val debug = FunctionHandler.debug(config, buildJsonObject {
            put("name", "debug_func")
            put("schema", "PUBLIC")
        })
        assertTrue(debug is JsonArray)
        assertTrue(debug.jsonArray.any {
            it.jsonObject["type"]?.jsonPrimitive?.content in listOf("EXPLAIN", "INFO")
        })
    }

    private fun assertFalse(condition: Boolean, message: String = "") {
        kotlin.test.assertTrue(!condition, message)
    }
}