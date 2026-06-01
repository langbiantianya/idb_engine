package com.kxxnzstdsw.dialect

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.sql.Connection

/**
 * Database dialect interface for handling database-specific operations
 */
interface DatabaseDialect {
    /**
     * List all schemas/databases
     */
    suspend fun listSchemas(conn: Connection): List<String>

    /**
     * Create a new schema/database
     */
    suspend fun createSchema(conn: Connection, name: String): Boolean

    /**
     * Delete a schema/database
     */
    suspend fun deleteSchema(conn: Connection, name: String): Boolean

    /**
     * List all tables in a specific database/schema
     */
    suspend fun listTables(conn: Connection, database: String): List<Map<String, String>>

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
     * Quote identifier (table name, column name, etc.)
     */
    fun quoteIdentifier(identifier: String): String

    /**
     * Build column definition for CREATE TABLE
     */
    fun buildColumnDefinition(
        name: String,
        type: String,
        size: Int?,
        nullable: Boolean,
        isPrimaryKey: Boolean,
        defaultValue: String?
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
}