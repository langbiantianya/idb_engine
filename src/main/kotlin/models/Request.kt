package com.kxxnzstdsw.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    SCHEMA, USER, TABLE, DATA, SQL
}

@Serializable
enum class Action {
    LIST, CREATE, UPDATE, DELETE, EXECUTE, GET_DDL
}

@Serializable
data class ConnectionConfig(
    val driver: Driver,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val database: String
) {
    fun toHashKey(): String {
        return "$driver://$user@$host:$port/$database"
    }
}

@Serializable
enum class Driver {
    Mysql, Postgresql
}