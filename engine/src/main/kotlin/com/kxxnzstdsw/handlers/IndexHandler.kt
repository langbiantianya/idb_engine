package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 索引管理 Handler。
 */
object IndexHandler {

    /**
     * LIST — 列出表的所有索引
     * payload: { "tableName": "users", "schema": "public" }
     */
    suspend fun list(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'tableName'")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            Json.encodeToJsonElement(dialect.listIndexes(conn, tableName))
        }
    }

    /**
     * CREATE — 创建索引
     * payload: { "tableName": "users", "indexName": "idx_email", "columns": ["email"], "unique": false }
     */
    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'tableName'")
        val indexName = payload["indexName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'indexName'")
        val columns = payload["columns"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw IllegalArgumentException("缺少参数 'columns'")
        val unique = payload["unique"]?.jsonPrimitive?.booleanOrNull ?: false
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.createIndex(conn, tableName, indexName, columns, unique)
            buildJsonObject {
                put("created", indexName)
                put("tableName", tableName)
            }
        }
    }

    /**
     * DELETE — 删除索引
     * payload: { "indexName": "idx_email", "tableName": "users" }
     */
    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val indexName = payload["indexName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'indexName'")
        val tableName = payload["tableName"]?.jsonPrimitive?.contentOrNull
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.dropIndex(conn, indexName, tableName)
            buildJsonObject { put("deleted", indexName) }
        }
    }
}