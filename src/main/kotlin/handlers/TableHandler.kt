package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.serialization.json.*

object TableHandler {
    fun list(config: ConnectionConfig): JsonElement {
        val connection = PoolManager.getConnection(config)
        return connection.use { conn ->
            val tables = mutableListOf<Map<String, String>>()
            val metaData = conn.metaData
            metaData.getTables(config.database, null, "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) {
                    tables.add(mapOf(
                        "name" to rs.getString("TABLE_NAME"),
                        "type" to rs.getString("TABLE_TYPE")
                    ))
                }
            }
            Json.encodeToJsonElement(tables)
        }
    }

    fun columnList(config: ConnectionConfig, payload: JsonObject): JsonElement {
        val tableName = payload["tableName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'tableName' in payload")
        val connection = PoolManager.getConnection(config)
        return connection.use { conn ->
            val columns = mutableListOf<Map<String, Any?>>()
            val metaData = conn.metaData

            // Get primary keys
            val primaryKeys = mutableSetOf<String>()
            metaData.getPrimaryKeys(config.database, null, tableName).use { rs ->
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"))
                }
            }

            // Get columns
            metaData.getColumns(config.database, null, tableName, "%").use { rs ->
                while (rs.next()) {
                    val columnName = rs.getString("COLUMN_NAME")
                    columns.add(mapOf(
                        "name" to columnName,
                        "type" to rs.getString("TYPE_NAME"),
                        "size" to rs.getInt("COLUMN_SIZE"),
                        "nullable" to (rs.getInt("NULLABLE") == 1),
                        "isPrimaryKey" to primaryKeys.contains(columnName),
                        "defaultValue" to rs.getString("COLUMN_DEF")
                    ))
                }
            }
            Json.encodeToJsonElement(columns)
        }
    }
}