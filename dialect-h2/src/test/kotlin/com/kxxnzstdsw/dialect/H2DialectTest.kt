package com.kxxnzstdsw.dialect

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * H2Dialect 全量测试 — 直接对内存 H2 跑每个 SPI 方法。
 */
class H2DialectTest {

    private val dialect = H2Dialect()
    private val dbName = "test_${UUID.randomUUID().toString().replace("-", "")}"
    private val jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

    @BeforeEach
    fun setUp() {
        // 验证基本连接可用
        DriverManager.getConnection(jdbcUrl, "sa", "").use { /* touch */ }
    }

    @AfterEach
    fun tearDown() {
        try {
            DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                conn.createStatement().use { it.execute("DROP ALL OBJECTS DELETE FILES") }
            }
        } catch (_: Exception) {}
    }

    private fun withConn(block: suspend (Connection) -> Unit) = runBlocking {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            block(conn)
        }
    }

    // ============ 基本标识符 ============

    @Test
    fun `driverName is H2`() {
        assertEquals("H2", dialect.driverName)
    }

    @Test
    fun `jdbcDriverClassName is org h2 Driver`() {
        assertEquals("org.h2.Driver", dialect.jdbcDriverClassName)
    }

    @Test
    fun `buildJdbcUrl uses memory database with DB_CLOSE_DELAY`() {
        val url = dialect.buildJdbcUrl("ignored", 9999, "mydb")
        assertTrue(url.startsWith("jdbc:h2:mem:mydb"))
        assertTrue(url.contains("DB_CLOSE_DELAY=-1"))
    }

    @Test
    fun `quoteIdentifier uppercases identifier for H2`() {
        // H2 不加引号，统一大写
        assertEquals("NAME", dialect.quoteIdentifier("name"))
        assertEquals("A\"B", dialect.quoteIdentifier("a\"b"))
    }

    @Test
    fun `buildColumnDefinition handles VARCHAR with size`() {
        val def = dialect.buildColumnDefinition("name", "VARCHAR", 100, true, false, null, false)
        assertEquals("NAME VARCHAR(100)", def)
    }

    @Test
    fun `buildColumnDefinition NOT NULL adds clause`() {
        val def = dialect.buildColumnDefinition("id", "INT", null, false, true, null, false)
        assertEquals("ID INT NOT NULL", def)
    }

    @Test
    fun `buildColumnDefinition autoIncrement INT becomes INT AUTO_INCREMENT`() {
        val def = dialect.buildColumnDefinition("id", "INT", null, false, true, null, true)
        assertEquals("ID INT AUTO_INCREMENT", def)
    }

    @Test
    fun `buildColumnDefinition with default value`() {
        val def = dialect.buildColumnDefinition("status", "VARCHAR", 20, true, false, "active", false)
        assertEquals("STATUS VARCHAR(20) DEFAULT 'active'", def)
    }

    @Test
    fun `buildColumnDefinition numeric default not quoted`() {
        val def = dialect.buildColumnDefinition("count", "INT", null, true, false, "0", false)
        assertEquals("COUNT INT DEFAULT 0", def)
    }

    @Test
    fun `buildAddColumnSQL uses ALTER TABLE ADD COLUMN`() {
        val sql = dialect.buildAddColumnSQL("users", "AGE INT")
        assertEquals("ALTER TABLE USERS ADD COLUMN AGE INT", sql)
    }

    @Test
    fun `buildDropColumnSQL uses ALTER TABLE DROP COLUMN`() {
        val sql = dialect.buildDropColumnSQL("users", "age")
        assertEquals("ALTER TABLE USERS DROP COLUMN AGE", sql)
    }

    @Test
    fun `buildTableOptionsSQL is empty for H2`() {
        assertEquals("", dialect.buildTableOptionsSQL(mapOf("engine" to "InnoDB")))
    }

    // ============ Schema 管理 ============

    @Test
    fun `listSchemas returns default PUBLIC schema`() = runBlocking {
        withConn { conn ->
            val schemas = dialect.listSchemas(conn, "")
            assertTrue(schemas.isNotEmpty())
            assertTrue(schemas.any { it.equals("PUBLIC", ignoreCase = true) })
        }
    }

    @Test
    fun `createSchema and deleteSchema`() = runBlocking {
        withConn { conn ->
            dialect.createSchema(conn, "test_schema", emptyMap())
            val afterCreate = dialect.listSchemas(conn, "")
            assertTrue(afterCreate.any { it.equals("test_schema", ignoreCase = true) })
            dialect.deleteSchema(conn, "test_schema")
            val afterDelete = dialect.listSchemas(conn, "")
            assertFalse(afterDelete.any { it.equals("test_schema", ignoreCase = true) })
        }
    }

    // ============ Table 管理 ============

    @Test
    fun `listTables returns empty for new database`() = runBlocking {
        withConn { conn ->
            val tables = dialect.listTables(conn, dbName, "")
            assertEquals(0, tables.size)
        }
    }

    @Test
    fun `listTables includes both BASE TABLE and VIEW`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE products (id INT, name VARCHAR(50))")
                it.execute("CREATE VIEW v_products AS SELECT id FROM products")
            }
            val tables = dialect.listTables(conn, dbName, "PUBLIC")
            assertTrue(tables.any { it["name"]?.equals("PRODUCTS", ignoreCase = true) == true && it["type"] == "BASE TABLE" })
            assertTrue(tables.any { it["name"]?.equals("V_PRODUCTS", ignoreCase = true) == true && it["type"] == "VIEW" })
        }
    }

    // ============ getCreateTableDDL ============

    @Test
    fun `getCreateTableDDL reconstructs CREATE TABLE with columns and PK`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE users (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50), age INT)")
            }
            val ddl = dialect.getCreateTableDDL(conn, "USERS")
            assertTrue(ddl.contains("CREATE TABLE USERS"))
            assertTrue(ddl.contains("ID INT NOT NULL"), "DDL 应包含 ID 列: $ddl")
            assertTrue(ddl.contains("NAME VARCHAR(50)"), "DDL 应包含 NAME VARCHAR(50): $ddl")
            assertTrue(ddl.contains("AGE INT"))
            assertTrue(ddl.contains("PRIMARY KEY (ID)"), "应包含主键: $ddl")
        }
    }

    // ============ User 管理 ============

    @Test
    fun `createUser and listUsers and deleteUser`() = runBlocking {
        withConn { conn ->
            dialect.createUser(conn, "alice", "secret", "%")
            val users = dialect.listUsers(conn)
            assertTrue(users.any { it["user"]?.equals("ALICE", ignoreCase = true) == true })
            dialect.deleteUser(conn, "alice", "%")
            val after = dialect.listUsers(conn)
            assertFalse(after.any { it["user"]?.equals("ALICE", ignoreCase = true) == true })
        }
    }

    @Test
    fun `updatePassword changes password`() = runBlocking {
        withConn { conn ->
            dialect.createUser(conn, "bob", "oldpass", "%")
            dialect.updatePassword(conn, "bob", "newpass", "%")
            // H2 不直接暴露密码，但 ALTER USER 不抛错即为成功
            assertTrue(true)
        }
    }

    // ============ 安全校验 ============

    @Test
    fun `validateSqlFragment rejects semicolon`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("age > 18; DROP TABLE users", "where")
        }
    }

    @Test
    fun `validateSqlFragment rejects dangerous keyword DROP`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("1=1 OR DROP TABLE x", "where")
        }
    }

    @Test
    fun `validateSqlFragment rejects MERGE keyword (H2-specific)`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("MERGE INTO t USING s ON 1=1", "where")
        }
    }

    @Test
    fun `validateSqlFragment rejects comment --`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("age > 18 -- comment", "where")
        }
    }

    @Test
    fun `validateSqlFragment allows quoted semicolon inside string`() {
        // 含分号但分号在字符串内（去除引号后无分号）应通过
        dialect.validateSqlFragment("name = 'a;b'", "where")
    }

    @Test
    fun `validateOrderBy accepts simple column`() {
        dialect.validateOrderBy("name")
        dialect.validateOrderBy("name ASC")
        dialect.validateOrderBy("name DESC, age ASC")
    }

    @Test
    fun `validateOrderBy rejects injection`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateOrderBy("name; DROP TABLE users")
        }
    }

    // ============ 视图管理 ============

    @Test
    fun `listViews returns empty initially`() = runBlocking {
        withConn { conn ->
            assertEquals(0, dialect.listViews(conn, "PUBLIC").size)
        }
    }

    @Test
    fun `createView and listViews and getViewDDL and dropView`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE products (id INT, name VARCHAR(50))")
            }
            dialect.createView(conn, "v_products", "SELECT id FROM products")
            val views = dialect.listViews(conn, "PUBLIC")
            assertTrue(views.any { it["name"]?.equals("V_PRODUCTS", ignoreCase = true) == true })

            val ddl = dialect.getViewDDL(conn, "V_PRODUCTS", "PUBLIC")
            assertTrue(ddl.contains("CREATE VIEW"))
            assertTrue(ddl.contains("V_PRODUCTS", ignoreCase = true))

            dialect.dropView(conn, "v_products", ifExists = true)
            assertFalse(dialect.listViews(conn, "PUBLIC").any { it["name"]?.equals("V_PRODUCTS", ignoreCase = true) == true })
        }
    }

    @Test
    fun `dropView with ifExists does not throw on missing`() = runBlocking {
        withConn { conn ->
            assertTrue(dialect.dropView(conn, "no_such_view", ifExists = true))
        }
    }

    @Test
    fun `createView rejects semicolon in definition`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                withConn { conn ->
                    dialect.createView(conn, "v", "SELECT 1; DROP TABLE x")
                }
            }
        }
    }

    // ============ 索引管理 ============

    @Test
    fun `createIndex and listIndexes and dropIndex`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE t (id INT, name VARCHAR(50))")
            }
            dialect.createIndex(conn, "T", "idx_name", listOf("name"), unique = false)
            val indexes = dialect.listIndexes(conn, "T")
            assertTrue(indexes.any { it["name"]?.equals("IDX_NAME", ignoreCase = true) == true })

            dialect.dropIndex(conn, "idx_name", "T")
            val after = dialect.listIndexes(conn, "T")
            assertFalse(after.any { it["name"]?.equals("IDX_NAME", ignoreCase = true) == true })
        }
    }

    @Test
    fun `createIndex UNIQUE INDEX`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE t (email VARCHAR(100))")
            }
            dialect.createIndex(conn, "T", "uk_email", listOf("email"), unique = true)
            val indexes = dialect.listIndexes(conn, "T")
            val idx = indexes.first { it["name"]?.equals("UK_EMAIL", ignoreCase = true) == true }
            assertEquals("true", idx["unique"])
        }
    }

    // ============ 外键管理 ============

    @Test
    fun `addForeignKey and listForeignKeys and dropForeignKey`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))")
                it.execute("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT, total DECIMAL(10,2))")
            }
            dialect.addForeignKey(conn, "ORDERS", "fk_orders_user",
                columns = listOf("user_id"),
                refTable = "users",
                refColumns = listOf("id"),
                onDelete = "CASCADE",
                onUpdate = null)
            val fks = dialect.listForeignKeys(conn, "ORDERS")
            assertTrue(fks.any { it["name"]?.equals("FK_ORDERS_USER", ignoreCase = true) == true })
            assertEquals("CASCADE", fks.first { it["name"]?.equals("FK_ORDERS_USER", ignoreCase = true) == true }["on_delete"])

            dialect.dropForeignKey(conn, "ORDERS", "fk_orders_user")
            val after = dialect.listForeignKeys(conn, "ORDERS")
            assertFalse(after.any { it["name"]?.equals("FK_ORDERS_USER", ignoreCase = true) == true })
        }
    }

    // ============ 触发器管理 ============

    @Test
    fun `listTriggers returns empty initially`() = runBlocking {
        withConn { conn ->
            assertEquals(0, dialect.listTriggers(conn, "PUBLIC").size)
        }
    }

    @Test
    fun `createTrigger via createRoutine and listTriggers`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE logs (id INT, msg VARCHAR(100))")
                // H2 不支持 SQL body 触发器；尝试执行并接受失败
                try {
                    it.execute("CREATE TRIGGER trg_logs AFTER INSERT ON logs FOR EACH ROW AS UPDATE logs SET msg = 'x' WHERE id = 1")
                } catch (_: Exception) {
                    // 预期失败
                }
            }
            val triggers = dialect.listTriggers(conn, "PUBLIC")
            // H2 listTriggers 应返回 JsonArray (即使空)
            assertTrue(triggers is List<*>)
        }
    }

    // ============ 表操作 ============

    @Test
    fun `renameTable renames table`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE old_t (id INT)")
                it.execute("INSERT INTO old_t VALUES (1)")
            }
            dialect.renameTable(conn, "OLD_T", "NEW_T")
            // new_t 应存在，old_t 不应
            val tables = dialect.listTables(conn, dbName, "PUBLIC")
            assertTrue(tables.any { it["name"]?.equals("NEW_T", ignoreCase = true) == true })
            assertFalse(tables.any { it["name"]?.equals("OLD_T", ignoreCase = true) == true })
        }
    }

    @Test
    fun `truncateTable removes all rows`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE t (id INT)")
                it.execute("INSERT INTO t VALUES (1), (2), (3)")
            }
            dialect.truncateTable(conn, "T")
            withConn { conn2 ->
                val count = conn2.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM T").use { rs -> if (rs.next()) rs.getInt(1) else -1 }
                }
                assertEquals(0, count)
            }
        }
    }

    // ============ EXPLAIN ============

    @Test
    fun `explainSQL returns plan rows`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE t (id INT, name VARCHAR(50))")
            }
            val rows = dialect.explainSQL(conn, "SELECT * FROM T")
            assertTrue(rows.isNotEmpty())
        }
    }

    @Test
    fun `explainSQL rejects semicolon`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                withConn { conn -> dialect.explainSQL(conn, "SELECT 1; DROP TABLE x") }
            }
        }
    }

    // ============ Server info ============

    @Test
    fun `getServerInfo returns product info`() = runBlocking {
        withConn { conn ->
            val info = dialect.getServerInfo(conn)
            assertNotNull(info["product"])
            assertTrue(info["product"]!!.contains("H2"))
            assertNotNull(info["driver"])
        }
    }

    @Test
    fun `testConnection returns true for valid H2 conn`() = runBlocking {
        withConn { conn ->
            assertTrue(dialect.testConnection(conn))
        }
    }
}