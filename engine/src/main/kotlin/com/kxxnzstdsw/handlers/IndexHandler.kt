package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.IndexCreateRequest
import com.kxxnzstdsw.grpc.IndexCreateResponse
import com.kxxnzstdsw.grpc.IndexDeleteRequest
import com.kxxnzstdsw.grpc.IndexDeleteResponse
import com.kxxnzstdsw.grpc.IndexListItem
import com.kxxnzstdsw.grpc.IndexListRequest
import com.kxxnzstdsw.grpc.IndexListResponse
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IndexHandler {

    /**
     * LIST — 列出表的所有索引
     */
    suspend fun list(config: ConnectionConfig, req: IndexListRequest): IndexListResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("缺少参数 'tableName'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val items = dialect.listIndexes(conn, req.tableName)
            val builder = IndexListResponse.newBuilder()
            items.forEach { row ->
                val columnsStr = row["columns"].orEmpty()
                val columnsList = if (columnsStr.isNotEmpty()) columnsStr.split(",") else emptyList()
                builder.addItems(
                    IndexListItem.newBuilder()
                        .setName(row["name"] ?: "")
                        .setTable(req.tableName)
                        .addAllColumns(columnsList)
                        .setUnique(row["unique"]?.toBooleanStrictOrNull() ?: false)
                        .setType(row["type"] ?: "")
                        .setDefinition(row["definition"] ?: "")
                )
            }
            builder.build()
        }
    }

    /**
     * CREATE — 创建索引
     */
    suspend fun create(config: ConnectionConfig, req: IndexCreateRequest): IndexCreateResponse = withContext(Dispatchers.IO) {
        if (req.tableName.isBlank()) throw IllegalArgumentException("缺少参数 'tableName'")
        if (req.indexName.isBlank()) throw IllegalArgumentException("缺少参数 'indexName'")
        if (req.columnsList.isEmpty()) throw IllegalArgumentException("缺少参数 'columns'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.createIndex(conn, req.tableName, req.indexName, req.columnsList, req.unique)
            IndexCreateResponse.newBuilder()
                .setCreated(req.indexName)
                .setTableName(req.tableName)
                .build()
        }
    }

    /**
     * DELETE — 删除索引
     */
    suspend fun delete(config: ConnectionConfig, req: IndexDeleteRequest): IndexDeleteResponse = withContext(Dispatchers.IO) {
        if (req.indexName.isBlank()) throw IllegalArgumentException("缺少参数 'indexName'")
        val tableName = req.tableName.ifBlank { null }
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.dropIndex(conn, req.indexName, tableName)
            IndexDeleteResponse.newBuilder().setDeleted(req.indexName).build()
        }
    }
}