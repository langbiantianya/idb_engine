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

    override suspend fun listTables(conn: Connection, database: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
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
        type: String,
        size: Int?,
        nullable: Boolean,
        defaultValue: String?,
        newName: String?
    ): String {
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
}