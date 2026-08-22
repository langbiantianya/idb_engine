package com.kxxnzstdsw.dialect

import kotlinx.coroutines.runBlocking
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DuckDBDialect 全量单元测试 — 直接对内存 DuckDB 跑每个 SPI 方法。
 *
 * DuckDB 的内存模式 (`jdbc:duckdb:`) 每连接私有 DB，因此每个测试方法独立创建连接即可。
 */
class DuckDBDialectTest {

    private val dialect = DuckDBDialect()
    private val jdbcUrl = "jdbc:duckdb:"
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

    // ============ 1. 身份与 URL ============

    @Test
    fun `driverName is Duckdb`() {
        assertEquals("Duckdb", dialect.driverName)
    }

    @Test
    fun `jdbcDriverClassName is org duckdb DuckDBDriver`() {
        assertEquals("org.duckdb.DuckDBDriver", dialect.jdbcDriverClassName)
    }

    @Test
    fun `buildJdbcUrl empty database returns in-memory url`() {
        val url = dialect.buildJdbcUrl("ignored", 9999, "")
        assertEquals("jdbc:duckdb:", url)
    }

    @Test
    fun `buildJdbcUrl with file path returns file url`() {
        val url = dialect.buildJdbcUrl("ignored", 9999, "/tmp/data.duckdb")
        assertEquals("jdbc:duckdb:/tmp/data.duckdb", url)
    }

    @Test
    fun `buildJdbcUrl with CSV path returns file url`() {
        val url = dialect.buildJdbcUrl("ignored", 9999, "/data/users.csv")
        assertEquals("jdbc:duckdb:/data/users.csv", url)
    }

    @Test
    fun `buildJdbcUrl with Parquet path returns file url`() {
        val url = dialect.buildJdbcUrl("ignored", 9999, "/data/events.parquet")
        assertEquals("jdbc:duckdb:/data/events.parquet", url)
    }

    @Test
    fun `buildJdbcUrl with JSON path returns file url`() {
        val url = dialect.buildJdbcUrl("ignored", 9999, "/data/payload.json")
        assertEquals("jdbc:duckdb:/data/payload.json", url)
    }

    @Test
    fun `buildJdbcUrl with Excel path returns temp duckdb url`(@TempDir tempDir: Path) {
        // ExcelToDuckDbCache 要求文件真实存在；此处创建空 .xlsx 文件即可
        val excelFile = tempDir.resolve("report.xlsx").toFile()
        XSSFWorkbook().use { wb ->
            wb.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("hi")
            FileOutputStream(excelFile).use { fos -> wb.write(fos) }
        }
        val url = dialect.buildJdbcUrl("ignored", 9999, excelFile.absolutePath)
        assertTrue(url.startsWith("jdbc:duckdb:"), "URL 必须以 jdbc:duckdb: 开头: $url")
        assertTrue(url.endsWith(".duckdb"), "Excel 转换后必须是 .duckdb 文件: $url")
        assertFalse(url.endsWith(".xlsx"), "Excel 不应保留原后缀: $url")
    }

    @Test
    fun `buildJdbcUrl ignores host and port completely`() {
        assertEquals(
            dialect.buildJdbcUrl("host1", 1234, "test.duckdb"),
            dialect.buildJdbcUrl("host2", 5678, "test.duckdb")
        )
    }

    // ============ 2. quoteIdentifier ============

    @Test
    fun `quoteIdentifier uses double quotes for standard identifiers`() {
        assertEquals("\"name\"", dialect.quoteIdentifier("name"))
        assertEquals("\"UserName\"", dialect.quoteIdentifier("UserName"))
    }

    @Test
    fun `quoteIdentifier escapes embedded double quotes`() {
        assertEquals("\"a\"\"b\"", dialect.quoteIdentifier("a\"b"))
    }

    // ============ 3. configureConnectionForStreaming / setSearchPath ============

    @Test
    fun `configureConnectionForStreaming returns current autoCommit`() {
        val original = dialect.configureConnectionForStreaming(conn)
        assertEquals(true, original)
        dialect.restoreConnectionAfterStreaming(conn, original)
        assertEquals(true, conn.autoCommit)
    }

    @Test
    fun `setSearchPath issues SET schema statement`() {
        dialect.setSearchPath(conn, "main")
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE main.test_scoped (id INTEGER)")
            stmt.execute("DROP TABLE main.test_scoped")
        }
    }

    @Test
    fun `setSearchPath with blank schema is no-op`() {
        dialect.setSearchPath(conn, "")
        dialect.setSearchPath(conn, "   ")
    }

    // ============ 4. Schema / Database 导航 ============

    @Test
    fun `listDatabases returns main by default`() = withConn { c ->
        val dbs = dialect.listDatabases(c)
        assertTrue(dbs.contains("main"), "应有 main 库: $dbs")
    }

    @Test
    fun `listSchemas returns main by default`() = withConn { c ->
        val schemas = dialect.listSchemas(c, "main")
        assertTrue(schemas.isNotEmpty(), "schema 列表不应为空")
    }

    @Test
    fun `createSchema and deleteSchema roundtrip`() = withConn { c ->
        dialect.createSchema(c, "analytics", emptyMap(), false)
        val schemas = dialect.listSchemas(c, "main")
        assertTrue(schemas.any { it.equals("analytics", ignoreCase = true) }, "应包含新建 schema: $schemas")
        dialect.deleteSchema(c, "analytics", false)
        val after = dialect.listSchemas(c, "main")
        assertFalse(after.any { it.equals("analytics", ignoreCase = true) }, "应已删除: $after")
    }

    @Test
    fun `createSchema with ifNotExists does not throw on duplicate`() = withConn { c ->
        dialect.createSchema(c, "sc1", emptyMap(), true)
        dialect.createSchema(c, "sc1", emptyMap(), true)
        dialect.deleteSchema(c, "sc1", true)
    }

    @Test
    fun `deleteSchema with ifExists does not throw on missing`() = withConn { c ->
        dialect.deleteSchema(c, "nonexistent_schema", true)
    }

    @Test
    fun `createSchema rejects blank name`() = assertThrows<IllegalArgumentException> {
        runBlocking { dialect.createSchema(conn, "", emptyMap(), false) }
    }

    @Test
    fun `createSchema rejects semicolon injection`() = assertThrows<IllegalArgumentException> {
        runBlocking { dialect.createSchema(conn, "bad;name", emptyMap(), false) }
    }

    // ============ 5. Table DDL ============

    @Test
    fun `listTables returns empty for new schema`() = withConn { c ->
        val tables = dialect.listTables(c, "main", "main")
        assertTrue(tables.isEmpty(), "新建 schema 应无表")
    }

    @Test
    fun `listTables returns tables in main schema`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.t1 (id INTEGER, name VARCHAR)") }
        c.createStatement().use { it.execute("CREATE TABLE main.t2 (id INTEGER)") }
        val tables = dialect.listTables(c, "main", "main")
        val names = tables.map { it["name"] }
        assertTrue(names.any { it.equals("t1", ignoreCase = true) }, "应包含 t1: $names")
        assertTrue(names.any { it.equals("t2", ignoreCase = true) }, "应包含 t2: $names")
    }

    @Test
    fun `listColumns returns column metadata`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.products (id INTEGER PRIMARY KEY, name VARCHAR(100) NOT NULL, price DECIMAL(10,2))") }
        val cols = dialect.listColumns(c, "main", "main", "products")
        assertEquals(3, cols.size)
        val idCol = cols.first { (it["name"] as String).equals("id", ignoreCase = true) }
        assertEquals(true, idCol["isPrimaryKey"], "id 应该是主键")
        val nameCol = cols.first { (it["name"] as String).equals("name", ignoreCase = true) }
        assertEquals(false, nameCol["nullable"], "name 应该 NOT NULL")
    }

    // ============ 6. DDL 构造 ============

    @Test
    fun `buildColumnDefinition handles VARCHAR with size`() {
        val def = dialect.buildColumnDefinition("name", "VARCHAR", 100, true, false, null, false)
        assertEquals("\"name\" VARCHAR(100)", def)
    }

    @Test
    fun `buildColumnDefinition NOT NULL adds clause`() {
        val def = dialect.buildColumnDefinition("id", "INTEGER", null, false, true, null, false)
        assertEquals("\"id\" INTEGER NOT NULL", def)
    }

    @Test
    fun `buildColumnDefinition autoIncrement without PK uses GENERATED BY DEFAULT AS IDENTITY`() {
        // 自增非主键：列自带 IDENTITY
        val def = dialect.buildColumnDefinition("seq", "INTEGER", null, false, false, null, true)
        assertTrue(def.contains("GENERATED BY DEFAULT AS IDENTITY"), "应该是 IDENTITY: $def")
    }

    @Test
    fun `buildColumnDefinition autoIncrement with PK uses DEFAULT nextval (sequence pre-created)`() {
        // 自增主键：DuckDB 不接受 IDENTITY + 表级 PK，用 SEQUENCE+DEFAULT+表级 PK 兜底
        val def = dialect.buildColumnDefinition("id", "INTEGER", null, false, true, null, true)
        assertEquals("\"id\" INTEGER DEFAULT nextval('seq_id')", def)
    }

    @Test
    fun `buildPreCreateStatements emits CREATE SEQUENCE for each autoIncrement column`() {
        val stmts = dialect.buildPreCreateStatements("\"users\"", listOf("id"))
        assertEquals(1, stmts.size)
        assertEquals("CREATE SEQUENCE IF NOT EXISTS seq_id START 1", stmts[0])
    }

    @Test
    fun `buildColumnDefinition with VARCHAR default value`() {
        val def = dialect.buildColumnDefinition("status", "VARCHAR", 20, true, false, "active", false)
        assertEquals("\"status\" VARCHAR(20) DEFAULT 'active'", def)
    }

    @Test
    fun `buildColumnDefinition numeric default not quoted`() {
        val def = dialect.buildColumnDefinition("count", "INTEGER", null, true, false, "0", false)
        assertEquals("\"count\" INTEGER DEFAULT 0", def)
    }

    @Test
    fun `buildColumnDefinition CURRENT_TIMESTAMP default not quoted`() {
        val def = dialect.buildColumnDefinition("created_at", "TIMESTAMP", null, true, false, "CURRENT_TIMESTAMP", false)
        assertEquals("\"created_at\" TIMESTAMP DEFAULT CURRENT_TIMESTAMP", def)
    }

    @Test
    fun `buildAddColumnSQL produces ALTER TABLE ADD COLUMN`() {
        val sql = dialect.buildAddColumnSQL("users", "\"email\" VARCHAR(100)")
        assertEquals("ALTER TABLE \"users\" ADD COLUMN \"email\" VARCHAR(100)", sql)
    }

    @Test
    fun `buildDropColumnSQL produces ALTER TABLE DROP COLUMN`() {
        val sql = dialect.buildDropColumnSQL("users", "email")
        assertEquals("ALTER TABLE \"users\" DROP COLUMN \"email\"", sql)
    }

    @Test
    fun `buildModifyColumnSQL with type uses SET DATA TYPE`() {
        val sql = dialect.buildModifyColumnSQL("users", "age", "BIGINT", null, true, null, null)
        assertEquals("ALTER TABLE \"users\" ALTER COLUMN \"age\" SET DATA TYPE BIGINT", sql)
    }

    @Test
    fun `buildModifyColumnSQL with rename uses RENAME COLUMN`() {
        val sql = dialect.buildModifyColumnSQL("users", "old_name", null, null, true, null, "new_name")
        assertEquals("ALTER TABLE \"users\" RENAME COLUMN \"old_name\" TO \"new_name\"", sql)
    }

    @Test
    fun `buildModifyColumnSQL with SET NOT NULL`() {
        val sql = dialect.buildModifyColumnSQL("users", "name", null, null, false, null, null)
        assertEquals("ALTER TABLE \"users\" ALTER COLUMN \"name\" SET NOT NULL", sql)
    }

    @Test
    fun `buildModifyColumnSQL with DROP NOT NULL`() {
        val sql = dialect.buildModifyColumnSQL("users", "name", null, null, true, null, null)
        assertEquals("ALTER TABLE \"users\" ALTER COLUMN \"name\" DROP NOT NULL", sql)
    }

    @Test
    fun `buildTableOptionsSQL returns empty for DuckDB`() {
        assertEquals("", dialect.buildTableOptionsSQL(mapOf("engine" to "InnoDB")))
    }

    @Test
    fun `buildPostCreateStatements emits COMMENT ON TABLE`() {
        val stmts = dialect.buildPostCreateStatements("users", mapOf("comment" to "user table"))
        assertEquals(1, stmts.size)
        assertEquals("COMMENT ON TABLE \"users\" IS 'user table'", stmts[0])
    }

    // ============ 7. getCreateTableDDL ============

    @Test
    fun `getCreateTableDDL returns DuckDB-native SHOW CREATE output`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.demo (id INTEGER PRIMARY KEY, name VARCHAR(50))") }
        val ddl = dialect.getCreateTableDDL(c, "demo")
        assertTrue(ddl.contains("CREATE TABLE"), "DDL 应包含 CREATE TABLE: $ddl")
        assertTrue(ddl.contains("demo"), "DDL 应包含表名: $ddl")
    }

    @Test
    fun `getCreateTableDDL throws for missing table`() = assertThrows<IllegalArgumentException> {
        runBlocking { dialect.getCreateTableDDL(conn, "missing_table_xyz") }
    }

    // ============ 8. validateSqlFragment / validateOrderBy ============

    @Test
    fun `validateSqlFragment accepts benign where`() {
        dialect.validateSqlFragment("age > 18 AND name = 'alice'", "where")
    }

    @Test
    fun `validateSqlFragment rejects semicolon`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("age > 18; DROP TABLE users", "where")
        }
    }

    @Test
    fun `validateSqlFragment rejects double-dash comment`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("age > 18 -- comment\n", "where")
        }
    }

    @Test
    fun `validateSqlFragment rejects block comment`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("age > 18 /* hack */", "where")
        }
    }

    @Test
    fun `validateSqlFragment rejects INSERT keyword outside quotes`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("INSERT INTO x VALUES(1)", "where")
        }
    }

    @Test
    fun `validateSqlFragment allows INSERT inside quoted string`() {
        dialect.validateSqlFragment("name = 'INSERT INTO x'", "where")
    }

    @Test
    fun `validateSqlFragment rejects DuckDB-specific INSTALL keyword`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("INSTALL excel", "where")
        }
    }

    @Test
    fun `validateSqlFragment rejects DuckDB-specific ATTACH keyword`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("ATTACH 'other.db'", "where")
        }
    }

    @Test
    fun `validateSqlFragment rejects DuckDB-specific COPY keyword`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateSqlFragment("age > 18 OR COPY x FROM y", "where")
        }
    }

    @Test
    fun `validateOrderBy accepts simple identifier`() {
        dialect.validateOrderBy("id")
    }

    @Test
    fun `validateOrderBy accepts with DESC`() {
        dialect.validateOrderBy("id DESC")
    }

    @Test
    fun `validateOrderBy accepts quoted identifier with DESC`() {
        dialect.validateOrderBy("\"createdAt\" DESC")
    }

    @Test
    fun `validateOrderBy accepts multiple columns`() {
        dialect.validateOrderBy("id, name DESC")
    }

    @Test
    fun `validateOrderBy rejects function call`() {
        assertThrows<IllegalArgumentException> {
            dialect.validateOrderBy("RAND()")
        }
    }

    // ============ 9. 用户/权限/触发器 — 不支持 ============

    @Test
    fun `listUsers throws UnsupportedOperationException`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.listUsers(conn) }
    }

    @Test
    fun `createUser throws UnsupportedOperationException`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.createUser(conn, "alice", "secret", "%") }
    }

    @Test
    fun `deleteUser throws UnsupportedOperationException`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.deleteUser(conn, "alice", "%") }
    }

    @Test
    fun `updatePassword throws UnsupportedOperationException`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.updatePassword(conn, "alice", "secret", "%") }
    }

    @Test
    fun `updatePrivileges throws UnsupportedOperationException`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.updatePrivileges(conn, "alice", "public", listOf("SELECT"), true, null, false) }
    }

    @Test
    fun `listPrivileges throws UnsupportedOperationException`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.listPrivileges(conn, "alice", "%") }
    }

    @Test
    fun `listAllGrants throws UnsupportedOperationException`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.listAllGrants(conn, "alice", "%") }
    }

    @Test
    fun `listTriggers throws UnsupportedOperationException`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.listTriggers(conn, "main") }
    }

    @Test
    fun `getTriggerDDL throws UnsupportedOperationException`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.getTriggerDDL(conn, "trg", "main") }
    }

    // ============ 10. Views ============

    @Test
    fun `createView and listViews and dropView roundtrip`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.users (id INTEGER, name VARCHAR)") }
        dialect.createView(c, "v_users", "SELECT id, name FROM main.users WHERE id > 0")
        val views = dialect.listViews(c, "main")
        assertTrue(views.any { (it["name"] as String).equals("v_users", ignoreCase = true) }, "应包含新建视图: $views")
        dialect.dropView(c, "v_users", false)
        val after = dialect.listViews(c, "main")
        assertFalse(after.any { (it["name"] as String).equals("v_users", ignoreCase = true) }, "应已删除")
    }

    @Test
    fun `createView rejects semicolon in definition`() = assertThrows<IllegalArgumentException> {
        runBlocking { dialect.createView(conn, "v1", "SELECT 1; DROP TABLE x") }
    }

    @Test
    fun `dropView with ifExists does not throw on missing`() = withConn { c ->
        dialect.dropView(c, "nonexistent_view_xyz", true)
    }

    @Test
    fun `getViewDDL returns SHOW CREATE VIEW output`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.t (id INTEGER)") }
        c.createStatement().use { it.execute("CREATE VIEW main.v AS SELECT id FROM main.t") }
        val ddl = dialect.getViewDDL(c, "v", "main")
        assertTrue(ddl.contains("CREATE"), "DDL 应包含 CREATE: $ddl")
        assertTrue(ddl.contains("v"), "DDL 应包含视图名: $ddl")
    }

    @Test
    fun `getViewDDL throws for missing view`() = assertThrows<IllegalArgumentException> {
        runBlocking { dialect.getViewDDL(conn, "missing_view_xyz", "main") }
    }

    // ============ 11. Indexes ============

    @Test
    fun `createIndex and listIndexes roundtrip`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.t (id INTEGER, email VARCHAR(100))") }
        dialect.createIndex(c, "t", "idx_email", listOf("email"), unique = false, ifNotExists = false)
        val indexes = dialect.listIndexes(c, "t")
        assertTrue(indexes.any { (it["name"] as String).equals("idx_email", ignoreCase = true) }, "应包含新建索引: $indexes")
    }

    @Test
    fun `createUniqueIndex with UNIQUE flag`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.t (id INTEGER, email VARCHAR(100))") }
        dialect.createIndex(c, "t", "uniq_email", listOf("email"), unique = true, ifNotExists = false)
        val indexes = dialect.listIndexes(c, "t")
        val uniq = indexes.first { (it["name"] as String).equals("uniq_email", ignoreCase = true) }
        assertEquals("true", uniq["unique"], "应为 unique: $uniq")
    }

    @Test
    fun `createIndex with ifNotExists does not throw on duplicate`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.t (id INTEGER, name VARCHAR)") }
        dialect.createIndex(c, "t", "idx_name", listOf("name"), false, true)
        dialect.createIndex(c, "t", "idx_name", listOf("name"), false, true)
        dialect.dropIndex(c, "idx_name", null, true)
    }

    @Test
    fun `dropIndex with ifExists does not throw on missing`() = withConn { c ->
        dialect.dropIndex(c, "nonexistent_idx_xyz", null, true)
    }

    // ============ 12. Foreign Keys ============

    @Test
    fun `addForeignKey and listForeignKeys roundtrip`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.parent (id INTEGER PRIMARY KEY)") }
        c.createStatement().use { it.execute("CREATE TABLE main.child (id INTEGER, parent_id INTEGER)") }
        dialect.addForeignKey(c, "child", "fk_child_parent", listOf("parent_id"), "parent", listOf("id"), "CASCADE", null)
        val fks = dialect.listForeignKeys(c, "child")
        // DuckDB 会忽略 CONSTRAINT 子句中的名称，自动生成 <table>_<cols>_fkey；按列匹配查找
        assertTrue(
            fks.any { (it["columns"] as String).split(",").map { c0 -> c0.trim().lowercase() }.contains("parent_id") && (it["ref_table"] as String).equals("parent", ignoreCase = true) },
            "应包含新建外键（按列匹配）: $fks"
        )
    }

    @Test
    fun `addForeignKey with ON UPDATE CASCADE is silently rewritten to NO ACTION`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.parent (id INTEGER PRIMARY KEY)") }
        c.createStatement().use { it.execute("CREATE TABLE main.child (id INTEGER, parent_id INTEGER)") }
        dialect.addForeignKey(c, "child", "fk_upd", listOf("parent_id"), "parent", listOf("id"), null, "CASCADE")
        val fks = dialect.listForeignKeys(c, "child")
        val fk = fks.first { (it["columns"] as String).split(",").map { c0 -> c0.trim().lowercase() }.contains("parent_id") }
        assertEquals("NO ACTION", fk["on_update"], "ON UPDATE CASCADE 须被改写为 NO ACTION: $fk")
    }

    @Test
    fun `dropForeignKey removes the constraint`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.parent (id INTEGER PRIMARY KEY)") }
        c.createStatement().use { it.execute("CREATE TABLE main.child (id INTEGER, parent_id INTEGER)") }
        dialect.addForeignKey(c, "child", "fk_drop", listOf("parent_id"), "parent", listOf("id"), null, null)
        dialect.dropForeignKey(c, "child", "fk_drop", false)
        val fks = dialect.listForeignKeys(c, "child")
        assertFalse(fks.isNotEmpty(), "应已删除 FK，列表为空: $fks")
    }

    @Test
    fun `dropForeignKey with ifExists does not throw on missing`() = withConn { c ->
        dialect.dropForeignKey(c, "nonexistent_table", "fk_xyz", true)
    }

    // ============ 13. Routines (MACRO) ============

    @Test
    fun `createRoutine MACRO and listRoutines roundtrip`() = withConn { c ->
        dialect.createRoutine(c, "CREATE OR REPLACE MACRO main.double_price(p) AS p * 2")
        val routines = dialect.listRoutines(c, "main")
        assertTrue(routines.any { (it["name"] as String).equals("double_price", ignoreCase = true) }, "应包含新建 MACRO: $routines")
    }

    @Test
    fun `createRoutine with scalar MACRO works`() = withConn { c ->
        dialect.createRoutine(c, "CREATE OR REPLACE MACRO main.triple(x) AS x * 3")
        val routines = dialect.listRoutines(c, "main")
        assertTrue(routines.any { (it["name"] as String).equals("triple", ignoreCase = true) })
    }

    @Test
    fun `dropRoutine removes MACRO`() = withConn { c ->
        dialect.createRoutine(c, "CREATE OR REPLACE MACRO main.temp_macro(x) AS x + 1")
        dialect.dropRoutine(c, "temp_macro", "MACRO", "main", false, false)
        val routines = dialect.listRoutines(c, "main")
        assertFalse(routines.any { (it["name"] as String).equals("temp_macro", ignoreCase = true) })
    }

    @Test
    fun `dropRoutine rejects PROCEDURE type`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.dropRoutine(conn, "x", "PROCEDURE", "main", false, false) }
    }

    @Test
    fun `dropRoutine with ifExists does not throw on missing`() = withConn { c ->
        dialect.dropRoutine(c, "missing_macro", "MACRO", "main", true, false)
    }

    @Test
    fun `callRoutine executes scalar MACRO`() = withConn { c ->
        dialect.createRoutine(c, "CREATE OR REPLACE MACRO main.times_two(x) AS x * 2")
        val result = dialect.callRoutine(c, "times_two", "MACRO", "main", listOf("21"))
        assertEquals(42, result["result"], "MACRO 应返回 21 * 2 = 42: $result")
        assertEquals(1, result["row_count"])
    }

    @Test
    fun `callRoutine rejects PROCEDURE`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.callRoutine(conn, "x", "PROCEDURE", "main", listOf("1")) }
    }

    @Test
    fun `getRoutineDDL returns macro definition`() = withConn { c ->
        dialect.createRoutine(c, "CREATE OR REPLACE MACRO main.quadruple(x) AS x * 4")
        val ddl = dialect.getRoutineDDL(c, "quadruple", "main")
        assertNotNull(ddl)
        assertTrue(ddl.contains("quadruple"), "DDL 应包含函数名: $ddl")
    }

    @Test
    fun `validateRoutineDDL accepts MACRO`() = withConn { c ->
        val valid = dialect.validateRoutineDDL(c, "CREATE OR REPLACE MACRO main.test_m(x) AS x + 1")
        assertEquals(true, valid)
    }

    @Test
    fun `validateRoutineDDL rejects PG-style dollar-quoted body`() = withConn { c ->
        assertThrows<IllegalArgumentException> {
            dialect.validateRoutineDDL(c, "CREATE FUNCTION main.bad() RETURNS INTEGER AS \$\$ BEGIN RETURN 1; END; \$\$ LANGUAGE plpgsql")
        }
    }

    @Test
    fun `validateRoutineDDL rejects PROCEDURE`() = assertThrows<UnsupportedOperationException> {
        runBlocking { dialect.validateRoutineDDL(conn, "CREATE PROCEDURE main.p() AS BEGIN END") }
    }

    // ============ 14. Table Operations ============

    @Test
    fun `renameTable changes table name`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.old_t (id INTEGER)") }
        dialect.renameTable(c, "old_t", "new_t")
        val tables = dialect.listTables(c, "main", "main")
        assertTrue(tables.any { (it["name"] as String).equals("new_t", ignoreCase = true) })
        assertFalse(tables.any { (it["name"] as String).equals("old_t", ignoreCase = true) })
    }

    @Test
    fun `truncateTable empties table`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.t (id INTEGER)") }
        c.createStatement().use { it.execute("INSERT INTO main.t VALUES (1), (2), (3)") }
        dialect.truncateTable(c, "t")
        val count = c.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM main.t").use { rs -> if (rs.next()) rs.getInt(1) else -1 }
        }
        assertEquals(0, count)
    }

    // ============ 15. EXPLAIN ============

    @Test
    fun `explainSQL returns query plan for SELECT`() = withConn { c ->
        val plan = dialect.explainSQL(c, "SELECT 1")
        assertTrue(plan.isNotEmpty(), "EXPLAIN 应返回 plan 行")
    }

    @Test
    fun `explainSQL returns plan for INSERT`() = withConn { c ->
        c.createStatement().use { it.execute("CREATE TABLE main.t (id INTEGER)") }
        val plan = dialect.explainSQL(c, "INSERT INTO main.t VALUES (1)")
        assertTrue(plan.isNotEmpty())
    }

    @Test
    fun `explainSQL rejects semicolon`() = assertThrows<IllegalArgumentException> {
        runBlocking { dialect.explainSQL(conn, "SELECT 1; DROP TABLE x") }
    }

    // ============ 16. Server Info ============

    @Test
    fun `getServerInfo returns DuckDB product info`() = withConn { c ->
        val info = dialect.getServerInfo(c)
        assertEquals("embedded", info["mode"], "DuckDB 模式应为 embedded: $info")
        assertTrue((info["product"] ?: "").contains("DuckDB"), "product 应包含 DuckDB: $info")
        assertNotNull(info["version"], "version 不应为 null")
    }

    @Test
    fun `testConnection returns true for valid conn`() = withConn { c ->
        assertEquals(true, dialect.testConnection(c))
    }

    // ============ 17. Excel 预转换 (POI) ============

    @Test
    fun `ExcelToDuckDbCache identifies Excel files by extension`() {
        assertTrue(ExcelToDuckDbCache.isExcelFile("/tmp/a.xlsx"))
        assertTrue(ExcelToDuckDbCache.isExcelFile("/tmp/a.XLSX"))
        assertTrue(ExcelToDuckDbCache.isExcelFile("/tmp/a.xls"))
        assertFalse(ExcelToDuckDbCache.isExcelFile("/tmp/a.csv"))
        assertFalse(ExcelToDuckDbCache.isExcelFile("/tmp/a.duckdb"))
        assertFalse(ExcelToDuckDbCache.isExcelFile("/tmp/a.parquet"))
    }

    @Test
    fun `ExcelToDuckDbCache converts xlsx to temp duckdb with sheets as tables`(@TempDir tempDir: Path) {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Users")
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("id")
        header.createCell(1).setCellValue("name")
        val row1 = sheet.createRow(1)
        row1.createCell(0).setCellValue(1.0)
        row1.createCell(1).setCellValue("Alice")
        val row2 = sheet.createRow(2)
        row2.createCell(0).setCellValue(2.0)
        row2.createCell(1).setCellValue("Bob")

        val excelFile: File = tempDir.resolve("test.xlsx").toFile()
        FileOutputStream(excelFile).use { fos: FileOutputStream -> workbook.write(fos) }
        workbook.close()

        val duckdbPath = ExcelToDuckDbCache.getOrCreate(excelFile.absolutePath)
        assertTrue(duckdbPath.endsWith(".duckdb"))
        DriverManager.getConnection("jdbc:duckdb:$duckdbPath").use { c ->
            c.createStatement().executeQuery("SELECT COUNT(*) FROM Users").use { rs ->
                assertTrue(rs.next())
            }
        }
    }

    @Test
    fun `ExcelToDuckDbCache caches results for same path`(@TempDir tempDir: Path) {
        val workbook = XSSFWorkbook()
        workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("hi")
        val excelFile: File = tempDir.resolve("cached.xlsx").toFile()
        FileOutputStream(excelFile).use { fos -> workbook.write(fos) }
        workbook.close()

        val first = ExcelToDuckDbCache.getOrCreate(excelFile.absolutePath)
        val second = ExcelToDuckDbCache.getOrCreate(excelFile.absolutePath)
        assertEquals(first, second, "同一 Excel 路径应返回同一临时 DuckDB")
    }
}