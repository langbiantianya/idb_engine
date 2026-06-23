package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 数据库函数/存储过程（Routine）管理 Handler。
 * 支持 PostgreSQL 函数和存储过程的完整管理。
 */
object FunctionHandler {

    /**
     * LIST — 获取函数/存储过程列表
     * payload: { "schema": "public" } (可选)
     */
    suspend fun list(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val routines = dialect.listRoutines(conn, schema)
            Json.encodeToJsonElement(routines)
        }
    }

    /**
     * INFO — 获取函数/存储过程的详细信息（后端自动解析 routineType）
     * payload: { "name": "函数名", "schema": "public" }
     */
    suspend fun info(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val info = dialect.getRoutineInfo(conn, name, schema)
            Json.encodeToJsonElement(info)
        }
    }

    /**
     * GET_DDL — 获取函数/存储过程的完整 DDL 定义
     * payload: { "name": "函数名", "routineType": "FUNCTION" | "PROCEDURE", "schema": "public" }
     */
    suspend fun getDDL(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val routineType = payload["routineType"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'routineType' (FUNCTION 或 PROCEDURE)")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val ddl = dialect.getRoutineDDL(conn, name, routineType, schema)
            JsonPrimitive(ddl)
        }
    }

    /**
     * CREATE — 创建函数/存储过程
     * payload: { "ddl": "CREATE OR REPLACE FUNCTION ..." }
     */
    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val ddl = payload["ddl"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'ddl'")

        val connection = PoolManager.getConnection(config, "")
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.createRoutine(conn, ddl)
            buildJsonObject {
                put("success", true)
                put("message", "函数/存储过程创建成功")
            }
        }
    }

    /**
     * DELETE — 删除函数/存储过程
     * payload: { "name": "函数名", "routineType": "FUNCTION" | "PROCEDURE", "schema": "public" }
     */
    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val routineType = payload["routineType"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'routineType' (FUNCTION 或 PROCEDURE)")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val ifExists = payload["ifExists"]?.jsonPrimitive?.booleanOrNull ?: false
        val cascade = payload["cascade"]?.jsonPrimitive?.booleanOrNull ?: false

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.dropRoutine(conn, name, routineType, schema, ifExists, cascade)
            buildJsonObject {
                put("success", true)
                put("message", "函数/存储过程删除成功")
                put("name", name)
                put("routineType", routineType)
            }
        }
    }

    /**
     * CALL — 调用函数/存储过程
     * payload: { "name": "函数名", "routineType": "FUNCTION" | "PROCEDURE", "schema": "public", "args": ["参数1", "参数2"] }
     */
    suspend fun call(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val routineType = payload["routineType"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'routineType' (FUNCTION 或 PROCEDURE)")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val args = payload["args"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull } ?: emptyList()

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val result = dialect.callRoutine(conn, name, routineType, schema, args)
            Json.encodeToJsonElement(result)
        }
    }

    /**
     * DEBUG — 调试函数（EXPLAIN、执行计划、依赖分析等）
     * payload: { "name": "函数名", "schema": "public" }
     */
    suspend fun debug(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val debugInfo = dialect.debugRoutine(conn, name, schema)
            Json.encodeToJsonElement(debugInfo)
        }
    }

    /**
     * UPDATE — 验证 DDL 语法（不创建，用于编辑时的语法检查）
     * payload: { "ddl": "CREATE OR REPLACE FUNCTION ..." }
     */
    suspend fun validate(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val ddl = payload["ddl"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'ddl'")

        val connection = PoolManager.getConnection(config, "")
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.validateRoutineDDL(conn, ddl)
            buildJsonObject {
                put("valid", true)
                put("message", "DDL 语法验证通过")
            }
        }
    }
}
