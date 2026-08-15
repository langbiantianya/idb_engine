package com.kxxnzstdsw.dialect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection

class PostgreSQLDialect : DatabaseDialect {
    private val logger = LoggerFactory.getLogger(PostgreSQLDialect::class.java)
    override val driverName = "Postgresql"
    override val jdbcDriverClassName = "org.postgresql.Driver"

    override fun buildJdbcUrl(host: String, port: Int, database: String): String {
        return "jdbc:postgresql://$host:$port/$database"
    }

    override fun configureConnectionForStreaming(conn: Connection): Boolean {
        val original = conn.autoCommit
        conn.autoCommit = false  // PostgreSQL 必须关闭 autoCommit 才能启用服务端游标
        return original
    }

    override fun setSearchPath(conn: Connection, schema: String) {
        if (schema.isBlank()) return
        conn.createStatement().use { stmt ->
            stmt.execute("SET search_path TO ${quoteIdentifier(schema)}")
        }
    }

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

    override suspend fun listSchemas(conn: Connection, database: String): List<String> = withContext(Dispatchers.IO) {
        val schemas = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            if (database.isBlank()) {
                // 未指定数据库 → 返回所有数据库列表
                stmt.executeQuery(
                    "SELECT datname FROM pg_catalog.pg_database " +
                    "WHERE datistemplate = false ORDER BY datname"
                ).use { rs ->
                    while (rs.next()) {
                        schemas.add(rs.getString(1))
                    }
                }
            } else {
                // 指定了数据库 → 返回该库下的所有 schema（排除系统 schema）
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
        }
        schemas
    }

    override suspend fun createSchema(conn: Connection, name: String, options: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
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

    override suspend fun listTables(conn: Connection, database: String, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val tables = mutableListOf<Map<String, String>>()
        val query = if (schema.isNotBlank()) {
            logger.debug("listTables: querying schema '{}' in database '{}'", schema, database)
            // 指定了 schema → 精确过滤
            "SELECT table_name, table_type FROM information_schema.tables " +
            "WHERE table_schema = ? AND table_type IN ('BASE TABLE', 'VIEW') ORDER BY table_name"
        } else {
            logger.debug("listTables: querying all schemas in search_path for database '{}'", database)
            // 未指定 → 匹配 search_path 中所有 schema
            "SELECT table_name, table_type FROM information_schema.tables " +
            "WHERE table_schema = ANY(current_schemas(true)) AND table_type IN ('BASE TABLE', 'VIEW') ORDER BY table_name"
        }
        conn.prepareStatement(query).use { stmt ->
            if (schema.isNotBlank()) stmt.setString(1, schema)
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
        defaultValue: String?,
        autoIncrement: Boolean
    ): String = buildString {
        append(quoteIdentifier(name))
        append(" ")
        // PostgreSQL: 自增列使用 SERIAL / BIGSERIAL 替代 INT / BIGINT
        if (autoIncrement && isPrimaryKey) {
            val upperType = type.uppercase()
            when (upperType) {
                "INT", "INTEGER" -> append("SERIAL")
                "BIGINT" -> append("BIGSERIAL")
                "SMALLINT" -> append("SMALLSERIAL")
                else -> append(buildTypeSpec(type, size))
            }
        } else {
            append(buildTypeSpec(type, size))
        }
        if (!autoIncrement && !nullable) append(" NOT NULL")
        // SERIAL 类型隐含 NOT NULL 和 DEFAULT，无需额外声明
        if (defaultValue != null && !autoIncrement) {
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
        type: String?,
        size: Int?,
        nullable: Boolean,
        defaultValue: String?,
        newName: String?
    ): String {
        val table = quoteIdentifier(tableName)
        val col = quoteIdentifier(name)

        val subCommands = mutableListOf<String>()

        // 类型变更（可选 — 纯重命名时跳过）
        if (!type.isNullOrBlank()) {
            val typeSpec = buildTypeSpec(type, size)
            subCommands.add("ALTER COLUMN $col TYPE $typeSpec")
        }

        // nullable 变更（仅在 type 缺省时才有意义，否则 type 已包含 NOT NULL 信息）
        if (type.isNullOrBlank()) {
            subCommands.add(if (nullable) {
                "ALTER COLUMN $col DROP NOT NULL"
            } else {
                "ALTER COLUMN $col SET NOT NULL"
            })
        }

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

        require(subCommands.isNotEmpty()) { "PG MODIFY_COLUMN 至少需要一个修改属性" }
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

    override fun buildTableOptionsSQL(options: Map<String, String>): String {
        // PostgreSQL 不支持表级 ENGINE/CHARSET/COLLATE，返回空串
        return ""
    }

    override fun buildPostCreateStatements(tableName: String, options: Map<String, String>): List<String> {
        val statements = mutableListOf<String>()
        val comment = options["comment"]
        if (comment != null) {
            val escaped = comment.replace("'", "''")
            statements.add("COMMENT ON TABLE ${quoteIdentifier(tableName)} IS '$escaped'")
        }
        return statements
    }

    // endregion

    // region SQL 安全校验

    /**
     * 校验 SQL 片段的安全性
     */
    override fun validateSqlFragment(sql: String, label: String) {
        if (sql.contains(' ')) throw IllegalArgumentException("$label 包含空字节")
        val bare = sql.replace(QUOTED_STRING_REGEX, "")
        if (bare.contains(';')) throw IllegalArgumentException("$label 包含非法字符 ';'")
        if (bare.contains("--") || bare.contains("/*")) throw IllegalArgumentException("$label 包含非法注释")
        val upper = bare.uppercase()
        for (regex in DANGEROUS_KEYWORD_REGEXES) {
            if (regex.containsMatchIn(upper)) {
                val kw = regex.pattern.substringAfter("\\b").substringBefore("\\b")
                throw IllegalArgumentException("$label 包含禁止关键词: $kw")
            }
        }
    }

    /**
     * 校验 ORDER BY 子句格式
     */
    override fun validateOrderBy(sql: String) {
        if (!ORDER_BY_REGEX.matches(sql)) {
            throw IllegalArgumentException("无效的 ORDER BY 格式: $sql")
        }
    }

    // endregion

    // region 函数/存储过程管理

    /**
     * 列出 schema 下所有函数、存储过程和触发器
     */
    override suspend fun listRoutines(conn: Connection, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val routines = mutableListOf<Map<String, String>>()
        val targetSchema = if (schema.isNotBlank()) schema else "public"

        // 1. 查询函数和存储过程
        val query = """
            SELECT p.proname AS name,
                   CASE p.prokind
                       WHEN 'f' THEN 'FUNCTION'
                       WHEN 'p' THEN 'PROCEDURE'
                       WHEN 'w' THEN 'AGGREGATE'
                       ELSE 'FUNCTION'
                   END AS routine_type,
                   pg_catalog.format_type(p.prorettype, NULL) AS return_type,
                   l.lanname AS language,
                   p.prosecdef AS security_definer,
                   CASE p.provolatile
                       WHEN 'i' THEN 'IMMUTABLE'
                       WHEN 's' THEN 'STABLE'
                       ELSE 'VOLATILE'
                   END AS volatility,
                   p.pronargs AS arg_count,
                   p.proargnames AS arg_names,
                   obj_description(p.oid, 'pg_proc') AS description,
                   p.oid AS proc_oid
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            JOIN pg_catalog.pg_language l ON l.oid = p.prolang
            WHERE n.nspname = ?
              AND p.prokind IN ('f', 'p', 'w')
            ORDER BY n.nspname, p.proname
        """.trimIndent()

        conn.prepareStatement(query).use { stmt ->
            stmt.setString(1, targetSchema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    @Suppress("UNCHECKED_CAST")
                    val argNames = (rs.getArray("arg_names")?.array as? Array<String>) ?: emptyArray()

                    routines.add(mapOf(
                        "name" to rs.getString("name"),
                        "routine_type" to rs.getString("routine_type"),
                        "return_type" to (rs.getString("return_type") ?: ""),
                        "language" to rs.getString("language"),
                        "security_definer" to if (rs.getBoolean("security_definer")) "SECURITY DEFINER" else "SECURITY INVOKER",
                        "volatility" to rs.getString("volatility"),
                        "arg_count" to rs.getInt("arg_count").toString(),
                        "arg_names" to argNames.joinToString(", "),
                        "schema" to targetSchema,
                        "description" to (rs.getString("description") ?: ""),
                        "trigger_table" to ""
                    ))
                }
            }
        }

        // 2. 查询触发器
        val triggerQuery = """
            SELECT t.tgname AS name,
                   'TRIGGER' AS routine_type,
                   CASE
                       WHEN t.tgtype & 1 = 1 THEN 'ROW'
                       ELSE 'STATEMENT'
                   END || ' ' ||(
                       CASE t.tgtype & 3
                           WHEN 1 THEN 'BEFORE'
                           WHEN 2 THEN 'AFTER'
                           WHEN 3 THEN 'INSTEAD OF'
                       END
                   ) || ' ' || (
                       SELECT string_agg(CASE WHEN e.event_manipulation = 'INSERT' THEN 'INSERT'
                                            WHEN e.event_manipulation = 'UPDATE' THEN 'UPDATE'
                                            WHEN e.event_manipulation = 'DELETE' THEN 'DELETE'
                                            ELSE e.event_manipulation END, ', ')
                       FROM information_schema.triggers e
                       WHERE e.trigger_name = t.tgname
                         AND e.event_object_schema = n.nspname
                   ) AS return_type,
                   'plpgsql' AS language,
                   'SECURITY INVOKER' AS security_definer,
                   'VOLATILE' AS volatility,
                   '0' AS arg_count,
                   '' AS arg_names,
                   obj_description(t.oid, 'pg_trigger') AS description,
                   c.relname AS trigger_table,
                   n.nspname AS trigger_schema
            FROM pg_trigger t
            JOIN pg_class c ON t.tgrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname = ?
              AND NOT t.tgisinternal
            ORDER BY n.nspname, c.relname, t.tgname
        """.trimIndent()

        conn.prepareStatement(triggerQuery).use { stmt ->
            stmt.setString(1, targetSchema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    routines.add(mapOf(
                        "name" to rs.getString("name"),
                        "routine_type" to rs.getString("routine_type"),
                        "return_type" to (rs.getString("return_type") ?: ""),
                        "language" to rs.getString("language"),
                        "security_definer" to rs.getString("security_definer"),
                        "volatility" to rs.getString("volatility"),
                        "arg_count" to rs.getString("arg_count"),
                        "arg_names" to rs.getString("arg_names"),
                        "schema" to targetSchema,
                        "description" to (rs.getString("description") ?: ""),
                        "trigger_table" to rs.getString("trigger_table")
                    ))
                }
            }
        }

        routines
    }

    /**
     * 根据 OID 获取类型名称
     */
    private fun getTypeNameByOid(conn: Connection, oid: Int): String {
        if (oid == 0) return "ANY"
        conn.prepareStatement("SELECT format_type(?, NULL)").use { stmt ->
            stmt.setInt(1, oid)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) rs.getString(1) ?: "unknown" else "unknown"
            }
        }
    }

    /**
     * 获取函数/存储过程/触发器的 DDL 定义（后端自动解析类型）
     */
    override suspend fun getRoutineDDL(conn: Connection, routineName: String, schema: String): String = withContext(Dispatchers.IO) {
        val targetSchema = if (schema.isNotBlank()) schema else "public"

        // 1. 先尝试获取函数/存储过程的 DDL
        conn.prepareStatement("""
            SELECT pg_get_functiondef(p.oid) AS ddl,
                   CASE p.prokind
                       WHEN 'f' THEN 'FUNCTION'
                       WHEN 'p' THEN 'PROCEDURE'
                       WHEN 'w' THEN 'AGGREGATE'
                       ELSE 'FUNCTION'
                   END AS routine_type
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE p.proname = ? AND n.nspname = ? AND p.prokind IN ('f', 'p', 'w')
        """.trimIndent()).use { stmt ->
            stmt.setString(1, routineName)
            stmt.setString(2, targetSchema)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return@withContext rs.getString("ddl") ?: throw IllegalArgumentException("无法获取 '$routineName' 的 DDL")
                }
            }
        }

        // 2. 如果没找到，尝试获取触发器的 DDL
        conn.prepareStatement("""
            SELECT pg_get_triggerdef(t.oid) AS def,
                   c.relname AS table_name,
                   CASE
                       WHEN t.tgtype & 1 = 1 THEN 'ROW'
                       ELSE 'STATEMENT'
                   END AS level,
                   CASE t.tgtype & 3
                       WHEN 1 THEN 'BEFORE'
                       WHEN 2 THEN 'AFTER'
                       WHEN 3 THEN 'INSTEAD OF'
                   END AS action_timing,
                   CASE
                       WHEN t.tgtype & 4 = 4 THEN 'INSERT'
                       ELSE ''
                   END ||
                   CASE
                       WHEN t.tgtype & 8 = 8 THEN ', UPDATE'
                       ELSE ''
                   END ||
                   CASE
                       WHEN t.tgtype & 16 = 16 THEN ', DELETE'
                       ELSE ''
                   END AS event_manipulation,
                   p.proname AS func_name,
                   n.nspname AS func_schema,
                   pg_get_function_arguments(p.oid) AS args,
                   t.tgenabled AS enabled
            FROM pg_trigger t
            JOIN pg_class c ON t.tgrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            JOIN pg_proc p ON t.tgfoid = p.oid
            WHERE t.tgname = ? AND n.nspname = ? AND NOT t.tgisinternal
        """.trimIndent()).use { stmt ->
            stmt.setString(1, routineName)
            stmt.setString(2, targetSchema)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val def = rs.getString("def")
                    val tableName = rs.getString("table_name")
                    val level = rs.getString("level")
                    val actionTiming = rs.getString("action_timing")
                    val events = rs.getString("event_manipulation").trimStart(',', ' ')
                    val funcName = rs.getString("func_name")
                    val funcSchema = rs.getString("func_schema")
                    val args = rs.getString("args") ?: ""
                    val enabled = rs.getString("enabled")

                    // 构建触发器 DDL
                    val funcArgs = if (args.isNotEmpty()) "($args)" else "()"
                    return@withContext buildString {
                        appendLine("CREATE OR REPLACE TRIGGER ${quoteIdentifier(routineName)}")
                        appendLine("  $level $actionTiming $events")
                        appendLine("  ON ${quoteIdentifier(tableName)}")
                        appendLine("  FOR EACH ROW")
                        appendLine("  EXECUTE FUNCTION ${quoteIdentifier(funcSchema)}.${quoteIdentifier(funcName)}$funcArgs;")
                    }.trimEnd()
                }
            }
        }

        throw IllegalArgumentException("未找到函数/存储过程/触发器 '$routineName'，schema: '$targetSchema'")
    }

    /**
     * 执行 DDL 创建函数或存储过程
     */
    override suspend fun createRoutine(conn: Connection, ddl: String): Boolean = withContext(Dispatchers.IO) {
        conn.createStatement().use { stmt ->
            stmt.execute(ddl)
        }
        true
    }

    /**
     * 删除函数、存储过程或触发器
     */
    override suspend fun dropRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        ifExists: Boolean,
        cascade: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val targetSchema = if (schema.isNotBlank()) schema else "public"
        val safeRoutineName = sanitizeIdentifier(routineName, "routine name")
        val safeSchema = sanitizeIdentifier(targetSchema, "schema name")
        val upperType = routineType.uppercase()

        when (upperType) {
            "TRIGGER" -> {
                // 删除触发器需要先获取关联的表名
                conn.prepareStatement("""
                    SELECT c.relname AS table_name
                    FROM pg_trigger t
                    JOIN pg_class c ON t.tgrelid = c.oid
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE t.tgname = ? AND n.nspname = ? AND NOT t.tgisinternal
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, routineName)
                    stmt.setString(2, targetSchema)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            val tableName = rs.getString("table_name")
                            val sql = buildString {
                                append("DROP TRIGGER ")
                                if (ifExists) append("IF EXISTS ")
                                append("${quoteIdentifier(safeRoutineName)} ON ${quoteIdentifier(tableName)}")
                                if (cascade) append(" CASCADE")
                            }
                            conn.createStatement().use { execStmt ->
                                execStmt.execute(sql)
                            }
                        } else if (ifExists) {
                            // 触发器不存在但使用了 IF EXISTS，不报错
                            return@withContext true
                        } else {
                            throw IllegalArgumentException("未找到触发器 '$routineName'，schema: '$targetSchema'")
                        }
                    }
                }
            }
            "PROCEDURE" -> {
                val sql = buildString {
                    append("DROP PROCEDURE ")
                    if (ifExists) append("IF EXISTS ")
                    append("${quoteIdentifier(safeSchema)}.${quoteIdentifier(safeRoutineName)}")
                    if (cascade) append(" CASCADE")
                }
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            }
            else -> {
                // FUNCTION 或其他类型，尝试删除函数
                val sql = buildString {
                    append("DROP FUNCTION ")
                    if (ifExists) append("IF EXISTS ")
                    append("${quoteIdentifier(safeSchema)}.${quoteIdentifier(safeRoutineName)}")
                    if (cascade) append(" CASCADE")
                }

                try {
                    conn.createStatement().use { stmt ->
                        stmt.execute(sql)
                    }
                } catch (e: Exception) {
                    // 如果遇到触发器依赖问题，自动尝试 CASCADE
                    if (!cascade && e.message?.contains("other objects depend on it") == true) {
                        val cascadeSql = "DROP FUNCTION ${if (ifExists) "IF EXISTS " else ""}${quoteIdentifier(safeSchema)}.${quoteIdentifier(safeRoutineName)} CASCADE"
                        conn.createStatement().use { stmt ->
                            stmt.execute(cascadeSql)
                        }
                    } else {
                        throw e
                    }
                }
            }
        }
        true
    }

    /**
     * 调用函数或存储过程
     */
    override suspend fun callRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        args: List<String?>
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val targetSchema = if (schema.isNotBlank()) schema else "public"
        val safeRoutineName = sanitizeIdentifier(routineName, "routine name")
        val safeSchema = sanitizeIdentifier(targetSchema, "schema name")
        val isFunction = routineType.uppercase() != "PROCEDURE"

        val result = mutableMapOf<String, Any?>()
        val actualArgs = args.filterNotNull()

        // 对于函数，直接构造 SQL 并用 Statement 执行（避免 PreparedStatement 参数绑定问题）
        if (isFunction) {
            // 转义参数中的单引号
            val escapedArgs = actualArgs.map { it.replace("'", "''") }
            val argsStr = if (escapedArgs.isNotEmpty()) {
                escapedArgs.joinToString(", ") { "'$it'" }
            } else {
                ""
            }

            val sql = if (argsStr.isNotEmpty()) {
                "SELECT * FROM ${quoteIdentifier(safeSchema)}.${quoteIdentifier(safeRoutineName)}($argsStr)"
            } else {
                "SELECT * FROM ${quoteIdentifier(safeSchema)}.${quoteIdentifier(safeRoutineName)}()"
            }

            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    val metaData = rs.metaData
                    val columnCount = metaData.columnCount
                    if (rs.next()) {
                        if (columnCount == 1) {
                            result["result"] = rs.getObject(1)
                        } else {
                            val row = mutableMapOf<String, Any?>()
                            for (col in 1..columnCount) {
                                row[metaData.getColumnLabel(col)] = rs.getObject(col)
                            }
                            result["result"] = row
                        }
                        result["row_count"] = 1
                    }
                }
            }
        } else {
            // 存储过程调用
            val escapedArgs = actualArgs.map { it.replace("'", "''") }
            val argsStr = if (escapedArgs.isNotEmpty()) {
                escapedArgs.joinToString(", ") { "'$it'" }
            } else {
                ""
            }

            val sql = if (argsStr.isNotEmpty()) {
                "{ CALL ${quoteIdentifier(safeSchema)}.${quoteIdentifier(safeRoutineName)}($argsStr) }"
            } else {
                "{ CALL ${quoteIdentifier(safeSchema)}.${quoteIdentifier(safeRoutineName)}() }"
            }

            conn.prepareCall(sql).use { callableStmt ->
                val hasResultSet = callableStmt.execute()
                if (hasResultSet) {
                    val rs = callableStmt.resultSet
                    val rows = mutableListOf<Map<String, Any?>>()
                    val metaData = rs.metaData
                    val columnCount = metaData.columnCount
                    while (rs.next()) {
                        val row = mutableMapOf<String, Any?>()
                        for (col in 1..columnCount) {
                            row[metaData.getColumnLabel(col)] = rs.getObject(col)
                        }
                        rows.add(row)
                    }
                    result["result_set"] = rows
                    result["row_count"] = rows.size
                }
                // 获取 OUT 参数
                val updateCount = callableStmt.updateCount
                if (updateCount >= 0) {
                    result["update_count"] = updateCount
                }
            }
        }

        result
    }

    /**
     * 获取函数/存储过程/触发器的详细信息（后端自动解析 routineType）
     */
    override suspend fun getRoutineInfo(conn: Connection, routineName: String, schema: String): Map<String, String> = withContext(Dispatchers.IO) {
        val targetSchema = if (schema.isNotBlank()) schema else "public"

        // 1. 先尝试获取函数/存储过程的信息
        conn.prepareStatement("""
            SELECT p.oid, p.proname, n.nspname, l.lanname,
                   pg_get_function_result(p.oid) AS return_type,
                   pg_get_function_identity_arguments(p.oid) AS args,
                   p.provolatile, p.prosecdef, p.proargmodes, p.proargnames,
                   p.pronargs,
                   obj_description(p.oid, 'pg_proc') AS description,
                   CASE p.prokind
                       WHEN 'f' THEN 'FUNCTION'
                       WHEN 'p' THEN 'PROCEDURE'
                       WHEN 'w' THEN 'AGGREGATE'
                       ELSE 'FUNCTION'
                   END AS routine_type
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            JOIN pg_language l ON l.oid = p.prolang
            WHERE p.proname = ? AND n.nspname = ? AND p.prokind IN ('f', 'p', 'w')
        """.trimIndent()).use { stmt ->
            stmt.setString(1, routineName)
            stmt.setString(2, targetSchema)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    @Suppress("UNCHECKED_CAST")
                    val argModes = (rs.getArray("proargmodes")?.array as? Array<String>) ?: emptyArray()
                    @Suppress("UNCHECKED_CAST")
                    val argNames = (rs.getArray("proargnames")?.array as? Array<String>) ?: emptyArray()

                    // 构建参数详情
                    val argsString = buildString {
                        for (i in argNames.indices) {
                            if (i > 0) append(", ")
                            val mode = argModes.getOrNull(i)?.firstOrNull()
                            if (mode == 'o') append("OUT ")
                            if (mode == 'i') append("IN ")
                            if (argNames[i].isNotEmpty()) {
                                append(argNames[i])
                                append(" ")
                            }
                        }
                    }

                    val returnType = rs.getString("return_type")
                    val volatilityStr = rs.getString("provolatile")
                    val argsCol = rs.getString("args")

                    return@withContext mapOf(
                        "name" to rs.getString("proname"),
                        "routine_type" to rs.getString("routine_type"),
                        "schema" to rs.getString("nspname"),
                        "language" to rs.getString("lanname"),
                        "return_type" to (returnType ?: ""),
                        "volatility" to when (volatilityStr) {
                            "i" -> "IMMUTABLE"
                            "s" -> "STABLE"
                            else -> "VOLATILE"
                        },
                        "security_definer" to if (rs.getBoolean("prosecdef")) "SECURITY DEFINER" else "SECURITY INVOKER",
                        "arg_count" to rs.getInt("pronargs").toString(),
                        "arg_names" to (argsString.ifEmpty { argsCol ?: "" }),
                        "description" to (rs.getString("description") ?: ""),
                        "trigger_table" to ""
                    )
                }
            }
        }

        // 2. 如果没找到，尝试获取触发器的信息
        conn.prepareStatement("""
            SELECT t.tgname AS name,
                   'TRIGGER' AS routine_type,
                   n.nspname AS schema,
                   'plpgsql' AS language,
                   CASE
                       WHEN t.tgtype & 1 = 1 THEN 'ROW'
                       ELSE 'STATEMENT'
                   END || ' ' ||(
                       CASE t.tgtype & 3
                           WHEN 1 THEN 'BEFORE'
                           WHEN 2 THEN 'AFTER'
                           WHEN 3 THEN 'INSTEAD OF'
                       END
                   ) || ' ' || (
                       SELECT string_agg(
                           CASE
                               WHEN e.event_manipulation = 'INSERT' THEN 'INSERT'
                               WHEN e.event_manipulation = 'UPDATE' THEN 'UPDATE'
                               WHEN e.event_manipulation = 'DELETE' THEN 'DELETE'
                               ELSE e.event_manipulation
                           END, ', ')
                       FROM information_schema.triggers e
                       WHERE e.trigger_name = t.tgname
                         AND e.event_object_schema = n.nspname
                   ) AS return_type,
                   'VOLATILE' AS volatility,
                   'SECURITY INVOKER' AS security_definer,
                   '0' AS arg_count,
                   '' AS arg_names,
                   obj_description(t.oid, 'pg_trigger') AS description,
                   c.relname AS trigger_table
            FROM pg_trigger t
            JOIN pg_class c ON t.tgrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE t.tgname = ? AND n.nspname = ? AND NOT t.tgisinternal
        """.trimIndent()).use { stmt ->
            stmt.setString(1, routineName)
            stmt.setString(2, targetSchema)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return@withContext mapOf(
                        "name" to rs.getString("name"),
                        "routine_type" to rs.getString("routine_type"),
                        "schema" to rs.getString("schema"),
                        "language" to rs.getString("language"),
                        "return_type" to (rs.getString("return_type") ?: ""),
                        "volatility" to rs.getString("volatility"),
                        "security_definer" to rs.getString("security_definer"),
                        "arg_count" to rs.getString("arg_count"),
                        "arg_names" to rs.getString("arg_names"),
                        "description" to (rs.getString("description") ?: ""),
                        "trigger_table" to rs.getString("trigger_table")
                    )
                }
            }
        }

        throw IllegalArgumentException("未找到函数/存储过程/触发器 '$routineName'，schema: '$targetSchema'")
    }

    /**
     * 调试函数（EXPLAIN、执行计划、依赖分析等）
     */
    override suspend fun debugRoutine(conn: Connection, routineName: String, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val targetSchema = if (schema.isNotBlank()) schema else "public"

        // 获取函数 OID
        val oidQuery = """
            SELECT p.oid FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE p.proname = ? AND n.nspname = ? AND p.prokind = 'f'
        """.trimIndent()

        conn.prepareStatement(oidQuery).use { oidStmt ->
            oidStmt.setString(1, routineName)
            oidStmt.setString(2, targetSchema)
            oidStmt.executeQuery().use { oidRs ->
                if (!oidRs.next()) {
                    throw IllegalArgumentException("未找到函数 '$routineName'，schema: '$targetSchema'")
                }
                val funcOid = oidRs.getInt(1)

                val results = mutableListOf<Map<String, String>>()

                // 1. EXPLAIN 输出
                conn.prepareStatement("EXPLAIN (FORMAT JSON) SELECT $routineName()").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            results.add(mapOf(
                                "type" to "EXPLAIN",
                                "output" to rs.getString(1)
                            ))
                        }
                    }
                }

                // 2. 函数信息（使用 getRoutineInfo，自动解析 routineType）
                val info = getRoutineInfo(conn, routineName, targetSchema)
                results.add(mapOf(
                    "type" to "INFO",
                    "output" to buildString {
                        appendLine("函数名: ${info["name"]}")
                        appendLine("Schema: ${info["schema"]}")
                        appendLine("语言: ${info["language"]}")
                        appendLine("返回类型: ${info["return_type"]}")
                        appendLine("稳定性: ${info["volatility"]}")
                        appendLine("安全性: ${info["security_definer"]}")
                        appendLine("参数: ${info["arg_names"]}")
                    }
                ))

                // 3. 函数依赖
                conn.prepareStatement("""
                    SELECT DISTINCT c.relname AS dependent_object, c.relkind,
                           CASE c.relkind WHEN 'r' THEN 'TABLE' WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' WHEN 'S' THEN 'SEQUENCE' ELSE 'UNKNOWN' END AS type
                    FROM pg_depend d
                    JOIN pg_proc p ON d.refobjid = p.oid
                    JOIN pg_class c ON d.objid = c.oid
                    WHERE d.refobjid = ? AND d.deptype = 'n'
                    ORDER BY c.relkind, c.relname
                """.trimIndent()).use { depStmt ->
                    depStmt.setInt(1, funcOid)
                    depStmt.executeQuery().use { rs ->
                        val deps = mutableListOf<String>()
                        while (rs.next()) {
                            deps.add("${rs.getString("type")}: ${rs.getString("dependent_object")}")
                        }
                        if (deps.isNotEmpty()) {
                            results.add(mapOf(
                                "type" to "DEPENDENCIES",
                                "output" to deps.joinToString("\n")
                            ))
                        }
                    }
                }

                results
            }
        }
    }

    /**
     * 验证 DDL 语法（不创建，用于编辑时的语法检查）
     * 通过 DO 块执行 DDL 来验证语法是否正确
     */
    override suspend fun validateRoutineDDL(conn: Connection, ddl: String): Boolean = withContext(Dispatchers.IO) {
        // 解析 DDL 中的函数名和参数（简单提取）
        val funcNameMatch = Regex("""(?i)(?:FUNCTION|PROCEDURE)\s+(\w+)""").find(ddl)
        val funcName = funcNameMatch?.groupValues?.get(1) ?: "_temp_validation_func"

        // 构建临时验证函数
        val tempFunc = if (Regex("(?i)PROCEDURE").containsMatchIn(ddl)) {
            "CREATE OR REPLACE PROCEDURE ${funcName}_temp() AS \$\$ BEGIN NULL; \$\$ LANGUAGE plpgsql"
        } else {
            "CREATE OR REPLACE FUNCTION ${funcName}_temp() RETURNS void AS \$\$ BEGIN NULL; \$\$ LANGUAGE plpgsql"
        }

        try {
            // 先创建空函数
            conn.createStatement().use { stmt ->
                stmt.execute(tempFunc)
            }
            // 再尝试创建原 DDL
            conn.createStatement().use { stmt ->
                stmt.execute(ddl)
            }
            true
        } catch (e: Exception) {
            throw IllegalArgumentException("DDL 语法验证失败: ${e.message}")
        } finally {
            // 清理临时函数
            try {
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP FUNCTION IF EXISTS ${funcName}_temp()")
                    stmt.execute("DROP PROCEDURE IF EXISTS ${funcName}_temp()")
                }
            } catch (e: Exception) {
                // 忽略清理错误
            }
        }
    }

    // endregion

    // region Views (视图)

    override suspend fun listViews(conn: Connection, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val targetSchema = if (schema.isNotBlank()) schema else "public"
        val views = mutableListOf<Map<String, String>>()
        conn.prepareStatement(
            "SELECT table_name FROM information_schema.views " +
            "WHERE table_schema = ? ORDER BY table_name"
        ).use { stmt ->
            stmt.setString(1, targetSchema)
            stmt.executeQuery().use { rs ->
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
            append(" CASCADE")
        }
        conn.createStatement().use { stmt -> stmt.execute(sql) }
        true
    }

    override suspend fun getViewDDL(conn: Connection, viewName: String, schema: String): String = withContext(Dispatchers.IO) {
        val targetSchema = if (schema.isNotBlank()) schema else "public"
        conn.prepareStatement(
            "SELECT view_definition FROM information_schema.views " +
            "WHERE table_name = ? AND table_schema = ?"
        ).use { stmt ->
            stmt.setString(1, viewName)
            stmt.setString(2, targetSchema)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val def = rs.getString(1) ?: ""
                    return@withContext "CREATE VIEW ${quoteIdentifier(viewName)} AS $def"
                }
            }
        }
        throw IllegalArgumentException("未找到视图 '$viewName'")
    }

    // endregion

    // region Indexes (索引)

    override suspend fun listIndexes(conn: Connection, tableName: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val indexes = mutableListOf<Map<String, String>>()
        conn.prepareStatement(
            "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = ? ORDER BY indexname"
        ).use { stmt ->
            stmt.setString(1, safeTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    indexes.add(mapOf(
                        "name" to rs.getString("indexname"),
                        "definition" to rs.getString("indexdef")
                    ))
                }
            }
        }
        indexes
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
        conn.createStatement().use { stmt ->
            stmt.execute("DROP INDEX IF EXISTS ${quoteIdentifier(safeIndex)}")
        }
        true
    }

    // endregion

    // region Foreign Keys (外键)

    override suspend fun listForeignKeys(conn: Connection, tableName: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val fks = mutableListOf<Map<String, String>>()
        conn.prepareStatement(
            "SELECT tc.constraint_name, kcu.column_name, " +
            "ccu.table_schema AS ref_schema, ccu.table_name AS ref_table, ccu.column_name AS ref_column, " +
            "rc.update_rule, rc.delete_rule " +
            "FROM information_schema.table_constraints tc " +
            "JOIN information_schema.key_column_usage kcu " +
            "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
            "JOIN information_schema.constraint_column_usage ccu " +
            "  ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema " +
            "JOIN information_schema.referential_constraints rc " +
            "  ON rc.constraint_name = tc.constraint_name " +
            "WHERE tc.table_name = ? AND tc.constraint_type = 'FOREIGN KEY' " +
            "ORDER BY tc.constraint_name, kcu.ordinal_position"
        ).use { stmt ->
            stmt.setString(1, safeTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    fks.add(mapOf(
                        "name" to rs.getString("constraint_name"),
                        "column" to rs.getString("column_name"),
                        "ref_table" to rs.getString("ref_table"),
                        "ref_column" to rs.getString("ref_column"),
                        "on_update" to (rs.getString("update_rule") ?: "NO ACTION"),
                        "on_delete" to (rs.getString("delete_rule") ?: "NO ACTION")
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
            stmt.execute("ALTER TABLE ${quoteIdentifier(safeTable)} DROP CONSTRAINT ${quoteIdentifier(safeFk)}")
        }
        true
    }

    // endregion

    // region Triggers (触发器) — 复用了 listRoutines 中的触发器查询

    override suspend fun listTriggers(conn: Connection, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val targetSchema = if (schema.isNotBlank()) schema else "public"
        val triggers = mutableListOf<Map<String, String>>()
        conn.prepareStatement(
            "SELECT t.tgname AS name, c.relname AS table_name, " +
            "obj_description(t.oid, 'pg_trigger') AS description " +
            "FROM pg_trigger t " +
            "JOIN pg_class c ON t.tgrelid = c.oid " +
            "JOIN pg_namespace n ON c.relnamespace = n.oid " +
            "WHERE n.nspname = ? AND NOT t.tgisinternal " +
            "ORDER BY c.relname, t.tgname"
        ).use { stmt ->
            stmt.setString(1, targetSchema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    triggers.add(mapOf(
                        "name" to rs.getString("name"),
                        "table" to rs.getString("table_name"),
                        "description" to (rs.getString("description") ?: ""),
                        "schema" to targetSchema
                    ))
                }
            }
        }
        triggers
    }

    override suspend fun getTriggerDDL(conn: Connection, triggerName: String, schema: String): String = withContext(Dispatchers.IO) {
        val targetSchema = if (schema.isNotBlank()) schema else "public"
        conn.prepareStatement(
            "SELECT pg_get_triggerdef(t.oid) AS def, c.relname AS table_name " +
            "FROM pg_trigger t JOIN pg_class c ON t.tgrelid = c.oid " +
            "JOIN pg_namespace n ON c.relnamespace = n.oid " +
            "WHERE t.tgname = ? AND n.nspname = ? AND NOT t.tgisinternal"
        ).use { stmt ->
            stmt.setString(1, triggerName)
            stmt.setString(2, targetSchema)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val def = rs.getString("def") ?: ""
                    return@withContext "CREATE TRIGGER ${quoteIdentifier(triggerName)} $def"
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
            stmt.execute("ALTER TABLE ${quoteIdentifier(safeOld)} RENAME TO ${quoteIdentifier(safeNew)}")
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
            stmt.executeQuery("SHOW server_version").use { rs ->
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
