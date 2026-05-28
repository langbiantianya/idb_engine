package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.dialect.DialectFactory
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object UserHandler {
    suspend fun list(config: ConnectionConfig): JsonElement = withContext(Dispatchers.IO) {
        val connection = PoolManager.getConnection(config)
        val dialect = DialectFactory.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val users = dialect.listUsers(conn)
            Json.encodeToJsonElement(users)
        }
    }

    suspend fun updatePrivileges(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val user = payload["user"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'user'")
        val schema = payload["schema"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'schema'")
        val privileges = payload["privileges"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw IllegalArgumentException("Missing 'privileges'")
        val isGrant = payload["isGrant"]?.jsonPrimitive?.booleanOrNull ?: true

        val connection = PoolManager.getConnection(config)
        val dialect = DialectFactory.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.updatePrivileges(conn, user, schema, privileges, isGrant)
            buildJsonObject {
                put("user", user)
                put("action", if (isGrant) "granted" else "revoked")
            }
        }
    }
}