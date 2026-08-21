package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.IndexCreateRequest
import com.kxxnzstdsw.grpc.IndexCreateResponse
import com.kxxnzstdsw.grpc.IndexDeleteRequest
import com.kxxnzstdsw.grpc.IndexDeleteResponse
import com.kxxnzstdsw.grpc.IndexListRequest
import com.kxxnzstdsw.grpc.IndexListResponse
import com.kxxnzstdsw.grpc.indexCreateResponse
import com.kxxnzstdsw.grpc.indexDeleteResponse
import com.kxxnzstdsw.grpc.indexListItem
import com.kxxnzstdsw.grpc.indexListResponse
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
            indexListResponse {
                items.forEach { row ->
                    val columnsStr = row["columns"].orEmpty()
                    val columnsList = if (columnsStr.isNotEmpty()) columnsStr.split(",") else emptyList()
                    this.items += indexListItem {
                        name = row["name"] ?: ""
                        table = req.tableName
                        this.columns += columnsList
                        unique = row["unique"]?.toBooleanStrictOrNull() ?: false
                        type = row["type"] ?: ""
                        definition = row["definition"] ?: ""
                    }
                }
            }
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
            indexCreateResponse {
                created = req.indexName
                tableName = req.tableName
            }
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
            indexDeleteResponse { deleted = req.indexName }
        }
    }
}