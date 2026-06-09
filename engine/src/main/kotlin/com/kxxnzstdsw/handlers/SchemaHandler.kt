package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object SchemaHandler {
    suspend fun list(config: ConnectionConfig): JsonElement = withContext(Dispatchers.IO) {
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver.name)

        return@withContext connection.use { conn ->
            val schemas = dialect.listSchemas(conn)
            Json.encodeToJsonElement(schemas)
        }
    }

    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val schemaName = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'name' in payload")
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver.name)

        return@withContext connection.use { conn ->
            dialect.createSchema(conn, schemaName)
            buildJsonObject { put("created", schemaName) }
        }
    }

    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val schemaName = payload["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'name' in payload")
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver.name)

        return@withContext connection.use { conn ->
            dialect.deleteSchema(conn, schemaName)
            buildJsonObject { put("deleted", schemaName) }
        }
    }
}