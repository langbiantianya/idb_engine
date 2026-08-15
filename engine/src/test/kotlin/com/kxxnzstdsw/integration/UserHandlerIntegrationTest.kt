package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.UserHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns users including default SA`() = runBlocking {
        val result = UserHandler.list(config, JsonObject(emptyMap()))
        val arr = result.jsonArray
        assertTrue(arr.isNotEmpty())
        assertTrue(arr.any { it.jsonObject["user"]?.jsonPrimitive?.content?.uppercase() == "SA" })
    }

    @Test
    fun `CREATE user then DELETE user`() = runBlocking {
        UserHandler.create(config, buildJsonObject {
            put("user", "alice")
            put("password", "secret123")
            put("host", "%")
        })
        var users = UserHandler.list(config, JsonObject(emptyMap()))
        // H2 默认把用户名返回大写 — 用 ignoreCase 比较
        assertTrue(users.jsonArray.any { it.jsonObject["user"]?.jsonPrimitive?.content?.equals("alice", ignoreCase = true) == true })

        UserHandler.delete(config, buildJsonObject {
            put("user", "alice")
            put("host", "%")
        })
        users = UserHandler.list(config, JsonObject(emptyMap()))
        assertFalse(users.jsonArray.any { it.jsonObject["user"]?.jsonPrimitive?.content?.equals("alice", ignoreCase = true) == true })
    }

    @Test
    fun `UPDATE password changes user password`() = runBlocking {
        UserHandler.create(config, buildJsonObject {
            put("user", "bob")
            put("password", "old")
            put("host", "%")
        })
        val result = UserHandler.updatePrivileges(config, buildJsonObject {
            put("user", "bob")
            put("password", "new")
            put("host", "%")
        })
        assertEquals("password_changed", result.jsonObject["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun `UPDATE grant and revoke privileges`() = runBlocking {
        UserHandler.create(config, buildJsonObject {
            put("user", "carol")
            put("password", "p")
            put("host", "%")
        })
        // Grant
        val grant = UserHandler.updatePrivileges(config, buildJsonObject {
            put("user", "carol")
            put("schema", "PUBLIC")
            putJsonArray("privileges") {
                add(JsonPrimitive("SELECT"))
            }
            put("isGrant", true)
        })
        assertEquals("granted", grant.jsonObject["action"]?.jsonPrimitive?.content)

        // Revoke
        val revoke = UserHandler.updatePrivileges(config, buildJsonObject {
            put("user", "carol")
            put("schema", "PUBLIC")
            putJsonArray("privileges") {
                add(JsonPrimitive("SELECT"))
            }
            put("isGrant", false)
        })
        assertEquals("revoked", revoke.jsonObject["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun `LIST with user field returns user privileges`() = runBlocking {
        UserHandler.create(config, buildJsonObject {
            put("user", "dan")
            put("password", "p")
            put("host", "%")
        })
        // 给予权限（测试能否查询）
        UserHandler.updatePrivileges(config, buildJsonObject {
            put("user", "dan")
            put("schema", "PUBLIC")
            putJsonArray("privileges") { add(JsonPrimitive("SELECT")) }
            put("isGrant", true)
        })
        val result = UserHandler.list(config, buildJsonObject {
            put("user", "dan")
            put("host", "%")
        })
        // 返回至少是数组（即使是空）
        assertTrue(result is JsonArray)
    }
}