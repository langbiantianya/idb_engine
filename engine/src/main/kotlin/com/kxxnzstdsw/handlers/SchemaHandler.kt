package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.SchemaCreateRequest
import com.kxxnzstdsw.grpc.SchemaCreateResponse
import com.kxxnzstdsw.grpc.SchemaDeleteRequest
import com.kxxnzstdsw.grpc.SchemaDeleteResponse
import com.kxxnzstdsw.grpc.SchemaListRequest
import com.kxxnzstdsw.grpc.SchemaListResponse
import com.kxxnzstdsw.grpc.schemaCreateResponse
import com.kxxnzstdsw.grpc.schemaDeleteResponse
import com.kxxnzstdsw.grpc.schemaListResponse
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SchemaHandler {
    /**
     * 导航层次分两级：
     * - req.level = "database"（默认） → 列出所有 database
     * - req.level = "schema" → 列出指定 database 下的 schema（必须同时传 req.database）
     */
    suspend fun list(config: ConnectionConfig, req: SchemaListRequest): SchemaListResponse = withContext(Dispatchers.IO) {
        val level = if (req.level.isBlank()) "database" else req.level
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            when (level) {
                "database" -> {
                    val items = dialect.listDatabases(conn)
                    schemaListResponse {
                        this.level = "database"
                        this.items += items
                    }
                }
                "schema" -> {
                    val database = req.database.ifBlank {
                        throw IllegalArgumentException(
                            "列出 schema 必须先指定 database — " +
                            "调用方应先调用 listDatabases 选定 database 后再调用本接口"
                        )
                    }
                    val items = dialect.listSchemas(conn, database)
                    schemaListResponse {
                        this.level = "schema"
                        this.database = database
                        this.items += items
                    }
                }
                else -> throw IllegalArgumentException(
                    "Unsupported SCHEMA LIST level: '$level' — 必须是 'database' 或 'schema'"
                )
            }
        }
    }

    suspend fun create(config: ConnectionConfig, req: SchemaCreateRequest): SchemaCreateResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("Missing 'name'")
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val ifNotExists = req.hasIfNotExists() && req.ifNotExists
            dialect.createSchema(conn, req.name, req.optionsMap, ifNotExists)
            schemaCreateResponse { created = req.name }
        }
    }

    suspend fun delete(config: ConnectionConfig, req: SchemaDeleteRequest): SchemaDeleteResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("Missing 'name'")
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val ifExists = req.hasIfExists() && req.ifExists
            dialect.deleteSchema(conn, req.name, ifExists)
            schemaDeleteResponse { deleted = req.name }
        }
    }
}