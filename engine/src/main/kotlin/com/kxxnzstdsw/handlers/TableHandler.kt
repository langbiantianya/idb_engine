package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.TableColumnListRequest
import com.kxxnzstdsw.grpc.TableColumnListResponse
import com.kxxnzstdsw.grpc.TableCreateRequest
import com.kxxnzstdsw.grpc.TableCreateResponse
import com.kxxnzstdsw.grpc.TableDeleteRequest
import com.kxxnzstdsw.grpc.TableDeleteResponse
import com.kxxnzstdsw.grpc.TableGetDdlRequest
import com.kxxnzstdsw.grpc.TableGetDdlResponse
import com.kxxnzstdsw.grpc.TableListRequest
import com.kxxnzstdsw.grpc.TableListResponse
import com.kxxnzstdsw.grpc.TableRenameRequest
import com.kxxnzstdsw.grpc.TableRenameResponse
import com.kxxnzstdsw.grpc.TableTruncateRequest
import com.kxxnzstdsw.grpc.TableTruncateResponse
import com.kxxnzstdsw.grpc.TableUpdateRequest
import com.kxxnzstdsw.grpc.TableUpdateResponse
import com.kxxnzstdsw.grpc.columnDef
import com.kxxnzstdsw.grpc.tableColumnListResponse
import com.kxxnzstdsw.grpc.tableCreateResponse
import com.kxxnzstdsw.grpc.tableDeleteResponse
import com.kxxnzstdsw.grpc.tableGetDdlResponse
import com.kxxnzstdsw.grpc.tableListItem
import com.kxxnzstdsw.grpc.tableListResponse
import com.kxxnzstdsw.grpc.tableRenameResponse
import com.kxxnzstdsw.grpc.tableTruncateResponse
import com.kxxnzstdsw.grpc.tableUpdateResponse
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TableHandler {
    suspend fun list(config: ConnectionConfig, req: TableListRequest): TableListResponse = withContext(Dispatchers.IO) {
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val tables = dialect.listTables(conn, config.database, schema)
            tableListResponse {
                tables.forEach { row ->
                    this.items += tableListItem {
                        name = row["name"] ?: ""
                        type = row["type"] ?: "TABLE"
                    }
                }
            }
        }
    }

    suspend fun columnList(config: ConnectionConfig, req: TableColumnListRequest): TableColumnListResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName' in payload")
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val cols = dialect.listColumns(conn, config.database, schema, req.tableName)
            tableColumnListResponse {
                cols.forEach { col -> this.items += buildColumnDef(col) }
            }
        }
    }

    /**
     * Convert a dialect-returned Map<String, Any?> column descriptor into a typed ColumnDef proto.
     */
    private fun buildColumnDef(col: Map<String, Any?>) = columnDef {
        name = col["name"]?.toString() ?: ""
        type = col["type"]?.toString() ?: ""
        size = (col["size"] as? Number)?.toInt() ?: 0
        isPrimaryKey = col["isPrimaryKey"] as? Boolean ?: false
        autoIncrement = col["autoIncrement"] as? Boolean ?: false
        // `nullable` is `optional` in proto3; preserve presence
        val nullable = col["nullable"] as? Boolean
        if (nullable != null) this.nullable = nullable
        val defaultValue = col["defaultValue"]?.toString()
        if (defaultValue != null) this.defaultValue = defaultValue
    }

    suspend fun create(config: ConnectionConfig, req: TableCreateRequest): TableCreateResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
        if (req.columnsList.isEmpty()) throw IllegalArgumentException("Missing 'columns' array")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val columnDefs = req.columnsList.map { col ->
                val name = col.name
                if (name.isBlank()) throw IllegalArgumentException("Column missing 'name'")
                val type = col.type
                if (type.isBlank()) throw IllegalArgumentException("Column missing 'type'")
                val size = if (col.size == 0) null else col.size
                val nullable = if (col.hasNullable()) col.nullable else true
                val isPrimaryKey = col.isPrimaryKey
                val defaultValue = col.defaultValue.ifBlank { null }
                val autoIncrement = col.autoIncrement

                dialect.buildColumnDefinition(name, type, size, nullable, isPrimaryKey, defaultValue, autoIncrement)
            }

            val primaryKeys = req.columnsList.mapNotNull { col ->
                if (col.isPrimaryKey) col.name else null
            }
            val autoIncrementColumns = req.columnsList.mapNotNull { col ->
                if (col.isPrimaryKey && col.autoIncrement) col.name else null
            }
            // SQLite / DuckDB 等方言在 autoIncrement 主键的列定义里已 inline 输出 PRIMARY KEY，
            // 此时不能再追加表级 PRIMARY KEY (会触发 "more than one primary key")。
            val dialectEmitsInlinePK = autoIncrementColumns.isNotEmpty()

            for (stmt in dialect.buildPreCreateStatements(dialect.quoteIdentifier(req.tableName), autoIncrementColumns)) {
                conn.createStatement().use { it.execute(stmt) }
            }

            val sql = buildString {
                append("CREATE TABLE ")
                if (req.hasIfNotExists() && req.ifNotExists) append("IF NOT EXISTS ")
                append(dialect.quoteIdentifier(req.tableName))
                append(" (")
                append(columnDefs.joinToString(", "))
                if (primaryKeys.isNotEmpty() && !dialectEmitsInlinePK) {
                    append(", PRIMARY KEY (")
                    append(primaryKeys.joinToString(", ") { dialect.quoteIdentifier(it) })
                    append(")")
                }
                append(")")
                append(dialect.buildTableOptionsSQL(req.optionsMap))
            }

            conn.createStatement().use { it.execute(sql) }

            for (stmt in dialect.buildPostCreateStatements(dialect.quoteIdentifier(req.tableName), req.optionsMap)) {
                conn.createStatement().use { it.execute(stmt) }
            }

            DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, req.tableName)
            tableCreateResponse { created = req.tableName }
        }
    }

    suspend fun update(config: ConnectionConfig, req: TableUpdateRequest): TableUpdateResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
        if (req.operation.isBlank()) throw IllegalArgumentException("Missing 'operation' (ADD_COLUMN|DROP_COLUMN|MODIFY_COLUMN)")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val sql = when (req.operation) {
                "ADD_COLUMN" -> {
                    val column = req.column
                    if (column.name.isBlank()) throw IllegalArgumentException("Missing 'column' for ADD_COLUMN")
                    val size = if (column.size == 0) null else column.size
                    val nullable = if (column.hasNullable()) column.nullable else true
                    val defaultValue = column.defaultValue.ifBlank { null }
                    val colDef = dialect.buildColumnDefinition(column.name, column.type, size, nullable, false, defaultValue)
                    dialect.buildAddColumnSQL(req.tableName, colDef)
                }
                "DROP_COLUMN" -> {
                    if (req.columnName.isBlank()) throw IllegalArgumentException("Missing 'columnName' for DROP_COLUMN")
                    dialect.buildDropColumnSQL(req.tableName, req.columnName)
                }
                "MODIFY_COLUMN" -> {
                    val column = req.column
                    if (column.name.isBlank()) throw IllegalArgumentException("Missing 'column' for MODIFY_COLUMN")
                    val type = column.type.ifBlank { null }
                    val size = if (column.size == 0) null else column.size
                    val nullable = if (column.hasNullable()) column.nullable else true
                    val defaultValue = column.defaultValue.ifBlank { null }
                    val newName = column.newName.ifBlank { null }
                    if (type == null && newName == null) {
                        throw IllegalArgumentException("Column requires 'type' or 'newName'")
                    }
                    dialect.buildModifyColumnSQL(req.tableName, column.name, type, size, nullable, defaultValue, newName)
                }
                else -> throw IllegalArgumentException("Unknown operation: ${req.operation}")
            }

            conn.createStatement().use { it.execute(sql) }
            DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, req.tableName)
            tableUpdateResponse {
                tableName = req.tableName
                operation = req.operation
            }
        }
    }

    suspend fun getDDL(config: ConnectionConfig, req: TableGetDdlRequest): TableGetDdlResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName' in payload")
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            tableGetDdlResponse { ddl = dialect.getCreateTableDDL(conn, req.tableName) }
        }
    }

    suspend fun delete(config: ConnectionConfig, req: TableDeleteRequest): TableDeleteResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val ifClause = if (req.hasIfExists() && req.ifExists) "IF EXISTS " else ""
            val sql = "DROP TABLE ${ifClause}${dialect.quoteIdentifier(req.tableName)}"
            conn.createStatement().use { it.execute(sql) }
            DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, req.tableName)
            tableDeleteResponse { deleted = req.tableName }
        }
    }

    suspend fun rename(config: ConnectionConfig, req: TableRenameRequest): TableRenameResponse = withContext(Dispatchers.IO) {
        val oldName = req.oldName.ifBlank { req.tableName.ifBlank {
            throw IllegalArgumentException("Missing 'oldName' (or 'tableName')")
        } }
        if (req.newName.isBlank()) throw IllegalArgumentException("Missing 'newName'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.renameTable(conn, oldName, req.newName)
            DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, oldName)
            DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, req.newName)
            tableRenameResponse {
                renamed = oldName
                newName = req.newName
            }
        }
    }

    suspend fun truncate(config: ConnectionConfig, req: TableTruncateRequest): TableTruncateResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("Missing 'tableName'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.truncateTable(conn, req.tableName)
            DataHandler.invalidateColumnTypeCache(config.driver, config.database, schema, req.tableName)
            tableTruncateResponse { truncated = req.tableName }
        }
    }
}