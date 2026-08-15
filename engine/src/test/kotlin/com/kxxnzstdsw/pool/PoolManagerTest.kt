package com.kxxnzstdsw.pool

import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.ConnectionConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PoolManager 单元测试 — 验证：
 * 1. 缓存 key 包含所有 6 个字段（driver/host/port/user/password/database）
 * 2. 改变 password 强制创建新连接池
 * 3. closeAll 释放所有连接池
 */
class PoolManagerTest {

    private lateinit var h2: H2Dialect
    private val dbName: String = "pmtest_${UUID.randomUUID().toString().replace("-", "")}"

    @BeforeEach
    fun setUp() {
        h2 = H2Dialect()
        DialectLoader.registerForTesting("H2", h2)
    }

    @AfterEach
    fun tearDown() {
        PoolManager.closeAll()
    }

    @Test
    fun `cache key includes all 6 fields`() {
        val a = ConnectionConfig("H2", "mem", 0, "sa", "p1", "db1")
        val b = ConnectionConfig("H2", "mem", 0, "sa", "p1", "db1")
        // 相同字段 → hash 相同
        assertEquals(a.toHashKey(), b.toHashKey())
    }

    @Test
    fun `different password produces different hash`() {
        val a = ConnectionConfig("H2", "mem", 0, "sa", "p1", "db1")
        val b = ConnectionConfig("H2", "mem", 0, "sa", "p2", "db1")
        assertFalse(a.toHashKey() == b.toHashKey())
    }

    @Test
    fun `different database produces different hash`() {
        val a = ConnectionConfig("H2", "mem", 0, "sa", "", "db1")
        val b = ConnectionConfig("H2", "mem", 0, "sa", "", "db2")
        assertFalse(a.toHashKey() == b.toHashKey())
    }

    @Test
    fun `different host produces different hash`() {
        val a = ConnectionConfig("H2", "host1", 0, "sa", "", "db1")
        val b = ConnectionConfig("H2", "host2", 0, "sa", "", "db1")
        assertFalse(a.toHashKey() == b.toHashKey())
    }

    @Test
    fun `different port produces different hash`() {
        val a = ConnectionConfig("H2", "mem", 1234, "sa", "", "db1")
        val b = ConnectionConfig("H2", "mem", 5678, "sa", "", "db1")
        assertFalse(a.toHashKey() == b.toHashKey())
    }

    @Test
    fun `different driver produces different hash`() {
        val a = ConnectionConfig("H2", "mem", 0, "sa", "", "db1")
        val b = ConnectionConfig("Mysql", "mem", 0, "sa", "", "db1")
        assertFalse(a.toHashKey() == b.toHashKey())
    }

    @Test
    fun `different user produces different hash`() {
        val a = ConnectionConfig("H2", "mem", 0, "sa", "", "db1")
        val b = ConnectionConfig("H2", "mem", 0, "other", "", "db1")
        assertFalse(a.toHashKey() == b.toHashKey())
    }

    @Test
    fun `getConnection returns a working connection`() {
        val cfg = ConnectionConfig("H2", "mem", 0, "sa", "", dbName)
        val conn = PoolManager.getConnection(cfg)
        assertNotNull(conn)
        assertFalse(conn.isClosed)
        conn.createStatement().use { it.execute("CREATE TABLE t (id INT)") }
        conn.close()
    }

    @Test
    fun `changing password forces a new pool (different hash key)`() {
        // H2 in-memory 默认不要密码；我们仅验证 hash 缓存机制，不真正 connect
        val cfg1 = ConnectionConfig("H2", "mem", 0, "sa", "first", dbName)
        val cfg2 = ConnectionConfig("H2", "mem", 0, "sa", "second", dbName)
        // 池缓存以 hash 为 key — 不同 password 必产生不同 hash，即不同池
        assertFalse(cfg1.toHashKey() == cfg2.toHashKey())
    }

    @Test
    fun `closeAll releases all pools`() {
        val cfg = ConnectionConfig("H2", "mem", 0, "sa", "", dbName)
        // 注意：H2 默认不要密码，传递空 password 才可成功 connect
        PoolManager.getConnection(cfg).close()
        PoolManager.closeAll()
        val c = PoolManager.getConnection(cfg)
        assertFalse(c.isClosed)
        c.close()
    }

    @Test
    fun `getConnection with schema invokes setSearchPath`() {
        val cfg = ConnectionConfig("H2", "mem", 0, "sa", "", dbName)
        // 先建 schema，再用 getConnection(schema="my_schema") 触发 SET SCHEMA
        PoolManager.getConnection(cfg).use { conn ->
            conn.createStatement().use { it.execute("CREATE SCHEMA my_schema") }
        }
        val conn = PoolManager.getConnection(cfg, "MY_SCHEMA")
        // H2 SCHEMA 切换后用 CURRENT_SCHEMA 函数查询当前 schema
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
