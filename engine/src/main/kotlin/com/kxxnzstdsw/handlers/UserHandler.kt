package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object UserHandler {
    suspend fun list(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        // payload 含 user 字段时查询该用户权限，否则返回用户列表
        val targetUser = payload["user"]?.jsonPrimitive?.contentOrNull
        val targetHost = payload["host"]?.jsonPrimitive?.contentOrNull ?: "%"

        return@withContext connection.use { conn ->
            if (targetUser != null) {
                val privileges = dialect.listPrivileges(conn, targetUser, targetHost)
                Json.encodeToJsonElement(privileges)
            } else {
                val users = dialect.listUsers(conn)
                Json.encodeToJsonElement(users)
            }
        }
    }

    suspend fun listAllGrants(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val user = payload["user"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'user'")
        val host = payload["host"]?.jsonPrimitive?.contentOrNull ?: "%"

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val grants = dialect.listAllGrants(conn, user, host)
            Json.encodeToJsonElement(grants)
        }
    }

    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val user = payload["user"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'user'")
        val password = payload["password"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'password'")
        val host = payload["host"]?.jsonPrimitive?.contentOrNull ?: "%"

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.createUser(conn, user, password, host)
            buildJsonObject { put("created", user) }
        }
    }

    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val user = payload["user"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'user'")
        val host = payload["host"]?.jsonPrimitive?.contentOrNull ?: "%"

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.deleteUser(conn, user, host)
            buildJsonObject { put("deleted", user) }
        }
    }

    suspend fun updatePrivileges(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val user = payload["user"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'user'")

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        // 当 payload 含 password 且不含 privileges 时走密码修改路径
        val newPassword = payload["password"]?.jsonPrimitive?.contentOrNull
        val hasPrivileges = payload.containsKey("privileges")

        return@withContext connection.use { conn ->
            if (newPassword != null && !hasPrivileges) {
                val host = payload["host"]?.jsonPrimitive?.contentOrNull ?: "%"
                dialect.updatePassword(conn, user, newPassword, host)
                buildJsonObject {
                    put("user", user)
                    put("action", "password_changed")
                }
            } else {
                val schema = payload["schema"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing 'schema'")
                val privileges = payload["privileges"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: throw IllegalArgumentException("Missing 'privileges'")
                val isGrant = payload["isGrant"]?.jsonPrimitive?.booleanOrNull ?: true
                val tableName = payload["tableName"]?.jsonPrimitive?.contentOrNull
                val withGrantOption = payload["withGrantOption"]?.jsonPrimitive?.booleanOrNull ?: false

                dialect.updatePrivileges(conn, user, schema, privileges, isGrant, tableName, withGrantOption)
                buildJsonObject {
                    put("user", user)
                    put("schema", schema)
                    if (tableName != null) put("table", tableName)
                    if (withGrantOption) put("withGrantOption", true)
                    put("action", if (isGrant) "granted" else "revoked")
                }
            }
        }
    }
}
