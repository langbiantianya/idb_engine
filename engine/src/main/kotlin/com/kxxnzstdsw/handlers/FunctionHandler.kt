package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.FunctionCallRequest
import com.kxxnzstdsw.grpc.FunctionCallResponse
import com.kxxnzstdsw.grpc.FunctionCreateRequest
import com.kxxnzstdsw.grpc.FunctionCreateResponse
import com.kxxnzstdsw.grpc.FunctionDebugRequest
import com.kxxnzstdsw.grpc.FunctionDebugResponse
import com.kxxnzstdsw.grpc.FunctionDeleteRequest
import com.kxxnzstdsw.grpc.FunctionDeleteResponse
import com.kxxnzstdsw.grpc.FunctionGetDdlRequest
import com.kxxnzstdsw.grpc.FunctionGetDdlResponse
import com.kxxnzstdsw.grpc.FunctionInfoRequest
import com.kxxnzstdsw.grpc.FunctionInfoResponse
import com.kxxnzstdsw.grpc.FunctionListRequest
import com.kxxnzstdsw.grpc.FunctionListResponse
import com.kxxnzstdsw.grpc.FunctionValidateRequest
import com.kxxnzstdsw.grpc.FunctionValidateResponse
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.functionCallResponse
import com.kxxnzstdsw.grpc.functionCreateResponse
import com.kxxnzstdsw.grpc.functionDebugItem
import com.kxxnzstdsw.grpc.functionDebugResponse
import com.kxxnzstdsw.grpc.functionDeleteResponse
import com.kxxnzstdsw.grpc.functionGetDdlResponse
import com.kxxnzstdsw.grpc.functionInfoResponse
import com.kxxnzstdsw.grpc.functionListItem
import com.kxxnzstdsw.grpc.functionListResponse
import com.kxxnzstdsw.grpc.functionValidateResponse
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 数据库函数/存储过程（Routine）管理 Handler。
 */
object FunctionHandler {

    suspend fun list(config: ConnectionConfig, req: FunctionListRequest): FunctionListResponse = withContext(Dispatchers.IO) {
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val routines = dialect.listRoutines(conn, schema)
            functionListResponse {
                routines.forEach { row ->
                    this.items += functionListItem {
                        name = row["name"] ?: ""
                        routineType = row["routine_type"] ?: ""
                        returnType = row["return_type"] ?: ""
                        language = row["language"] ?: ""
                        securityDefiner = row["security_definer"] ?: ""
                        volatility = row["volatility"] ?: ""
                        argCount = row["arg_count"] ?: ""
                        argNames = row["arg_names"] ?: ""
                        this.schema = row["schema"] ?: ""
                        description = row["description"] ?: ""
                        triggerTable = row["trigger_table"] ?: ""
                    }
                }
            }
        }
    }

    suspend fun info(config: ConnectionConfig, req: FunctionInfoRequest): FunctionInfoResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("缺少参数 'name'")
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val info = dialect.getRoutineInfo(conn, req.name, schema)
            // info shape is genuinely dialect-specific (e.g. TRIGGER has different fields),
            // so we keep packing as Value here.
            val obj = buildJsonObject {
                info.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            }
            functionInfoResponse { this.info = PayloadAdapter.toValue(obj) }
        }
    }

    suspend fun getDDL(config: ConnectionConfig, req: FunctionGetDdlRequest): FunctionGetDdlResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("缺少参数 'name'")
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            functionGetDdlResponse { ddl = dialect.getRoutineDDL(conn, req.name, schema) }
        }
    }

    suspend fun create(config: ConnectionConfig, req: FunctionCreateRequest): FunctionCreateResponse = withContext(Dispatchers.IO) {
        if (req.ddl.isBlank()) throw IllegalArgumentException("缺少参数 'ddl'")

        val connection = PoolManager.getConnection(config, "")
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.createRoutine(conn, req.ddl)
            functionCreateResponse {
                success = true
                message = "函数/存储过程创建成功"
            }
        }
    }

    suspend fun delete(config: ConnectionConfig, req: FunctionDeleteRequest): FunctionDeleteResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("缺少参数 'name'")
        if (req.routineType.isBlank()) throw IllegalArgumentException("缺少参数 'routineType' (FUNCTION 或 PROCEDURE)")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.dropRoutine(conn, req.name, req.routineType, schema, req.ifExists, req.cascade)
            functionDeleteResponse {
                success = true
                message = "函数/存储过程删除成功"
                name = req.name
                routineType = req.routineType
            }
        }
    }

    suspend fun call(config: ConnectionConfig, req: FunctionCallRequest): FunctionCallResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("缺少参数 'name'")
        if (req.routineType.isBlank()) throw IllegalArgumentException("缺少参数 'routineType' (FUNCTION 或 PROCEDURE)")
        val schema = req.schema
        val args = req.argsList

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val result = dialect.callRoutine(conn, req.name, req.routineType, schema, args)
            // callRoutine result shape is genuinely dialect-specific (FUNCTION returns single value,
            // PROCEDURE returns update_count, etc.) — keep Value packing.
            val obj = buildJsonObject {
                result.forEach { (key, value) ->
                    put(key, value.toJsonElement())
                }
            }
            functionCallResponse { this.result = PayloadAdapter.toValue(obj) }
        }
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val m = this as Map<String, Any?>
            buildJsonObject { m.forEach { (k, v) -> put(k, v.toJsonElement()) } }
        }
        is List<*> -> buildJsonArray { (this@toJsonElement as List<*>).forEach { add(it?.toJsonElement() ?: JsonNull) } }
        else -> JsonPrimitive(this.toString())
    }

    suspend fun debug(config: ConnectionConfig, req: FunctionDebugRequest): FunctionDebugResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("缺少参数 'name'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val debugInfo = dialect.debugRoutine(conn, req.name, schema)
            functionDebugResponse {
                debugInfo.forEach { row ->
                    this.items += functionDebugItem {
                        type = row["type"] ?: ""
                        output = row["output"] ?: ""
                    }
                }
            }
        }
    }

    suspend fun validate(config: ConnectionConfig, req: FunctionValidateRequest): FunctionValidateResponse = withContext(Dispatchers.IO) {
        if (req.ddl.isBlank()) throw IllegalArgumentException("缺少参数 'ddl'")

        val connection = PoolManager.getConnection(config, "")
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.validateRoutineDDL(conn, req.ddl)
            functionValidateResponse {
                valid = true
                message = "DDL 语法验证通过"
            }
        }
    }
}