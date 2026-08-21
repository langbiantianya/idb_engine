package com.kxxnzstdsw.handlers

import com.google.protobuf.Value
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.Row
import com.kxxnzstdsw.grpc.SqlExecuteRequest
import com.kxxnzstdsw.grpc.SqlExecuteResponse
import com.kxxnzstdsw.grpc.SqlExplainRequest
import com.kxxnzstdsw.grpc.SqlExplainResponse
import com.kxxnzstdsw.grpc.SqlSelectRowFrame
import com.kxxnzstdsw.grpc.row
import com.kxxnzstdsw.grpc.sqlExecuteResponse
import com.kxxnzstdsw.grpc.sqlExplainResponse
import com.kxxnzstdsw.grpc.sqlSelectRowFrame
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.sql.ResultSet

object SqlEngineHandler {
    /**
     * @param onRow 流式回调；SELECT 查询时逐行调用一次；非 SELECT 时不调用
     * @return SELECT 走 [onRow] 路径，返回占位 [SqlExecuteResponse]（dispatcher 不使用）；非 SELECT 返回真正的 [SqlExecuteResponse]
     */
    suspend fun execute(
        config: ConnectionConfig,
        req: SqlExecuteRequest,
        onRow: (suspend (SqlSelectRowFrame) -> Unit)? = null
    ): SqlExecuteResponse = withContext(Dispatchers.IO) {
        if (req.sql.isBlank()) throw IllegalArgumentException("Missing 'sql' in payload")
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)

        return@withContext connection.use { conn ->
            val dialect = DialectLoader.getDialect(config.driver)
            conn.createStatement(
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
            ).use { stmt ->
                val originalAutoCommit = dialect.configureConnectionForStreaming(conn)
                try {
                    val hasResultSet = stmt.execute(req.sql)

                    if (hasResultSet) {
                        if (onRow != null) {
                            // 游标流式模式
                            stmt.fetchSize = 100
                            stmt.resultSet.use { rs ->
                                var pageIdx = 0
                                while (rs.next()) {
                                    onRow(
                                        sqlSelectRowFrame {
                                            this.total = -1L
                                            this.page = pageIdx
                                            this.pageSize = 1
                                            row = buildRow(rs)
                                        }
                                    )
                                    pageIdx++
                                }
                            }
                            sqlExecuteResponse { }
                        } else {
                            // 非流式模式：此路径 dispatcher 不会调用（dispatcher 总走流式），保留占位
                            sqlExecuteResponse { }
                        }
                    } else {
                        sqlExecuteResponse { this.affectedRows = stmt.updateCount }
                    }
                } finally {
                    dialect.restoreConnectionAfterStreaming(conn, originalAutoCommit)
                }
            }
        }
    }

    /**
     * Build a typed Row proto from a JDBC ResultSet.
     */
    private fun buildRow(rs: ResultSet): Row {
        val metaData = rs.metaData
        val columnCount = metaData.columnCount
        return row {
            for (i in 1..columnCount) {
                val columnName = metaData.getColumnName(i)
                val columnType = metaData.getColumnTypeName(i)
                val value: Value = if (columnType in listOf("BLOB", "LONGTEXT", "BYTEA", "TEXT")) {
                    PayloadAdapter.toValue(JsonPrimitive("[LOB Data]"))
                } else {
                    PayloadAdapter.toValue(JsonPrimitive(rs.getString(i)))
                }
                values.put(columnName, value)
            }
        }
    }

    /**
     * EXPLAIN — 返回 SQL 的执行计划
     */
    suspend fun explain(config: ConnectionConfig, req: SqlExplainRequest): SqlExplainResponse = withContext(Dispatchers.IO) {
        if (req.sql.isBlank()) throw IllegalArgumentException("Missing 'sql' in payload")
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val rows = dialect.explainSQL(conn, req.sql)
            sqlExplainResponse {
                rows.forEach { row ->
                    val r = row {
                        row.forEach { (k, v) ->
                            values.put(k, PayloadAdapter.toValue(JsonPrimitive(v)))
                        }
                    }
                    this.rows += r
                }
            }
        }
    }
}