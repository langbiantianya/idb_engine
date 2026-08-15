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

    override suspend fun listSchemas(conn: Connection, database: String): List<String> = withContext(Dispatchers.IO) {
        val schemas = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW DATABASES").use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString(1))
                }
            }
        }
        schemas
    }

    override suspend fun createSchema(conn: Connection, name: String, options: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(name, "schema name")
        val sql = buildString {
            append("CREATE DATABASE ${quoteIdentifier(safeName)}")
            options["charset"]?.let { append(" CHARACTER SET $it") }
            options["collate"]?.let { append(" COLLATE $it") }
        }
        conn.createStatement().use { stmt -> stmt.execute(sql) }
        true
    }

    override suspend fun deleteSchema(conn: Connection, name: String): Boolean = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(name, "schema name")
        conn.createStatement().use { stmt ->
            stmt.execute("DROP DATABASE ${quoteIdentifier(safeName)}")
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
        val users = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT User, Host FROM mysql.user").use { rs ->
                while (rs.next()) {
                    users.add(mapOf(
                        "user" to rs.getString("User"),
                        "host" to rs.getString("Host")
                    ))
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
        isGrant: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val safeSchema = sanitizeIdentifier(schema, "schema name")
        // 校验权限名只含字母下划线，防止注入
        val safePrivs = privileges.map { priv ->
            val p = priv.trim()
            if (!p.matches(Regex("^[A-Z_]+$", RegexOption.IGNORE_CASE))) {
                throw IllegalArgumentException("Invalid privilege name: $p")
            }
            p
        }
        val privilegeList = safePrivs.joinToString(", ")
        val sql = if (isGrant) {
            "GRANT $privilegeList ON ${quoteIdentifier(safeSchema)}.* TO '${safeUser}'"
        } else {
            "REVOKE $privilegeList ON ${quoteIdentifier(safeSchema)}.* FROM '${safeUser}'"
        }
        conn.createStatement().use { stmt ->
            stmt.execute(sql)
        }
        true
    }

    override suspend fun createUser(conn: Connection, user: String, password: String, host: String): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val safeHost = sanitizeIdentifier(host, "host")
        val escapedPwd = password.replace("'", "\\'")
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE USER '$safeUser'@'$safeHost' IDENTIFIED BY '$escapedPwd'")
        }
        true
    }

    override suspend fun deleteUser(conn: Connection, user: String, host: String): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val safeHost = sanitizeIdentifier(host, "host")
        conn.createStatement().use { stmt ->
            stmt.execute("DROP USER '$safeUser'@'$safeHost'")
        }
        true
    }

    override suspend fun updatePassword(conn: Connection, user: String, password: String, host: String): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val safeHost = sanitizeIdentifier(host, "host")
        val escapedPwd = password.replace("'", "\\'")
        conn.createStatement().use { stmt ->
            stmt.execute("ALTER USER '$safeUser'@'$safeHost' IDENTIFIED BY '$escapedPwd'")
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
        if (name.contains(' ')) throw IllegalArgumentException("$label contains null byte")
        return name
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

    // region 函数/存储过程管理（MySQL 占位实现）

    /**
     * 列出函数/存储过程（MySQL 暂未实现）
     */
    override suspend fun listRoutines(conn: Connection, schema: String): List<Map<String, String>> {
        throw UnsupportedOperationException("MySQL 函数/存储过程管理暂未实现")
    }

    /**
     * 获取函数/存储过程/触发器 DDL（MySQL 暂未实现）
     */
    override suspend fun getRoutineDDL(conn: Connection, routineName: String, schema: String): String {
        throw UnsupportedOperationException("MySQL 函数/存储过程管理暂未实现")
    }

    /**
     * 执行 DDL 创建函数/存储过程（MySQL 暂未实现）
     */
    override suspend fun createRoutine(conn: Connection, ddl: String): Boolean {
        throw UnsupportedOperationException("MySQL 函数/存储过程管理暂未实现")
    }

    /**
     * 删除函数/存储过程（MySQL 暂未实现）
     */
    override suspend fun dropRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        ifExists: Boolean,
        cascade: Boolean
    ): Boolean {
        throw UnsupportedOperationException("MySQL 函数/存储过程管理暂未实现")
    }

    /**
     * 调用函数/存储过程（MySQL 暂未实现）
     */
    override suspend fun callRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        args: List<String?>
    ): Map<String, Any?> {
        throw UnsupportedOperationException("MySQL 函数/存储过程管理暂未实现")
    }

    /**
     * 获取函数/存储过程详细信息（MySQL 暂未实现）
     */
    override suspend fun getRoutineInfo(conn: Connection, routineName: String, schema: String): Map<String, String> {
        throw UnsupportedOperationException("MySQL 函数/存储过程管理暂未实现")
    }

    /**
     * 调试函数（MySQL 暂未实现）
     */
    override suspend fun debugRoutine(conn: Connection, routineName: String, schema: String): List<Map<String, String>> {
        throw UnsupportedOperationException("MySQL 函数/存储过程管理暂未实现")
    }

    /**
     * 验证 DDL 语法（MySQL 暂未实现）
     */
    override suspend fun validateRoutineDDL(conn: Connection, ddl: String): Boolean {
        throw UnsupportedOperationException("MySQL 函数/存储过程管理暂未实现")
    }

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
        unique: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val safeIndex = sanitizeIdentifier(indexName, "index name")
        val safeCols = columns.joinToString(", ") { quoteIdentifier(it) }
        val sql = buildString {
            if (unique) append("CREATE UNIQUE INDEX ")
            else append("CREATE INDEX ")
            append(quoteIdentifier(safeIndex))
            append(" ON ${quoteIdentifier(safeTable)} ($safeCols)")
        }
        conn.createStatement().use { stmt -> stmt.execute(sql) }
        true
    }

    override suspend fun dropIndex(conn: Connection, indexName: String, tableName: String?): Boolean = withContext(Dispatchers.IO) {
        val safeIndex = sanitizeIdentifier(indexName, "index name")
        val safeTable = tableName?.let { sanitizeIdentifier(it, "table name") }
        val sql = if (safeTable != null) {
            "DROP INDEX ${quoteIdentifier(safeIndex)} ON ${quoteIdentifier(safeTable)}"
        } else {
            "DROP INDEX ${quoteIdentifier(safeIndex)}"
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

    override suspend fun dropForeignKey(conn: Connection, tableName: String, fkName: String): Boolean = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val safeFk = sanitizeIdentifier(fkName, "foreign key name")
        conn.createStatement().use { stmt ->
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