package com.kxxnzstdsw.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class Request(
    val id: String,
    val category: Category,
    val action: Action,
    val connection: ConnectionConfig,
    val payload: JsonObject = JsonObject(emptyMap())
)

@Serializable
enum class Category {
    SCHEMA, USER, TABLE, DATA, SQL, SYSTEM, FUNCTION, EXPORT
}

@Serializable
enum class Action {
    LIST, CREATE, UPDATE, DELETE, EXECUTE, GET_DDL, INFO, GRANTS, GENERATE, DEBUG, CALL, EXPORT
}

@Serializable
data class ConnectionConfig(
    val driver: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val database: String
) {
    fun toHashKey(): String {
        return "$driver://$user@$host:$port/$database"
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("driver", driver)
        put("host", host)
        put("port", port)
        put("user", user)
        put("password", password)
        put("database", database)
    }
}