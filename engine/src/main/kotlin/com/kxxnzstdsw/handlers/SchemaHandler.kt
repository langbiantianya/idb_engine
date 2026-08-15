package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object SchemaHandler {
    /**
     * 导航层次分两级：
     * - payload.level = "database"（默认） → 列出所有 database（MySQL: SHOW DATABASES, PG: pg_database, H2: [catalog]）
     * - payload.level = "schema" → 列出指定 database 下的 schema（PG: pg_namespace, MySQL: [database], H2: INFORMATION_SCHEMA.SCHEMATA）
     *   必须同时传 payload.database
     *
     * 返回结构：
     * - database 列表 → `{ "level": "database", "items": [...] }`
     * - schema 列表  → `{ "level": "schema", "items": [...], "database": "..." }`
     */
    suspend fun list(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val level = payload["level"]?.jsonPrimitive?.contentOrNull ?: "database"
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            when (level) {
                "database" -> {
                    val items = dialect.listDatabases(conn)
                    buildJsonObject {
                        put("level", "database")
                        put("items", Json.encodeToJsonElement(items))
                    }
                }
                "schema" -> {
                    val database = payload["database"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException(
                            "列出 schema 必须先指定 payload.database — " +
                            "调用方应先调用 listDatabases 选定 database 后再调用本接口"
                        )
                    val items = dialect.listSchemas(conn, database)
                    buildJsonObject {
                        put("level", "schema")
                        put("database", database)
                        put("items", Json.encodeToJsonElement(items))
                    }
                }
                else -> throw IllegalArgumentException(
                    "Unsupported SCHEMA LIST level: '$level' — 必须是 'database' 或 'schema'"
                )
            }
        }
    }

    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val schemaName = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'name' in payload")
        val options = payload["options"]?.jsonObject?.let { obj ->
            obj.entries.associate { it.key to it.value.jsonPrimitive.content }
        } ?: emptyMap()
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.createSchema(conn, schemaName, options)
            buildJsonObject { put("created", schemaName) }
        }
    }

    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val schemaName = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'name' in payload")
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.deleteSchema(conn, schemaName)
            buildJsonObject { put("deleted", schemaName) }
        }
    }
}