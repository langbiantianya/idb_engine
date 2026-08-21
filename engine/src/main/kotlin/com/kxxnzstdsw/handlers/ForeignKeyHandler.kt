package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.ForeignKeyCreateRequest
import com.kxxnzstdsw.grpc.ForeignKeyCreateResponse
import com.kxxnzstdsw.grpc.ForeignKeyDeleteRequest
import com.kxxnzstdsw.grpc.ForeignKeyDeleteResponse
import com.kxxnzstdsw.grpc.ForeignKeyListItem
import com.kxxnzstdsw.grpc.ForeignKeyListRequest
import com.kxxnzstdsw.grpc.ForeignKeyListResponse
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 外键管理 Handler。
 */
object ForeignKeyHandler {

    /**
     * LIST — 列出表的所有外键
     */
    suspend fun list(config: ConnectionConfig, req: ForeignKeyListRequest): ForeignKeyListResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("缺少参数 'tableName'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val items = dialect.listForeignKeys(conn, req.tableName)
            val builder = ForeignKeyListResponse.newBuilder()
            // Accumulate columns / ref_columns per FK name (some dialects return one row per column)
            val acc = mutableMapOf<String, MutableMap<String, Any>>()
            items.forEach { row ->
                val name = row["name"] ?: return@forEach
                val existing = acc.getOrPut(name) { mutableMapOf(
                    "name" to name,
                    "table" to req.tableName,
                    "columns" to mutableListOf<String>(),
                    "ref_table" to (row["ref_table"] ?: ""),
                    "ref_columns" to mutableListOf<String>(),
                    "on_delete" to (row["on_delete"] ?: "NO ACTION"),
                    "on_update" to (row["on_update"] ?: "NO ACTION")
                ) }
                (existing["columns"] as MutableList<String>).add(row["column"] ?: row["columns"] ?: "")
                (existing["ref_columns"] as MutableList<String>).add(row["ref_column"] ?: row["ref_columns"] ?: "")
            }
            acc.values.forEach { m ->
                builder.addItems(
                    ForeignKeyListItem.newBuilder()
                        .setName(m["name"] as String)
                        .setTable(m["table"] as String)
                        .addAllColumns(m["columns"] as List<String>)
                        .setRefTable(m["ref_table"] as String)
                        .addAllRefColumns(m["ref_columns"] as List<String>)
                        .setOnDelete(m["on_delete"] as String)
                        .setOnUpdate(m["on_update"] as String)
                )
            }
            builder.build()
        }
    }

    /**
     * CREATE — 添加外键
     */
    suspend fun create(config: ConnectionConfig, req: ForeignKeyCreateRequest): ForeignKeyCreateResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("缺少参数 'tableName'")
        if (req.fkName.isBlank()) throw IllegalArgumentException("缺少参数 'fkName'")
        if (req.columnsList.isEmpty()) throw IllegalArgumentException("缺少参数 'columns'")
        if (req.refTable.isBlank()) throw IllegalArgumentException("缺少参数 'refTable'")
        if (req.refColumnsList.isEmpty()) throw IllegalArgumentException("缺少参数 'refColumns'")
        val schema = req.schema
        val onDelete = req.onDelete.ifBlank { null }
        val onUpdate = req.onUpdate.ifBlank { null }

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.addForeignKey(conn, req.tableName, req.fkName, req.columnsList, req.refTable, req.refColumnsList, onDelete, onUpdate)
            ForeignKeyCreateResponse.newBuilder()
                .setCreated(req.fkName)
                .setTableName(req.tableName)
                .build()
        }
    }

    /**
     * DELETE — 删除外键
     */
    suspend fun delete(config: ConnectionConfig, req: ForeignKeyDeleteRequest): ForeignKeyDeleteResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("缺少参数 'tableName'")
        if (req.fkName.isBlank()) throw IllegalArgumentException("缺少参数 'fkName'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.dropForeignKey(conn, req.tableName, req.fkName)
            ForeignKeyDeleteResponse.newBuilder().setDeleted(req.fkName).build()
        }
    }
}