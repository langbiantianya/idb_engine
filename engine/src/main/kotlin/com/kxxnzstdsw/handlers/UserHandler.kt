package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.UserCreateRequest
import com.kxxnzstdsw.grpc.UserCreateResponse
import com.kxxnzstdsw.grpc.UserDeleteRequest
import com.kxxnzstdsw.grpc.UserDeleteResponse
import com.kxxnzstdsw.grpc.UserGrantItem
import com.kxxnzstdsw.grpc.UserGrantsRequest
import com.kxxnzstdsw.grpc.UserGrantsResponse
import com.kxxnzstdsw.grpc.UserListRequest
import com.kxxnzstdsw.grpc.UserListResponse
import com.kxxnzstdsw.grpc.UserUpdateRequest
import com.kxxnzstdsw.grpc.UserUpdateResponse
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object UserHandler {

    suspend fun list(config: ConnectionConfig, req: UserListRequest): UserListResponse = withContext(Dispatchers.IO) {
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val builder = UserListResponse.newBuilder()
            if (req.user.isNotBlank()) {
                val targetHost = req.host.ifBlank { "%" }
                val privileges = dialect.listPrivileges(conn, req.user, targetHost)
                // Privileges shape varies by dialect (MySQL raw SHOW GRANTS text vs PG/H2 structured
                // {schema, table, privilege}). Keep Value packing — genuinely dialect-specific.
                privileges.forEach { row ->
                    val obj = JsonObject(row.mapValues { (_, v) -> JsonPrimitive(v) })
                    builder.addItems(PayloadAdapter.toValue(obj))
                }
            } else {
                val users = dialect.listUsers(conn)
                users.forEach { row ->
                    val obj = JsonObject(row.mapValues { (_, v) -> JsonPrimitive(v) })
                    builder.addItems(PayloadAdapter.toValue(obj))
                }
            }
            builder.build()
        }
    }

    suspend fun listAllGrants(config: ConnectionConfig, req: UserGrantsRequest): UserGrantsResponse = withContext(Dispatchers.IO) {
        if (req.user.isBlank()) throw IllegalArgumentException("Missing 'user'")
        val host = req.host.ifBlank { "%" }

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val grants = dialect.listAllGrants(conn, req.user, host)
            val builder = UserGrantsResponse.newBuilder()
            grants.forEach { row ->
                builder.addItems(
                    UserGrantItem.newBuilder()
                        .setSchema(row["schema"] ?: "")
                        .setTable(row["table"] ?: "")
                        .setPrivilege(row["privileges"] ?: row["privilege"] ?: "")
                )
            }
            builder.build()
        }
    }

    suspend fun create(config: ConnectionConfig, req: UserCreateRequest): UserCreateResponse = withContext(Dispatchers.IO) {
        if (req.user.isBlank()) throw IllegalArgumentException("Missing 'user'")
        if (req.password.isBlank()) throw IllegalArgumentException("Missing 'password'")
        val host = req.host.ifBlank { "%" }

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.createUser(conn, req.user, req.password, host)
            UserCreateResponse.newBuilder().setCreated(req.user).build()
        }
    }

    suspend fun delete(config: ConnectionConfig, req: UserDeleteRequest): UserDeleteResponse = withContext(Dispatchers.IO) {
        if (req.user.isBlank()) throw IllegalArgumentException("Missing 'user'")
        val host = req.host.ifBlank { "%" }

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            dialect.deleteUser(conn, req.user, host)
            UserDeleteResponse.newBuilder().setDeleted(req.user).build()
        }
    }

    suspend fun updatePrivileges(config: ConnectionConfig, req: UserUpdateRequest): UserUpdateResponse = withContext(Dispatchers.IO) {
        if (req.user.isBlank()) throw IllegalArgumentException("Missing 'user'")

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        // 当 password 非空且 privileges 为空时走密码修改路径；否则走权限授予/回收
        val hasPrivileges = req.privilegesList.isNotEmpty()
        return@withContext connection.use { conn ->
            if (req.password.isNotBlank() && !hasPrivileges) {
                val host = req.host.ifBlank { "%" }
                dialect.updatePassword(conn, req.user, req.password, host)
                UserUpdateResponse.newBuilder()
                    .setUser(req.user)
                    .setAction("password_changed")
                    .build()
            } else {
                if (req.schema.isBlank()) throw IllegalArgumentException("Missing 'schema'")
                val isGrant = req.isGrant
                val tableName = req.tableName.ifBlank { null }
                val withGrantOption = req.withGrantOption

                dialect.updatePrivileges(conn, req.user, req.schema, req.privilegesList, isGrant, tableName, withGrantOption)
                val builder = UserUpdateResponse.newBuilder()
                    .setUser(req.user)
                    .setSchema(req.schema)
                    .setAction(if (isGrant) "granted" else "revoked")
                if (tableName != null) builder.table = tableName
                if (withGrantOption) builder.withGrantOption = true
                builder.build()
            }
        }
    }
}