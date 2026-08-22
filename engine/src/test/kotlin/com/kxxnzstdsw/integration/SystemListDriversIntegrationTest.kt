package com.kxxnzstdsw.integration

import com.kxxnzstdsw.dialect.DuckDBDialect
import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.dialect.MySQLDialect
import com.kxxnzstdsw.dialect.PostgreSQLDialect
import com.kxxnzstdsw.dialect.SQLiteDialect
import com.kxxnzstdsw.handlers.SystemHandler
import com.kxxnzstdsw.loader.DialectLoader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SYSTEM.LIST_DRIVERS (v2.8) 集成测试 — 验证引擎可枚举已加载方言并返回前端连接元数据。
 *
 * 测试目标：
 * 1. 至少包含 5 个方言（Mysql / Postgresql / H2 / Duckdb / Sqlite）
 * 2. 每个 DialectInfo 字段都映射正确
 * 3. 客户端/服务端方言标记 requiresHost=true；嵌入式方言标记 requiresHost=false
 * 4. capabilities 集合正确反映方言功能
 */
class SystemListDriversIntegrationTest {

    @BeforeEach
    fun setUp() {
        // 清空方言表，重新注册（测试间隔离）
        DialectLoader.closeAll()
        DialectLoader.registerForTesting("Mysql", MySQLDialect())
        DialectLoader.registerForTesting("Postgresql", PostgreSQLDialect())
        DialectLoader.registerForTesting("H2", H2Dialect())
        DialectLoader.registerForTesting("Duckdb", DuckDBDialect())
        DialectLoader.registerForTesting("Sqlite", SQLiteDialect())
    }

    @AfterEach
    fun tearDown() {
        DialectLoader.closeAll()
    }

    @Test
    fun `listDrivers returns all 5 dialects`() {
        val res = SystemHandler.listDrivers()
        assertEquals(5, res.itemsList.size, "应该注册 5 个方言，实际: ${res.itemsList.map { it.driverName }}")
    }

    @Test
    fun `MySQL is CLIENT_SERVER with default port 3306`() {
        val res = SystemHandler.listDrivers()
        val mysql = res.itemsList.first { it.driverName == "Mysql" }
        assertEquals("MySQL", mysql.displayName)
        assertEquals("CLIENT_SERVER", mysql.connectionType)
        assertTrue(mysql.requiresHost)
        assertTrue(mysql.requiresPort)
        assertEquals(3306, mysql.defaultPort)
        assertTrue(mysql.supportsUser)
        assertTrue(mysql.supportsPassword)
        assertFalse(mysql.supportsSchema)
        assertFalse(mysql.supportsCrossDatabase)
        assertTrue(mysql.jdbcUrlExample.startsWith("jdbc:mysql://"))
        // capabilities
        assertTrue(mysql.capabilitiesList.contains("USERS"))
        assertTrue(mysql.capabilitiesList.contains("PRIVILEGES"))
        assertTrue(mysql.capabilitiesList.contains("ROUTINES"))
        assertTrue(mysql.capabilitiesList.contains("VIEWS"))
        assertTrue(mysql.capabilitiesList.contains("INDEXES"))
        assertTrue(mysql.capabilitiesList.contains("FOREIGN_KEYS"))
        assertTrue(mysql.capabilitiesList.contains("TRIGGERS"))
    }

    @Test
    fun `PostgreSQL is CLIENT_SERVER with default port 5432 and supports schema`() {
        val res = SystemHandler.listDrivers()
        val pg = res.itemsList.first { it.driverName == "Postgresql" }
        assertEquals("PostgreSQL", pg.displayName)
        assertEquals("CLIENT_SERVER", pg.connectionType)
        assertEquals(5432, pg.defaultPort)
        assertTrue(pg.supportsSchema)
        assertTrue(pg.supportsCrossDatabase)
        assertTrue(pg.capabilitiesList.contains("MULTI_SCHEMA"))
        assertTrue(pg.capabilitiesList.contains("CROSS_DATABASE"))
    }

    @Test
    fun `H2 is IN_MEMORY without host and port`() {
        val res = SystemHandler.listDrivers()
        val h2 = res.itemsList.first { it.driverName == "H2" }
        assertEquals("H2 (In-Memory)", h2.displayName)
        assertEquals("IN_MEMORY", h2.connectionType)
        assertFalse(h2.requiresHost)
        assertFalse(h2.requiresPort)
        assertEquals(0, h2.defaultPort)
        assertFalse(h2.supportsUser)
        assertTrue(h2.supportsSchema)
        assertTrue(h2.capabilitiesList.contains("EMBEDDED_MODE"))
        // H2 完整实现用户/权限管理（CREATE/DELETE USER, GRANT/REVOKE, INFORMATION_SCHEMA.USERS）
        assertTrue(h2.capabilitiesList.contains("USERS"))
        assertTrue(h2.capabilitiesList.contains("PRIVILEGES"))
        // H2 不支持触发器
        assertFalse(h2.capabilitiesList.contains("TRIGGERS"))
    }

    @Test
    fun `DuckDB is EMBEDDED without host port user`() {
        val res = SystemHandler.listDrivers()
        val duck = res.itemsList.first { it.driverName == "Duckdb" }
        assertEquals("DuckDB (Embedded OLAP)", duck.displayName)
        assertEquals("EMBEDDED", duck.connectionType)
        assertFalse(duck.requiresHost)
        assertFalse(duck.requiresPort)
        assertFalse(duck.supportsUser)
        assertTrue(duck.capabilitiesList.contains("EMBEDDED_MODE"))
        assertFalse(duck.capabilitiesList.contains("USERS"))
        assertFalse(duck.capabilitiesList.contains("TRIGGERS"))
    }

    @Test
    fun `SQLite is FILE_BASED without host port user`() {
        val res = SystemHandler.listDrivers()
        val sqlite = res.itemsList.first { it.driverName == "Sqlite" }
        assertEquals("SQLite (Embedded)", sqlite.displayName)
        assertEquals("FILE_BASED", sqlite.connectionType)
        assertFalse(sqlite.requiresHost)
        assertFalse(sqlite.requiresPort)
        assertEquals(0, sqlite.defaultPort)
        assertFalse(sqlite.supportsUser)
        assertFalse(sqlite.supportsSchema)
        assertFalse(sqlite.supportsCrossDatabase)
        assertTrue(sqlite.jdbcUrlExample.startsWith("jdbc:sqlite:"))
        // SQLite 支持视图/索引/FK/导出；不支持用户/触发器/存储过程
        assertTrue(sqlite.capabilitiesList.contains("VIEWS"))
        assertTrue(sqlite.capabilitiesList.contains("INDEXES"))
        assertTrue(sqlite.capabilitiesList.contains("FOREIGN_KEYS"))
        assertTrue(sqlite.capabilitiesList.contains("EXPORT"))
        assertTrue(sqlite.capabilitiesList.contains("EMBEDDED_MODE"))
        assertFalse(sqlite.capabilitiesList.contains("USERS"))
        assertFalse(sqlite.capabilitiesList.contains("TRIGGERS"))
        assertFalse(sqlite.capabilitiesList.contains("ROUTINES"))
    }

    @Test
    fun `driverName list is sorted alphabetically for stable frontend ordering`() {
        val res = SystemHandler.listDrivers()
        val names = res.itemsList.map { it.driverName }
        assertEquals(names.sorted(), names, "itemsList 应该按 driverName 排序: $names")
    }

    @Test
    fun `jdbcDriverClassName is filled for each dialect`() {
        val res = SystemHandler.listDrivers()
        res.itemsList.forEach { info ->
            assertTrue(info.jdbcDriverClassName.isNotEmpty(),
                "jdbcDriverClassName 不能为空: ${info.driverName}")
        }
    }
}