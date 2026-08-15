package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 外键管理 Handler。
 */
object ForeignKeyHandler {

    /**
     * LIST — 列出表的所有外键
     * payload: { "tableName": "orders", "schema": "public" }
     */
    suspend fun list(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'tableName'")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            Json.encodeToJsonElement(dialect.listForeignKeys(conn, tableName))
        }
    }

    /**
     * CREATE — 添加外键
     * payload: {
     *   "tableName": "orders",
     *   "fkName": "fk_orders_user",
     *   "columns": ["user_id"],
     *   "refTable": "users",
     *   "refColumns": ["id"],
     *   "onDelete": "CASCADE",
     *   "onUpdate": "RESTRICT"
     * }
     */
    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'tableName'")
        val fkName = payload["fkName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'fkName'")
        val columns = payload["columns"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw IllegalArgumentException("缺少参数 'columns'")
        val refTable = payload["refTable"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'refTable'")
        val refColumns = payload["refColumns"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw IllegalArgumentException("缺少参数 'refColumns'")
        val onDelete = payload["onDelete"]?.jsonPrimitive?.contentOrNull
        val onUpdate = payload["onUpdate"]?.jsonPrimitive?.contentOrNull
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.addForeignKey(conn, tableName, fkName, columns, refTable, refColumns, onDelete, onUpdate)
            buildJsonObject {
                put("created", fkName)
                put("tableName", tableName)
            }
        }
    }

    /**
     * DELETE — 删除外键
     * payload: { "tableName": "orders", "fkName": "fk_orders_user" }
     */
    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'tableName'")
        val fkName = payload["fkName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少参数 'fkName'")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.dropForeignKey(conn, tableName, fkName)
            buildJsonObject { put("deleted", fkName) }
        }
    }
}