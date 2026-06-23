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
     * 解析 JSON 参数数组为 Map 列表
     */
    private fun parseArgs(jsonArray: JsonArray?): List<Map<String, String?>> {
        if (jsonArray == null) return emptyList()
        val result = mutableListOf<Map<String, String?>>()
        for (element in jsonArray) {
            val obj = element.jsonObject
            val map = mutableMapOf<String, String?>()
            map["name"] = obj["name"]?.jsonPrimitive?.contentOrNull
            map["mode"] = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "IN"
            map["dataType"] = obj["dataType"]?.jsonPrimitive?.contentOrNull ?: "TEXT"
            map["defaultValue"] = obj["defaultValue"]?.jsonPrimitive?.contentOrNull
            result.add(map)
        }
        return result
    }

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
     * INFO — 获取函数/存储过程详细信息
     * payload: { "name": "函数名", "routineType": "FUNCTION", "schema": "public" }
     */
    suspend fun info(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val routineType = payload["routineType"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'routineType' (FUNCTION 或 PROCEDURE)")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val info = dialect.getRoutineInfo(conn, name, routineType, schema)
            Json.encodeToJsonElement(info)
        }
    }

    /**
     * GET_DDL — 获取函数/存储过程的 DDL 定义
     * payload: { "name": "函数名", "routineType": "FUNCTION", "schema": "public" }
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
     * payload:
     * {
     *   "name": "函数名",
     *   "routineType": "FUNCTION" | "PROCEDURE",
     *   "schema": "public",
     *   "args": [{"name": "参数名", "mode": "IN", "dataType": "INTEGER", "defaultValue": null}, ...],
     *   "returnType": "INTEGER" (仅 FUNCTION),
     *   "language": "plpgsql",
     *   "body": "BEGIN ... END",
     *   "options": {"security_definer": "true", "volatility": "STABLE", "cost": "100"}
     * }
     */
    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val routineType = payload["routineType"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'routineType' (FUNCTION 或 PROCEDURE)")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val args = parseArgs(payload["args"]?.jsonArray)
        val returnType = payload["returnType"]?.jsonPrimitive?.contentOrNull
        val language = payload["language"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'language'")
        val body = payload["body"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'body'")
        val options = payload["options"]?.jsonObject?.let { obj ->
            obj.entries.associate { it.key to it.value.jsonPrimitive.content }
        } ?: emptyMap()

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.createRoutine(conn, name, routineType, schema, args, returnType, language, body, options)
            buildJsonObject {
                put("created", name)
                put("routineType", routineType)
                put("schema", schema.ifEmpty { "public" })
            }
        }
    }

    /**
     * DELETE — 删除函数/存储过程
     * payload: { "name": "函数名", "routineType": "FUNCTION", "schema": "public", "ifExists": true, "cascade": false }
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
                put("deleted", name)
                put("routineType", routineType)
                put("schema", schema.ifEmpty { "public" })
            }
        }
    }

    /**
     * CALL — 调用函数/存储过程
     * payload: { "name": "函数名", "routineType": "FUNCTION", "schema": "public", "args": ["参数1", "参数2"] }
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
     * UPDATE — 验证函数体语法（不创建，用于编辑时的语法检查）
     * payload: {
     *   "routineType": "FUNCTION",
     *   "args": [{"name": "x", "mode": "IN", "dataType": "INTEGER"}],
     *   "returnType": "INTEGER",
     *   "language": "plpgsql",
     *   "body": "BEGIN RETURN x * 2; END"
     * }
     */
    suspend fun validate(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val routineType = payload["routineType"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'routineType'")
        val args = parseArgs(payload["args"]?.jsonArray)
        val returnType = payload["returnType"]?.jsonPrimitive?.contentOrNull
        val language = payload["language"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'language'")
        val body = payload["body"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'body'")

        val connection = PoolManager.getConnection(config, "")
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.validateRoutineBody(conn, routineType, args, returnType, language, body)
            buildJsonObject {
                put("valid", true)
                put("routineType", routineType)
                put("language", language)
            }
        }
    }
}
