package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.serialization.json.*

object SqlEngineHandler {
    fun execute(config: ConnectionConfig, payload: JsonObject): JsonElement {
        val sql = payload["sql"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'sql' in payload")
        val connection = PoolManager.getConnection(config)

        return connection.use { conn ->
            conn.createStatement().use { stmt ->
                val hasResultSet = stmt.execute(sql)

                if (hasResultSet) {
                    // Query with result set
                    stmt.resultSet.use { rs ->
                        val rows = mutableListOf<Map<String, String?>>()
                        val metaData = rs.metaData
                        val columnCount = metaData.columnCount

                        while (rs.next()) {
                            val row = mutableMapOf<String, String?>()
                            for (i in 1..columnCount) {
                                row[metaData.getColumnName(i)] = rs.getString(i)
                            }
                            rows.add(row)
                        }
                        Json.encodeToJsonElement(rows)
                    }
                } else {
                    // Update/Insert/Delete operation
                    buildJsonObject {
                        put("affectedRows", stmt.updateCount)
                    }
                }
            }
        }
    }
}