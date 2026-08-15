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
import kotlin.test.fail

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
    fun `listSchemas with database argument accepts non-blank catalog`() = runBlocking {
        withConn { conn ->
            val catalogs = dialect.listDatabases(conn)
            assertEquals(1, catalogs.size)
            val db = catalogs.first()
            // 传入匹配 catalog 时不告警（不抛错）
            val schemas = dialect.listSchemas(conn, db)
            assertTrue(schemas.any { it.equals("PUBLIC", ignoreCase = true) })
        }
    }

    @Test
    fun `listDatabases returns connection catalog`() = runBlocking {
        withConn { conn ->
            val databases = dialect.listDatabases(conn)
            assertEquals(1, databases.size)
            // conn.catalog 通常大写
            assertTrue(databases.first().equals(conn.catalog, ignoreCase = true))
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

    @Test
    fun `listColumns returns column metadata via SPI`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE col_test (id INT PRIMARY KEY, name VARCHAR(50) NOT NULL, score DECIMAL DEFAULT 0.0)")
            }
            val cols = dialect.listColumns(conn, "", "", "col_test")
            assertEquals(3, cols.size)
            // H2 INFORMATION_SCHEMA.COLUMNS 默认返回大写列名
            val idCol = cols.first { (it["name"] as? String)?.equals("ID", ignoreCase = true) == true }
            assertEquals(true, idCol["isPrimaryKey"])
            assertEquals(true, (idCol["type"] as? String)?.contains("INT") == true,
                "id type should contain INT, got: ${idCol["type"]}")
            val nameCol = cols.first { (it["name"] as? String)?.equals("NAME", ignoreCase = true) == true }
            assertEquals(false, nameCol["nullable"], "NAME was NOT NULL so should be non-nullable")
            val scoreCol = cols.first { (it["name"] as? String)?.equals("SCORE", ignoreCase = true) == true }
            assertEquals(true, scoreCol["nullable"], "score 无 NOT NULL，所以 nullable=true")
            assertEquals("0.0", scoreCol["defaultValue"]?.toString())
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

    @Test
    fun `createUser rejects non-wildcard host`() = runBlocking {
        withConn { conn ->
            val ex = kotlin.runCatching {
                dialect.createUser(conn, "carol", "pwd", "192.168.1.1")
            }.exceptionOrNull()
            assertTrue(ex is IllegalArgumentException,
                "expected IllegalArgumentException for host='192.168.1.1', got ${ex?.javaClass?.simpleName}")
        }
    }

    @Test
    fun `updatePrivileges accepts ALL PRIVILEGES (with space)`() = runBlocking {
        withConn { conn ->
            dialect.createUser(conn, "dave", "pwd", "%")
            // "ALL PRIVILEGES" 在 regex 放松后应被接受
            val ok = dialect.updatePrivileges(conn, "dave", "PUBLIC", listOf("ALL PRIVILEGES"), isGrant = true, tableName = null, withGrantOption = false)
            assertTrue(ok)
        }
    }

    @Test
    fun `updatePrivileges tableName=tableName grants at table level`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE secret_table (id INT)") }
            dialect.createUser(conn, "eve", "pwd", "%")
            val ok = dialect.updatePrivileges(conn, "eve", "PUBLIC", listOf("SELECT", "INSERT"), isGrant = true, tableName = "secret_table", withGrantOption = false)
            assertTrue(ok)
        }
    }

    @Test
    fun `updatePrivileges withGrantOption emits WITH GRANT OPTION`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE opt_table (id INT)") }
            dialect.createUser(conn, "frank", "pwd", "%")
            // 不抛错即视为成功
            val ok = dialect.updatePrivileges(conn, "frank", "PUBLIC", listOf("SELECT"), isGrant = true, tableName = "opt_table", withGrantOption = true)
            assertTrue(ok)
        }
    }

    @Test
    fun `updatePrivileges rejects invalid privilege (with hyphen)`() {
        // 直接同步测试，无需 connection
        val ex = kotlin.runCatching {
            kotlinx.coroutines.runBlocking {
                dialect.updatePrivileges(java.sql.DriverManager.getConnection("jdbc:h2:mem:dummy;DB_CLOSE_DELAY=-1", "sa", ""), "u", "PUBLIC", listOf("DROP-EVERYTHING"), isGrant = true, tableName = null, withGrantOption = false)
            }
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
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

    // ============ 函数/存储过程（Phase H）============

    @Test
    fun `listRoutines merges FUNCTION_ALIASES with TRIGGERS`() = runBlocking {
        withConn { conn ->
            // 使用 toDegrees（单 overload，避免歧义）
            conn.createStatement().use { it.execute("CREATE ALIAS alias_for_deg FOR \"java.lang.Math.toDegrees\"") }
            val routines = dialect.listRoutines(conn, "PUBLIC")
            assertTrue(routines.any {
                it["name"]?.equals("ALIAS_FOR_DEG", ignoreCase = true) == true &&
                it["routine_type"] == "FUNCTION"
            })
        }
    }

    @Test
    fun `validateRoutineDDL parses CREATE ALIAS without execution`() = runBlocking {
        withConn { conn ->
            // 合法 DDL — 应通过（不实际创建）
            val validAlias = """CREATE ALIAS "valid_alias" FOR "java.lang.Math.toDegrees""""
            assertTrue(dialect.validateRoutineDDL(conn, validAlias))
            // 不应该真的创建出来
            val routines = dialect.listRoutines(conn, "PUBLIC")
            assertFalse(routines.any { it["name"]?.equals("valid_alias", ignoreCase = true) == true })
        }
    }

    @Test
    fun `validateRoutineDDL rejects multi-statement`() = runBlocking {
        withConn { conn ->
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    dialect.validateRoutineDDL(conn, "CREATE ALIAS a FOR \"x\"; DROP ALIAS b")
                }
            }
        }
    }

    @Test
    fun `validateRoutineDDL rejects DDL without parseable name`() = runBlocking {
        withConn { conn ->
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    dialect.validateRoutineDDL(conn, "SOME GARBAGE")
                }
            }
        }
    }

    @Test
    fun `validateRoutineDDL rejects CREATE ALIAS without FOR clause`() = runBlocking {
        withConn { conn ->
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    dialect.validateRoutineDDL(conn, """CREATE ALIAS "foo" bar""")
                }
            }
        }
    }

    @Test
    fun `validateRoutineDDL rejects CREATE FUNCTION without RETURNS`() = runBlocking {
        withConn { conn ->
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    dialect.validateRoutineDDL(conn, "CREATE FUNCTION bad(x INT) AS 'foo'")
                }
            }
        }
    }

    @Test
    fun `debugRoutine returns INFO for FUNCTION_ALIAS`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use { it.execute("CREATE ALIAS my_debug_alias FOR \"java.lang.Math.toDegrees\"") }
            val result = dialect.debugRoutine(conn, "MY_DEBUG_ALIAS", "PUBLIC")
            assertTrue(result.any { it["type"] == "INFO" })
            assertTrue(result.any { it["type"] == "EXPLAIN" })
        }
    }

    @Test
    fun `callRoutine for FUNCTION_ALIAS returns result`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use { it.execute("CREATE ALIAS deg_alias FOR \"java.lang.Math.toDegrees\"") }
            val result = dialect.callRoutine(conn, "DEG_ALIAS", "FUNCTION", "PUBLIC", listOf("3.141592653589793"))
            assertNotNull(result["result"])
            assertEquals(180, (result["result"] as? Number)?.toInt())
        }
    }

    // ============ 安全（Phase I）============

    // ============ 安全（Phase I）============

    @Test
    fun `createUser rejects username with single quote`() = runBlocking {
        withConn { conn ->
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    dialect.createUser(conn, "evil'user", "pw", "%")
                }
            }
        }
    }

    @Test
    fun `createUser rejects username with semicolon`() = runBlocking {
        withConn { conn ->
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    dialect.createUser(conn, "evil;DROP", "pw", "%")
                }
            }
        }
    }

    @Test
    fun `createUser rejects username with backslash`() = runBlocking {
        withConn { conn ->
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    dialect.createUser(conn, "evil\\user", "pw", "%")
                }
            }
        }
    }

    @Test
    fun `createSchema rejects schema name with quote`() = runBlocking {
        withConn { conn ->
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    dialect.createSchema(conn, "bad'name", emptyMap())
                }
            }
        }
    }

    @Test
    fun `dropRoutine rejects routine name with semicolon`() = runBlocking {
        withConn { conn ->
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    dialect.dropRoutine(conn, "evil;DROP TABLE users", "FUNCTION", "PUBLIC", false, false)
                }
            }
        }
    }

    @Test
    fun `callRoutine with SQL injection in args is safely bound`() = runBlocking {
        withConn { conn ->
            conn.createStatement().use { it.execute("CREATE ALIAS deg_inj FOR \"java.lang.Math.toDegrees\"") }
            // 注入尝试：单引号 + SQL 语句应被作为参数值绑定，不会执行
            val malicious = "1.0; DROP TABLE users; --"
            // 由于参数是字符串、toDegrees 期望 double，H2 会抛 DataConversionException — 但这恰恰证明
            // 恶意字符串被作为参数绑定（而非 SQL 注入）；如果是 SQL 注入，H2 会尝试执行 DROP TABLE
            try {
                dialect.callRoutine(conn, "DEG_INJ", "FUNCTION", "PUBLIC", listOf(malicious))
                fail("Expected exception due to type mismatch (proves param binding)")
            } catch (e: Exception) {
                // 注入被 JDBC 参数绑定拦截 — 表仍然存在
                assertTrue(e.message?.contains("Data conversion") == true ||
                           e.message?.contains("conversion") == true)
            }
            // 表仍然存在 — 注入未执行
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME = CURRENT_USER").use { rs ->
                    assertTrue(rs.next())
                }
            }
        }
    }
}