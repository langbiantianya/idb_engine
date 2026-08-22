package com.kxxnzstdsw.dialect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * SQLite 方言 —— 嵌入式文件型关系数据库。
 *
 * ## 连接约定
 *
 * `host` / `port` / `user` / `password` / `use_ssl` 全部忽略，仅由 `database` 字段承载路径：
 *
 * | database 取值 | SQLite JDBC URL | 用途 |
 * | --- | --- | --- |
 * | `""` 或 `":memory:"` | `jdbc:sqlite::memory:` | 进程内内存数据库（每连接私有） |
 * | `"/path/to/data.db"` | `jdbc:sqlite:/path/to/data.db` | 本地 SQLite 文件 |
 *
 * ## 方言特性
 *
 * - 默认 schema 名是 `main`（SQLite 命名约定），`temp` 是临时 schema
 * - 自增主键用 `INTEGER PRIMARY KEY AUTOINCREMENT`（列定义 inline）
 * - DDL 重建通过解析 `sqlite_master` + 应用层 DDL 重构
 * - 标识符使用双引号或反引号包裹
 * - 无用户/权限/触发器/存储过程概念（业务层抛 `UnsupportedOperationException`）
 * - 不支持 `ALTER TABLE DROP COLUMN`（3.35+ 才支持，默认保守禁用）
 * - 无 schema 概念（除 `main` / `temp`）
 * - 多 database 走 `ATTACH '<path>' AS <alias>`
 */
class SQLiteDialect : DatabaseDialect {
    private val logger = LoggerFactory.getLogger(SQLiteDialect::class.java)

    override val driverName = "Sqlite"
    override val jdbcDriverClassName = "org.sqlite.JDBC"

    // v2.8: 前端连接表单元数据
    override val displayName = "SQLite (Embedded)"
    override val connectionType = ConnectionType.FILE_BASED
    override val requiresHost = false
    override val requiresPort = false
    override val supportsUser = false
    override val supportsPassword = false
    override val supportsSchema = false             // SQLite 只有 main/temp，无业务 schema
    override val supportsCrossDatabase = false     // 业务层不暴露 ATTACH（嵌入场景罕见）
    override val jdbcUrlExample = "jdbc:sqlite:/path/to/data.db  (or :memory: for in-memory)"
    override val capabilities = setOf(
        DialectCapability.VIEWS,
        DialectCapability.INDEXES,
        DialectCapability.FOREIGN_KEYS,
        DialectCapability.EXPORT,
        DialectCapability.EMBEDDED_MODE,
    )

    override fun buildJdbcUrl(host: String, port: Int, database: String): String {
        // host/port 完全忽略；database 承载 SQLite URL 主体
        return when {
            database.isBlank() -> "jdbc:sqlite::memory:"
            database == ":memory:" -> "jdbc:sqlite::memory:"
            else -> "jdbc:sqlite:$database"
        }
    }

    override fun configureConnectionForStreaming(conn: Connection): Boolean {
        // SQLite 流式读取无需关闭 autoCommit（与 PG 不同）
        return conn.autoCommit
    }

    override fun setSearchPath(conn: Connection, schema: String) {
        if (schema.isBlank()) return
        // SQLite 通过 ATTACH 创建 alias；非空 schema 走 ATTACH 'main' AS <schema> 兼容层。
        // 默认 main/temp 已是内置，无需操作；非默认 schema 业务层通常不传。
        if (schema.equals("main", ignoreCase = true) || schema.equals("temp", ignoreCase = true)) {
            return
        }
        // 非默认 schema：用 ATTACH 当前数据库文件到给定 alias
        try {
            val dbPath = conn.metaData.url.removePrefix("jdbc:sqlite:")
            if (dbPath.isNotBlank() && dbPath != ":memory:") {
                conn.createStatement().use { stmt ->
                    stmt.execute("ATTACH DATABASE '${dbPath.replace("'", "''")}' AS ${quoteIdentifier(schema)}")
                }
            }
        } catch (e: Exception) {
            logger.debug("setSearchPath ATTACH failed: ${e.message}")
        }
    }

    override fun quoteIdentifier(identifier: String): String {
        // SQLite 支持双引号（v2.9 #16 转 DialectUtil.quoteWith）
        return DialectUtil.quoteWith(identifier, '"')
    }

    // region Schema / Database 导航

    override suspend fun listDatabases(conn: Connection): List<String> = withContext(Dispatchers.IO) {
        // SQLite 单文件 → 返回该文件路径（也包含 ATTACH 的别名）。简单起见返回单元素列表
        val url = conn.metaData.url ?: ""
        val path = url.removePrefix("jdbc:sqlite:")
        if (path.isBlank() || path == ":memory:") {
            return@withContext listOf(":memory:")
        }
        return@withContext listOf(path)
    }

    override suspend fun listSchemas(conn: Connection, database: String): List<String> = withContext(Dispatchers.IO) {
        // SQLite 内置 schema 始终只有 main + temp（即使 ATTACH 也是 alias 不是 schema）
        // PRAGMA database_list 返回 3 列：seq (int), name (str), file (str) — JDBC 索引从 1 起
        val schemas = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA database_list").use { rs ->
                while (rs.next()) {
                    val name = rs.getString(2) ?: continue  // 第 1 列是 seq 序号
                    if (name.isNotBlank()) schemas.add(name)
                }
            }
        }
        if (schemas.isEmpty()) schemas.add("main")
        schemas
    }

    override suspend fun createSchema(conn: Connection, name: String, options: Map<String, String>, ifNotExists: Boolean): Boolean = withContext(Dispatchers.IO) {
        // SQLite 没有 CREATE SCHEMA；要创建新"database"用 ATTACH
        val safeName = sanitizeIdentifier(name, "database name")
        val dbFile = options["path"] ?: "$safeName.db"
        val ifClause = if (ifNotExists) "" else ""  // ATTACH 不支持 IF NOT EXISTS
        conn.createStatement().use { stmt ->
            stmt.execute("ATTACH DATABASE '$dbFile' AS ${quoteIdentifier(safeName)}")
        }
        true
    }

    override suspend fun deleteSchema(conn: Connection, name: String, ifExists: Boolean): Boolean = withContext(Dispatchers.IO) {
        // SQLite 删除"schema"等价于 DETACH + 删除文件
        val safeName = sanitizeIdentifier(name, "database name")
        if (safeName.equals("main", ignoreCase = true) || safeName.equals("temp", ignoreCase = true)) {
            throw IllegalArgumentException("不能删除 SQLite 内置 schema '$safeName'")
        }
        conn.createStatement().use { stmt ->
            stmt.execute("DETACH DATABASE ${quoteIdentifier(safeName)}")
        }
        true
    }

    // endregion

    // region Table / Column

    override suspend fun listTables(conn: Connection, database: String, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val tables = mutableListOf<Map<String, String>>()
        // sqlite_master.name 是表名（也包含视图等其他对象）
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT name, type FROM sqlite_master " +
                "WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' " +
                "ORDER BY type, name"
            ).use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1) ?: continue
                    val type = rs.getString(2) ?: "table"
                    tables.add(mapOf(
                        "name" to name,
                        "type" to if (type.equals("view", ignoreCase = true)) "VIEW" else "TABLE"
                    ))
                }
            }
        }
        tables
    }

    /**
     * SQLite listColumns：通过 PRAGMA table_info(<table>) 获取列信息 + PRAGMA 主键判定。
     * 走 PRAGMA 而非 JDBC metaData 是因为 PRAGMA 是 SQLite 原生稳定接口。
     */
    override suspend fun listColumns(
        conn: Connection,
        database: String,
        schema: String,
        tableName: String
    ): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val columns = mutableListOf<Map<String, Any?>>()

        // 1. PRAGMA 主键列表（pk > 0 的列）
        val primaryKeys = mutableSetOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(${quoteIdentifier(safeTable)})").use { rs ->
                while (rs.next()) {
                    val name = rs.getString("name") ?: continue
                    val pk = rs.getInt("pk")
                    val type = rs.getString("type") ?: ""
                    val notnull = rs.getInt("notnull") == 1
                    val dflt = rs.getString("dflt_value")
                    if (pk > 0) primaryKeys.add(name)
                    columns.add(mapOf(
                        "name" to name,
                        "type" to type,
                        "size" to extractSize(type),
                        "nullable" to !notnull,
                        "isPrimaryKey" to (pk > 0),
                        "defaultValue" to dflt
                    ))
                }
            }
        }
        columns
    }

    private fun extractSize(dataType: String): Int {
        val match = Regex("""\(\s*(\d+)""").find(dataType) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    // 用户/权限 全部不支持
    override suspend fun createUser(conn: Connection, user: String, password: String, host: String): Boolean {
        throw UnsupportedOperationException("Sqlite 不支持用户管理")
    }

    override suspend fun deleteUser(conn: Connection, user: String, host: String): Boolean {
        throw UnsupportedOperationException("Sqlite 不支持用户管理")
    }

    override suspend fun updatePassword(conn: Connection, user: String, password: String, host: String): Boolean {
        throw UnsupportedOperationException("Sqlite 不支持用户管理")
    }

    override suspend fun listUsers(conn: Connection): List<Map<String, String>> {
        throw UnsupportedOperationException("Sqlite 不支持用户列表")
    }

    override suspend fun updatePrivileges(
        conn: Connection,
        user: String,
        schema: String,
        privileges: List<String>,
        isGrant: Boolean,
        tableName: String?,
        withGrantOption: Boolean
    ): Boolean {
        throw UnsupportedOperationException("Sqlite 不支持权限管理")
    }

    override suspend fun listPrivileges(conn: Connection, user: String, host: String): List<Map<String, String>> {
        throw UnsupportedOperationException("Sqlite 不支持权限查询")
    }

    override suspend fun listAllGrants(conn: Connection, user: String, host: String): List<Map<String, String>> {
        throw UnsupportedOperationException("Sqlite 不支持权限查询")
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
        if (autoIncrement && isPrimaryKey) {
            // SQLite 自增主键语法：INTEGER PRIMARY KEY AUTOINCREMENT（必须 inline，且必须 INTEGER）
            // 列定义后面不能单独加 PRIMARY KEY 约束
            append("INTEGER PRIMARY KEY AUTOINCREMENT")
            return@buildString  // PK 与 NOT NULL/DEFAULT 都已隐含
        }
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
        // SQLite 3.35.0 (2021-03-12) 才支持 DROP COLUMN；xerial 3.46.1.3 默认带 ≥3.35 应支持
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
        // SQLite 不支持 ALTER COLUMN；改类型/默认值/可空性 需 table-rebuild（drop+recreate）
        // 这里简化为重命名支持；其他复杂修改直接抛错，由 caller 走 table-rebuild
        if (newName != null && type.isNullOrBlank()) {
            return "ALTER TABLE ${quoteIdentifier(tableName)} RENAME COLUMN ${quoteIdentifier(name)} TO ${quoteIdentifier(newName)}"
        }
        throw IllegalArgumentException(
            "Sqlite 不支持 ALTER COLUMN（类型/默认值/可空性），" +
            "请走 DROP+CREATE 重建路径。当前请求: type=$type newName=$newName"
        )
    }

    /**
     * SQLite getCreateTableDDL：从 sqlite_master.sql 读取原始 DDL。
     * 注：sqlite_master.sql 已包含完整 CREATE TABLE 语句（用户当时建的原样）。
     */
    override suspend fun getCreateTableDDL(conn: Connection, tableName: String): String = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        conn.prepareStatement("SELECT sql FROM sqlite_master WHERE name = ? AND type = 'table'").use { ps ->
            ps.setString(1, safeTable)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val sql = rs.getString(1) ?: throw IllegalArgumentException("未找到表 '$safeTable'")
                    // 末尾分号统一保留
                    return@withContext if (sql.endsWith(";")) sql else "$sql;"
                }
            }
        }
        throw IllegalArgumentException("未找到表 '$safeTable'")
    }

    override fun buildTableOptionsSQL(options: Map<String, String>): String = ""  // SQLite 无表级选项

    override fun buildPostCreateStatements(tableName: String, options: Map<String, String>): List<String> {
        // SQLite 不支持 COMMENT ON TABLE；忽略 comment
        return emptyList()
    }

    // endregion

    // region SQL 安全校验

    override fun validateSqlFragment(sql: String, label: String) {
        DialectUtil.validateSqlFragment(sql, label, DANGEROUS_KEYWORD_REGEXES)
    }

    override fun validateOrderBy(sql: String) {
        DialectUtil.validateOrderBy(sql, ORDER_BY_REGEX)
    }

    // endregion

    // region 函数 / 宏管理 — SQLite 无过程/函数概念，全部抛 UnsupportedOperationException

    override suspend fun listRoutines(conn: Connection, schema: String): List<Map<String, String>> {
        throw UnsupportedOperationException("Sqlite 不支持函数/存储过程")
    }

    override suspend fun getRoutineDDL(conn: Connection, routineName: String, schema: String): String {
        throw UnsupportedOperationException("Sqlite 不支持函数/存储过程")
    }

    override suspend fun createRoutine(conn: Connection, ddl: String): Boolean {
        throw UnsupportedOperationException("Sqlite 不支持函数/存储过程")
    }

    override suspend fun dropRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        ifExists: Boolean,
        cascade: Boolean
    ): Boolean {
        throw UnsupportedOperationException("Sqlite 不支持函数/存储过程")
    }

    override suspend fun callRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        args: List<String?>
    ): Map<String, Any?> {
        throw UnsupportedOperationException("Sqlite 不支持函数/存储过程调用")
    }

    override suspend fun getRoutineInfo(conn: Connection, routineName: String, schema: String): Map<String, String> {
        throw UnsupportedOperationException("Sqlite 不支持函数/存储过程")
    }

    override suspend fun debugRoutine(conn: Connection, routineName: String, schema: String): List<Map<String, String>> {
        throw UnsupportedOperationException("Sqlite 不支持函数/存储过程")
    }

    override suspend fun validateRoutineDDL(conn: Connection, ddl: String): Boolean {
        throw UnsupportedOperationException("Sqlite 不支持函数/存储过程")
    }

    // endregion

    // region Views

    override suspend fun listViews(conn: Connection, schema: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val views = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT name, sql FROM sqlite_master " +
                "WHERE type = 'view' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            ).use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1) ?: continue
                    val sql = rs.getString(2) ?: ""
                    views.add(mapOf(
                        "name" to name,
                        "type" to "VIEW",
                        "definition" to sql
                    ))
                }
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
        val ifClause = if (ifExists) "IF EXISTS " else ""
        conn.createStatement().use { stmt ->
            stmt.execute("DROP VIEW ${ifClause}${quoteIdentifier(safeName)}")
        }
        true
    }

    override suspend fun getViewDDL(conn: Connection, viewName: String, schema: String): String = withContext(Dispatchers.IO) {
        val safeName = sanitizeIdentifier(viewName, "view name")
        conn.prepareStatement("SELECT sql FROM sqlite_master WHERE name = ? AND type = 'view'").use { ps ->
            ps.setString(1, safeName)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val sql = rs.getString(1)
                        ?: throw IllegalArgumentException("视图 '$safeName' 无可用的定义")
                    return@withContext sql
                }
            }
        }
        throw IllegalArgumentException("未找到视图 '$safeName'")
    }

    // endregion

    // region Indexes

    override suspend fun listIndexes(conn: Connection, tableName: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val indexes = mutableListOf<Map<String, String>>()
        // SQLite 通过 sqlite_master 拿到所有 INDEX，且通过 PRAGMA index_list(<table>) 拿表关联的索引
        conn.prepareStatement("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND name NOT LIKE 'sqlite_%' ORDER BY name").use { ps ->
            ps.setString(1, safeTable)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString(1) ?: continue
                    val sql = rs.getString(2) ?: ""
                    val cols = extractIndexColumns(sql)
                    val isUnique = sql.uppercase().contains("CREATE UNIQUE INDEX")
                    indexes.add(mapOf(
                        "name" to name,
                        "table" to safeTable,
                        "columns" to cols,
                        "unique" to isUnique.toString(),
                        "type" to "BTREE"
                    ))
                }
            }
        }
        indexes
    }

    private fun extractIndexColumns(sql: String): String {
        val match = Regex("""\(\s*([^)]+)\s*\)""").find(sql) ?: return ""
        return match.groupValues[1].split(",").joinToString(",") { it.trim() }
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
        val ifClause = if (ifExists) "IF EXISTS " else ""
        conn.createStatement().use { stmt ->
            stmt.execute("DROP INDEX ${ifClause}${quoteIdentifier(safeIndex)}")
        }
        true
    }

    // endregion

    // region Foreign Keys

    override suspend fun listForeignKeys(conn: Connection, tableName: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val fks = mutableListOf<Map<String, String>>()
        // SQLite 通过 PRAGMA foreign_key_list(<table>) 拿到 FK 列表
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA foreign_key_list(${quoteIdentifier(safeTable)})").use { rs ->
                var idCounter = 0
                val current = mutableListOf<MutableMap<String, String>>()
                while (rs.next()) {
                    idCounter++
                    val from = rs.getString("from") ?: continue
                    val to = rs.getString("to") ?: continue
                    val refTable = rs.getString("table") ?: continue
                    val onDelete = (rs.getString("on_delete") ?: "NO ACTION").uppercase()
                    val onUpdate = (rs.getString("on_update") ?: "NO ACTION").uppercase()
                    // SQLite PRAGMA 每行一列；多列 FK 共享同一 id
                    val id = rs.getInt("id")
                    val entry = current.getOrNull(id) ?: run {
                        val newEntry = mutableMapOf<String, String>()
                        current.add(id, newEntry)
                        newEntry
                    }
                    val cols = entry["columns"]?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
                    cols.add(from)
                    val refCols = entry["ref_columns"]?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
                    refCols.add(to)
                    entry["name"] = "fk_${safeTable}_$id"
                    entry["table"] = safeTable
                    entry["columns"] = cols.joinToString(",")
                    entry["ref_table"] = refTable
                    entry["ref_columns"] = refCols.joinToString(",")
                    entry["on_delete"] = onDelete
                    entry["on_update"] = onUpdate
                }
                for (e in current) if (e.isNotEmpty()) fks.add(e)
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
        // SQLite 不支持 ALTER TABLE ADD CONSTRAINT —— 必须重建表
        val safeTable = sanitizeIdentifier(tableName, "table name")
        val safeFk = sanitizeIdentifier(fkName, "foreign key name")
        val cols = columns.joinToString(", ") { quoteIdentifier(it) }
        val refCols = refColumns.joinToString(", ") { quoteIdentifier(it) }
        val fkInline = buildString {
            append("FOREIGN KEY ($cols) REFERENCES ${quoteIdentifier(refTable)} ($refCols)")
            onDelete?.takeIf { it.isNotBlank() }?.let { append(" ON DELETE $it") }
            onUpdate?.takeIf { it.isNotBlank() }?.let { append(" ON UPDATE $it") }
        }
        rebuildTableWithExtraConstraint(conn, safeTable, extraConstraintSql = "$fkInline")  // SQLite CREATE TABLE 不支持 CONSTRAINT 子句名
        true
    }

    override suspend fun dropForeignKey(conn: Connection, tableName: String, fkName: String, ifExists: Boolean): Boolean = withContext(Dispatchers.IO) {
        // SQLite 不支持 DROP CONSTRAINT —— 重建表（去掉所有 FK）
        val safeTable = sanitizeIdentifier(tableName, "table name")
        rebuildTableExcludingConstraint(conn, safeTable)
        true
    }

    /**
     * 重建表，追加额外约束（用于 addForeignKey）。
     * SQLite CREATE TABLE 不支持 CONSTRAINT 子句名，直接拼 FK 子句。
     */
    private fun rebuildTableWithExtraConstraint(
        conn: Connection,
        tableName: String,
        extraConstraintSql: String
    ) {
        // 1. 取出列定义（从原 CREATE TABLE 解析）
        val createSql = conn.prepareStatement("SELECT sql FROM sqlite_master WHERE name = ? AND type = 'table'").use { ps ->
            ps.setString(1, tableName)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: throw IllegalArgumentException("未找到表 '$tableName'")

        // 简单解析：找到第一个 '(' 与最后一个 ')' 之间的内容
        val bodyStart = createSql.indexOf('(')
        val bodyEnd = createSql.lastIndexOf(')')
        if (bodyStart < 0 || bodyEnd < 0 || bodyEnd <= bodyStart) {
            throw IllegalArgumentException("无法解析原 CREATE TABLE 语句: $createSql")
        }
        val originalBody = createSql.substring(bodyStart + 1, bodyEnd)

        // 2. 重建 CREATE TABLE
        val newCreateSql = "CREATE TABLE ${quoteIdentifier(tableName)} (\n  " +
            originalBody.trim().trimEnd(',') + ",\n  " + extraConstraintSql + "\n)"

        // 3. 备份 → DROP → CREATE → 数据回填
        val tempName = "${tableName}_tmp_${System.currentTimeMillis()}"
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE ${quoteIdentifier(tempName)} AS SELECT * FROM ${quoteIdentifier(tableName)}")
            stmt.execute("DROP TABLE ${quoteIdentifier(tableName)}")
            stmt.execute(newCreateSql)
            stmt.execute("INSERT INTO ${quoteIdentifier(tableName)} SELECT * FROM ${quoteIdentifier(tempName)}")
            stmt.execute("DROP TABLE ${quoteIdentifier(tempName)}")
        }
    }

    /**
     * 重建表，移除所有 FK 约束（用于 dropForeignKey）。
     * SQLite 没有 DROP CONSTRAINT，要去掉某列的 FK 只能重建表。
     */
    private fun rebuildTableExcludingConstraint(conn: Connection, tableName: String) {
        val createSql = conn.prepareStatement("SELECT sql FROM sqlite_master WHERE name = ? AND type = 'table'").use { ps ->
            ps.setString(1, tableName)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: throw IllegalArgumentException("未找到表 '$tableName'")

        val bodyStart = createSql.indexOf('(')
        val bodyEnd = createSql.lastIndexOf(')')
        if (bodyStart < 0 || bodyEnd < 0 || bodyEnd <= bodyStart) {
            throw IllegalArgumentException("无法解析原 CREATE TABLE 语句: $createSql")
        }
        val originalBody = createSql.substring(bodyStart + 1, bodyEnd)

        // 移除所有 FOREIGN KEY ... 整段（简单正则匹配；SQLite FK 是单行语句）
        val cleanedBody = originalBody.lines().filterNot { line ->
            line.trim().uppercase().contains("FOREIGN KEY")
        }.joinToString(",\n  ")

        val newCreateSql = "CREATE TABLE ${quoteIdentifier(tableName)} (\n  $cleanedBody\n)"

        val tempName = "${tableName}_tmp_${System.currentTimeMillis()}"
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE ${quoteIdentifier(tempName)} AS SELECT * FROM ${quoteIdentifier(tableName)}")
            stmt.execute("DROP TABLE ${quoteIdentifier(tableName)}")
            stmt.execute(newCreateSql)
            stmt.execute("INSERT INTO ${quoteIdentifier(tableName)} SELECT * FROM ${quoteIdentifier(tempName)}")
            stmt.execute("DROP TABLE ${quoteIdentifier(tempName)}")
        }
    }

    // endregion

    // region Triggers（不支持，沿用 SPI 默认抛异常）

    // listTriggers / getTriggerDDL 继承默认实现 → UnsupportedOperationException

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
            // SQLite 没有 TRUNCATE；用 DELETE + 重置 sqlite_sequence 模拟
            stmt.execute("DELETE FROM ${quoteIdentifier(safeTable)}")
            stmt.execute("DELETE FROM sqlite_sequence WHERE name = '${safeTable.replace("'", "''")}'")
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
                    for (i in 1..columnCount) {
                        row[meta.getColumnLabel(i)] = rs.getString(i) ?: ""
                    }
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
        mapOf(
            "product" to (meta.databaseProductName ?: "SQLite"),
            "version" to (meta.databaseProductVersion ?: ""),
            "driver" to (meta.driverName ?: ""),
            "driverVersion" to (meta.driverVersion ?: ""),
            "mode" to "embedded",
            "url" to (meta.url ?: "")
        )
    }

    // endregion

    // region 工具方法

    private fun sanitizeIdentifier(name: String, label: String): String {
        if (name.isBlank()) throw IllegalArgumentException("$label cannot be empty")
        if (name.contains(' ')) throw IllegalArgumentException("$label contains null byte")
        if (name.contains('\'')) throw IllegalArgumentException("$label contains single quote")
        if (name.contains('\\')) throw IllegalArgumentException("$label contains backslash")
        if (name.contains(';')) throw IllegalArgumentException("$label contains semicolon")
        return name
    }

    private fun buildTypeSpec(type: String, size: Int?): String {
        val upper = type.uppercase()
        return if (size != null && upper in SIZABLE_TYPES) {
            "$type($size)"
        } else {
            type
        }
    }

    companion object {
        /** ORDER BY 校验（v2.9 #16 用 DialectUtil 工厂） */
        private val ORDER_BY_REGEX = DialectUtil.orderByRegex('"')

        /** SQLite 危险关键词（v2.9 #16 用 DialectUtil 工厂） */
        private val DANGEROUS_KEYWORD_REGEXES: List<Regex> =
            DialectUtil.buildDangerousKeywordRegexes(setOf("ATTACH", "DETACH", "PRAGMA", "REPLACE", "VACUUM", "REINDEX"))

        /** SQLite 可带 size 后缀的类型 */
        private val SIZABLE_TYPES = setOf("VARCHAR", "CHAR", "NVARCHAR", "NCHAR", "DECIMAL", "NUMERIC")

        /** 默认值中不需要加引号的字面量 */
        private val UNQUOTED_DEFAULT_REGEX = Regex(
            """\d+(\.\d+)?|NULL|CURRENT_TIMESTAMP|CURRENT_DATE|CURRENT_TIME|NOW\(\)|TRUE|FALSE""",
            RegexOption.IGNORE_CASE
        )
    }

    // endregion
}