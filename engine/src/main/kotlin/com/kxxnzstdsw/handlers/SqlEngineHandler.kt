package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.grpc.ConnectionConfig
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
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)

        return@withContext connection.use { conn ->
            val dialect = DialectLoader.getDialect(config.driver)
            conn.createStatement(
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
            ).use { stmt ->
                val originalAutoCommit = dialect.configureConnectionForStreaming(conn)
                try {
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
                    dialect.restoreConnectionAfterStreaming(conn, originalAutoCommit)
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

    /**
     * EXPLAIN — 返回 SQL 的执行计划（行集合）
     * payload: { "sql": "SELECT * FROM users" }
     */
    suspend fun explain(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val sql = payload["sql"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'sql' in payload")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            Json.encodeToJsonElement(dialect.explainSQL(conn, sql))
        }
    }
}