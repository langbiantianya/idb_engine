package com.kxxnzstdsw.dialect

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SQLiteDialect 全量单元测试 — 直接对内存 SQLite (`jdbc:sqlite::memory:`) 跑每个 SPI 方法。
 */
class SQLiteDialectTest {

    private val dialect = SQLiteDialect()
    private val jdbcUrl = "jdbc:sqlite::memory:"
    private lateinit var conn: Connection

    @BeforeEach
    fun setUp() {
        conn = DriverManager.getConnection(jdbcUrl)
    }

    @AfterEach
    fun tearDown() {
        try { conn.close() } catch (_: Exception) {}
    }

    private fun withConn(block: suspend (Connection) -> Unit) = runBlocking {
        block(conn)
    }

    private fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    // ============ 1. 身份与 URL ============

    @Test
    fun `driverName is Sqlite`() {
        assertEquals("Sqlite", dialect.driverName)
    }

    @Test
    fun `jdbcDriverClassName is org sqlite JDBC`() {
        assertEquals("org.sqlite.JDBC", dialect.jdbcDriverClassName)
    }

    @Test
    fun `buildJdbcUrl empty database returns in-memory url`() {
        val url = dialect.buildJdbcUrl("ignored", 9999, "")
        assertEquals("jdbc:sqlite::memory:", url)
    }

    @Test
    fun `buildJdbcUrl with memory token returns in-memory url`() {
        val url = dialect.buildJdbcUrl("ignored", 9999, ":memory:")
        assertEquals("jdbc:sqlite::memory:", url)
    }

    @Test
    fun `buildJdbcUrl with file path returns file url`(@TempDir tempDir: Path) {
        val dbFile = tempDir.resolve("test.db").toFile()
        val url = dialect.buildJdbcUrl("ignored", 9999, dbFile.absolutePath)
        assertEquals("jdbc:sqlite:${dbFile.absolutePath}", url)
    }

    @Test
    fun `host port user password ignored`() {
        val url = dialect.buildJdbcUrl("1.2.3.4", 12345, "/tmp/x.db")
        assertFalse(url.contains("1.2.3.4"))
        assertFalse(url.contains("12345"))
    }

    // ============ 2. 标识符引用 ============

    @Test
    fun `quoteIdentifier wraps with double quotes`() {
        assertEquals("\"users\"", dialect.quoteIdentifier("users"))
    }

    @Test
    fun `quoteIdentifier escapes inner double quotes`() {
        assertEquals("\"weird\"\"name\"", dialect.quoteIdentifier("weird\"name"))
    }

    // ============ 3. 流式配置 ============

    @Test
    fun `configureConnectionForStreaming returns current autoCommit`() {
        val original = dialect.configureConnectionForStreaming(conn)
        assertEquals(conn.autoCommit, original)
    }

    // ============ 4. Schema / Database ============

    @Test
    fun `listDatabases returns single element for in-memory`() = withConn { c ->
        val dbs = dialect.listDatabases(c)
        assertEquals(1, dbs.size)
        assertEquals(":memory:", dbs[0])
    }

    @Test
    fun `listSchemas returns main and temp`() = withConn { c ->
        val schemas = dialect.listSchemas(c, "")
        assertTrue(schemas.contains("main"))
    }

    @Test
    fun `deleteSchema rejects main schema`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { dialect.deleteSchema(conn, "main", ifExists = true) }
        }
    }

    // ============ 5. Table / Column ============

    @Test
    fun `listTables returns empty for fresh database`() = withConn { c ->
        val tables = dialect.listTables(c, "", "")
        assertEquals(0, tables.size)
    }

    @Test
    fun `listTables returns created table`() = withConn { c ->
        exec("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
        val tables = dialect.listTables(c, "", "")
        assertEquals(1, tables.size)
        assertEquals("users", tables[0]["name"])
        assertEquals("TABLE", tables[0]["type"])
    }

    @Test
    fun `listColumns returns columns with PK flag`() = withConn { c ->
        exec("CREATE TABLE products (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, price REAL)")
        val cols = dialect.listColumns(c, "", "", "products")
        assertEquals(3, cols.size)
        val id = cols.first { it["name"] == "id" }
        assertEquals(true, id["isPrimaryKey"])
        val name = cols.first { it["name"] == "name" }
        assertEquals(false, name["nullable"])
        assertEquals("TEXT", name["type"])
    }

    // ============ 6. DDL 构造 ============

    @Test
    fun `buildColumnDefinition basic VARCHAR`() {
        val col = dialect.buildColumnDefinition("name", "VARCHAR", 255, nullable = false, isPrimaryKey = false, defaultValue = null)
        assertEquals("\"name\" VARCHAR(255) NOT NULL", col)
    }

    @Test
    fun `buildColumnDefinition INTEGER PRIMARY KEY AUTOINCREMENT for autoIncrement PK`() {
        val col = dialect.buildColumnDefinition("id", "INT", null, nullable = false, isPrimaryKey = true, defaultValue = null, autoIncrement = true)
        assertEquals("\"id\" INTEGER PRIMARY KEY AUTOINCREMENT", col)
    }

    @Test
    fun `buildColumnDefinition with default string literal`() {
        val col = dialect.buildColumnDefinition("status", "TEXT", null, nullable = false, isPrimaryKey = false, defaultValue = "active")
        assertEquals("\"status\" TEXT NOT NULL DEFAULT 'active'", col)
    }

    @Test
    fun `buildColumnDefinition with default numeric literal`() {
        val col = dialect.buildColumnDefinition("count", "INT", null, nullable = true, isPrimaryKey = false, defaultValue = "0")
        assertEquals("\"count\" INT DEFAULT 0", col)
    }

    @Test
    fun `buildColumnDefinition with default CURRENT_TIMESTAMP`() {
        val col = dialect.buildColumnDefinition("created", "TIMESTAMP", null, nullable = false, isPrimaryKey = false, defaultValue = "CURRENT_TIMESTAMP")
        assertEquals("\"created\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP", col)
    }

    @Test
    fun `buildAddColumnSQL uses ALTER TABLE ADD COLUMN`() {
        val sql = dialect.buildAddColumnSQL("users", "\"age\" INT")
        assertEquals("ALTER TABLE \"users\" ADD COLUMN \"age\" INT", sql)
    }

    @Test
    fun `buildDropColumnSQL uses ALTER TABLE DROP COLUMN`() {
        val sql = dialect.buildDropColumnSQL("users", "age")
        assertEquals("ALTER TABLE \"users\" DROP COLUMN \"age\"", sql)
    }

    @Test
    fun `buildModifyColumnSQL rename only`() {
        val sql = dialect.buildModifyColumnSQL("users", "old_name", null, null, true, null, newName = "new_name")
        assertEquals("ALTER TABLE \"users\" RENAME COLUMN \"old_name\" TO \"new_name\"", sql)
    }

    @Test
    fun `buildModifyColumnSQL change type throws`() {
        assertFailsWith<IllegalArgumentException> {
            dialect.buildModifyColumnSQL("users", "age", "BIGINT", null, true, null, newName = null)
        }
    }

    @Test
    fun `buildTableOptionsSQL returns empty`() {
        assertEquals("", dialect.buildTableOptionsSQL(mapOf("engine" to "InnoDB")))
    }

    // ============ 7. getCreateTableDDL ============

    @Test
    fun `getCreateTableDDL returns original DDL`() = withConn { c ->
        exec("CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT)")
        val ddl = dialect.getCreateTableDDL(c, "products")
        assertTrue(ddl.contains("CREATE TABLE"), "DDL must contain CREATE TABLE: $ddl")
        assertTrue(ddl.contains("products"), "DDL must contain table name: $ddl")
        assertTrue(ddl.contains("id"), "DDL must contain column id: $ddl")
    }

    @Test
    fun `getCreateTableDDL throws for missing table`() = withConn { c ->
        assertFailsWith<IllegalArgumentException> {
            runBlocking { dialect.getCreateTableDDL(c, "nonexistent") }
        }
    }

    // ============ 8. SQL 安全校验 ============

    @Test
    fun `validateSqlFragment rejects semicolon`() {
        assertFailsWith<IllegalArgumentException> {
            dialect.validateSqlFragment("id = 1; DROP TABLE x", "WHERE")
        }
    }

    @Test
    fun `validateSqlFragment rejects line comment`() {
        assertFailsWith<IllegalArgumentException> {
            dialect.validateSqlFragment("id = 1 -- comment\n", "WHERE")
        }
    }

    @Test
    fun `validateSqlFragment rejects block comment`() {
        assertFailsWith<IllegalArgumentException> {
            dialect.validateSqlFragment("id /* hack */ = 1", "WHERE")
        }
    }

    @Test
    fun `validateSqlFragment rejects dangerous keyword`() {
        assertFailsWith<IllegalArgumentException> {
            dialect.validateSqlFragment("1; ATTACH DATABASE '/tmp/x.db' AS evil", "WHERE")
        }
    }

    @Test
    fun `validateSqlFragment allows quoted strings with semicolon`() {
        dialect.validateSqlFragment("name = 'a;b;c'", "WHERE")
    }

    @Test
    fun `validateOrderBy accepts single column ASC`() {
        dialect.validateOrderBy("id ASC")
    }

    @Test
    fun `validateOrderBy accepts quoted identifier`() {
        dialect.validateOrderBy("\"createdAt\" DESC")
    }

    @Test
    fun `validateOrderBy rejects garbage`() {
        assertFailsWith<IllegalArgumentException> {
            dialect.validateOrderBy("name; DROP TABLE x")
        }
    }

    // ============ 9. 不支持的功能 ============

    @Test
    fun `createUser throws`() {
        assertFailsWith<UnsupportedOperationException> {
            runBlocking { dialect.createUser(conn, "u", "p", "%") }
        }
    }

    @Test
    fun `deleteUser throws`() {
        assertFailsWith<UnsupportedOperationException> {
            runBlocking { dialect.deleteUser(conn, "u", "%") }
        }
    }

    @Test
    fun `listUsers throws`() {
        assertFailsWith<UnsupportedOperationException> {
            runBlocking { dialect.listUsers(conn) }
        }
    }

    @Test
    fun `updatePrivileges throws`() {
        assertFailsWith<UnsupportedOperationException> {
            runBlocking { dialect.updatePrivileges(conn, "u", "", listOf("SELECT"), true, null, false) }
        }
    }

    @Test
    fun `listRoutines throws`() {
        assertFailsWith<UnsupportedOperationException> {
            runBlocking { dialect.listRoutines(conn, "main") }
        }
    }

    @Test
    fun `callRoutine throws`() {
        assertFailsWith<UnsupportedOperationException> {
            runBlocking { dialect.callRoutine(conn, "f", "FUNCTION", "", emptyList()) }
        }
    }

    @Test
    fun `listTriggers throws (default impl)`() {
        assertFailsWith<UnsupportedOperationException> {
            runBlocking { dialect.listTriggers(conn, "main") }
        }
    }

    // ============ 10. View / Index / FK ============

    @Test
    fun `listViews returns created view`() = withConn { c ->
        exec("CREATE TABLE t (id INT)")
        exec("CREATE VIEW v AS SELECT id FROM t")
        val views = dialect.listViews(c, "main")
        assertEquals(1, views.size)
        assertEquals("v", views[0]["name"])
    }

    @Test
    fun `createView then getDDL returns DDL`() = withConn { c ->
        exec("CREATE TABLE t (id INT, name TEXT)")
        dialect.createView(c, "v_t", "SELECT id, name FROM t")
        val ddl = dialect.getViewDDL(c, "v_t", "main")
        assertTrue(ddl.contains("CREATE VIEW"), "DDL: $ddl")
        assertTrue(ddl.contains("v_t"), "DDL: $ddl")
    }

    @Test
    fun `dropView with ifExists does not throw on missing`() = withConn { c ->
        dialect.dropView(c, "nonexistent", ifExists = true)
    }

    @Test
    fun `dropView without ifExists throws on missing`() = withConn { c ->
        assertFailsWith<Exception> {
            runBlocking { dialect.dropView(c, "nonexistent", ifExists = false) }
        }
    }

    @Test
    fun `listIndexes returns created indexes`() = withConn { c ->
        exec("CREATE TABLE t (id INT, name TEXT)")
        dialect.createIndex(c, "t", "idx_t_name", listOf("name"), unique = false)
        val idxs = dialect.listIndexes(c, "t")
        assertEquals(1, idxs.size)
        assertEquals("idx_t_name", idxs[0]["name"])
        assertEquals("false", idxs[0]["unique"])
    }

    @Test
    fun `createIndex UNIQUE then list shows unique true`() = withConn { c ->
        exec("CREATE TABLE t (email TEXT)")
        dialect.createIndex(c, "t", "idx_email", listOf("email"), unique = true, ifNotExists = true)
        val idxs = dialect.listIndexes(c, "t")
        assertEquals("true", idxs[0]["unique"])
    }

    @Test
    fun `addForeignKey then listForeignKeys returns FK`() = withConn { c ->
        exec("CREATE TABLE users (id INTEGER PRIMARY KEY)")
        exec("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER)")
        dialect.addForeignKey(c, "orders", "fk_orders_user",
            columns = listOf("user_id"), refTable = "users", refColumns = listOf("id"),
            onDelete = "CASCADE", onUpdate = "NO ACTION")
        val fks = dialect.listForeignKeys(c, "orders")
        assertEquals(1, fks.size)
        assertEquals("users", fks[0]["ref_table"])
        assertEquals("user_id", fks[0]["columns"])
    }

    // ============ 11. Table Operations ============

    @Test
    fun `renameTable works`() = withConn { c ->
        exec("CREATE TABLE old (id INT)")
        dialect.renameTable(c, "old", "new")
        val tables = dialect.listTables(c, "", "")
        assertTrue(tables.any { it["name"] == "new" })
    }

    @Test
    fun `truncateTable empties data but keeps schema`() = withConn { c ->
        exec("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, v TEXT)")
        exec("INSERT INTO t (v) VALUES ('a'), ('b')")
        dialect.truncateTable(c, "t")
        conn.createStatement().use { rs ->
            rs.executeQuery("SELECT COUNT(*) FROM t").use { cnt ->
                cnt.next()
                assertEquals(0, cnt.getInt(1))
            }
        }
    }

    // ============ 12. EXPLAIN ============

    @Test
    fun `explainSQL returns plan rows`() = withConn { c ->
        exec("CREATE TABLE t (id INT)")
        val plan = dialect.explainSQL(c, "SELECT id FROM t")
        assertTrue(plan.isNotEmpty(), "EXPLAIN 必须至少返回一行")
    }

    // ============ 13. Server Info ============

    @Test
    fun `getServerInfo returns SQLite product`() = withConn { c ->
        val info = dialect.getServerInfo(c)
        assertEquals("SQLite", info["product"])
        assertEquals("embedded", info["mode"])
        assertNotNull(info["version"])
        assertTrue((info["version"] ?: "").isNotEmpty())
    }

    // ============ 14. 元数据 (v2.8) ============

    @Test
    fun `displayName is human readable`() {
        assertEquals("SQLite (Embedded)", dialect.displayName)
    }

    @Test
    fun `connectionType is FILE_BASED`() {
        assertEquals(ConnectionType.FILE_BASED, dialect.connectionType)
    }

    @Test
    fun `host and port not required`() {
        assertFalse(dialect.requiresHost)
        assertFalse(dialect.requiresPort)
    }

    @Test
    fun `user and password not supported`() {
        assertFalse(dialect.supportsUser)
        assertFalse(dialect.supportsPassword)
    }

    @Test
    fun `schema and cross database not supported`() {
        assertFalse(dialect.supportsSchema)
        assertFalse(dialect.supportsCrossDatabase)
    }

    @Test
    fun `capabilities includes views and indexes`() {
        assertTrue(dialect.capabilities.contains(DialectCapability.VIEWS))
        assertTrue(dialect.capabilities.contains(DialectCapability.INDEXES))
        assertTrue(dialect.capabilities.contains(DialectCapability.FOREIGN_KEYS))
        assertTrue(dialect.capabilities.contains(DialectCapability.EMBEDDED_MODE))
    }

    @Test
    fun `capabilities excludes users and triggers`() {
        assertFalse(dialect.capabilities.contains(DialectCapability.USERS))
        assertFalse(dialect.capabilities.contains(DialectCapability.TRIGGERS))
        assertFalse(dialect.capabilities.contains(DialectCapability.ROUTINES))
    }

    @Test
    fun `jdbcUrlExample is present`() {
        assertTrue(dialect.jdbcUrlExample.startsWith("jdbc:sqlite:"))
    }

    @Test
    fun `defaultPort is null`() {
        assertEquals(null, dialect.defaultPort)
    }
}