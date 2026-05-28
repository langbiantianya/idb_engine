package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object SqlEngineHandler {
    suspend fun execute(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val sql = payload["sql"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'sql' in payload")
        val connection = PoolManager.getConnection(config)

        return@withContext connection.use { conn ->
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