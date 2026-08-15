package com.kxxnzstdsw.models

import com.kxxnzstdsw.proto.PayloadValue
import kotlinx.serialization.Serializable

/**
 * 统一请求体（wire 层）
 *
 * payload 用 Map<String, PayloadValue> 而非 JsonObject 是为了让 Request 能直接被
 * kotlinx-serialization-protobuf 序列化（protobuf 不支持 JsonObject 这类无 schema 类型）。
 *
 * 业务层（Handler）继续使用 JsonObject / JsonElement，RequestDispatcher 在边界负责
 * 双向转换（见 com.kxxnzstdsw.proto.ProtoConverters）。
 */
@Serializable
data class Request(
    val id: String,
    val category: Category,
    val action: Action,
    val connection: ConnectionConfig,
    val payload: Map<String, PayloadValue> = emptyMap()
)

@Serializable
enum class Category {
    SCHEMA, USER, TABLE, DATA, SQL, SYSTEM, FUNCTION, EXPORT,
    VIEW, INDEX, FOREIGN_KEY, TRIGGER
}

@Serializable
enum class Action {
    LIST, CREATE, UPDATE, DELETE, EXECUTE, GET_DDL, INFO, GRANTS, GENERATE, DEBUG, CALL, EXPORT,
    RENAME, TRUNCATE, EXPLAIN, TEST_CONNECTION, SERVER_INFO
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
    /**
     * 池缓存 key — 包含 driver + host + port + user + password + database
     *
     * password 也参与 hash：同一 user/host/db 在不同 password 下应使用不同连接池，
     * 避免凭据混淆导致的连接复用问题。
     */
    fun toHashKey(): String {
        return "$driver://$user@$host:$port/$database?password=$password"
    }
}