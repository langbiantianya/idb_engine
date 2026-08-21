package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.FunctionCallRequest
import com.kxxnzstdsw.grpc.FunctionCallResponse
import com.kxxnzstdsw.grpc.FunctionCreateRequest
import com.kxxnzstdsw.grpc.FunctionCreateResponse
import com.kxxnzstdsw.grpc.FunctionDebugItem
import com.kxxnzstdsw.grpc.FunctionDebugRequest
import com.kxxnzstdsw.grpc.FunctionDebugResponse
import com.kxxnzstdsw.grpc.FunctionDeleteRequest
import com.kxxnzstdsw.grpc.FunctionDeleteResponse
import com.kxxnzstdsw.grpc.FunctionGetDdlRequest
import com.kxxnzstdsw.grpc.FunctionGetDdlResponse
import com.kxxnzstdsw.grpc.FunctionInfoRequest
import com.kxxnzstdsw.grpc.FunctionInfoResponse
import com.kxxnzstdsw.grpc.FunctionListItem
import com.kxxnzstdsw.grpc.FunctionListRequest
import com.kxxnzstdsw.grpc.FunctionListResponse
import com.kxxnzstdsw.grpc.FunctionValidateRequest
import com.kxxnzstdsw.grpc.FunctionValidateResponse
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
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
            val builder = FunctionListResponse.newBuilder()
            routines.forEach { row ->
                builder.addItems(
                    FunctionListItem.newBuilder()
                        .setName(row["name"] ?: "")
                        .setRoutineType(row["routine_type"] ?: "")
                        .setReturnType(row["return_type"] ?: "")
                        .setLanguage(row["language"] ?: "")
                        .setSecurityDefiner(row["security_definer"] ?: "")
                        .setVolatility(row["volatility"] ?: "")
                        .setArgCount(row["arg_count"] ?: "")
                        .setArgNames(row["arg_names"] ?: "")
                        .setSchema(row["schema"] ?: "")
                        .setDescription(row["description"] ?: "")
                        .setTriggerTable(row["trigger_table"] ?: "")
                )
            }
            builder.build()
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
            FunctionInfoResponse.newBuilder()
                .setInfo(PayloadAdapter.toValue(obj))
                .build()
        }
    }

    suspend fun getDDL(config: ConnectionConfig, req: FunctionGetDdlRequest): FunctionGetDdlResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("缺少参数 'name'")
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            FunctionGetDdlResponse.newBuilder()
                .setDdl(dialect.getRoutineDDL(conn, req.name, schema))
                .build()
        }
    }

    suspend fun create(config: ConnectionConfig, req: FunctionCreateRequest): FunctionCreateResponse = withContext(Dispatchers.IO) {
        if (req.ddl.isBlank()) throw IllegalArgumentException("缺少参数 'ddl'")

        val connection = PoolManager.getConnection(config, "")
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.createRoutine(conn, req.ddl)
            FunctionCreateResponse.newBuilder()
                .setSuccess(true)
                .setMessage("函数/存储过程创建成功")
                .build()
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
            FunctionDeleteResponse.newBuilder()
                .setSuccess(true)
                .setMessage("函数/存储过程删除成功")
                .setName(req.name)
                .setRoutineType(req.routineType)
                .build()
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
            FunctionCallResponse.newBuilder()
                .setResult(PayloadAdapter.toValue(obj))
                .build()
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
            val builder = FunctionDebugResponse.newBuilder()
            debugInfo.forEach { row ->
                builder.addItems(
                    FunctionDebugItem.newBuilder()
                        .setType(row["type"] ?: "")
                        .setOutput(row["output"] ?: "")
                )
            }
            builder.build()
        }
    }

    suspend fun validate(config: ConnectionConfig, req: FunctionValidateRequest): FunctionValidateResponse = withContext(Dispatchers.IO) {
        if (req.ddl.isBlank()) throw IllegalArgumentException("缺少参数 'ddl'")

        val connection = PoolManager.getConnection(config, "")
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.validateRoutineDDL(conn, req.ddl)
            FunctionValidateResponse.newBuilder()
                .setValid(true)
                .setMessage("DDL 语法验证通过")
                .build()
        }
    }
}