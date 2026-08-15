package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.format.DateTimeFormatter

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
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

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
            val countSql = "SELECT COUNT(*) AS cnt FROM ${dialect.quoteIdentifier(tableName)}$whereSql"
            val total = conn.prepareStatement(countSql).use { countStmt ->
                countStmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("cnt") else 0L
                }
            }

            if (pageSize == 0 && onRow != null) {
                // 流式全量模式：使用 JDBC 游标逐行读取
                val sql = "SELECT * FROM ${dialect.quoteIdentifier(tableName)}$whereSql$orderBySql"
                val originalAutoCommit = dialect.configureConnectionForStreaming(conn)
                try {
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
                    dialect.restoreConnectionAfterStreaming(conn, originalAutoCommit)
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
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val columnTypes = loadColumnTypes(conn, config.driver, config.database, schema, tableName)

            val columns = values.keys.joinToString(", ") { dialect.quoteIdentifier(it) }
            val placeholders = values.keys.joinToString(", ") { "?" }
            val sql = "INSERT INTO ${dialect.quoteIdentifier(tableName)} ($columns) VALUES ($placeholders)"

            conn.prepareStatement(sql).use { stmt ->
                values.entries.forEachIndexed { index, (name, value) ->
                    bindTypedValue(stmt, index + 1, columnTypes[name.lowercase()], value)
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
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val columnTypes = loadColumnTypes(conn, config.driver, config.database, schema, tableName)

            val setClause = changes.keys.joinToString(", ") { "${dialect.quoteIdentifier(it)} = ?" }
            val whereClause = where.keys.joinToString(" AND ") { "${dialect.quoteIdentifier(it)} = ?" }
            val sql = "UPDATE ${dialect.quoteIdentifier(tableName)} SET $setClause WHERE $whereClause"

            conn.prepareStatement(sql).use { stmt ->
                var paramIndex = 1
                changes.forEach { (name, value) ->
                    bindTypedValue(stmt, paramIndex++, columnTypes[name.lowercase()], value)
                }
                where.forEach { (name, value) ->
                    bindTypedValue(stmt, paramIndex++, columnTypes[name.lowercase()], value)
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
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val columnTypes = loadColumnTypes(conn, config.driver, config.database, schema, tableName)

            val whereClause = where.keys.joinToString(" AND ") { "${dialect.quoteIdentifier(it)} = ?" }
            val sql = "DELETE FROM ${dialect.quoteIdentifier(tableName)} WHERE $whereClause"

            conn.prepareStatement(sql).use { stmt ->
                where.entries.forEachIndexed { index, (name, value) ->
                    bindTypedValue(stmt, index + 1, columnTypes[name.lowercase()], value)
                }
                val affectedRows = stmt.executeUpdate()
                buildJsonObject { put("affectedRows", affectedRows) }
            }
        }
    }

    /**
     * 通过方言的 listColumns SPI 获取表的列类型映射（columnName -> TYPE_NAME）。
     * 名称统一为小写匹配；TYPE_NAME 保持原始大小写供后续 dispatch 使用。
     * 查询失败时返回空 map，调用方会回退到 setString。
     */
    private fun loadColumnTypes(
        conn: java.sql.Connection,
        driver: String,
        database: String?,
        schema: String,
        tableName: String
    ): Map<String, String> {
        return try {
            val dialect = com.kxxnzstdsw.loader.DialectLoader.getDialect(driver)
            val cols = kotlinx.coroutines.runBlocking {
                dialect.listColumns(conn, database ?: "", schema, tableName)
            }
            cols.mapNotNull { col ->
                val name = col["name"] as? String ?: return@mapNotNull null
                val type = col["type"] as? String ?: return@mapNotNull null
                name.lowercase() to type
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 工业做法（对齐 MyBatis BaseTypeHandler / Hibernate JdbcTimeJavaType）：
     * 对带 TZ 类型（PG timestamptz / timetz），字符串必须先解析成 java.time 对象
     * 然后 setObject — 因为 PG JDBC `setObject(idx, String, Types.TIMESTAMP_WITH_TIMEZONE)`
     * 会主动拒绝 String（其它类型已支持）。
     *
     * - 无 TZ 类型（DATE / TIME / DATETIME / TIMESTAMP）直接 setObject 字符串：
     *   PG/MySQL driver 都接受 ISO-8601 字符串。
     *
     * 失败时抛 IllegalArgumentException 让 RequestDispatcher 包成错误信封。
     */
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

    /**
     * 根据列 SQL 类型名将 JsonPrimitive 绑定到 PreparedStatement。
     * 涵盖常见 PG / MySQL 数值/布尔/日期/文本类型，未匹配则回退到 setString。
     */
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
            // 整数类型
            "TINYINT", "SMALLINT", "INT2", "INT", "MEDIUMINT", "INT4", "INTEGER",
            "YEAR", "BIGINT", "INT8", "SERIAL", "BIGSERIAL", "SMALLSERIAL" -> {
                try {
                    stmt.setLong(paramIndex, raw.toLong())
                } catch (_: NumberFormatException) {
                    stmt.setString(paramIndex, raw)
                }
            }
            // 浮点类型
            "FLOAT", "REAL", "FLOAT4", "DOUBLE", "DOUBLE PRECISION", "FLOAT8",
            "NUMERIC", "DECIMAL", "MONEY" -> {
                try {
                    stmt.setDouble(paramIndex, raw.toDouble())
                } catch (_: NumberFormatException) {
                    stmt.setString(paramIndex, raw)
                }
            }
            // 布尔
            "BOOL", "BOOLEAN", "BIT" -> {
                val v = raw.trim().lowercase()
                when (v) {
                    "true", "1", "t", "yes", "y" -> stmt.setBoolean(paramIndex, true)
                    "false", "0", "f", "no", "n" -> stmt.setBoolean(paramIndex, false)
                    else -> stmt.setString(paramIndex, raw)
                }
            }
            // 日期时间 — 走 MyBatis / Hibernate 风格：一律 `setObject(idx, raw)` 让驱动解析
            // driver 接受 ISO-8601 字符串，按列类型自动转换到 java.time / 二进制 timestamp
            "DATE" -> bindTemporal(stmt, paramIndex, raw, Types.DATE)
            "TIME" -> bindTemporal(stmt, paramIndex, raw, Types.TIME)
            "DATETIME", "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> bindTemporal(
                stmt, paramIndex, raw, Types.TIMESTAMP
            )
            // 带 TZ 类型：PG driver 仍要把 VARCHAR 显式标成 *_WITH_TIMEZONE，否则走不了二进制 timestamp 协议
            "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> bindTemporal(
                stmt, paramIndex, raw, Types.TIMESTAMP_WITH_TIMEZONE
            )
            "TIMETZ", "TIME WITH TIME ZONE" -> bindTemporal(
                stmt, paramIndex, raw, Types.TIME_WITH_TIMEZONE
            )
            // 二进制
            "BLOB", "BINARY", "VARBINARY", "LONGBLOB", "MEDIUMBLOB", "TINYBLOB", "BYTEA" -> {
                stmt.setBytes(paramIndex, raw.toByteArray(Charsets.UTF_8))
            }
            // 默认按字符串（涵盖 VARCHAR/TEXT/CHAR/ENUM/SET/JSON/JSONB 等）
            else -> stmt.setString(paramIndex, raw)
        }
    }
}