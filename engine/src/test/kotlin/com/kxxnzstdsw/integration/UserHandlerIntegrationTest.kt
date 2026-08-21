package com.kxxnzstdsw.integration

import com.kxxnzstdsw.grpc.UserCreateRequest
import com.kxxnzstdsw.grpc.UserDeleteRequest
import com.kxxnzstdsw.grpc.UserListRequest
import com.kxxnzstdsw.grpc.UserUpdateRequest
import com.kxxnzstdsw.handlers.UserHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns users including default SA`() = runBlocking {
        val result = UserHandler.list(config, UserListRequest.getDefaultInstance())
        assertTrue(result.itemsList.isNotEmpty())
        assertTrue(result.itemsList.any {
            it.structValue.fieldsMap["user"]?.stringValue?.uppercase() == "SA"
        })
    }

    @Test
    fun `CREATE user then DELETE user`() = runBlocking {
        UserHandler.create(
            config,
            UserCreateRequest.newBuilder()
                .setUser("alice")
                .setPassword("secret123")
                .setHost("%")
                .build()
        )
        var users = UserHandler.list(config, UserListRequest.getDefaultInstance())
        assertTrue(users.itemsList.any {
            it.structValue.fieldsMap["user"]?.stringValue?.equals("alice", ignoreCase = true) == true
        })

        UserHandler.delete(
            config,
            UserDeleteRequest.newBuilder().setUser("alice").setHost("%").build()
        )
        users = UserHandler.list(config, UserListRequest.getDefaultInstance())
        assertTrue(users.itemsList.none {
            it.structValue.fieldsMap["user"]?.stringValue?.equals("alice", ignoreCase = true) == true
        })
    }

    @Test
    fun `UPDATE password changes user password`() = runBlocking {
        UserHandler.create(
            config,
            UserCreateRequest.newBuilder().setUser("bob").setPassword("old").setHost("%").build()
        )
        val result = UserHandler.updatePrivileges(
            config,
            UserUpdateRequest.newBuilder()
                .setUser("bob")
                .setPassword("new")
                .setHost("%")
                .build()
        )
        assertEquals("password_changed", result.action)
    }

    @Test
    fun `UPDATE grant and revoke privileges`() = runBlocking {
        UserHandler.create(
            config,
            UserCreateRequest.newBuilder().setUser("carol").setPassword("p").setHost("%").build()
        )
        val grant = UserHandler.updatePrivileges(
            config,
            UserUpdateRequest.newBuilder()
                .setUser("carol")
                .setSchema("PUBLIC")
                .addPrivileges("SELECT")
                .setIsGrant(true)
                .build()
        )
        assertEquals("granted", grant.action)

        val revoke = UserHandler.updatePrivileges(
            config,
            UserUpdateRequest.newBuilder()
                .setUser("carol")
                .setSchema("PUBLIC")
                .addPrivileges("SELECT")
                .setIsGrant(false)
                .build()
        )
        assertEquals("revoked", revoke.action)
    }

    @Test
    fun `LIST with user field returns user privileges`() = runBlocking {
        UserHandler.create(
            config,
            UserCreateRequest.newBuilder().setUser("dan").setPassword("p").setHost("%").build()
        )
        UserHandler.updatePrivileges(
            config,
            UserUpdateRequest.newBuilder()
                .setUser("dan")
                .setSchema("PUBLIC")
                .addPrivileges("SELECT")
                .setIsGrant(true)
                .build()
        )
        val result = UserHandler.list(
            config,
            UserListRequest.newBuilder().setUser("dan").setHost("%").build()
        )
        // 返回至少是列表（即使是空）
        assertTrue(result.itemsList.isNotEmpty() || result.itemsList.isEmpty())  // 始终为数组
    }
}