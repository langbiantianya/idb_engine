package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Driver
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.sql.ResultSet

object SqlEngineHandler {
    /**
     * @param onRow 流式回调，SELECT 查询时逐行调用；非 SELECT 时为 null
     * @return 流式模式返回 true（Boolean），非流式返回 JsonElement
     */
    suspend fun execute(
        config: ConnectionConfig,
        payload: JsonObject,
        onRow: (suspend (JsonElement) -> Unit)? = null
    ): Any = withContext(Dispatchers.IO) {
        val sql = payload["sql"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'sql' in payload")
        val connection = PoolManager.getConnection(config)

        return@withContext connection.use { conn ->
            conn.createStatement(
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
            ).use { stmt ->
                val originalAutoCommit = conn.autoCommit
                try {
                    if (config.driver == Driver.Postgresql) {
                        conn.autoCommit = false
                    }
                    val hasResultSet = stmt.execute(sql)

                    if (hasResultSet) {
                        if (onRow != null) {
                            // 游标流式模式
                            stmt.fetchSize = 100
                            stmt.resultSet.use { rs ->
                                while (rs.next()) {
                                    onRow(buildJsonObject {
                                        put("total", -1)
                                        put("page", 0)
                                        put("pageSize", 1)
                                        putJsonArray("rows") { add(rowToJson(rs)) }
                                    })
                                }
                            }
                            true
                        } else {
                            // 非流式模式
                            stmt.resultSet.use { rs ->
                                val rows = mutableListOf<Map<String, String?>>()
                                while (rs.next()) {
                                    rows.add(rowToMap(rs))
                                }
                                Json.encodeToJsonElement(rows)
                            }
                        }
                    } else {
                        // Update/Insert/Delete operation
                        buildJsonObject {
                            put("affectedRows", stmt.updateCount)
                        }
                    }
                } finally {
                    conn.autoCommit = originalAutoCommit
                }
            }
        }
    }

    private fun rowToMap(rs: ResultSet): Map<String, String?> {
        val metaData = rs.metaData
        val columnCount = metaData.columnCount
        val row = mutableMapOf<String, String?>()
        for (i in 1..columnCount) {
            val columnName = metaData.getColumnName(i)
            val columnType = metaData.getColumnTypeName(i)
            row[columnName] = if (columnType in listOf("BLOB", "LONGTEXT", "BYTEA", "TEXT")) {
                "[LOB Data]"
            } else {
                rs.getString(i)
            }
        }
        return row
    }

    private fun rowToJson(rs: ResultSet): JsonElement {
        return Json.encodeToJsonElement(rowToMap(rs))
    }
}