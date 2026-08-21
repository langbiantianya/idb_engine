package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.ViewCreateRequest
import com.kxxnzstdsw.grpc.ViewCreateResponse
import com.kxxnzstdsw.grpc.ViewDeleteRequest
import com.kxxnzstdsw.grpc.ViewDeleteResponse
import com.kxxnzstdsw.grpc.ViewGetDdlRequest
import com.kxxnzstdsw.grpc.ViewGetDdlResponse
import com.kxxnzstdsw.grpc.ViewListRequest
import com.kxxnzstdsw.grpc.ViewListResponse
import com.kxxnzstdsw.grpc.viewCreateResponse
import com.kxxnzstdsw.grpc.viewDeleteResponse
import com.kxxnzstdsw.grpc.viewGetDdlResponse
import com.kxxnzstdsw.grpc.viewListItem
import com.kxxnzstdsw.grpc.viewListResponse
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视图管理 Handler。视图列表的 schema 默认为 ""（PG 走 search_path）。
 */
object ViewHandler {

    /**
     * LIST — 列出 schema 下的视图
     */
    suspend fun list(config: ConnectionConfig, req: ViewListRequest): ViewListResponse = withContext(Dispatchers.IO) {
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val items = dialect.listViews(conn, schema)
            viewListResponse {
                items.forEach { row ->
                    this.items += viewListItem {
                        name = row["name"] ?: ""
                        type = row["type"] ?: "VIEW"
                        definition = row["definition"] ?: ""
                    }
                }
            }
        }
    }

    /**
     * CREATE — 创建视图
     */
    suspend fun create(config: ConnectionConfig, req: ViewCreateRequest): ViewCreateResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("缺少参数 'name'")
        if (req.definition.isBlank()) throw IllegalArgumentException("缺少参数 'definition'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.createView(conn, req.name, req.definition)
            viewCreateResponse { created = req.name }
        }
    }

    /**
     * DELETE — 删除视图
     */
    suspend fun delete(config: ConnectionConfig, req: ViewDeleteRequest): ViewDeleteResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("缺少参数 'name'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            dialect.dropView(conn, req.name, req.ifExists)
            viewDeleteResponse { deleted = req.name }
        }
    }

    /**
     * GET_DDL — 获取视图完整定义
     */
    suspend fun getDDL(config: ConnectionConfig, req: ViewGetDdlRequest): ViewGetDdlResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("缺少参数 'name'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            viewGetDdlResponse { ddl = dialect.getViewDDL(conn, req.name, schema) }
        }
    }
}