package com.kxxnzstdsw.dialect

import java.sql.Connection

/**
 * Database dialect SPI interface for handling database-specific operations.
 * Each dialect plugin must implement this interface and register via
 * META-INF/services/com.kxxnzstdsw.dialect.DatabaseDialect.
 */
interface DatabaseDialect {
    /**
     * 该方言处理的驱动名称，与请求中 connection.driver 匹配（如 "Mysql"、"Postgresql"）
     */
    val driverName: String

    /**
     * JDBC 驱动类名（如 "com.mysql.cj.jdbc.Driver"）
     */
    val jdbcDriverClassName: String

    /**
     * 构建 JDBC URL
     */
    fun buildJdbcUrl(host: String, port: Int, database: String): String

    /**
     * 为流式游标读取配置连接（如 PostgreSQL 需关闭 autoCommit）
     * @return 原始 autoCommit 值，供 restoreConnectionAfterStreaming 恢复
     */
    fun configureConnectionForStreaming(conn: Connection): Boolean

    /**
     * 流式读取完毕后恢复连接状态
     */
    fun restoreConnectionAfterStreaming(conn: Connection, originalAutoCommit: Boolean) {
        conn.autoCommit = originalAutoCommit
    }

    /**
     * 设置当前连接的 schema/search_path 上下文（PostgreSQL 用，MySQL 空实现）。
     * 自定义 SQL 执行前调用，确保无前缀表名能正确解析。
     */
    fun setSearchPath(conn: Connection, schema: String) {
        // 默认空实现，MySQL 不需要
    }

    /**
     * 列出所有 database（导航第一级：MySQL 的 SHOW DATABASES / PG 的 pg_database / H2 的 [config.database]）。
     *
     * MySQL 默认过滤掉 information_schema / mysql / performance_schema / sys 等系统库。
     * PG 返回所有 datistemplate=false 的数据库。
     * H2 单实例内存库：返回 `setOf(config.database)` — 见具体实现。
     */
    suspend fun listDatabases(conn: Connection): List<String> {
        // 默认实现：兼容旧 SPI 实现，由各方言覆盖
        return listSchemas(conn, "")
    }

    /**
     * 列出指定 database 下的 schema（导航第二级：PG pg_namespace / MySQL 不支持 / H2 INFORMATION_SCHEMA.SCHEMATA）。
     * @param database 必填 — PG 会 assert 该 database 名；MySQL 抛 UnsupportedOperationException
     */
    suspend fun listSchemas(conn: Connection, database: String): List<String>

    /**
     * Create a new schema/database
     * @param name schema/database name
     * @param options 可选项（如 MySQL: charset, collate）
     */
    suspend fun createSchema(conn: Connection, name: String, options: Map<String, String> = emptyMap()): Boolean

    /**
     * Delete a schema/database
     */
    suspend fun deleteSchema(conn: Connection, name: String): Boolean

    /**
     * List all tables in a specific database/schema
     * @param schema 可选的 schema 名。为空时使用默认行为（MySQL: database, PG: search_path）。
     */
    suspend fun listTables(conn: Connection, database: String, schema: String = ""): List<Map<String, String>>

    /**
     * 获取表的列定义列表 — 由方言实现处理 catalog/schema 边界差异。
     * 默认实现：走 JDBC DatabaseMetaData.getColumns，H2/MySQL/PG 共用。
     *
     * @param database 数据库名（catalog）
     * @param schema schema 名（PG 必填，MySQL 通常留空，H2 大写如 PUBLIC）
     * @param tableName 表名
     * @return 列定义列表，每列至少包含 name/type/size/nullable/isPrimaryKey/defaultValue
     */
    suspend fun listColumns(
        conn: Connection,
        database: String,
        schema: String,
        tableName: String
    ): List<Map<String, Any?>> {
        // 默认实现：使用 JDBC metaData.getColumns（与原 TableHandler.columnList 行为一致）
        val result = mutableListOf<Map<String, Any?>>()
        // 主键查询 — 合并各候选名（原样 + 大写）
        val candidates = buildList {
            add(tableName)
            if (driverName.equals("H2", ignoreCase = true)) add(tableName.uppercase())
        }.distinct()
        val lookupSchema = if (driverName.equals("H2", ignoreCase = true)) schema.ifBlank { "PUBLIC" } else schema
        val catalog = if (driverName.equals("H2", ignoreCase = true)) null else database.ifBlank { null }

        val primaryKeys = mutableSetOf<String>()
        for (cand in candidates) {
            conn.metaData.getPrimaryKeys(catalog, lookupSchema, cand).use { rs ->
                while (rs.next()) primaryKeys.add(rs.getString("COLUMN_NAME"))
            }
        }

        val seen = mutableSetOf<String>()
        for (cand in candidates) {
            conn.metaData.getColumns(catalog, lookupSchema, cand, "%").use { rs ->
                while (rs.next()) {
                    val name = rs.getString("COLUMN_NAME")
                    if (!seen.add(name)) continue
                    result.add(mapOf(
                        "name" to name,
                        "type" to rs.getString("TYPE_NAME"),
                        "size" to rs.getInt("COLUMN_SIZE"),
                        "nullable" to (rs.getInt("NULLABLE") == 1),
                        "isPrimaryKey" to (name in primaryKeys),
                        "defaultValue" to rs.getString("COLUMN_DEF")
                    ))
                }
            }
        }
        return result
    }

    /**
     * List all users
     */
    suspend fun listUsers(conn: Connection): List<Map<String, String>>

    /**
     * Grant or revoke privileges.
     *
     * @param user 用户名
     * @param schema database/schema 名
     * @param privileges 权限列表（"SELECT"、"INSERT"、"ALL"、"ALL PRIVILEGES" 等）
     * @param isGrant true=GRANT, false=REVOKE
     * @param tableName 可选的表名（null 时走 schema 级 GRANT/REVOKE，非空时为表级）
     * @param withGrantOption 是否 WITH GRANT OPTION（PG/H2 支持，MySQL 用 WITH GRANT OPTION 关键字）
     */
    suspend fun updatePrivileges(
        conn: Connection,
        user: String,
        schema: String,
        privileges: List<String>,
        isGrant: Boolean,
        tableName: String? = null,
        withGrantOption: Boolean = false
    ): Boolean

    /**
     * Create a new database user
     * @param user 用户名
     * @param password 密码
     * @param host 主机（MySQL 用，PostgreSQL 忽略）
     */
    suspend fun createUser(conn: Connection, user: String, password: String, host: String): Boolean

    /**
     * Drop a database user
     * @param user 用户名
     * @param host 主机（MySQL 用，PostgreSQL 忽略）
     */
    suspend fun deleteUser(conn: Connection, user: String, host: String): Boolean

    /**
     * Change user password
     * @param user 用户名
     * @param password 新密码
     * @param host 主机（MySQL 用，PostgreSQL 忽略）
     */
    suspend fun updatePassword(conn: Connection, user: String, password: String, host: String): Boolean

    /**
     * List privileges for a specific user on a specific schema
     * @param user 用户名
     * @param host 主机（MySQL 用，PostgreSQL 忽略）
     * @return 权限列表
     */
    suspend fun listPrivileges(conn: Connection, user: String, host: String): List<Map<String, String>>

    /**
     * List all table-level grants for a user, grouped by schema + table
     * @param user 用户名
     * @param host 主机（MySQL 用，PostgreSQL 忽略）
     * @return 每个元素包含 schema、table、privileges（逗号分隔的权限列表）
     */
    suspend fun listAllGrants(conn: Connection, user: String, host: String): List<Map<String, String>>

    /**
     * Quote identifier (table name, column name, etc.)
     */
    fun quoteIdentifier(identifier: String): String

    /**
     * Build column definition for CREATE TABLE
     * @param autoIncrement 是否自增（仅对主键列有效）
     */
    fun buildColumnDefinition(
        name: String,
        type: String,
        size: Int?,
        nullable: Boolean,
        isPrimaryKey: Boolean,
        defaultValue: String?,
        autoIncrement: Boolean = false
    ): String

    /**
     * Build ALTER TABLE ADD COLUMN statement
     */
    fun buildAddColumnSQL(tableName: String, columnDef: String): String

    /**
     * Build ALTER TABLE DROP COLUMN statement
     */
    fun buildDropColumnSQL(tableName: String, columnName: String): String

    suspend fun getCreateTableDDL(conn: Connection, tableName: String): String

    /**
     * 构建 CREATE TABLE 语句的表级选项后缀（如 ENGINE、CHARSET、COLLATE）
     * @param options 用户传入的选项 map（如 engine, charset, collate）
     * @return 表级选项 SQL 片段，无可选项时返回空串
     */
    fun buildTableOptionsSQL(options: Map<String, String>): String

    /**
     * 构建 CREATE TABLE 之后需要单独执行的语句（如 PostgreSQL 的 COMMENT ON TABLE）
     * @param tableName 表名（已由方言引用）
     * @param options 用户传入的选项 map
     * @return 需要执行的 SQL 语句列表，无需时返回空列表
     */
    fun buildPostCreateStatements(tableName: String, options: Map<String, String>): List<String>

    /**
     * 校验原始 SQL 片段（WHERE 条件等）的安全性。
     * 去除引号内容后，按方言规则扫描危险关键词、分号、注释符。
     */
    fun validateSqlFragment(sql: String, label: String)

    /**
     * 校验 ORDER BY 子句格式。
     * 只允许方言合法的标识符 [ASC|DESC] 列表。
     */
    fun validateOrderBy(sql: String)

    /**
     * Build ALTER TABLE MODIFY/ALTER COLUMN statement
     * @param newName optional new column name for renaming
     */
    fun buildModifyColumnSQL(
        tableName: String,
        name: String,
        type: String?,
        size: Int?,
        nullable: Boolean,
        defaultValue: String?,
        newName: String? = null
    ): String

    // region 函数/存储过程管理

    /**
     * 列出 schema 下的所有函数、存储过程和触发器
     * @param schema Schema 名称（为空使用默认 schema）
     * @return 函数/存储过程/触发器列表，包含 name, routine_type, return_type, language 等字段
     */
    suspend fun listRoutines(conn: Connection, schema: String): List<Map<String, String>>

    /**
     * 获取函数/存储过程/触发器的完整 DDL 定义（后端自动解析类型）
     * @param routineName 函数/存储过程/触发器名称
     * @param schema Schema 名称
     * @return 完整的 DDL 字符串
     */
    suspend fun getRoutineDDL(conn: Connection, routineName: String, schema: String): String

    /**
     * 执行 DDL 创建函数或存储过程
     * @param ddl 完整的 CREATE OR REPLACE 语句
     */
    suspend fun createRoutine(conn: Connection, ddl: String): Boolean

    /**
     * 删除函数或存储过程
     * @param routineName 函数/存储过程名称
     * @param routineType 类型：FUNCTION 或 PROCEDURE
     * @param schema Schema 名称
     * @param ifExists 是否添加 IF EXISTS
     * @param cascade 是否级联删除依赖
     */
    suspend fun dropRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        ifExists: Boolean = false,
        cascade: Boolean = false
    ): Boolean

    /**
     * 调用函数或存储过程
     * @param routineName 函数/存储过程名称
     * @param routineType 类型：FUNCTION 或 PROCEDURE
     * @param schema Schema 名称
     * @param args 参数值列表
     * @return 执行结果（函数返回 result，存储过程返回 result_set）
     */
    suspend fun callRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        args: List<String?>
    ): Map<String, Any?>

    /**
     * 获取函数/存储过程的详细信息（后端自动解析 routineType）
     * @param routineName 函数/存储过程名称
     * @param schema Schema 名称
     * @return 详细信息，包含 routine_type 由后端自动确定
     */
    suspend fun getRoutineInfo(conn: Connection, routineName: String, schema: String): Map<String, String>

    /**
     * 调试函数（EXPLAIN、执行计划、依赖分析等）
     * @param routineName 函数名称
     * @param schema Schema 名称
     * @return 调试信息列表（EXPLAIN、INFO、DEPENDENCIES）
     */
    suspend fun debugRoutine(conn: Connection, routineName: String, schema: String): List<Map<String, String>>

    /**
     * 验证 DDL 语法（不创建，用于编辑时的语法检查）
     * @param ddl 完整的 DDL 语句
     */
    suspend fun validateRoutineDDL(conn: Connection, ddl: String): Boolean

    // endregion

    // region Views (视图)

    /**
     * 列出 schema 下的所有视图
     * @param schema Schema 名称（为空使用默认 schema）
     */
    suspend fun listViews(conn: Connection, schema: String): List<Map<String, String>> {
        throw UnsupportedOperationException("$driverName 不支持视图列表")
    }

    /**
     * 创建视图
     * @param viewName 视图名称（未引用）
     * @param definition 视图定义 SQL（如 "SELECT id, name FROM users"）
     */
    suspend fun createView(conn: Connection, viewName: String, definition: String): Boolean {
        throw UnsupportedOperationException("$driverName 不支持创建视图")
    }

    /**
     * 删除视图
     */
    suspend fun dropView(conn: Connection, viewName: String, ifExists: Boolean): Boolean {
        throw UnsupportedOperationException("$driverName 不支持删除视图")
    }

    /**
     * 获取视图完整 DDL
     */
    suspend fun getViewDDL(conn: Connection, viewName: String, schema: String): String {
        throw UnsupportedOperationException("$driverName 不支持视图 DDL")
    }

    // endregion

    // region Indexes (索引)

    /**
     * 列出表的所有索引
     */
    suspend fun listIndexes(conn: Connection, tableName: String): List<Map<String, String>> {
        throw UnsupportedOperationException("$driverName 不支持索引列表")
    }

    /**
     * 创建索引
     * @param columns 要索引的列列表
     * @param unique 是否唯一索引
     */
    suspend fun createIndex(
        conn: Connection,
        tableName: String,
        indexName: String,
        columns: List<String>,
        unique: Boolean
    ): Boolean {
        throw UnsupportedOperationException("$driverName 不支持创建索引")
    }

    /**
     * 删除索引
     * @param tableName 部分方言需要（如 MySQL），可为空
     */
    suspend fun dropIndex(conn: Connection, indexName: String, tableName: String?): Boolean {
        throw UnsupportedOperationException("$driverName 不支持删除索引")
    }

    // endregion

    // region Foreign Keys (外键)

    /**
     * 列出表的所有外键
     */
    suspend fun listForeignKeys(conn: Connection, tableName: String): List<Map<String, String>> {
        throw UnsupportedOperationException("$driverName 不支持外键列表")
    }

    /**
     * 添加外键
     */
    suspend fun addForeignKey(
        conn: Connection,
        tableName: String,
        fkName: String,
        columns: List<String>,
        refTable: String,
        refColumns: List<String>,
        onDelete: String?,
        onUpdate: String?
    ): Boolean {
        throw UnsupportedOperationException("$driverName 不支持添加外键")
    }

    /**
     * 删除外键
     */
    suspend fun dropForeignKey(conn: Connection, tableName: String, fkName: String): Boolean {
        throw UnsupportedOperationException("$driverName 不支持删除外键")
    }

    // endregion

    // region Triggers (触发器)

    /**
     * 列出 schema 下的所有触发器
     */
    suspend fun listTriggers(conn: Connection, schema: String): List<Map<String, String>> {
        throw UnsupportedOperationException("$driverName 不支持触发器列表")
    }

    /**
     * 获取触发器完整 DDL
     */
    suspend fun getTriggerDDL(conn: Connection, triggerName: String, schema: String): String {
        throw UnsupportedOperationException("$driverName 不支持触发器 DDL")
    }

    // endregion

    // region Table Operations (表操作)

    /**
     * 重命名表
     */
    suspend fun renameTable(conn: Connection, oldName: String, newName: String): Boolean {
        throw UnsupportedOperationException("$driverName 不支持表重命名")
    }

    /**
     * 清空表（TRUNCATE）
     */
    suspend fun truncateTable(conn: Connection, tableName: String): Boolean {
        throw UnsupportedOperationException("$driverName 不支持清空表")
    }

    // endregion

    // region SQL

    /**
     * 返回 SQL 的执行计划（每行一个 map，键为列名）
     */
    suspend fun explainSQL(conn: Connection, sql: String): List<Map<String, String>> {
        throw UnsupportedOperationException("$driverName 不支持 EXPLAIN")
    }

    // endregion

    // region Server

    /**
     * 测试连接是否有效
     */
    suspend fun testConnection(conn: Connection): Boolean {
        return conn.isValid(5)
    }

    /**
     * 获取数据库服务端信息（版本、模式等）
     */
    suspend fun getServerInfo(conn: Connection): Map<String, String> {
        return mapOf(
            "product" to (conn.metaData.databaseProductName ?: ""),
            "version" to (conn.metaData.databaseProductVersion ?: ""),
            "driver" to (conn.metaData.driverName ?: ""),
            "driverVersion" to (conn.metaData.driverVersion ?: "")
        )
    }

    // endregion
}
