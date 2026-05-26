package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.serialization.json.*

object DataHandler {
    fun list(config: ConnectionConfig, payload: JsonObject): JsonElement {
        val tableName = payload["tableName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'tableName'")
        val page = payload["page"]?.jsonPrimitive?.intOrNull ?: 1
        val pageSize = payload["pageSize"]?.jsonPrimitive?.intOrNull ?: 50
        val offset = (page - 1) * pageSize

        val connection = PoolManager.getConnection(config)
        return connection.use { conn ->
            val sql = "SELECT * FROM `$tableName` LIMIT ? OFFSET ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, pageSize)
                stmt.setInt(2, offset)
                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<Map<String, String?>>()
                    val metaData = rs.metaData
                    val columnCount = metaData.columnCount

                    while (rs.next()) {
                        val row = mutableMapOf<String, String?>()
                        for (i in 1..columnCount) {
                            val columnName = metaData.getColumnName(i)
                            val columnType = metaData.getColumnTypeName(i)

                            // Handle LOB types
                            val value = if (columnType in listOf("BLOB", "LONGTEXT", "BYTEA", "TEXT")) {
                                "[LOB Data]"
                            } else {
                                rs.getString(i)
                            }
                            row[columnName] = value
                        }
                        rows.add(row)
                    }
                    Json.encodeToJsonElement(rows)
                }
            }
        }
    }

    fun create(config: ConnectionConfig, payload: JsonObject): JsonElement {
        val tableName = payload["tableName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'tableName'")
        val values = payload["values"]?.jsonObject ?: throw IllegalArgumentException("Missing 'values'")

        val connection = PoolManager.getConnection(config)
        return connection.use { conn ->
            val columns = values.keys.joinToString(", ") { "`$it`" }
            val placeholders = values.keys.joinToString(", ") { "?" }
            val sql = "INSERT INTO `$tableName` ($columns) VALUES ($placeholders)"

            conn.prepareStatement(sql).use { stmt ->
                values.values.forEachIndexed { index, value ->
                    stmt.setString(index + 1, value.jsonPrimitive.content)
                }
                val affectedRows = stmt.executeUpdate()
                buildJsonObject { put("affectedRows", affectedRows) }
            }
        }
    }

    fun update(config: ConnectionConfig, payload: JsonObject): JsonElement {
        val tableName = payload["tableName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'tableName'")
        val changes = payload["changes"]?.jsonObject ?: throw IllegalArgumentException("Missing 'changes'")
        val where = payload["where"]?.jsonObject ?: throw IllegalArgumentException("Missing 'where'")

        val connection = PoolManager.getConnection(config)
        return connection.use { conn ->
            val setClause = changes.keys.joinToString(", ") { "`$it` = ?" }
            val whereClause = where.keys.joinToString(" AND ") { "`$it` = ?" }
            val sql = "UPDATE `$tableName` SET $setClause WHERE $whereClause"

            conn.prepareStatement(sql).use { stmt ->
                var paramIndex = 1
                changes.values.forEach { value ->
                    stmt.setString(paramIndex++, value.jsonPrimitive.content)
                }
                where.values.forEach { value ->
                    stmt.setString(paramIndex++, value.jsonPrimitive.content)
                }
                val affectedRows = stmt.executeUpdate()
                buildJsonObject { put("affectedRows", affectedRows) }
            }
        }
    }

    fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement {
        val tableName = payload["tableName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'tableName'")
        val where = payload["where"]?.jsonObject ?: throw IllegalArgumentException("Missing 'where'")

        val connection = PoolManager.getConnection(config)
        return connection.use { conn ->
            val whereClause = where.keys.joinToString(" AND ") { "`$it` = ?" }
            val sql = "DELETE FROM `$tableName` WHERE $whereClause"

            conn.prepareStatement(sql).use { stmt ->
                where.values.forEachIndexed { index, value ->
                    stmt.setString(index + 1, value.jsonPrimitive.content)
                }
                val affectedRows = stmt.executeUpdate()
                buildJsonObject { put("affectedRows", affectedRows) }
            }
        }
    }
}