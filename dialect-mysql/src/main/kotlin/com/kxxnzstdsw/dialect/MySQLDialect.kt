package com.kxxnzstdsw.dialect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

class MySQLDialect : DatabaseDialect {
    override val driverName = "Mysql"
    override val jdbcDriverClassName = "com.mysql.cj.jdbc.Driver"

    override fun buildJdbcUrl(host: String, port: Int, database: String): String {
        return "jdbc:mysql://$host:$port/$database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    }

    override fun configureConnectionForStreaming(conn: Connection): Boolean {
        val original = conn.autoCommit
        // MySQL 不需要关闭 autoCommit，使用 fetchSize 即可
        return original
    }

    override suspend fun listDatabases(conn: Connection): List<String> = withContext(Dispatchers.IO) {
        // MySQL 没有独立的 schema 层，schema == database
        // 过滤系统库（information_schema / mysql / performance_schema / sys）
        val databases = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW DATABASES").use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1)
                    if (name in setOf("information_schema", "mysql", "performance_schema", "sys")) continue
                    databases.add(name)
                }
            }
        }
        databases
    }

    override suspend fun listSchemas(conn: Connection, database: String): List<String> = withContext(Dispatchers.IO) {
        // MySQL 不支持第二级 schema — listSchemas 必须传 database，否则直接抛错
        if (database.isBlank()) {
            throw IllegalArgumentException(
                "MySQL 不支持跨数据库的 schema 列表 — 必须先选定 database 后再调用 listSchemas"
            )
        }
        // 对 MySQL 而言 schema == database，返回单元素
        listOf(database)
    }

    override suspend fun createSchema(conn: Connection, name: String, options: Map<String, String>, ifNotExists: Boolean): Boolean = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(name, "schema name")
        val sql = buildString {
            append("CREATE DATABASE ")
            if (ifNotExists) append("IF NOT EXISTS ")
            append(quoteIdentifier(safeName))
            options["charset"]?.let { append(" CHARACTER SET $it") }
            options["collate"]?.let { append(" COLLATE $it") }
        }
        conn.createStatement().use { stmt -> stmt.execute(sql) }
        true
    }

    override suspend fun deleteSchema(conn: Connection, name: String, ifExists: Boolean): Boolean = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(name, "schema name")
        conn.createStatement().use { stmt ->
            val ifClause = if (ifExists) "IF EXISTS " else ""
            stmt.execute("DROP DATABASE ${ifClause}${quoteIdentifier(safeName)}")
        }
        true
    }

    override suspend fun listTables(conn: Connection, database: String, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeDb = sanitizeIdentifier(database, "database name")
        val tables = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW FULL TABLES FROM ${quoteIdentifier(safeDb)}").use { rs ->
                while (rs.next()) {
                    tables.add(mapOf(
                        "name" to rs.getString(1),
                        "type" to rs.getString(2)
                    ))
                }
            }
        }
        tables
    }

    override suspend fun listUsers(conn: Connection): List<Map<String, String>> = withContext(Dispatchers.IO) {
        // 默认过滤 MySQL 系统用户（mysql.sys / mysql.session / mysql.infoschema 等）。
        // 任何 'mysql.%' 开头的用户视为系统用户 — MySQL 8 默认还有 public / role_* 等也过滤。
        val SYSTEM_USER_PREFIXES = listOf("mysql.", "mariadb.")
        val users = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT User, Host FROM mysql.user").use { rs ->
                while (rs.next()) {
                    val u = rs.getString("User") ?: continue
                    val h = rs.getString("Host") ?: ""
                    if (SYSTEM_USER_PREFIXES.any { u.startsWith(it) }) continue
                    users.add(mapOf("user" to u, "host" to h))
                }
            }
        }
        users
    }

    override suspend fun updatePrivileges(
        conn: Connection,
        user: String,
        schema: String,
        privileges: List<String>,
        isGrant: Boolean,
        tableName: String?,
        withGrantOption: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val safeSchema = sanitizeIdentifier(schema, "schema name")
        // 校验权限名：允许大写字母 + 空格（"ALL PRIVILEGES" / "ALL"）+ 下划线。
        val safePrivs = privileges.map { priv ->
            val p = priv.trim()
            if (!p.matches(Regex("^[A-Z_ ]+$", RegexOption.IGNORE_CASE))) {
                throw IllegalArgumentException("Invalid privilege name: $p — 仅允许字母/空格/下划线")
            }
            p.uppercase()
        }
        val privilegeList = safePrivs.joinToString(", ")
        val grantOptionSql = if (withGrantOption) " WITH GRANT OPTION" else ""
        val onTarget = if (tableName.isNullOrBlank()) {
            "${quoteIdentifier(safeSchema)}.*"
        } else {
            "${quoteIdentifier(safeSchema)}.${quoteIdentifier(sanitizeIdentifier(tableName, "table name"))}"
        }
        val sql = if (isGrant) {
            "GRANT $privilegeList ON $onTarget TO '$safeUser'@'%'$grantOptionSql"
        } else {
            "REVOKE $privilegeList ON $onTarget FROM '$safeUser'@'%'"
        }
        conn.createStatement().use { stmt ->
            stmt.execute(sql)
        }
        true
    }

    override suspend fun createUser(conn: Connection, user: String, password: String, host: String): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        // host 必须用反引号包裹，不允许 `'` / `\` 等破坏引号的字符
        val hostSafe = host.takeIf { '\'' !in it && '\\' !in it && '"' !in it }
            ?: throw IllegalArgumentException(
                "Invalid MySQL host '$host' — 不允许包含 '、\\ 或 \" 字符（避免 SQL 注入）"
            )
        val escapedPwd = password.replace("\\", "\\\\").replace("'", "\\'")
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE USER '$safeUser'@'$hostSafe' IDENTIFIED BY '$escapedPwd'")
        }
        true
    }

    override suspend fun deleteUser(conn: Connection, user: String, host: String): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val hostSafe = host.takeIf { '\'' !in it && '\\' !in it && '"' !in it }
            ?: throw IllegalArgumentException("Invalid MySQL host '$host'")
        conn.createStatement().use { stmt ->
            stmt.execute("DROP USER '$safeUser'@'$hostSafe'")
        }
        true
    }

    override suspend fun updatePassword(conn: Connection, user: String, password: String, host: String): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val hostSafe = host.takeIf { '\'' !in it && '\\' !in it && '"' !in it }
            ?: throw IllegalArgumentException("Invalid MySQL host '$host'")
        val escapedPwd = password.replace("\\", "\\\\").replace("'", "\\'")
        conn.createStatement().use { stmt ->
            stmt.execute("ALTER USER '$safeUser'@'$hostSafe' IDENTIFIED BY '$escapedPwd'")
        }
        true
    }

    override suspend fun listPrivileges(conn: Connection, user: String, host: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val safeHost = sanitizeIdentifier(host, "host")
        val privileges = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW GRANTS FOR '$safeUser'@'$safeHost'").use { rs ->
                while (rs.next()) {
                    privileges.add(mapOf("grant" to rs.getString(1)))
                }
            }
        }
        privileges
    }

    override suspend fun listAllGrants(conn: Connection, user: String, host: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val safeHost = sanitizeIdentifier(host, "host")
        val grants = mutableListOf<Map<String, String>>()
        // 匹配 GRANT <privileges> ON <schema>.<table> TO ...
        val grantRegex = Regex("""GRANT\s+(.+?)\s+ON\s+(?:(`[^`]*`|\*)\.)?(`[^`]*`|\*)\s+TO""", RegexOption.IGNORE_CASE)
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW GRANTS FOR '$safeUser'@'$safeHost'").use { rs ->
                while (rs.next()) {
                    val line = rs.getString(1) ?: continue
                    val match = grantRegex.find(line) ?: continue
                    val privs = match.groupValues[1]
                    val schema = match.groupValues[2].removeSurrounding("`").ifEmpty { "*" }
                    val table = match.groupValues[3].removeSurrounding("`")
                    // 跳过 *.* 级别的全局授权（非具体表级权限）
                    if (schema == "*" && table == "*") continue
                    grants.add(mapOf(
                        "schema" to schema,
                        "table" to table,
                        "privileges" to privs
                    ))
                }
            }
        }
        grants
    }

    // 转义标识符内部的反引号（` → ``），防止跳出引用
    override fun quoteIdentifier(identifier: String): String {
        val escaped = identifier.replace("`", "``")
        return "`$escaped`"
    }

    private fun sanitizeIdentifier(name: String, label: String): String {
        if (name.isBlank()) throw IllegalArgumentException("$label cannot be empty")
        // 拒绝 null 字节（0x00）— 防 SQL 截断
        if (name.contains(' ')) throw IllegalArgumentException("$label contains null byte")
        // 拒绝单引号 — 防 SQL 注入跳出字面量
        if (name.contains('\'')) throw IllegalArgumentException("$label contains single quote")
        // 拒绝反斜杠 — 防转义序列注入
        if (name.contains('\\')) throw IllegalArgumentException("$label contains backslash")
        // 拒绝分号 — 防语句分割
        if (name.contains(';')) throw IllegalArgumentException("$label contains semicolon")
        return name
    }

    /**
     * MySQL 风格的字符串字面量转义（用于 DDL 中无法参数化的位置，如 COMMENT 子句）。
     * 拒绝 null 字节；保留单引号转义为 \\\'
     */
    internal fun escapeSqlString(value: String, label: String): String {
        if (value.contains(' ')) throw IllegalArgumentException("$label contains null byte")
        if (value.contains(';')) throw IllegalArgumentException("$label contains semicolon")
        if (value.contains('\n')) throw IllegalArgumentException("$label contains newline")
        return value.replace("\\\\", "\\\\\\\\").replace("'", "\\'")
    }

    override fun buildColumnDefinition(
        name: String,
        type: String,
        size: Int?,
        nullable: Boolean,
        isPrimaryKey: Boolean,
        defaultValue: String?,
        autoIncrement: Boolean
    ): String = buildString {
        append(quoteIdentifier(name))
        append(" ")
        append(buildTypeSpec(type, size))
        if (!nullable) append(" NOT NULL")
        if (autoIncrement && isPrimaryKey) append(" AUTO_INCREMENT")
        if (defaultValue != null) {
            append(" DEFAULT ")
            append(if (defaultValue.matches(Regex("\\d+(\\.\\d+)?|NULL|CURRENT_TIMESTAMP", RegexOption.IGNORE_CASE))) {
                defaultValue
            } else {
                "'$defaultValue'"
            })
        }
    }

    override fun buildAddColumnSQL(tableName: String, columnDef: String): String {
        return "ALTER TABLE ${quoteIdentifier(tableName)} ADD COLUMN $columnDef"
    }

    override fun buildDropColumnSQL(tableName: String, columnName: String): String {
        return "ALTER TABLE ${quoteIdentifier(tableName)} DROP COLUMN ${quoteIdentifier(columnName)}"
    }

    override fun buildModifyColumnSQL(
        tableName: String,
        name: String,
        type: String?,
        size: Int?,
        nullable: Boolean,
        defaultValue: String?,
        newName: String?
    ): String {
        require(!type.isNullOrBlank()) { "MySQL MODIFY_COLUMN 需要指定 type" }
        val targetName = newName ?: name
        val colDef = buildColumnDefinition(targetName, type, size, nullable, false, defaultValue)
        return "ALTER TABLE ${quoteIdentifier(tableName)} CHANGE COLUMN ${quoteIdentifier(name)} $colDef"
    }

    override suspend fun getCreateTableDDL(conn: Connection, tableName: String): String = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW CREATE TABLE ${quoteIdentifier(safeTable)}").use { rs ->
                if (rs.next()) rs.getString(2)
                else throw IllegalArgumentException("Table '$tableName' not found")
            }
        }
    }

    override fun buildTableOptionsSQL(options: Map<String, String>): String = buildString {
        val parts = mutableListOf<String>()
        options["engine"]?.let { parts.add("ENGINE=$it") }
        options["charset"]?.let { parts.add("DEFAULT CHARSET=$it") }
        options["collate"]?.let { parts.add("COLLATE=$it") }
        options["comment"]?.let { parts.add("COMMENT='${it.replace("'", "\\'")}'") }
        if (parts.isNotEmpty()) {
            append(" ")
            append(parts.joinToString(" "))
        }
    }

    override fun buildPostCreateStatements(tableName: String, options: Map<String, String>): List<String> {
        // MySQL 的 COMMENT 在 CREATE TABLE 语句内处理，无需后续语句
        return emptyList()
    }

    // MySQL 标识符可用反引号包裹
    private val orderByRegex = Regex(
        """^\s*[`]?[\w]+[`]?(\s+(ASC|DESC))?(\s*,\s*[`]?[\w]+[`]?(\s+(ASC|DESC))?)?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val dangerousKeywords = setOf(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE",
        "UNION", "EXEC", "EXECUTE", "TRUNCATE", "GRANT", "REVOKE"
    )

    override fun validateSqlFragment(sql: String, label: String) {
        if (sql.contains(' ')) throw IllegalArgumentException("$label contains null byte")
        if (sql.contains(';')) throw IllegalArgumentException("$label contains illegal character ';'")
        if (sql.contains("--") || sql.contains("/*")) throw IllegalArgumentException("$label contains illegal comment")
        // 去除引号内容后再扫描关键词：
        // 先把 '' 转义引号替换为占位符，再去除所有 '...' 片段
        val bare = sql
            .replace("''", "")
            .replace(Regex("'[^']*'"), "")
            .uppercase()
        for (kw in dangerousKeywords) {
            if (Regex("\\b$kw\\b").containsMatchIn(bare)) {
                throw IllegalArgumentException("$label contains forbidden keyword: $kw")
            }
        }
    }

    override fun validateOrderBy(sql: String) {
        if (!orderByRegex.matches(sql)) {
            throw IllegalArgumentException("Invalid ORDER BY format: $sql")
        }
    }

    private fun buildTypeSpec(type: String, size: Int?): String {
        return if (size != null && type.uppercase() in listOf("VARCHAR", "CHAR", "VARBINARY", "BINARY")) {
            "$type($size)"
        } else {
            type
        }
    }

    // region 函数/存储过程管理

    /**
     * 列出 schema 下所有函数、存储过程和触发器。
     *
     * MySQL schema == database：从 INFORMATION_SCHEMA.ROUTINES 读函数/过程，
     * 从 INFORMATION_SCHEMA.TRIGGERS 读触发器，最后按名称合并。
     */
    override suspend fun listRoutines(conn: Connection, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val routines = mutableListOf<Map<String, String>>()
        val safeDb = sanitizeIdentifier(schema.ifBlank { conn.catalog ?: "" }, "database name")

        // 1. 函数 / 存储过程
        conn.prepareStatement("""
            SELECT ROUTINE_NAME, ROUTINE_TYPE, DATA_TYPE, REMARKS,
                   SQL_DATA_ACCESS, IS_DETERMINISTIC, SECURITY_TYPE
            FROM INFORMATION_SCHEMA.ROUTINES
            WHERE ROUTINE_SCHEMA = ?
            ORDER BY ROUTINE_TYPE, ROUTINE_NAME
        """.trimIndent()).use { stmt ->
            stmt.setString(1, safeDb)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val sqlDataAccess = rs.getString("SQL_DATA_ACCESS") ?: ""
                    val deterministic = rs.getString("IS_DETERMINISTIC") ?: "NO"
                    routines.add(mapOf(
                        "name" to (rs.getString("ROUTINE_NAME") ?: ""),
                        "routine_type" to (rs.getString("ROUTINE_TYPE") ?: "FUNCTION"),
                        "return_type" to (rs.getString("DATA_TYPE") ?: ""),
                        "language" to "SQL",
                        "security_definer" to (rs.getString("SECURITY_TYPE") ?: "DEFINER"),
                        "volatility" to if (deterministic.equals("YES", ignoreCase = true)) "DETERMINISTIC"
                                        else if (sqlDataAccess.equals("NO SQL", ignoreCase = true)) "NO SQL"
                                        else "VOLATILE",
                        "arg_count" to countParameters(conn, safeDb, rs.getString("ROUTINE_NAME") ?: ""),
                        "arg_names" to listParameterNames(conn, safeDb, rs.getString("ROUTINE_NAME") ?: ""),
                        "schema" to safeDb,
                        "description" to (rs.getString("REMARKS") ?: ""),
                        "trigger_table" to ""
                    ))
                }
            }
        }

        // 2. 触发器
        conn.prepareStatement("""
            SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, REMARKS, ACTION_TIMING, EVENT_MANIPULATION
            FROM INFORMATION_SCHEMA.TRIGGERS
            WHERE TRIGGER_SCHEMA = ?
            ORDER BY TRIGGER_NAME
        """.trimIndent()).use { stmt ->
            stmt.setString(1, safeDb)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    routines.add(mapOf(
                        "name" to (rs.getString("TRIGGER_NAME") ?: ""),
                        "routine_type" to "TRIGGER",
                        "return_type" to "${rs.getString("ACTION_TIMING") ?: ""} ${rs.getString("EVENT_MANIPULATION") ?: ""}".trim(),
                        "language" to "SQL",
                        "security_definer" to "DEFINER",
                        "volatility" to "VOLATILE",
                        "arg_count" to "0",
                        "arg_names" to "",
                        "schema" to safeDb,
                        "description" to (rs.getString("REMARKS") ?: ""),
                        "trigger_table" to (rs.getString("EVENT_OBJECT_TABLE") ?: "")
                    ))
                }
            }
        }

        routines
    }

    private fun countParameters(conn: Connection, db: String, routineName: String): String {
        conn.prepareStatement(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.PARAMETERS " +
            "WHERE SPECIFIC_SCHEMA = ? AND SPECIFIC_NAME = ? AND PARAMETER_NAME IS NOT NULL"
        ).use { stmt ->
            stmt.setString(1, db)
            stmt.setString(2, routineName)
            stmt.executeQuery().use { rs -> if (rs.next()) return rs.getInt(1).toString() }
        }
        return "0"
    }

    private fun listParameterNames(conn: Connection, db: String, routineName: String): String {
        val names = mutableListOf<String>()
        conn.prepareStatement(
            "SELECT PARAMETER_NAME, PARAMETER_MODE, DATA_TYPE " +
            "FROM INFORMATION_SCHEMA.PARAMETERS " +
            "WHERE SPECIFIC_SCHEMA = ? AND SPECIFIC_NAME = ? AND PARAMETER_NAME IS NOT NULL " +
            "ORDER BY ORDINAL_POSITION"
        ).use { stmt ->
            stmt.setString(1, db)
            stmt.setString(2, routineName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val mode = rs.getString("PARAMETER_MODE") ?: "IN"
                    val name = rs.getString("PARAMETER_NAME") ?: continue
                    val type = rs.getString("DATA_TYPE") ?: ""
                    names.add("$mode $name $type")
                }
            }
        }
        return names.joinToString(", ")
    }

    /**
     * 获取函数/存储过程/触发器的 DDL 定义。
     * 使用 MySQL 原生 SHOW CREATE 命令。
     */
    override suspend fun getRoutineDDL(conn: Connection, routineName: String, schema: String): String = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(routineName, "routine name")
        val safeDb = sanitizeIdentifier(schema.ifBlank { conn.catalog ?: "" }, "database name")
        val ddl = mutableListOf<String>()

        // 尝试 SHOW CREATE PROCEDURE
        try {
            conn.prepareStatement("SHOW CREATE PROCEDURE ${quoteIdentifier(safeDb)}.${quoteIdentifier(safeName)}").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        // 第 2 列是 Create Procedure
                        val createSql = rs.getString(2)
                        if (!createSql.isNullOrBlank()) return@withContext createSql
                    }
                }
            }
        } catch (_: Exception) { /* 可能是函数而非过程，继续 */ }

        // 尝试 SHOW CREATE FUNCTION
        try {
            conn.prepareStatement("SHOW CREATE FUNCTION ${quoteIdentifier(safeDb)}.${quoteIdentifier(safeName)}").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val createSql = rs.getString(2)
                        if (!createSql.isNullOrBlank()) return@withContext createSql
                    }
                }
            }
        } catch (_: Exception) { /* 可能是触发器 */ }

        // 尝试 SHOW CREATE TRIGGER
        try {
            conn.prepareStatement("SHOW CREATE TRIGGER ${quoteIdentifier(safeDb)}.${quoteIdentifier(safeName)}").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        // MySQL 8.0+ SHOW CREATE TRIGGER 返回的列为 SQL Original Statement
                        val createSql = try { rs.getString(2) } catch (_: Exception) { rs.getString(1) }
                        if (!createSql.isNullOrBlank()) return@withContext createSql
                    }
                }
            }
        } catch (_: Exception) { /* 都不是 — 抛错 */ }

        throw IllegalArgumentException("未找到函数/存储过程/触发器 '$routineName'，database: '$safeDb'")
    }

    /**
     * 执行 DDL 创建函数/存储过程/触发器。
     * 注意：CREATE TRIGGER 不支持 IF NOT EXISTS；DDL 中必须携带完整定义。
     */
    override suspend fun createRoutine(conn: Connection, ddl: String): Boolean = withContext(Dispatchers.IO) {
        conn.createStatement().use { stmt -> stmt.execute(ddl) }
        true
    }

    /**
     * 删除函数/存储过程/触发器。
     */
    override suspend fun dropRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        ifExists: Boolean,
        cascade: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(routineName, "routine name")
        val safeDb = sanitizeIdentifier(schema.ifBlank { conn.catalog ?: "" }, "database name")
        val upperType = routineType.uppercase()

        val sql = buildString {
            when (upperType) {
                "TRIGGER" -> {
                    append("DROP TRIGGER ")
                    if (ifExists) append("IF EXISTS ")
                    append(quoteIdentifier(safeDb))
                    append(".")
                    append(quoteIdentifier(safeName))
                }
                "FUNCTION" -> {
                    append("DROP FUNCTION ")
                    if (ifExists) append("IF EXISTS ")
                    append(quoteIdentifier(safeDb))
                    append(".")
                    append(quoteIdentifier(safeName))
                }
                "PROCEDURE" -> {
                    append("DROP PROCEDURE ")
                    if (ifExists) append("IF EXISTS ")
                    append(quoteIdentifier(safeDb))
                    append(".")
                    append(quoteIdentifier(safeName))
                }
                else -> throw IllegalArgumentException("Unsupported routine type: $routineType")
            }
        }
        conn.createStatement().use { stmt -> stmt.execute(sql) }
        true
    }

    /**
     * 调用函数或存储过程 — 使用参数化绑定防止注入。
     *
     * 函数：SELECT name(?, ?, ...)
     * 过程：CALL schema.name(?, ?, ...)
     */
    override suspend fun callRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        args: List<String?>
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(routineName, "routine name")
        val safeDb = sanitizeIdentifier(schema.ifBlank { conn.catalog ?: "" }, "database name")
        val isProcedure = routineType.equals("PROCEDURE", ignoreCase = true)

        val result = mutableMapOf<String, Any?>()
        val placeholders = args.joinToString(", ") { "?" }
        val qualifiedName = "${quoteIdentifier(safeDb)}.${quoteIdentifier(safeName)}"
        val sql = if (isProcedure) {
            "CALL $qualifiedName($placeholders)"
        } else {
            "SELECT $qualifiedName($placeholders) AS result"
        }

        if (isProcedure) {
            conn.prepareCall(sql).use { callable ->
                bindArgs(callable, args)
                val hasResultSet = callable.execute()
                if (hasResultSet) {
                    val rs = callable.resultSet
                    val rows = mutableListOf<Map<String, Any?>>()
                    val metaData = rs.metaData
                    val columnCount = metaData.columnCount
                    while (rs.next()) {
                        val row = mutableMapOf<String, Any?>()
                        for (col in 1..columnCount) row[metaData.getColumnLabel(col)] = rs.getObject(col)
                        rows.add(row)
                    }
                    result["result_set"] = rows
                    result["row_count"] = rows.size
                }
                val updateCount = callable.updateCount
                if (updateCount >= 0) result["update_count"] = updateCount
            }
        } else {
            conn.prepareStatement(sql).use { stmt ->
                bindArgs(stmt, args)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val metaData = rs.metaData
                        val columnCount = metaData.columnCount
                        if (columnCount == 1) {
                            result["result"] = rs.getObject(1)
                        } else {
                            val row = mutableMapOf<String, Any?>()
                            for (col in 1..columnCount) row[metaData.getColumnLabel(col)] = rs.getObject(col)
                            result["result"] = row
                        }
                        result["row_count"] = 1
                    }
                }
            }
        }

        result["routine_type"] = upperType(routineType)
        result["schema"] = safeDb
        result
    }

    private fun bindArgs(stmt: java.sql.PreparedStatement, args: List<String?>) {
        for ((i, v) in args.withIndex()) {
            if (v == null) stmt.setNull(i + 1, java.sql.Types.NULL)
            else stmt.setString(i + 1, v)
        }
    }

    /**
     * 获取函数/存储过程/触发器的详细信息（后端自动解析 routineType）。
     */
    override suspend fun getRoutineInfo(conn: Connection, routineName: String, schema: String): Map<String, String> = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(routineName, "routine name")
        val safeDb = sanitizeIdentifier(schema.ifBlank { conn.catalog ?: "" }, "database name")

        // 1. 查 ROUTINES（函数/过程）
        conn.prepareStatement("""
            SELECT ROUTINE_NAME, ROUTINE_TYPE, DATA_TYPE, REMARKS,
                   SQL_DATA_ACCESS, IS_DETERMINISTIC, SECURITY_TYPE, CREATED, LAST_ALTERED
            FROM INFORMATION_SCHEMA.ROUTINES
            WHERE ROUTINE_SCHEMA = ? AND ROUTINE_NAME = ?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, safeDb)
            stmt.setString(2, safeName)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val sqlDataAccess = rs.getString("SQL_DATA_ACCESS") ?: ""
                    val deterministic = rs.getString("IS_DETERMINISTIC") ?: "NO"
                    return@withContext mapOf(
                        "name" to (rs.getString("ROUTINE_NAME") ?: safeName),
                        "routine_type" to (rs.getString("ROUTINE_TYPE") ?: "FUNCTION"),
                        "schema" to safeDb,
                        "language" to "SQL",
                        "return_type" to (rs.getString("DATA_TYPE") ?: ""),
                        "volatility" to if (deterministic.equals("YES", ignoreCase = true)) "DETERMINISTIC"
                                        else if (sqlDataAccess.equals("NO SQL", ignoreCase = true)) "NO SQL"
                                        else "VOLATILE",
                        "security_definer" to (rs.getString("SECURITY_TYPE") ?: "DEFINER"),
                        "arg_count" to countParameters(conn, safeDb, safeName),
                        "arg_names" to listParameterNames(conn, safeDb, safeName),
                        "description" to (rs.getString("REMARKS") ?: ""),
                        "trigger_table" to "",
                        "created" to (rs.getTimestamp("CREATED")?.toString() ?: ""),
                        "last_altered" to (rs.getTimestamp("LAST_ALTERED")?.toString() ?: "")
                    )
                }
            }
        }

        // 2. 查 TRIGGERS
        conn.prepareStatement("""
            SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, REMARKS, ACTION_TIMING, EVENT_MANIPULATION
            FROM INFORMATION_SCHEMA.TRIGGERS
            WHERE TRIGGER_SCHEMA = ? AND TRIGGER_NAME = ?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, safeDb)
            stmt.setString(2, safeName)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return@withContext mapOf(
                        "name" to (rs.getString("TRIGGER_NAME") ?: safeName),
                        "routine_type" to "TRIGGER",
                        "schema" to safeDb,
                        "language" to "SQL",
                        "return_type" to "${rs.getString("ACTION_TIMING") ?: ""} ${rs.getString("EVENT_MANIPULATION") ?: ""}".trim(),
                        "volatility" to "VOLATILE",
                        "security_definer" to "DEFINER",
                        "arg_count" to "0",
                        "arg_names" to "",
                        "description" to (rs.getString("REMARKS") ?: ""),
                        "trigger_table" to (rs.getString("EVENT_OBJECT_TABLE") ?: "")
                    )
                }
            }
        }

        throw IllegalArgumentException("未找到函数/存储过程/触发器 '$routineName'，database: '$safeDb'")
    }

    /**
     * 调试函数（SHOW CREATE + 参数列表 + 依赖关系）。
     */
    override suspend fun debugRoutine(conn: Connection, routineName: String, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(routineName, "routine name")
        val safeDb = sanitizeIdentifier(schema.ifBlank { conn.catalog ?: "" }, "database name")
        val results = mutableListOf<Map<String, String>>()

        // 1. DDL (SHOW CREATE)
        try {
            val ddl = getRoutineDDL(conn, safeName, safeDb)
            results.add(mapOf("type" to "DDL", "output" to ddl))
        } catch (e: Exception) {
            results.add(mapOf("type" to "DDL", "output" to "(DDL 不可用: ${e.message})"))
        }

        // 2. INFO
        try {
            val info = getRoutineInfo(conn, safeName, safeDb)
            results.add(mapOf(
                "type" to "INFO",
                "output" to buildString {
                    appendLine("Name: ${info["name"]}")
                    appendLine("Database: ${info["schema"]}")
                    appendLine("Type: ${info["routine_type"]}")
                    appendLine("Language: ${info["language"]}")
                    appendLine("Return: ${info["return_type"]}")
                    appendLine("Determinism: ${info["volatility"]}")
                    appendLine("Security: ${info["security_definer"]}")
                    appendLine("Parameters: ${info["arg_names"]}")
                    appendLine("Description: ${info["description"]}")
                }
            ))
        } catch (e: Exception) {
            results.add(mapOf("type" to "INFO", "output" to "(INFO 不可用: ${e.message})"))
        }

        // 3. EXPLAIN（MySQL 函数不支持 EXPLAIN — 给出说明）
        results.add(mapOf("type" to "EXPLAIN", "output" to "MySQL 不支持函数级别的 EXPLAIN — 请使用 SHOW CREATE 或检查调用方 SQL"))

        results
    }

    /**
     * 验证 DDL 语法 — 解析后尝试用临时隔离名执行并立即回滚（MySQL DDL 隐式提交，
     * 因此无法回滚；改用解析对象名后用临时别名创建并立即 DROP）。
     */
    override suspend fun validateRoutineDDL(conn: Connection, ddl: String): Boolean = withContext(Dispatchers.IO) {
        // 拒绝多语句注入：只允许一条 CREATE 类型的 DDL
        val trimmed = ddl.trim().trimEnd(';')
        if (trimmed.contains(';')) throw IllegalArgumentException("DDL 不允许包含多条语句（分号）")

        // 解析 DDL 类型 + 名称（schema.name）
        val match = Regex("""(?i)CREATE\s+(?:OR\s+REPLACE\s+)?(FUNCTION|PROCEDURE|TRIGGER)\s+(?:`?(\w+)`?\.)?`?(\w+)`?""").find(trimmed)
            ?: throw IllegalArgumentException("无法从 DDL 中解析对象类型/名称")

        val objType = match.groupValues[1].uppercase()
        val objSchema = match.groupValues[2].ifBlank { conn.catalog ?: "" }
        val objName = match.groupValues[3]
        if (objSchema.isBlank()) throw IllegalArgumentException("无法定位对象所属 database")

        val qualifiedName = "${quoteIdentifier(objSchema)}.${quoteIdentifier(objName)}"
        // 用 SET autocommit=0 + ROLLBACK 隔离（DDL 在 MySQL 仍会隐式提交，best-effort 验证）
        try {
            conn.createStatement().use { stmt ->
                stmt.execute(trimmed)
                // 立刻清理
                val dropSql = when (objType) {
                    "FUNCTION" -> "DROP FUNCTION IF EXISTS $qualifiedName"
                    "PROCEDURE" -> "DROP PROCEDURE IF EXISTS $qualifiedName"
                    "TRIGGER" -> "DROP TRIGGER IF EXISTS $qualifiedName"
                    else -> ""
                }
                if (dropSql.isNotEmpty()) {
                    try { stmt.execute(dropSql) } catch (_: Exception) { /* 忽略清理失败 */ }
                }
            }
            true
        } catch (e: Exception) {
            throw IllegalArgumentException("DDL 语法验证失败: ${e.message}")
        }
    }

    private fun upperType(s: String): String = s.uppercase()

    // endregion

    // region Views (视图)

    override suspend fun listViews(conn: Connection, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeDb = sanitizeIdentifier(schema.ifBlank { "" }, "database name")
        val views = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                if (safeDb.isNotBlank()) "SHOW FULL TABLES FROM ${quoteIdentifier(safeDb)} WHERE Table_type = 'VIEW'"
                else "SHOW FULL TABLES WHERE Table_type = 'VIEW'"
            ).use { rs ->
                while (rs.next()) views.add(mapOf("name" to rs.getString(1)))
            }
        }
        views
    }

    override suspend fun createView(conn: Connection, viewName: String, definition: String): Boolean = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(viewName, "view name")
        if (definition.contains(';')) throw IllegalArgumentException("视图定义不允许包含分号")
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE VIEW ${quoteIdentifier(safeName)} AS $definition")
        }
        true
    }

    override suspend fun dropView(conn: Connection, viewName: String, ifExists: Boolean): Boolean = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(viewName, "view name")
        val sql = buildString {
            append("DROP VIEW ")
            if (ifExists) append("IF EXISTS ")
            append(quoteIdentifier(safeName))
        }
        conn.createStatement().use { stmt -> stmt.execute(sql) }
        true
    }

    override suspend fun getViewDDL(conn: Connection, viewName: String, schema: String): String = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(viewName, "view name")
        val safeDb = sanitizeIdentifier(schema.ifBlank { "" }, "database name")
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                if (safeDb.isNotBlank()) "SHOW CREATE VIEW ${quoteIdentifier(safeDb)}.${quoteIdentifier(safeName)}"
                else "SHOW CREATE VIEW ${quoteIdentifier(safeName)}"
            ).use { rs ->
                if (rs.next()) return@withContext (rs.getString(2) ?: "")
            }
        }
        throw IllegalArgumentException("未找到视图 '$viewName'")
    }

    // endregion

    // region Indexes (索引)

    override suspend fun listIndexes(conn: Connection, tableName: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        // 用 mutable map 累积，再转为不可变 map
        val acc = mutableMapOf<String, MutableMap<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW INDEX FROM ${quoteIdentifier(safeTable)}").use { rs ->
                while (rs.next()) {
                    val name = rs.getString("Key_name") ?: continue
                    val column = rs.getString("Column_name") ?: continue
                    val nonUnique = rs.getInt("Non_unique") != 0
                    val existing = acc[name]
                    if (existing != null) {
                        val cols = (existing["columns"] ?: "").split(",").toMutableList()
                        cols.add(column)
                        existing["columns"] = cols.joinToString(",")
                    } else {
                        acc[name] = mutableMapOf(
                            "name" to name,
                            "unique" to (!nonUnique).toString(),
                            "columns" to column
                        )
                    }
                }
            }
        }
        // 按 name 排序输出
        acc.values.map { it.toMap() }.sortedBy { it["name"] }
    }

    override suspend fun createIndex(
        conn: Connection,
        tableName: String,
        indexName: String,
        columns: List<String>,
        unique: Boolean,
        ifNotExists: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val safeIndex = sanitizeIdentifier(indexName, "index name")
        val safeCols = columns.joinToString(", ") { quoteIdentifier(it) }
        val sql = buildString {
            if (unique) append("CREATE UNIQUE INDEX ")
            else append("CREATE INDEX ")
            if (ifNotExists) append("IF NOT EXISTS ")
            append(quoteIdentifier(safeIndex))
            append(" ON ${quoteIdentifier(safeTable)} ($safeCols)")
        }
        conn.createStatement().use { stmt -> stmt.execute(sql) }
        true
    }

    override suspend fun dropIndex(conn: Connection, indexName: String, tableName: String?, ifExists: Boolean): Boolean = withContext(Dispatchers.IO) {
        val safeIndex = sanitizeIdentifier(indexName, "index name")
        val safeTable = tableName?.let { sanitizeIdentifier(it, "table name") }
        val ifClause = if (ifExists) "IF EXISTS " else ""
        val sql = if (safeTable != null) {
            "DROP INDEX ${ifClause}${quoteIdentifier(safeIndex)} ON ${quoteIdentifier(safeTable)}"
        } else {
            "DROP INDEX ${ifClause}${quoteIdentifier(safeIndex)}"
        }
        conn.createStatement().use { stmt -> stmt.execute(sql) }
        true
    }

    // endregion

    // region Foreign Keys (外键)

    override suspend fun listForeignKeys(conn: Connection, tableName: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeDb = conn.catalog ?: ""
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val fks = mutableListOf<Map<String, String>>()
        conn.prepareStatement(
            "SELECT kcu.CONSTRAINT_NAME, kcu.COLUMN_NAME, kcu.REFERENCED_TABLE_NAME, kcu.REFERENCED_COLUMN_NAME, " +
            "rc.UPDATE_RULE, rc.DELETE_RULE " +
            "FROM information_schema.KEY_COLUMN_USAGE kcu " +
            "JOIN information_schema.REFERENTIAL_CONSTRAINTS rc " +
            "  ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA " +
            "WHERE kcu.TABLE_SCHEMA = ? AND kcu.TABLE_NAME = ? AND kcu.REFERENCED_TABLE_NAME IS NOT NULL " +
            "ORDER BY kcu.CONSTRAINT_NAME, kcu.ORDINAL_POSITION"
        ).use { stmt ->
            stmt.setString(1, safeDb)
            stmt.setString(2, safeTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    fks.add(mapOf(
                        "name" to rs.getString("CONSTRAINT_NAME"),
                        "column" to rs.getString("COLUMN_NAME"),
                        "ref_table" to rs.getString("REFERENCED_TABLE_NAME"),
                        "ref_column" to rs.getString("REFERENCED_COLUMN_NAME"),
                        "on_update" to (rs.getString("UPDATE_RULE") ?: "RESTRICT"),
                        "on_delete" to (rs.getString("DELETE_RULE") ?: "RESTRICT")
                    ))
                }
            }
        }
        fks
    }

    override suspend fun addForeignKey(
        conn: Connection,
        tableName: String,
        fkName: String,
        columns: List<String>,
        refTable: String,
        refColumns: List<String>,
        onDelete: String?,
        onUpdate: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val safeFk = sanitizeIdentifier(fkName, "foreign key name")
        val cols = columns.joinToString(", ") { quoteIdentifier(it) }
        val refCols = refColumns.joinToString(", ") { quoteIdentifier(it) }
        val sql = buildString {
            append("ALTER TABLE ${quoteIdentifier(safeTable)} ADD CONSTRAINT ${quoteIdentifier(safeFk)} ")
            append("FOREIGN KEY ($cols) REFERENCES ${quoteIdentifier(refTable)} ($refCols)")
            onDelete?.takeIf { it.isNotBlank() }?.let { append(" ON DELETE $it") }
            onUpdate?.takeIf { it.isNotBlank() }?.let { append(" ON UPDATE $it") }
        }
        conn.createStatement().use { stmt -> stmt.execute(sql) }
        true
    }

    override suspend fun dropForeignKey(conn: Connection, tableName: String, fkName: String, ifExists: Boolean): Boolean = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val safeFk = sanitizeIdentifier(fkName, "foreign key name")
        conn.createStatement().use { stmt ->
            // MySQL 无 DROP FOREIGN KEY IF EXISTS — 预查询 information_schema
            if (ifExists) {
                val exists = stmt.executeQuery(
                    "SELECT 1 FROM information_schema.table_constraints " +
                    "WHERE table_schema=DATABASE() AND table_name='$safeTable' " +
                    "AND constraint_name='$safeFk' LIMIT 1"
                ).use { rs -> rs.next() }
                if (!exists) return@withContext true
            }
            stmt.execute("ALTER TABLE ${quoteIdentifier(safeTable)} DROP FOREIGN KEY ${quoteIdentifier(safeFk)}")
        }
        true
    }

    // endregion

    // region Triggers (触发器)

    override suspend fun listTriggers(conn: Connection, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeDb = sanitizeIdentifier(schema.ifBlank { conn.catalog ?: "" }, "database name")
        val triggers = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, REMARKS " +
                "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ? " +
                "ORDER BY TRIGGER_NAME"
            ).use { rs ->
                // prepared statement 占位符
            }
        }
        // prepared 走一遍
        conn.prepareStatement(
            "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, REMARKS " +
            "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ? ORDER BY TRIGGER_NAME"
        ).use { stmt ->
            stmt.setString(1, safeDb)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    triggers.add(mapOf(
                        "name" to rs.getString("TRIGGER_NAME"),
                        "table" to rs.getString("EVENT_OBJECT_TABLE"),
                        "description" to (rs.getString("REMARKS") ?: ""),
                        "schema" to safeDb
                    ))
                }
            }
        }
        triggers
    }

    override suspend fun getTriggerDDL(conn: Connection, triggerName: String, schema: String): String = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(triggerName, "trigger name")
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW CREATE TRIGGER ${quoteIdentifier(safeName)}").use { rs ->
                if (rs.next()) {
                    // 返回所有列拼接
                    val meta = rs.metaData
                    val sb = StringBuilder()
                    for (i in 1..meta.columnCount) {
                        if (i > 1) sb.append("\n")
                        sb.append(rs.getString(i) ?: "")
                    }
                    return@withContext sb.toString()
                }
            }
        }
        throw IllegalArgumentException("未找到触发器 '$triggerName'")
    }

    // endregion

    // region Table Operations

    override suspend fun renameTable(conn: Connection, oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val safeOld = sanitizeIdentifier(oldName, "table name")
        val safeNew = sanitizeIdentifier(newName, "table name")
        conn.createStatement().use { stmt ->
            stmt.execute("RENAME TABLE ${quoteIdentifier(safeOld)} TO ${quoteIdentifier(safeNew)}")
        }
        true
    }

    override suspend fun truncateTable(conn: Connection, tableName: String): Boolean = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        conn.createStatement().use { stmt ->
            stmt.execute("TRUNCATE TABLE ${quoteIdentifier(safeTable)}")
        }
        true
    }

    // endregion

    // region SQL — EXPLAIN

    override suspend fun explainSQL(conn: Connection, sql: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        if (sql.contains(';')) throw IllegalArgumentException("EXPLAIN 不允许包含分号")
        val rows = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("EXPLAIN $sql").use { rs ->
                val meta = rs.metaData
                val columnCount = meta.columnCount
                while (rs.next()) {
                    val row = mutableMapOf<String, String>()
                    for (i in 1..columnCount) row[meta.getColumnLabel(i)] = rs.getString(i) ?: ""
                    rows.add(row)
                }
            }
        }
        rows
    }

    // endregion

    // region Server

    override suspend fun getServerInfo(conn: Connection): Map<String, String> = withContext(Dispatchers.IO) {
        val meta = conn.metaData
        val version = mutableMapOf<String, String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT VERSION()").use { rs ->
                if (rs.next()) version["serverVersion"] = rs.getString(1)
            }
        }
        mapOf(
            "product" to (meta.databaseProductName ?: ""),
            "version" to (meta.databaseProductVersion ?: ""),
            "driver" to (meta.driverName ?: ""),
            "driverVersion" to (meta.driverVersion ?: ""),
            "url" to (meta.url ?: ""),
            "userName" to (meta.userName ?: "")
        ) + version
    }

    // endregion
}