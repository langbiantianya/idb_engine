package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.SchemaCreateRequest
import com.kxxnzstdsw.grpc.SchemaCreateResponse
import com.kxxnzstdsw.grpc.SchemaDeleteRequest
import com.kxxnzstdsw.grpc.SchemaDeleteResponse
import com.kxxnzstdsw.grpc.SchemaListRequest
import com.kxxnzstdsw.grpc.SchemaListResponse
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
                    SchemaListResponse.newBuilder()
                        .setLevel("database")
                        .addAllItems(items)
                        .build()
                }
                "schema" -> {
                    val database = req.database.ifBlank {
                        throw IllegalArgumentException(
                            "列出 schema 必须先指定 database — " +
                            "调用方应先调用 listDatabases 选定 database 后再调用本接口"
                        )
                    }
                    val items = dialect.listSchemas(conn, database)
                    SchemaListResponse.newBuilder()
                        .setLevel("schema")
                        .setDatabase(database)
                        .addAllItems(items)
                        .build()
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
            dialect.createSchema(conn, req.name, req.optionsMap)
            SchemaCreateResponse.newBuilder().setCreated(req.name).build()
        }
    }

    suspend fun delete(config: ConnectionConfig, req: SchemaDeleteRequest): SchemaDeleteResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("Missing 'name'")
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.deleteSchema(conn, req.name)
            SchemaDeleteResponse.newBuilder().setDeleted(req.name).build()
        }
    }
}