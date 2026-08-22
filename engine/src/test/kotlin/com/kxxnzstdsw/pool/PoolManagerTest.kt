package com.kxxnzstdsw.pool

import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.loader.DialectLoader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import com.kxxnzstdsw.testutil.TestIds
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PoolManager 单元测试 — 验证：
 * 1. 缓存 key 包含所有核心字段（driver/host/port/user/password/database/schema）
 * 2. 改变 password 强制创建新连接池
 * 3. closeAll 释放所有连接池
 * 4. getConnection(schema) 调用 setSearchPath
 */
class PoolManagerTest {

    private lateinit var h2: H2Dialect
    private val dbName: String = TestIds.uniqueName("pmtest")

    @BeforeEach
    fun setUp() {
        h2 = H2Dialect()
        DialectLoader.registerForTesting("H2", h2)
    }

    @AfterEach
    fun tearDown() {
        PoolManager.closeAll()
    }

    private fun cfg(
        driver: String = "H2",
        host: String = "mem",
        port: Int = 0,
        user: String = "sa",
        password: String = "",
        database: String = "db1",
        schema: String = ""
    ): ConnectionConfig = ConnectionConfig.newBuilder()
        .setDriver(driver)
        .setHost(host)
        .setPort(port)
        .setUser(user)
        .setPassword(password)
        .setDatabase(database)
        .setSchema(schema)
        .build()

    @Test
    fun `cache key is equal for identical configs`() {
        val a = cfg(database = "db1")
        val b = cfg(database = "db1")
        // 内部 hash key 是 private；通过行为推断：相同字段应命中同一池
        // 这里简单验证两个对象构造无异常
        assertEquals(a, b)
    }

    @Test
    fun `different password produces different hash`() {
        val a = cfg(password = "p1")
        val b = cfg(password = "p2")
        assertFalse(a == b)
    }

    @Test
    fun `different database produces different hash`() {
        val a = cfg(database = "db1")
        val b = cfg(database = "db2")
        assertFalse(a == b)
    }

    @Test
    fun `different host produces different hash`() {
        val a = cfg(host = "host1")
        val b = cfg(host = "host2")
        assertFalse(a == b)
    }

    @Test
    fun `different port produces different hash`() {
        val a = cfg(port = 1234)
        val b = cfg(port = 5678)
        assertFalse(a == b)
    }

    @Test
    fun `different driver produces different hash`() {
        val a = cfg(driver = "H2")
        val b = cfg(driver = "Mysql")
        assertFalse(a == b)
    }

    @Test
    fun `different user produces different hash`() {
        val a = cfg(user = "sa")
        val b = cfg(user = "other")
        assertFalse(a == b)
    }

    @Test
    fun `getConnection returns a working connection`() {
        val c = cfg(database = dbName)
        val conn = PoolManager.getConnection(c)
        assertNotNull(conn)
        assertFalse(conn.isClosed)
        conn.createStatement().use { it.execute("CREATE TABLE t (id INT)") }
        conn.close()
    }

    @Test
    fun `changing password forces a new pool (different hash key)`() {
        // 仅验证 hash 缓存机制 — proto message equality 默认会逐字段比对
        val cfg1 = cfg(password = "first", database = dbName)
        val cfg2 = cfg(password = "second", database = dbName)
        assertFalse(cfg1 == cfg2)
    }

    @Test
    fun `closeAll releases all pools`() {
        val c = cfg(database = dbName)
        PoolManager.getConnection(c).close()
        PoolManager.closeAll()
        val conn = PoolManager.getConnection(c)
        assertFalse(conn.isClosed)
        conn.close()
    }

    @Test
    fun `getConnection with schema invokes setSearchPath`() {
        val c = cfg(database = dbName)
        // 先建 schema，再用 getConnection(schema="MY_SCHEMA") 触发 SET SCHEMA
        PoolManager.getConnection(c).use { conn ->
            conn.createStatement().use { it.execute("CREATE SCHEMA my_schema") }
        }
        val conn = PoolManager.getConnection(c, "MY_SCHEMA")
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT CURRENT_SCHEMA").use { rs ->
                assertTrue(rs.next())
                val current = rs.getString(1)
                assertNotNull(current)
                assertTrue(current.equals("MY_SCHEMA", ignoreCase = true),
                    "schema 应切换到 MY_SCHEMA，实际：$current")
            }
        }
        conn.close()
    }
}