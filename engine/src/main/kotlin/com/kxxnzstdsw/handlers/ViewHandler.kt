package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 视图管理 Handler。
 * payload 中所有列名都使用未引用形式（由 dialect.quoteIdentifier 处理）。
 */
object ViewHandler {

    /**
     * LIST — 列出 schema 下的视图
     * payload: { "schema": "public" } (可选)
     */
    suspend fun list(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            Json.encodeToJsonElement(dialect.listViews(conn, schema))
        }
    }

    /**
     * CREATE — 创建视图
     * payload: { "name": "v_name", "definition": "SELECT id, name FROM users" }
     */
    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val definition = payload["definition"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'definition'")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.createView(conn, name, definition)
            buildJsonObject { put("created", name) }
        }
    }

    /**
     * DELETE — 删除视图
     * payload: { "name": "v_name", "ifExists": true, "schema": "public" }
     */
    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val ifExists = payload["ifExists"]?.jsonPrimitive?.booleanOrNull ?: false
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.dropView(conn, name, ifExists)
            buildJsonObject { put("deleted", name) }
        }
    }

    /**
     * GET_DDL — 获取视图完整定义
     * payload: { "name": "v_name", "schema": "public" }
     */
    suspend fun getDDL(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val name = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'name'")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            JsonPrimitive(dialect.getViewDDL(conn, name, schema))
        }
    }
}