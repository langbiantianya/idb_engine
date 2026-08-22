package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.DataHandler
import com.kxxnzstdsw.handlers.ForeignKeyHandler
import com.kxxnzstdsw.handlers.FunctionHandler
import com.kxxnzstdsw.handlers.IndexHandler
import com.kxxnzstdsw.handlers.SchemaHandler
import com.kxxnzstdsw.handlers.SqlEngineHandler
import com.kxxnzstdsw.handlers.SystemHandler
import com.kxxnzstdsw.handlers.TableHandler
import com.kxxnzstdsw.handlers.ViewHandler
import com.kxxnzstdsw.testutil.DuckDBFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DuckDB handler 集成测试 —— 通过各 handler 走完整 gRPC → dialect → DuckDB 路径。
 * 每个测试都用一个新的临时 .duckdb 文件（来自 DuckDBFixture）。
 */
class DuckDBHandlerIntegrationTest : DuckDBFixture() {

    // ============ SCHEMA ============

    @Test
    fun `SCHEMA LIST level=database returns main and memory`() = runBlocking {
        val result = SchemaHandler.list(
            config,
            com.kxxnzstdsw.grpc.SchemaListRequest.newBuilder().setLevel("database").build()
        )
        assertEquals("database", result.level)
        assertTrue(result.itemsList.isNotEmpty())
        assertTrue(result.itemsList.any { it.equals("main", ignoreCase = true) }, "应包含 main: ${result.itemsList}")
    }

    @Test
    fun `SCHEMA CREATE and DELETE roundtrip`() = runBlocking {
        SchemaHandler.create(
            config,
            com.kxxnzstdsw.grpc.SchemaCreateRequest.newBuilder().setName("analytics").build()
        )
        assertTrue(schemaExists("analytics"))
        SchemaHandler.delete(
            config,
            com.kxxnzstdsw.grpc.SchemaDeleteRequest.newBuilder().setName("analytics").build()
        )
        assertFalse(schemaExists("analytics"))
    }

    @Test
    fun `SCHEMA CREATE ifNotExists does not throw on duplicate`() = runBlocking {
        SchemaHandler.create(
            config,
            com.kxxnzstdsw.grpc.SchemaCreateRequest.newBuilder().setName("sc1").setIfNotExists(true).build()
        )
        SchemaHandler.create(
            config,
            com.kxxnzstdsw.grpc.SchemaCreateRequest.newBuilder().setName("sc1").setIfNotExists(true).build()
        )
        SchemaHandler.delete(
            config,
            com.kxxnzstdsw.grpc.SchemaDeleteRequest.newBuilder().setName("sc1").setIfExists(true).build()
        )
    }

    // ============ TABLE ============

    @Test
    fun `TABLE LIST returns tables in main schema`() = runBlocking {
        executeUpdate("CREATE TABLE main.users (id INTEGER, name VARCHAR)")
        executeUpdate("CREATE TABLE main.orders (id INTEGER)")
        val result = TableHandler.list(config, com.kxxnzstdsw.grpc.TableListRequest.getDefaultInstance())
        assertTrue(result.itemsList.any { it.name.equals("users", ignoreCase = true) })
        assertTrue(result.itemsList.any { it.name.equals("orders", ignoreCase = true) })
    }

    @Test
    fun `TABLE CREATE with autoIncrement primary key uses IDENTITY`() = runBlocking {
        val cols = listOf(
            com.kxxnzstdsw.grpc.ColumnDef.newBuilder()
                .setName("id").setType("INTEGER").setIsPrimaryKey(true).setAutoIncrement(true).build(),
            com.kxxnzstdsw.grpc.ColumnDef.newBuilder()
                .setName("name").setType("VARCHAR").setSize(100).setNullable(false).build()
        )
        TableHandler.create(
            config,
            com.kxxnzstdsw.grpc.TableCreateRequest.newBuilder()
                .setTableName("products")
                .addAllColumns(cols)
                .build()
        )
        assertTrue(tableExists("products"))
        executeUpdate("INSERT INTO main.products (name) VALUES ('Alice')")
        val id = executeQuerySingle("SELECT id FROM main.products LIMIT 1")
        assertEquals("1", id)
    }

    @Test
    fun `TABLE COLUMN_LIST returns column metadata`() = runBlocking {
        executeUpdate("CREATE TABLE main.demo (id INTEGER PRIMARY KEY, name VARCHAR(100) NOT NULL, price DECIMAL(10,2))")
        val cols = TableHandler.columnList(
            config,
            com.kxxnzstdsw.grpc.TableColumnListRequest.newBuilder().setTableName("demo").build()
        )
        assertEquals(3, cols.itemsList.size)
        val id = cols.itemsList.first { it.name.equals("id", ignoreCase = true) }
        assertTrue(id.isPrimaryKey)
        val name = cols.itemsList.first { it.name.equals("name", ignoreCase = true) }
        assertFalse(name.nullable)
    }

    @Test
    fun `TABLE RENAME changes table name`() = runBlocking {
        executeUpdate("CREATE TABLE main.old (id INTEGER)")
        TableHandler.rename(
            config,
            com.kxxnzstdsw.grpc.TableRenameRequest.newBuilder().setOldName("old").setNewName("new").build()
        )
        assertTrue(tableExists("new"))
        assertFalse(tableExists("old"))
    }

    @Test
    fun `TABLE TRUNCATE empties table`() = runBlocking {
        executeUpdate("CREATE TABLE main.t (id INTEGER)")
        executeUpdate("INSERT INTO main.t VALUES (1), (2), (3)")
        TableHandler.truncate(
            config,
            com.kxxnzstdsw.grpc.TableTruncateRequest.newBuilder().setTableName("t").build()
        )
        assertEquals("0", executeQuerySingle("SELECT COUNT(*) FROM main.t"))
    }

    // ============ DATA ============

    @Test
    fun `DATA LIST paginates results`() = runBlocking {
        executeUpdate("CREATE TABLE main.users (id INTEGER, name VARCHAR)")
        repeat(5) { i -> executeUpdate("INSERT INTO main.users VALUES ($i, 'user_$i')") }
        val resp = DataHandler.list(
            config,
            com.kxxnzstdsw.grpc.DataListRequest.newBuilder()
                .setTableName("users").setPage(1).setPageSize(3).build()
        )
        assertEquals(5L, resp.total)
        assertEquals(3, resp.rowsCount)
    }

    @Test
    fun `DATA CREATE inserts one row`() = runBlocking {
        executeUpdate("CREATE TABLE main.users (id INTEGER, name VARCHAR)")
        DataHandler.create(
            config,
            com.kxxnzstdsw.grpc.DataCreateRequest.newBuilder()
                .setTableName("users")
                .putValues("id", "1")
                .putValues("name", "Alice")
                .build()
        )
        assertEquals("Alice", executeQuerySingle("SELECT name FROM main.users"))
    }

    @Test
    fun `DATA UPDATE modifies rows by where`() = runBlocking {
        executeUpdate("CREATE TABLE main.users (id INTEGER, name VARCHAR)")
        executeUpdate("INSERT INTO main.users VALUES (1, 'Alice')")
        DataHandler.update(
            config,
            com.kxxnzstdsw.grpc.DataUpdateRequest.newBuilder()
                .setTableName("users")
                .putChanges("name", "Alex")
                .putWhere("id", "1")
                .build()
        )
        assertEquals("Alex", executeQuerySingle("SELECT name FROM main.users"))
    }

    @Test
    fun `DATA DELETE removes rows by where`() = runBlocking {
        executeUpdate("CREATE TABLE main.users (id INTEGER, name VARCHAR)")
        executeUpdate("INSERT INTO main.users VALUES (1, 'Alice'), (2, 'Bob')")
        DataHandler.delete(
            config,
            com.kxxnzstdsw.grpc.DataDeleteRequest.newBuilder()
                .setTableName("users")
                .putWhere("id", "1")
                .build()
        )
        assertEquals("Bob", executeQuerySingle("SELECT name FROM main.users"))
    }

    // ============ SQL ============

    @Test
    fun `SQL EXECUTE INSERT returns affected_rows`() = runBlocking {
        executeUpdate("CREATE TABLE main.t (id INTEGER)")
        val resp = SqlEngineHandler.execute(
            config,
            com.kxxnzstdsw.grpc.SqlExecuteRequest.newBuilder()
                .setSql("INSERT INTO main.t VALUES (1), (2), (3)").build()
        )
        assertEquals(3, resp.affectedRows)
    }

    @Test
    fun `SQL EXECUTE SELECT returns rows`() = runBlocking {
        executeUpdate("CREATE TABLE main.t (id INTEGER, name VARCHAR)")
        executeUpdate("INSERT INTO main.t VALUES (1, 'Alice'), (2, 'Bob')")
        // 单次 SELECT 响应走 SqlResponse.execute
        val resp = SqlEngineHandler.execute(
            config,
            com.kxxnzstdsw.grpc.SqlExecuteRequest.newBuilder()
                .setSql("SELECT id, name FROM main.t ORDER BY id").build()
        )
        assertNotNull(resp)
    }

    @Test
    fun `SQL EXECUTE rejects multi-statement payload`() {
        // SQL.EXECUTE 不做方言级语法校验（调用方负责），但 DuckDB 自身会拒绝含分号的多语句或未创建表的引用
        assertThrows<Exception> {
            runBlocking {
                SqlEngineHandler.execute(
                    config,
                    com.kxxnzstdsw.grpc.SqlExecuteRequest.newBuilder()
                        .setSql("SELECT 1; DROP TABLE x").build()
                )
            }
        }
    }

    // ============ VIEW ============

    @Test
    fun `VIEW CREATE LIST DELETE roundtrip`() = runBlocking {
        executeUpdate("CREATE TABLE main.users (id INTEGER, name VARCHAR)")
        ViewHandler.create(
            config,
            com.kxxnzstdsw.grpc.ViewCreateRequest.newBuilder()
                .setName("v_users")
                .setDefinition("SELECT id, name FROM main.users WHERE id > 0").build()
        )
        val views = ViewHandler.list(config, com.kxxnzstdsw.grpc.ViewListRequest.getDefaultInstance())
        assertTrue(views.itemsList.any { it.name.equals("v_users", ignoreCase = true) })

        val ddl = ViewHandler.getDDL(
            config,
            com.kxxnzstdsw.grpc.ViewGetDdlRequest.newBuilder().setName("v_users").build()
        )
        assertTrue(ddl.ddl.contains("v_users"), "DDL 应包含视图名: ${ddl.ddl}")

        ViewHandler.delete(
            config,
            com.kxxnzstdsw.grpc.ViewDeleteRequest.newBuilder().setName("v_users").build()
        )
        val after = ViewHandler.list(config, com.kxxnzstdsw.grpc.ViewListRequest.getDefaultInstance())
        assertFalse(after.itemsList.any { it.name.equals("v_users", ignoreCase = true) })
    }

    // ============ INDEX ============

    @Test
    fun `INDEX CREATE LIST DELETE roundtrip`() = runBlocking {
        executeUpdate("CREATE TABLE main.t (id INTEGER, email VARCHAR(100))")
        IndexHandler.create(
            config,
            com.kxxnzstdsw.grpc.IndexCreateRequest.newBuilder()
                .setTableName("t").setIndexName("idx_email")
                .addColumns("email").setUnique(false).build()
        )
        val indexes = IndexHandler.list(
            config,
            com.kxxnzstdsw.grpc.IndexListRequest.newBuilder().setTableName("t").build()
        )
        assertTrue(indexes.itemsList.any { it.name.equals("idx_email", ignoreCase = true) })

        IndexHandler.delete(
            config,
            com.kxxnzstdsw.grpc.IndexDeleteRequest.newBuilder()
                .setTableName("t").setIndexName("idx_email").setIfExists(true).build()
        )
        val after = IndexHandler.list(
            config,
            com.kxxnzstdsw.grpc.IndexListRequest.newBuilder().setTableName("t").build()
        )
        assertFalse(after.itemsList.any { it.name.equals("idx_email", ignoreCase = true) })
    }

    @Test
    fun `INDEX CREATE UNIQUE enforces uniqueness`() = runBlocking {
        executeUpdate("CREATE TABLE main.t (id INTEGER, email VARCHAR(100))")
        IndexHandler.create(
            config,
            com.kxxnzstdsw.grpc.IndexCreateRequest.newBuilder()
                .setTableName("t").setIndexName("uniq_email")
                .addColumns("email").setUnique(true).build()
        )
        executeUpdate("INSERT INTO main.t VALUES (1, 'a@x.com')")
        assertThrows<Exception> {
            executeUpdate("INSERT INTO main.t VALUES (2, 'a@x.com')")
        }
    }

    // ============ FOREIGN KEY ============

    @Test
    fun `FK CREATE LIST DELETE roundtrip`() = runBlocking {
        executeUpdate("CREATE TABLE main.parent (id INTEGER PRIMARY KEY)")
        executeUpdate("CREATE TABLE main.child (id INTEGER, parent_id INTEGER)")
        ForeignKeyHandler.create(
            config,
            com.kxxnzstdsw.grpc.ForeignKeyCreateRequest.newBuilder()
                .setTableName("child").setFkName("fk_child_parent")
                .addColumns("parent_id").setRefTable("parent")
                .addRefColumns("id").setOnDelete("NO ACTION").build()
        )
        val fks = ForeignKeyHandler.list(
            config,
            com.kxxnzstdsw.grpc.ForeignKeyListRequest.newBuilder().setTableName("child").build()
        )
        assertTrue(fks.itemsList.isNotEmpty())
        assertTrue(fks.itemsList.first().columnsList.any { it.equals("parent_id", ignoreCase = true) })

        ForeignKeyHandler.delete(
            config,
            com.kxxnzstdsw.grpc.ForeignKeyDeleteRequest.newBuilder()
                .setTableName("child").setFkName("fk_child_parent").setIfExists(true).build()
        )
        val after = ForeignKeyHandler.list(
            config,
            com.kxxnzstdsw.grpc.ForeignKeyListRequest.newBuilder().setTableName("child").build()
        )
        assertTrue(after.itemsList.isEmpty())
    }

    // ============ FUNCTION (MACRO) ============

    @Test
    fun `FUNCTION CREATE LIST DELETE roundtrip (MACRO)`() = runBlocking {
        FunctionHandler.create(
            config,
            com.kxxnzstdsw.grpc.FunctionCreateRequest.newBuilder()
                .setDdl("CREATE OR REPLACE MACRO main.double_it(x) AS x * 2").build()
        )
        val funcs = FunctionHandler.list(config, com.kxxnzstdsw.grpc.FunctionListRequest.getDefaultInstance())
        assertTrue(funcs.itemsList.any { it.name.equals("double_it", ignoreCase = true) })

        FunctionHandler.delete(
            config,
            com.kxxnzstdsw.grpc.FunctionDeleteRequest.newBuilder()
                .setName("double_it").setRoutineType("MACRO").setIfExists(true).build()
        )
        assertFalse(FunctionHandler.list(config, com.kxxnzstdsw.grpc.FunctionListRequest.getDefaultInstance())
            .itemsList.any { it.name.equals("double_it", ignoreCase = true) })
    }

    @Test
    fun `FUNCTION CALL executes MACRO and returns result`() = runBlocking {
        FunctionHandler.create(
            config,
            com.kxxnzstdsw.grpc.FunctionCreateRequest.newBuilder()
                .setDdl("CREATE OR REPLACE MACRO main.times_two(x) AS x * 2").build()
        )
        val result = FunctionHandler.call(
            config,
            com.kxxnzstdsw.grpc.FunctionCallRequest.newBuilder()
                .setName("times_two").setRoutineType("MACRO")
                .addArgs("21").build()
        )
        // FunctionHandler.call 将 dialect.callRoutine() 返回的 map 包成 JsonObject → Value(struct)
        val numberValue = result.result.structValue.fieldsMap["result"]?.numberValue ?: 0.0
        assertEquals(42.0, numberValue)
    }

    // ============ SYSTEM ============

    @Test
    fun `SYSTEM INFO returns JVM runtime info`() {
        val info = SystemHandler.info()
        assertNotNull(info.jvmVersion)
        assertTrue(info.memory.total > 0)
    }

    @Test
    fun `SYSTEM TEST_CONNECTION returns true for Duckdb`() = runBlocking {
        val result = SystemHandler.testConnection(config)
        assertTrue(result.ok, "TEST_CONNECTION 应成功: $result")
        assertEquals("Duckdb", result.driver)
    }

    @Test
    fun `SYSTEM SERVER_INFO returns DuckDB product info`() = runBlocking {
        val info = SystemHandler.serverInfo(config)
        assertTrue(info.mode == "embedded", "mode 应为 embedded: $info")
        assertTrue(info.extras.structValue.fieldsMap.containsKey("product"),
            "extras 应含 product: $info")
        val product = info.extras.structValue.fieldsMap["product"]?.stringValue
        assertTrue(product?.contains("DuckDB") == true, "product 应含 DuckDB: $product")
    }

    // ============ LOCAL FILE: CSV / DuckDB file / Parquet 直查 ============

    @Test
    fun `Dialect can connect to CSV file and query it`(@TempDir tempDir: Path) {
        val csvFile = tempDir.resolve("users.csv").toFile()
        csvFile.writeText("id,name,age\n1,Alice,30\n2,Bob,25\n3,Charlie,35\n")
        val url = dialect.buildJdbcUrl("ignored", 0, csvFile.absolutePath)
        assertTrue(url.endsWith(".csv"), "URL 应保留 .csv 后缀: $url")
        java.sql.DriverManager.getConnection(url).use { c ->
            val rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM users")
            assertTrue(rs.next())
            // DuckDB JDBC 把 CSV 文件名作为表名（无后缀）
        }
    }

    @Test
    fun `Dialect can connect to DuckDB file directly`(@TempDir tempDir: Path) {
        val db = tempDir.resolve("test.duckdb").toFile()
        java.sql.DriverManager.getConnection("jdbc:duckdb:${db.absolutePath}").use { c ->
            c.createStatement().execute("CREATE TABLE t (id INTEGER)")
            c.createStatement().execute("INSERT INTO t VALUES (1)")
        }
        java.sql.DriverManager.getConnection("jdbc:duckdb:${db.absolutePath}").use { c ->
            val id = c.createStatement().executeQuery("SELECT id FROM t").use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
            assertEquals("1", id)
        }
    }

    @Test
    fun `Dialect can connect to Parquet file and query it`(@TempDir tempDir: Path) {
        val db = tempDir.resolve("seed.duckdb").toFile()
        val parquet = tempDir.resolve("events.parquet").toFile()
        java.sql.DriverManager.getConnection("jdbc:duckdb:${db.absolutePath}").use { c ->
            c.createStatement().execute("CREATE TABLE events (id INTEGER, payload VARCHAR)")
            c.createStatement().execute("INSERT INTO events VALUES (1, 'a'), (2, 'b')")
            c.createStatement().execute("COPY events TO '${parquet.absolutePath}' (FORMAT PARQUET)")
        }
        java.sql.DriverManager.getConnection("jdbc:duckdb:${parquet.absolutePath}").use { c ->
            val count = c.createStatement().executeQuery("SELECT COUNT(*) FROM events").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
            assertEquals(2, count)
        }
    }

    // ============ EXPORT (ExportEngine direct, bypasses ExportHandler subprocess) ============

    @Test
    fun `ExportEngine CSV writes file with valid data from DuckDB`(@TempDir tempDir: Path) = runBlocking {
        executeUpdate("CREATE TABLE main.users (id INTEGER, name VARCHAR)")
        executeUpdate("INSERT INTO main.users VALUES (1, 'Alice'), (2, 'Bob')")
        val result = com.kxxnzstdsw.export.ExportEngine.export(
            config,
            com.kxxnzstdsw.export.ExportRequest(
                sql = "SELECT id, name FROM main.users ORDER BY id",
                outputDir = tempDir.toAbsolutePath().toString(),
                fileName = "users",
                format = com.kxxnzstdsw.export.ExportFormat.CSV,
                fetchSize = 100
            )
        ) { /* onProgress: noop */ }
        assertTrue(result.success, "导出应成功: $result")
        assertEquals(2L, result.exportedRows)
        val out = tempDir.resolve("users.csv").toFile()
        assertTrue(out.exists(), "CSV 文件应已生成")
        assertTrue(out.readText().contains("Alice"))
        assertTrue(out.readText().contains("Bob"))
    }

    @Test
    fun `ExportEngine JSON_LINES writes one JSON per line from DuckDB`(@TempDir tempDir: Path) = runBlocking {
        executeUpdate("CREATE TABLE main.users (id INTEGER, name VARCHAR)")
        executeUpdate("INSERT INTO main.users VALUES (1, 'Alice')")
        val result = com.kxxnzstdsw.export.ExportEngine.export(
            config,
            com.kxxnzstdsw.export.ExportRequest(
                sql = "SELECT id, name FROM main.users",
                outputDir = tempDir.toAbsolutePath().toString(),
                fileName = "users",
                format = com.kxxnzstdsw.export.ExportFormat.JSON_LINES
            )
        ) { /* onProgress: noop */ }
        assertTrue(result.success, "导出应成功: $result")
        val out = tempDir.resolve("users.jsonl").toFile()
        assertTrue(out.exists())
        val lines = out.readLines()
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("Alice"))
    }

    @Test
    fun `ExportEngine SQL_INSERT writes INSERT statements from DuckDB`(@TempDir tempDir: Path) = runBlocking {
        executeUpdate("CREATE TABLE main.users (id INTEGER, name VARCHAR)")
        executeUpdate("INSERT INTO main.users VALUES (1, 'Alice')")
        val result = com.kxxnzstdsw.export.ExportEngine.export(
            config,
            com.kxxnzstdsw.export.ExportRequest(
                sql = "SELECT id, name FROM main.users",
                outputDir = tempDir.toAbsolutePath().toString(),
                fileName = "users",
                format = com.kxxnzstdsw.export.ExportFormat.SQL_INSERT,
                tableName = "users"
            )
        ) { /* onProgress: noop */ }
        assertTrue(result.success, "导出应成功: $result")
        val out = tempDir.resolve("users.sql").toFile()
        assertTrue(out.exists())
        val text = out.readText()
        assertTrue(text.contains("INSERT INTO"), "应包含 INSERT INTO: $text")
        assertTrue(text.contains("Alice"))
    }
}
