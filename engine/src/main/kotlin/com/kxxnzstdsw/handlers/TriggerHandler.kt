package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 触发器管理 Handler。
 * - LIST/GET_DDL 直接调用 dialect
 * - CREATE/DELETE 复用 FunctionHandler.createRoutine / dropRoutine（routineType="TRIGGER"）
 */
object TriggerHandler {

    /**
     * LIST — 列出 schema 下的触发器
     * payload: { "schema": "public" }
     */
    suspend fun list(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            Json.encodeToJsonElement(dialect.listTriggers(conn, schema))
        }
    }

    /**
     * GET_DDL — 获取触发器 DDL
     * payload: { "name": "trg_xxx", "schema": "public" }
     */
    suspend fun getDDL(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            JsonPrimitive(dialect.getTriggerDDL(conn, name, schema))
        }
    }
}