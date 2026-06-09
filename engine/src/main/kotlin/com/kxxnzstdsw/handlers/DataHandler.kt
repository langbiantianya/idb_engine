package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Driver
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.sql.ResultSet

object DataHandler {
    /**
     * @param onRow 流式回调，pageSize == 0 时每读一行调用一次；pageSize > 0 时为 null
     * @return 流式模式返回 Unit，分页模式返回完整 JsonElement
     */
    suspend fun list(
        config: ConnectionConfig,
        payload: JsonObject,
        onRow: (suspend (JsonElement) -> Unit)? = null
    ): Any = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'tableName'")
        val page = payload["page"]?.jsonPrimitive?.intOrNull ?: 1
        val pageSize = payload["pageSize"]?.jsonPrimitive?.intOrNull ?: 50

        val whereRaw = payload["where"]?.jsonPrimitive?.contentOrNull
        val orderByRaw = payload["orderBy"]?.jsonPrimitive?.contentOrNull

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver.name)

        // 按方言规则校验 SQL 片段安全性
        if (!whereRaw.isNullOrBlank()) dialect.validateSqlFragment(whereRaw, "where")
        if (!orderByRaw.isNullOrBlank()) {
            dialect.validateSqlFragment(orderByRaw, "orderBy")
            dialect.validateOrderBy(orderByRaw)
        }

        val whereSql  = if (!whereRaw.isNullOrBlank())  " WHERE $whereRaw"   else ""
        val orderBySql = if (!orderByRaw.isNullOrBlank()) " ORDER BY $orderByRaw" else ""

        return@withContext connection.use { conn ->
            // 查询总行数（带 WHERE）
            val countSql = "SELECT COUNT(*) AS cnt FROM ${dialect.quoteIdentifier(tableName)}$whereSql"
            val total = conn.prepareStatement(countSql).use { countStmt ->
                countStmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("cnt") else 0L
                }
            }

            if (pageSize == 0 && onRow != null) {
                // 流式全量模式：使用 JDBC 游标逐行读取
                val sql = "SELECT * FROM ${dialect.quoteIdentifier(tableName)}$whereSql$orderBySql"
                val originalAutoCommit = conn.autoCommit
                try {
                    if (config.driver == Driver.Postgresql) {
                        conn.autoCommit = false
                    }
                    conn.prepareStatement(
                        sql,
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY
                    ).use { stmt ->
                        stmt.fetchSize = 100
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val row = rowToJson(rs)
                                onRow(buildJsonObject {
                                    put("total", total)
                                    put("page", 0)
                                    put("pageSize", 1)
                                    putJsonArray("rows") { add(row) }
                                })
                            }
                        }
                    }
                } finally {
                    conn.autoCommit = originalAutoCommit
                }
            } else {
                // 普通分页模式
                val offset = (page - 1) * pageSize
                val sql = "SELECT * FROM ${dialect.quoteIdentifier(tableName)}$whereSql$orderBySql LIMIT ? OFFSET ?"
                val rows = conn.prepareStatement(sql).use { stmt ->
                    stmt.setInt(1, pageSize)
                    stmt.setInt(2, offset)
                    stmt.executeQuery().use { rs ->
                        val resultRows = mutableListOf<Map<String, String?>>()
                        while (rs.next()) {
                            resultRows.add(rowToMap(rs))
                        }
                        resultRows
                    }
                }
                buildJsonObject {
                    put("total", total)
                    put("page", page)
                    put("pageSize", pageSize)
                    putJsonArray("rows") {
                        rows.forEach { add(Json.encodeToJsonElement(it)) }
                    }
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

    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'tableName'")
        val values = payload["values"]?.jsonObject
            ?: throw IllegalArgumentException("Missing 'values'")

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver.name)

        return@withContext connection.use { conn ->
            val columns = values.keys.joinToString(", ") { dialect.quoteIdentifier(it) }
            val placeholders = values.keys.joinToString(", ") { "?" }
            val sql = "INSERT INTO ${dialect.quoteIdentifier(tableName)} ($columns) VALUES ($placeholders)"

            conn.prepareStatement(sql).use { stmt ->
                values.values.forEachIndexed { index, value ->
                    stmt.setString(index + 1, value.jsonPrimitive.content)
                }
                val affectedRows = stmt.executeUpdate()
                buildJsonObject { put("affectedRows", affectedRows) }
            }
        }
    }

    suspend fun update(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'tableName'")
        val changes = payload["changes"]?.jsonObject
            ?: throw IllegalArgumentException("Missing 'changes'")
        val where = payload["where"]?.jsonObject
            ?: throw IllegalArgumentException("Missing 'where'")

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver.name)

        return@withContext connection.use { conn ->
            val setClause = changes.keys.joinToString(", ") { "${dialect.quoteIdentifier(it)} = ?" }
            val whereClause = where.keys.joinToString(" AND ") { "${dialect.quoteIdentifier(it)} = ?" }
            val sql = "UPDATE ${dialect.quoteIdentifier(tableName)} SET $setClause WHERE $whereClause"

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

    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'tableName'")
        val where = payload["where"]?.jsonObject
            ?: throw IllegalArgumentException("Missing 'where'")

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver.name)

        return@withContext connection.use { conn ->
            val whereClause = where.keys.joinToString(" AND ") { "${dialect.quoteIdentifier(it)} = ?" }
            val sql = "DELETE FROM ${dialect.quoteIdentifier(tableName)} WHERE $whereClause"

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