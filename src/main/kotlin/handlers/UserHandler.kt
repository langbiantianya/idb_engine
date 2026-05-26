package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Driver
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.serialization.json.*

object UserHandler {
    fun list(config: ConnectionConfig): JsonElement {
        val connection = PoolManager.getConnection(config)
        return connection.use { conn ->
            val users = when (config.driver) {
                Driver.mysql -> listMySQLUsers(conn)
                Driver.postgresql -> listPostgreSQLUsers(conn)
            }
            Json.encodeToJsonElement(users)
        }
    }

    private fun listMySQLUsers(conn: java.sql.Connection): List<Map<String, String>> {
        val users = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT User, Host FROM mysql.user").use { rs ->
                while (rs.next()) {
                    users.add(mapOf(
                        "user" to rs.getString("User"),
                        "host" to rs.getString("Host")
                    ))
                }
            }
        }
        return users
    }

    private fun listPostgreSQLUsers(conn: java.sql.Connection): List<Map<String, String>> {
        val users = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT usename FROM pg_user").use { rs ->
                while (rs.next()) {
                    users.add(mapOf(
                        "user" to rs.getString("usename")
                    ))
                }
            }
        }
        return users
    }

    fun updatePrivileges(config: ConnectionConfig, payload: JsonObject): JsonElement {
        val user = payload["user"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'user'")
        val schema = payload["schema"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'schema'")
        val privileges = payload["privileges"]?.jsonArray?.map { it.jsonPrimitive.content } ?: throw IllegalArgumentException("Missing 'privileges'")
        val isGrant = payload["isGrant"]?.jsonPrimitive?.booleanOrNull ?: true

        val connection = PoolManager.getConnection(config)
        return connection.use { conn ->
            val privilegeList = privileges.joinToString(", ")
            val sql = if (isGrant) {
                "GRANT $privilegeList ON $schema.* TO '$user'"
            } else {
                "REVOKE $privilegeList ON $schema.* FROM '$user'"
            }

            conn.createStatement().use { it.execute(sql) }
            buildJsonObject {
                put("user", user)
                put("action", if (isGrant) "granted" else "revoked")
            }
        }
    }
}