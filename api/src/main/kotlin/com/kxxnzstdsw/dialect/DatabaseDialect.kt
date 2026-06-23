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
     * List all schemas/databases.
     * @param database 可选的数据库名。为空时返回数据库列表；有值时返回该库下的 schema 列表（PG）。
     */
    suspend fun listSchemas(conn: Connection, database: String = ""): List<String>

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
     * List all users
     */
    suspend fun listUsers(conn: Connection): List<Map<String, String>>

    /**
     * Grant or revoke privileges
     */
    suspend fun updatePrivileges(
        conn: Connection,
        user: String,
        schema: String,
        privileges: List<String>,
        isGrant: Boolean
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
        type: String,
        size: Int?,
        nullable: Boolean,
        defaultValue: String?,
        newName: String? = null
    ): String

    // region 函数/存储过程管理

    /**
     * 列出 schema 下的所有函数和存储过程
     * @param schema Schema 名称（为空使用默认 schema）
     * @return 函数/存储过程列表，包含 name, type, return type, language 等字段
     */
    suspend fun listRoutines(conn: Connection, schema: String): List<Map<String, String>>

    /**
     * 获取函数/存储过程的详细信息
     * @param routineName 函数/存储过程名称
     * @param routineType 类型：FUNCTION 或 PROCEDURE
     * @param schema Schema 名称
     * @return 包含参数、返回类型、安全性、稳定性等详细信息
     */
    suspend fun getRoutineInfo(conn: Connection, routineName: String, routineType: String, schema: String): Map<String, String?>

    /**
     * 获取函数/存储过程的 DDL 定义
     * @param routineName 函数/存储过程名称
     * @param routineType 类型：FUNCTION 或 PROCEDURE
     * @param schema Schema 名称
     * @return DDL 字符串（CREATE OR REPLACE ...）
     */
    suspend fun getRoutineDDL(conn: Connection, routineName: String, routineType: String, schema: String): String

    /**
     * 创建函数或存储过程
     * @param routineName 函数/存储过程名称
     * @param routineType 类型：FUNCTION 或 PROCEDURE
     * @param schema Schema 名称
     * @param args 参数列表：[{name, mode (IN/OUT/INOUT), dataType, defaultValue}]
     * @param returnType 返回类型（函数有效）
     * @param language 语言（plpgsql, sql 等）
     * @param body 函数体源代码
     * @param options 可选配置：security_definer, volatility, cost 等
     */
    suspend fun createRoutine(
        conn: Connection,
        routineName: String,
        routineType: String,
        schema: String,
        args: List<Map<String, String?>>,
        returnType: String?,
        language: String,
        body: String,
        options: Map<String, String> = emptyMap()
    ): Boolean

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
     * 调试函数（EXPLAIN、执行计划、依赖分析等）
     * @param routineName 函数名称
     * @param schema Schema 名称
     * @return 调试信息列表（EXPLAIN、INFO、DEPENDENCIES）
     */
    suspend fun debugRoutine(conn: Connection, routineName: String, schema: String): List<Map<String, String>>

    /**
     * 验证函数/存储过程体的语法（不创建）
     * @param routineType 类型：FUNCTION 或 PROCEDURE
     * @param args 参数定义
     * @param returnType 返回类型
     * @param language 语言
     * @param body 函数体
     * @return 验证是否通过
     */
    suspend fun validateRoutineBody(
        conn: Connection,
        routineType: String,
        args: List<Map<String, String?>>,
        returnType: String?,
        language: String,
        body: String
    ): Boolean

    // endregion
}
