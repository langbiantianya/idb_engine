package com.kxxnzstdsw.integration

import com.kxxnzstdsw.grpc.FunctionCallRequest
import com.kxxnzstdsw.grpc.FunctionCreateRequest
import com.kxxnzstdsw.grpc.FunctionDebugRequest
import com.kxxnzstdsw.grpc.FunctionDeleteRequest
import com.kxxnzstdsw.grpc.FunctionInfoRequest
import com.kxxnzstdsw.grpc.FunctionListRequest
import com.kxxnzstdsw.grpc.FunctionValidateRequest
import com.kxxnzstdsw.handlers.FunctionHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FunctionHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns empty when no routines exist`() = runBlocking {
        val result = FunctionHandler.list(
            config,
            FunctionListRequest.newBuilder().setSchema("PUBLIC").build()
        )
        assertEquals(0, result.itemsCount)
    }

    @Test
    fun `CREATE a Java function alias via DDL then LIST shows it`() = runBlocking {
        val ddl = "CREATE ALIAS my_func FOR \"java.lang.Math.toDegrees\""
        val result = FunctionHandler.create(
            config,
            FunctionCreateRequest.newBuilder().setDdl(ddl).build()
        )
        assertTrue(result.success)

        val list = FunctionHandler.list(
            config,
            FunctionListRequest.newBuilder().setSchema("PUBLIC").build()
        )
        assertTrue(list.itemsList.any { it.name.equals("my_func", ignoreCase = true) })
    }

    @Test
    fun `CALL a function alias returns result`() = runBlocking<Unit> {
        FunctionHandler.create(
            config,
            FunctionCreateRequest.newBuilder()
                .setDdl("CREATE ALIAS my_abs FOR \"java.lang.Math.toDegrees\"")
                .build()
        )
        val result = FunctionHandler.call(
            config,
            FunctionCallRequest.newBuilder()
                .setName("my_abs")
                .setRoutineType("FUNCTION")
                .setSchema("PUBLIC")
                .addArgs("3.141592653589793")
                .build()
        )
        // Function.call 返回统一结构（Struct with key "result"）
        assertEquals(
            com.google.protobuf.Value.KindCase.STRUCT_VALUE,
            result.result.kindCase
        )
        val struct = result.result.structValue.fieldsMap
        assertNotNull(struct["result"])
        val numberValue = struct["result"]!!.numberValue
        assertEquals(180.0, numberValue, 0.001)
    }

    @Test
    fun `INFO returns function info`() = runBlocking {
        FunctionHandler.create(
            config,
            FunctionCreateRequest.newBuilder()
                .setDdl("CREATE ALIAS my_func FOR \"java.lang.Math.toDegrees\"")
                .build()
        )
        val info = FunctionHandler.info(
            config,
            FunctionInfoRequest.newBuilder().setName("my_func").setSchema("PUBLIC").build()
        )
        assertTrue(info.info.structValue.fieldsMap["name"]?.stringValue?.equals("my_func", ignoreCase = true) == true)
        assertNotNull(info.info.structValue.fieldsMap["routine_type"])
    }

    @Test
    fun `DELETE drops a function`() = runBlocking {
        FunctionHandler.create(
            config,
            FunctionCreateRequest.newBuilder()
                .setDdl("CREATE ALIAS tmp_func FOR \"java.lang.Math.toDegrees\"")
                .build()
        )
        val delete = FunctionHandler.delete(
            config,
            FunctionDeleteRequest.newBuilder()
                .setName("tmp_func")
                .setRoutineType("FUNCTION")
                .setSchema("PUBLIC")
                .setIfExists(true)
                .build()
        )
        assertTrue(delete.success)

        val after = FunctionHandler.list(
            config,
            FunctionListRequest.newBuilder().setSchema("PUBLIC").build()
        )
        assertTrue(after.itemsList.none { it.name.equals("tmp_func", ignoreCase = true) })
    }

    @Test
    fun `VALIDATE returns valid=true for good DDL`() = runBlocking {
        val result = FunctionHandler.validate(
            config,
            FunctionValidateRequest.newBuilder()
                .setDdl("CREATE ALIAS good_func FOR \"java.lang.Math.toDegrees\"")
                .build()
        )
        assertTrue(result.valid)
    }

    @Test
    fun `DEBUG returns EXPLAIN and INFO for function`() = runBlocking {
        FunctionHandler.create(
            config,
            FunctionCreateRequest.newBuilder()
                .setDdl("CREATE ALIAS debug_func FOR \"java.lang.Math.toDegrees\"")
                .build()
        )
        val debug = FunctionHandler.debug(
            config,
            FunctionDebugRequest.newBuilder().setName("debug_func").setSchema("PUBLIC").build()
        )
        assertTrue(debug.itemsList.any { it.type in listOf("EXPLAIN", "INFO") })
    }

    @Test
    fun `CALL returns unified result with routine_type and schema fields`() = runBlocking {
        FunctionHandler.create(
            config,
            FunctionCreateRequest.newBuilder()
                .setDdl("CREATE ALIAS unified_func FOR \"java.lang.Math.toDegrees\"")
                .build()
        )
        val result = FunctionHandler.call(
            config,
            FunctionCallRequest.newBuilder()
                .setName("unified_func")
                .setRoutineType("FUNCTION")
                .setSchema("PUBLIC")
                .addArgs("3.141592653589793")
                .build()
        )
        // Phase H 统一返回形状
        assertEquals("FUNCTION", result.result.structValue.fieldsMap["routine_type"]?.stringValue)
        assertNotNull(result.result.structValue.fieldsMap["schema"])
        assertNotNull(result.result.structValue.fieldsMap["result"])
    }

    @Test
    fun `CALL with null arg does not throw`() = runBlocking {
        FunctionHandler.create(
            config,
            FunctionCreateRequest.newBuilder()
                .setDdl("CREATE ALIAS to_deg_safe FOR \"java.lang.Math.toDegrees\"")
                .build()
        )
        // 单参方法，传 null — 应绑定为 SQL NULL，不报错
        val result = FunctionHandler.call(
            config,
            FunctionCallRequest.newBuilder()
                .setName("to_deg_safe")
                .setRoutineType("FUNCTION")
                .setSchema("PUBLIC")
                .build()
        )
        assertNotNull(result)
    }

    @Test
    fun `VALIDATE accepts valid ALIAS DDL`() = runBlocking {
        val result = FunctionHandler.validate(
            config,
            FunctionValidateRequest.newBuilder()
                .setDdl("""CREATE ALIAS "valid_one" FOR "java.lang.Math.toDegrees"""")
                .build()
        )
        assertTrue(result.valid)
    }

    @Test
    fun `VALIDATE rejects multi-statement`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                FunctionHandler.validate(
                    config,
                    FunctionValidateRequest.newBuilder()
                        .setDdl("""CREATE ALIAS a FOR "java.lang.Math.toDegrees"; DROP ALIAS b""")
                        .build()
                )
            }
        }
    }
}