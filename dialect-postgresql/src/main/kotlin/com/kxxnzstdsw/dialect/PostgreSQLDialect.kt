package com.kxxnzstdsw.dialect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

class PostgreSQLDialect : DatabaseDialect {
    override val driverName = "Postgresql"

    // region companion object — 预编译正则与常量集合

    companion object {
        /** 去除单引号字符串内容（含转义引号 '' ） */
        private val QUOTED_STRING_REGEX = Regex("'(?:[^']|'')*'")

        /** ORDER BY 格式校验：标识符可用双引号包裹，后跟可选 ASC/DESC */
        private val ORDER_BY_REGEX = Regex(
            """^\s*"?[\w]+"?(\s+(ASC|DESC))?(\s*,\s*"?[\w]+"?(\s+(ASC|DESC))?)?\s*$""",
            RegexOption.IGNORE_CASE
        )

        /** 危险关键词集合（PG 额外禁止 COPY/DO） */
        private val DANGEROUS_KEYWORDS = setOf(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE",
            "UNION", "EXEC", "EXECUTE", "TRUNCATE", "GRANT", "REVOKE",
            "COPY", "DO"
        )

        /** 每个关键词预编译为 \bKW\b 正则 */
        private val DANGEROUS_KEYWORD_REGEXES: List<Regex> = DANGEROUS_KEYWORDS.map { kw ->
            Regex("\\b$kw\\b", RegexOption.IGNORE_CASE)
        }

        /** 可带 size 后缀的类型 */
        private val SIZABLE_TYPES = setOf("VARCHAR", "CHAR", "VARBINARY", "BINARY")

        /** 默认值中不需要加引号的字面量 */
        private val UNQUOTED_DEFAULT_REGEX = Regex(
            """\d+(\.\d+)?|NULL|CURRENT_TIMESTAMP|CURRENT_DATE|CURRENT_TIME|NOW\(\)""",
            RegexOption.IGNORE_CASE
        )
    }

    // endregion

    // region Schema / Table / User 管理

    override suspend fun listSchemas(conn: Connection): List<String> = withContext(Dispatchers.IO) {
        val schemas = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT nspname FROM pg_catalog.pg_namespace " +
                "WHERE nspname NOT LIKE 'pg_%' AND nspname != 'information_schema' " +
                "ORDER BY nspname"
            ).use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString(1))
                }
            }
        }
        schemas
    }

    override suspend fun createSchema(conn: Connection, name: String): Boolean = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(name, "schema name")
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE SCHEMA ${quoteIdentifier(safeName)}")
        }
        true
    }

    override suspend fun deleteSchema(conn: Connection, name: String): Boolean = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(name, "schema name")
        conn.createStatement().use { stmt ->
            stmt.execute("DROP SCHEMA ${quoteIdentifier(safeName)} CASCADE")
        }
        true
    }

    override suspend fun listTables(conn: Connection, database: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeDb = sanitizeIdentifier(database, "database name")
        val tables = mutableListOf<Map<String, String>>()
        conn.prepareStatement(
            "SELECT table_name, table_type FROM information_schema.tables " +
            "WHERE table_schema = ? AND table_type IN ('BASE TABLE', 'VIEW') ORDER BY table_name"
        ).use { stmt ->
            stmt.setString(1, safeDb)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    tables.add(mapOf(
                        "name" to rs.getString("table_name"),
                        "type" to rs.getString("table_type")
                    ))
                }
            }
        }
        tables
    }

    override suspend fun listUsers(conn: Connection): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val users = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT rolname FROM pg_roles WHERE rolcanlogin = true ORDER BY rolname"
            ).use { rs ->
                while (rs.next()) {
                    users.add(mapOf(
                        "user" to rs.getString("rolname")
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
        val safePrivs = privileges.map { priv ->
            val p = priv.trim()
            if (!p.matches(Regex("^[A-Z_]+$", RegexOption.IGNORE_CASE))) {
                throw IllegalArgumentException("Invalid privilege name: $p")
            }
            p.uppercase()
        }

        // PostgreSQL 将权限分为 schema 级和 table 级，需分别授予/回收
        val schemaLevel = setOf("CREATE", "USAGE")
        val schemaPrivs = safePrivs.filter { it in schemaLevel }
        val tablePrivs = safePrivs.filter { it !in schemaLevel }

        conn.createStatement().use { stmt ->
            // Schema 级权限：GRANT ... ON SCHEMA
            if (schemaPrivs.isNotEmpty()) {
                val privList = schemaPrivs.joinToString(", ")
                val sql = if (isGrant) {
                    "GRANT $privList ON SCHEMA ${quoteIdentifier(safeSchema)} TO ${quoteIdentifier(safeUser)}"
                } else {
                    "REVOKE $privList ON SCHEMA ${quoteIdentifier(safeSchema)} FROM ${quoteIdentifier(safeUser)}"
                }
                stmt.execute(sql)
            }
            // 表级权限：GRANT ... ON ALL TABLES IN SCHEMA
            if (tablePrivs.isNotEmpty()) {
                val privList = tablePrivs.joinToString(", ")
                val sql = if (isGrant) {
                    "GRANT $privList ON ALL TABLES IN SCHEMA ${quoteIdentifier(safeSchema)} TO ${quoteIdentifier(safeUser)}"
                } else {
                    "REVOKE $privList ON ALL TABLES IN SCHEMA ${quoteIdentifier(safeSchema)} FROM ${quoteIdentifier(safeUser)}"
                }
                stmt.execute(sql)
            }
        }
        true
    }

    override suspend fun createUser(conn: Connection, user: String, password: String, host: String): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val escapedPwd = password.replace("'", "''")
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE USER ${quoteIdentifier(safeUser)} WITH PASSWORD '$escapedPwd'")
        }
        true
    }

    override suspend fun deleteUser(conn: Connection, user: String, host: String): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        conn.createStatement().use { stmt ->
            stmt.execute("DROP USER ${quoteIdentifier(safeUser)}")
        }
        true
    }

    override suspend fun updatePassword(conn: Connection, user: String, password: String, host: String): Boolean = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val escapedPwd = password.replace("'", "''")
        conn.createStatement().use { stmt ->
            stmt.execute("ALTER USER ${quoteIdentifier(safeUser)} PASSWORD '$escapedPwd'")
        }
        true
    }

    override suspend fun listPrivileges(conn: Connection, user: String, host: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val privileges = mutableListOf<Map<String, String>>()
        conn.prepareStatement(
            "SELECT table_schema, table_name, privilege_type " +
            "FROM information_schema.table_privileges " +
            "WHERE grantee = ? ORDER BY table_schema, table_name, privilege_type"
        ).use { stmt ->
            stmt.setString(1, safeUser)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    privileges.add(mapOf(
                        "schema" to rs.getString("table_schema"),
                        "table" to rs.getString("table_name"),
                        "privilege" to rs.getString("privilege_type")
                    ))
                }
            }
        }
        privileges
    }

    override suspend fun listAllGrants(conn: Connection, user: String, host: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeUser = sanitizeIdentifier(user, "user name")
        val grants = mutableListOf<Map<String, String>>()
        conn.prepareStatement(
            "SELECT table_schema, table_name, string_agg(privilege_type, ', ' ORDER BY privilege_type) AS privileges " +
            "FROM information_schema.table_privileges " +
            "WHERE grantee = ? " +
            "GROUP BY table_schema, table_name " +
            "ORDER BY table_schema, table_name"
        ).use { stmt ->
            stmt.setString(1, safeUser)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    grants.add(mapOf(
                        "schema" to rs.getString("table_schema"),
                        "table" to rs.getString("table_name"),
                        "privileges" to rs.getString("privileges")
                    ))
                }
            }
        }
        grants
    }

    // endregion

    // region 标识符引用

    // 转义标识符内部的双引号（" → ""），防止跳出引用
    override fun quoteIdentifier(identifier: String): String {
        val escaped = identifier.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun sanitizeIdentifier(name: String, label: String): String {
        if (name.isBlank()) throw IllegalArgumentException("$label cannot be empty")
        if (name.contains(' ')) throw IllegalArgumentException("$label contains null byte")
        return name
    }

    // endregion

    // region DDL 构建

    override fun buildColumnDefinition(
        name: String,
        type: String,
        size: Int?,
        nullable: Boolean,
        isPrimaryKey: Boolean,
        defaultValue: String?
    ): String = buildString {
        append(quoteIdentifier(name))
        append(" ")
        append(buildTypeSpec(type, size))
        if (!nullable) append(" NOT NULL")
        if (defaultValue != null) {
            append(" DEFAULT ")
            append(if (UNQUOTED_DEFAULT_REGEX.matches(defaultValue)) {
                defaultValue
            } else {
                "'${defaultValue.replace("'", "''")}'"
            })
        }
    }

    override fun buildAddColumnSQL(tableName: String, columnDef: String): String {
        return "ALTER TABLE ${quoteIdentifier(tableName)} ADD COLUMN $columnDef"
    }

    override fun buildDropColumnSQL(tableName: String, columnName: String): String {
        return "ALTER TABLE ${quoteIdentifier(tableName)} DROP COLUMN ${quoteIdentifier(columnName)}"
    }

    /**
     * 构建 ALTER TABLE 修改列的完整 SQL。
     * PG 支持单条 ALTER TABLE 语句内用逗号分隔多个子命令（TYPE / SET NOT NULL / SET DEFAULT / RENAME）。
     */
    override fun buildModifyColumnSQL(
        tableName: String,
        name: String,
        type: String,
        size: Int?,
        nullable: Boolean,
        defaultValue: String?,
        newName: String?
    ): String {
        val table = quoteIdentifier(tableName)
        val col = quoteIdentifier(name)
        val typeSpec = buildTypeSpec(type, size)

        val subCommands = mutableListOf<String>()

        // 类型变更
        subCommands.add("ALTER COLUMN $col TYPE $typeSpec")

        // nullable 变更
        subCommands.add(if (nullable) {
            "ALTER COLUMN $col DROP NOT NULL"
        } else {
            "ALTER COLUMN $col SET NOT NULL"
        })

        // 默认值变更
        if (defaultValue != null) {
            val literal = if (UNQUOTED_DEFAULT_REGEX.matches(defaultValue)) {
                defaultValue
            } else {
                "'${defaultValue.replace("'", "''")}'"
            }
            subCommands.add("ALTER COLUMN $col SET DEFAULT $literal")
        } else {
            subCommands.add("ALTER COLUMN $col DROP DEFAULT")
        }

        // 重命名
        if (newName != null) {
            subCommands.add("RENAME COLUMN $col TO ${quoteIdentifier(newName)}")
        }

        return "ALTER TABLE $table ${subCommands.joinToString(", ")}"
    }

    override suspend fun getCreateTableDDL(conn: Connection, tableName: String): String = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")

        // 查询主键列（有序）
        val primaryKeys = mutableListOf<String>()
        conn.prepareStatement(
            "SELECT a.attname FROM pg_index i " +
            "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) " +
            "WHERE i.indrelid = ?::regclass AND i.indisprimary " +
            "ORDER BY array_position(i.indkey, a.attnum)"
        ).use { stmt ->
            stmt.setString(1, safeTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) primaryKeys.add(rs.getString(1))
            }
        }

        // 查询列定义
        val columns = mutableListOf<String>()
        conn.prepareStatement(
            "SELECT c.column_name, c.data_type, c.character_maximum_length, " +
            "c.is_nullable, c.column_default, c.numeric_precision, c.numeric_scale " +
            "FROM information_schema.columns c " +
            "WHERE c.table_schema = (SELECT schemaname FROM pg_tables WHERE tablename = ?) " +
            "AND c.table_name = ? ORDER BY c.ordinal_position"
        ).use { stmt ->
            stmt.setString(1, safeTable)
            stmt.setString(2, safeTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val colName = rs.getString("column_name")
                    val rawType = rs.getString("data_type")
                    val charLen = rs.getInt("character_maximum_length")
                    val nullable = rs.getString("is_nullable") == "YES"
                    val defaultVal = rs.getString("column_default")
                    val numPrec = rs.getInt("numeric_precision")
                    val numScale = rs.getInt("numeric_scale")

                    val typeSpec = when (rawType.uppercase()) {
                        "CHARACTER VARYING" -> if (!rs.wasNull() && charLen > 0) "VARCHAR($charLen)" else "VARCHAR"
                        "CHARACTER" -> if (!rs.wasNull() && charLen > 0) "CHAR($charLen)" else "CHAR"
                        "NUMERIC" -> if (numPrec > 0) "NUMERIC($numPrec, $numScale)" else "NUMERIC"
                        else -> rawType
                    }

                    buildString {
                        append("${quoteIdentifier(colName)} $typeSpec")
                        if (!nullable) append(" NOT NULL")
                        if (defaultVal != null) append(" DEFAULT $defaultVal")
                    }.let { columns.add(it) }
                }
            }
        }

        // 查询 UNIQUE 约束
        val uniqueConstraints = mutableListOf<String>()
        conn.prepareStatement(
            "SELECT tc.constraint_name, string_agg(quote_ident(kcu.column_name), ', ' ORDER BY kcu.ordinal_position) AS cols " +
            "FROM information_schema.table_constraints tc " +
            "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name " +
            "AND tc.table_schema = kcu.table_schema " +
            "WHERE tc.table_name = ? AND tc.constraint_type = 'UNIQUE' " +
            "AND tc.table_schema = (SELECT schemaname FROM pg_tables WHERE tablename = ?) " +
            "GROUP BY tc.constraint_name"
        ).use { stmt ->
            stmt.setString(1, safeTable)
            stmt.setString(2, safeTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    uniqueConstraints.add("UNIQUE (${rs.getString("cols")})")
                }
            }
        }

        // 查询 CHECK 约束
        val checkConstraints = mutableListOf<String>()
        conn.prepareStatement(
            "SELECT cc.check_clause FROM information_schema.table_constraints tc " +
            "JOIN information_schema.check_constraints cc ON tc.constraint_name = cc.constraint_name " +
            "AND tc.table_schema = cc.constraint_schema " +
            "WHERE tc.table_name = ? AND tc.constraint_type = 'CHECK' " +
            "AND tc.table_schema = (SELECT schemaname FROM pg_tables WHERE tablename = ?)"
        ).use { stmt ->
            stmt.setString(1, safeTable)
            stmt.setString(2, safeTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    checkConstraints.add("CHECK (${rs.getString("check_clause")})")
                }
            }
        }

        // 查询索引（排除主键和 UNIQUE 约束自动创建的索引）
        val indexes = mutableListOf<String>()
        conn.prepareStatement(
            "SELECT indexname, indexdef FROM pg_indexes " +
            "WHERE tablename = ? AND schemaname = (SELECT schemaname FROM pg_tables WHERE tablename = ?) " +
            "AND indexname NOT IN (SELECT constraint_name FROM information_schema.table_constraints " +
            "WHERE table_name = ? AND constraint_type IN ('PRIMARY KEY', 'UNIQUE'))"
        ).use { stmt ->
            stmt.setString(1, safeTable)
            stmt.setString(2, safeTable)
            stmt.setString(3, safeTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    indexes.add("-- INDEX: ${rs.getString("indexdef")}")
                }
            }
        }

        // 组装 DDL
        buildString {
            append("CREATE TABLE ${quoteIdentifier(safeTable)} (\n")
            val allDefs = mutableListOf<String>()
            allDefs.addAll(columns)
            if (primaryKeys.isNotEmpty()) {
                allDefs.add("PRIMARY KEY (${primaryKeys.joinToString(", ") { quoteIdentifier(it) }})")
            }
            allDefs.addAll(uniqueConstraints)
            allDefs.addAll(checkConstraints)
            append("  ${allDefs.joinToString(",\n  ")}\n")
            append(")")
            if (indexes.isNotEmpty()) {
                append("\n\n-- Indexes\n")
                append(indexes.joinToString("\n"))
            }
        }
    }

    // endregion

    // region SQL 安全校验

    override fun validateSqlFragment(sql: String, label: String) {
        if (sql.contains(' ')) throw IllegalArgumentException("$label contains null byte")
        val bare = sql.replace(QUOTED_STRING_REGEX, "")
        if (bare.contains(';')) throw IllegalArgumentException("$label contains illegal character ';'")
        if (bare.contains("--") || bare.contains("/*")) throw IllegalArgumentException("$label contains illegal comment")
        val upper = bare.uppercase()
        for (regex in DANGEROUS_KEYWORD_REGEXES) {
            if (regex.containsMatchIn(upper)) {
                val kw = regex.pattern.substringAfter("\\b").substringBefore("\\b")
                throw IllegalArgumentException("$label contains forbidden keyword: $kw")
            }
        }
    }

    override fun validateOrderBy(sql: String) {
        if (!ORDER_BY_REGEX.matches(sql)) {
            throw IllegalArgumentException("Invalid ORDER BY format: $sql")
        }
    }

    // endregion

    // region 工具方法

    private fun buildTypeSpec(type: String, size: Int?): String {
        return if (size != null && type.uppercase() in SIZABLE_TYPES) {
            "$type($size)"
        } else {
            type
        }
    }

    // endregion
}
