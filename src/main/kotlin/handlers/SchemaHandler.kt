package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Driver
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.sql.Connection

object SchemaHandler {
    suspend fun list(config: ConnectionConfig): JsonElement = withContext(Dispatchers.IO) {
        val connection = PoolManager.getConnection(config)
        return@withContext connection.use { conn ->
            val schemas = when (config.driver) {
                Driver.mysql -> listMySQLDatabases(conn)
                Driver.postgresql -> listPostgreSQLSchemas(conn)
            }
            Json.encodeToJsonElement(schemas)
        }
    }

    private fun listMySQLDatabases(conn: Connection): List<String> {
        val databases = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW DATABASES").use { rs ->
                while (rs.next()) {
                    databases.add(rs.getString(1))
                }
            }
        }
        return databases
    }

    private fun listPostgreSQLSchemas(conn: Connection): List<String> {
        val schemas = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('pg_catalog', 'information_schema')").use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString(1))
                }
            }
        }
        return schemas
    }

    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val schemaName = payload["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'name' in payload")
        val connection = PoolManager.getConnection(config)
        return@withContext connection.use { conn ->
            val sql = when (config.driver) {
                Driver.mysql -> "CREATE DATABASE `$schemaName`"
                Driver.postgresql -> "CREATE SCHEMA \"$schemaName\""
            }
            conn.createStatement().use { it.execute(sql) }
            buildJsonObject { put("created", schemaName) }
        }
    }

    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val schemaName = payload["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'name' in payload")
        val connection = PoolManager.getConnection(config)
        return@withContext connection.use { conn ->
            val sql = when (config.driver) {
                Driver.mysql -> "DROP DATABASE `$schemaName`"
                Driver.postgresql -> "DROP SCHEMA \"$schemaName\" CASCADE"
            }
            conn.createStatement().use { it.execute(sql) }
            buildJsonObject { put("deleted", schemaName) }
        }
    }
}