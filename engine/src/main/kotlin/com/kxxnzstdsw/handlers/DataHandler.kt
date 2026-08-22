package com.kxxnzstdsw.handlers

import com.google.protobuf.Value
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.DataCreateRequest
import com.kxxnzstdsw.grpc.DataCreateResponse
import com.kxxnzstdsw.grpc.DataDeleteRequest
import com.kxxnzstdsw.grpc.DataDeleteResponse
import com.kxxnzstdsw.grpc.DataListPagedResponse
import com.kxxnzstdsw.grpc.DataListRequest
import com.kxxnzstdsw.grpc.DataRowFrame
import com.kxxnzstdsw.grpc.DataUpdateRequest
import com.kxxnzstdsw.grpc.DataUpdateResponse
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.Row
import com.kxxnzstdsw.grpc.dataCreateResponse
import com.kxxnzstdsw.grpc.dataDeleteResponse
import com.kxxnzstdsw.grpc.dataListPagedResponse
import com.kxxnzstdsw.grpc.dataRowFrame
import com.kxxnzstdsw.grpc.dataUpdateResponse
import com.kxxnzstdsw.grpc.row
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object DataHandler {
    private val logger = LoggerFactory.getLogger(DataHandler::class.java)
    private val LOB_TYPES = setOf("BLOB", "LONGTEXT", "BYTEA", "TEXT")

    /**
     * 流式 fetch size (JDBC 游标行数)
     */
    private const val STREAM_FETCH_SIZE = 100

    /**
     * 列类型缓存 — 每次 create/update/delete 都查方言 listColumns 是浪费
     * key: "driver|database|schema|table"
     * TableHandler.create/update/delete 时通过 [invalidateColumnTypeCache] 失效
     */
    private val columnTypeCache = ConcurrentHashMap<String, Map<String, String>>()
    /**
     * 流式模式（req.hasPageSize() && req.pageSize == 0）通过 [onRow] 每行回调一次；
     * 否则返回分页 [DataListPagedResponse]。
     */
    suspend fun list(
        config: ConnectionConfig,
        req: DataListRequest,
        onRow: (suspend (DataRowFrame) -> Unit)? = null
    ): DataListPagedResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
        val page = if (req.page == 0) 1 else req.page
        val pageSize = if (req.hasPageSize()) req.pageSize else 50

        val whereRaw = req.where.ifBlank { null }
        val orderByRaw = req.orderBy.ifBlank { null }
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

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
            val countSql = "SELECT COUNT(*) AS cnt FROM ${dialect.quoteIdentifier(req.tableName)}$whereSql"
            val total = conn.prepareStatement(countSql).use { countStmt ->
                countStmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("cnt") else 0L
                }
            }

            if (pageSize == 0 && onRow != null) {
                // 流式全量模式：使用 JDBC 游标逐行读取
                val sql = "SELECT * FROM ${dialect.quoteIdentifier(req.tableName)}$whereSql$orderBySql"
                val originalAutoCommit = dialect.configureConnectionForStreaming(conn)
                try {
                    conn.prepareStatement(
                        sql,
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY
                    ).use { stmt ->
                        stmt.fetchSize = STREAM_FETCH_SIZE
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                onRow(
                                    dataRowFrame {
                                        this.total = total
                                        this.page = 0
                                        this.pageSize = 1
                                        row = buildRow(rs)
                                    }
                                )
                            }
                        }
                    }
                } finally {
                    dialect.restoreConnectionAfterStreaming(conn, originalAutoCommit)
                }
                // 流式模式不回传 paged body — 返回空 paged 仅占位（dispatcher 不会使用）
                dataListPagedResponse {
                    this.total = total
                    this.page = 0
                    this.pageSize = 0
                }
            } else {
                // 普通分页模式
                val offset = (page - 1) * pageSize
                val sql = "SELECT * FROM ${dialect.quoteIdentifier(req.tableName)}$whereSql$orderBySql LIMIT ? OFFSET ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setInt(1, pageSize)
                    stmt.setInt(2, offset)
                    stmt.executeQuery().use { rs ->
                        dataListPagedResponse {
                            this.total = total
                            this.page = page
                            this.pageSize = pageSize
                            while (rs.next()) {
                                this.rows += buildRow(rs)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Build a typed Row proto from a JDBC ResultSet. Each cell is packed as Value;
     * the column set is dynamic (table-specific) so cell values stay Value while the
     * row envelope is typed.
     */
    private fun buildRow(rs: ResultSet): Row {
        val metaData = rs.metaData
        val columnCount = metaData.columnCount
        return row {
            for (i in 1..columnCount) {
                val columnName = metaData.getColumnName(i)
                val columnType = metaData.getColumnTypeName(i)
                val value: Value = if (columnType in LOB_TYPES) {
                    PayloadAdapter.toValue(JsonPrimitive("[LOB Data]"))
                } else {
                    PayloadAdapter.toValue(JsonPrimitive(rs.getString(i)))
                }
                values.put(columnName, value)
            }
        }
    }

    suspend fun create(config: ConnectionConfig, req: DataCreateRequest): DataCreateResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val columnTypes = loadColumnTypes(conn, config.driver, config.database, schema, req.tableName)

            val columns = req.valuesMap.keys.joinToString(", ") { dialect.quoteIdentifier(it) }
            val placeholders = req.valuesMap.keys.joinToString(", ") { "?" }
            val sql = "INSERT INTO ${dialect.quoteIdentifier(req.tableName)} ($columns) VALUES ($placeholders)"

            conn.prepareStatement(sql).use { stmt ->
                req.valuesMap.entries.forEachIndexed { index, (name, value) ->
                    bindTypedValue(stmt, index + 1, columnTypes[name.lowercase()], JsonPrimitive(value))
                }
                val affectedRows = stmt.executeUpdate()
                dataCreateResponse { this.affectedRows = affectedRows }
            }
        }
    }

    suspend fun update(config: ConnectionConfig, req: DataUpdateRequest): DataUpdateResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val columnTypes = loadColumnTypes(conn, config.driver, config.database, schema, req.tableName)

            val setClause = req.changesMap.keys.joinToString(", ") { "${dialect.quoteIdentifier(it)} = ?" }
            val whereClause = req.whereMap.keys.joinToString(" AND ") { "${dialect.quoteIdentifier(it)} = ?" }
            val sql = "UPDATE ${dialect.quoteIdentifier(req.tableName)} SET $setClause WHERE $whereClause"

            conn.prepareStatement(sql).use { stmt ->
                var paramIndex = 1
                req.changesMap.forEach { (name, value) ->
                    bindTypedValue(stmt, paramIndex++, columnTypes[name.lowercase()], JsonPrimitive(value))
                }
                req.whereMap.forEach { (name, value) ->
                    bindTypedValue(stmt, paramIndex++, columnTypes[name.lowercase()], JsonPrimitive(value))
                }
                val affectedRows = stmt.executeUpdate()
                dataUpdateResponse { this.affectedRows = affectedRows }
            }
        }
    }

    suspend fun delete(config: ConnectionConfig, req: DataDeleteRequest): DataDeleteResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val columnTypes = loadColumnTypes(conn, config.driver, config.database, schema, req.tableName)

            val whereClause = req.whereMap.keys.joinToString(" AND ") { "${dialect.quoteIdentifier(it)} = ?" }
            val sql = "DELETE FROM ${dialect.quoteIdentifier(req.tableName)} WHERE $whereClause"

            conn.prepareStatement(sql).use { stmt ->
                req.whereMap.entries.forEachIndexed { index, (name, value) ->
                    bindTypedValue(stmt, index + 1, columnTypes[name.lowercase()], JsonPrimitive(value))
                }
                val affectedRows = stmt.executeUpdate()
                dataDeleteResponse { this.affectedRows = affectedRows }
            }
        }
    }

    /**
     * 通过方言的 listColumns SPI 获取表的列类型映射（columnName -> TYPE_NAME）。
     * 带缓存 — 高频 create/update/delete 调用避免每次都查元数据。
     * TableHandler 应在 create/update/delete 后调用 [invalidateColumnTypeCache] 失效。
     */
    private fun loadColumnTypes(
        conn: java.sql.Connection,
        driver: String,
        database: String?,
        schema: String,
        tableName: String
    ): Map<String, String> {
        val cacheKey = columnTypeCacheKey(driver, database, schema, tableName)
        columnTypeCache[cacheKey]?.let { return it }

        val types = try {
            val dialect = com.kxxnzstdsw.loader.DialectLoader.getDialect(driver)
            val cols = kotlinx.coroutines.runBlocking {
                dialect.listColumns(conn, database ?: "", schema, tableName)
            }
            cols.mapNotNull { col ->
                val name = col["name"] as? String ?: return@mapNotNull null
                val type = col["type"] as? String ?: return@mapNotNull null
                name.lowercase() to type
            }.toMap()
        } catch (e: Exception) {
            // 不能确定列类型时,绑定回退到 setString() — 但需记录,避免静默丢错
            // (可能错类型写入 — 列类型探测失败时记录以便诊断)
            logger.warn("loadColumnTypes failed for driver=$driver table=$tableName: ${e.message}", e)
            emptyMap()
        }
        columnTypeCache[cacheKey] = types
        return types
    }

    private fun columnTypeCacheKey(driver: String, database: String?, schema: String, table: String): String =
        "$driver|${database ?: ""}|$schema|$table"

    /**
     * 失效表的列类型缓存 — 由 TableHandler 在 CREATE/ADD_COLUMN/DROP_COLUMN/MODIFY_COLUMN/RENAME/DROP/TRUNCATE 后调用。
     */
    fun invalidateColumnTypeCache(driver: String, database: String?, schema: String, table: String) {
        columnTypeCache.remove(columnTypeCacheKey(driver, database, schema, table))
    }

    private fun bindTemporal(
        stmt: PreparedStatement,
        paramIndex: Int,
        raw: String,
        sqlType: Int
    ) {
        try {
            when (sqlType) {
                Types.TIMESTAMP_WITH_TIMEZONE ->
                    stmt.setObject(
                        paramIndex,
                        OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        sqlType
                    )
                Types.TIME_WITH_TIMEZONE ->
                    stmt.setObject(
                        paramIndex,
                        OffsetTime.parse(raw),
                        sqlType
                    )
                else -> stmt.setObject(paramIndex, raw, sqlType)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Cannot bind '$raw' as SQL type $sqlType for parameter $paramIndex: ${e.message}",
                e
            )
        }
    }

    private fun bindTypedValue(
        stmt: PreparedStatement,
        paramIndex: Int,
        sqlType: String?,
        value: JsonElement
    ) {
        if (value is JsonNull) {
            stmt.setObject(paramIndex, null)
            return
        }
        val raw = value.jsonPrimitive.content
        val type = sqlType?.uppercase()

        when (type) {
            "TINYINT", "SMALLINT", "INT2", "INT", "MEDIUMINT", "INT4", "INTEGER",
            "YEAR", "BIGINT", "INT8", "SERIAL", "BIGSERIAL", "SMALLSERIAL" -> {
                try {
                    stmt.setLong(paramIndex, raw.toLong())
                } catch (_: NumberFormatException) {
                    stmt.setString(paramIndex, raw)
                }
            }
            "FLOAT", "REAL", "FLOAT4", "DOUBLE", "DOUBLE PRECISION", "FLOAT8",
            "NUMERIC", "DECIMAL", "MONEY" -> {
                try {
                    stmt.setDouble(paramIndex, raw.toDouble())
                } catch (_: NumberFormatException) {
                    stmt.setString(paramIndex, raw)
                }
            }
            "BOOL", "BOOLEAN", "BIT" -> {
                val v = raw.trim().lowercase()
                when (v) {
                    "true", "1", "t", "yes", "y" -> stmt.setBoolean(paramIndex, true)
                    "false", "0", "f", "no", "n" -> stmt.setBoolean(paramIndex, false)
                    else -> stmt.setString(paramIndex, raw)
                }
            }
            "DATE" -> bindTemporal(stmt, paramIndex, raw, Types.DATE)
            "TIME" -> bindTemporal(stmt, paramIndex, raw, Types.TIME)
            "DATETIME", "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> bindTemporal(
                stmt, paramIndex, raw, Types.TIMESTAMP
            )
            "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> bindTemporal(
                stmt, paramIndex, raw, Types.TIMESTAMP_WITH_TIMEZONE
            )
            "TIMETZ", "TIME WITH TIME ZONE" -> bindTemporal(
                stmt, paramIndex, raw, Types.TIME_WITH_TIMEZONE
            )
            "BLOB", "BINARY", "VARBINARY", "LONGBLOB", "MEDIUMBLOB", "TINYBLOB", "BYTEA" -> {
                stmt.setBytes(paramIndex, raw.toByteArray(Charsets.UTF_8))
            }
            else -> stmt.setString(paramIndex, raw)
        }
    }
}