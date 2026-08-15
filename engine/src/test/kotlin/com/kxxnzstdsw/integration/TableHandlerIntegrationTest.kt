package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.TableHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns tables in schema`() = runBlocking {
        executeUpdate("CREATE TABLE t1 (id INT)")
        executeUpdate("CREATE TABLE t2 (id INT)")
        val result = TableHandler.list(config, JsonObject(emptyMap()))
        val arr = result.jsonArray
        // H2 默认把表名存储为大写
        assertTrue(arr.any { it.jsonObject["name"]?.jsonPrimitive?.content?.equals("t1", ignoreCase = true) == true })
        assertTrue(arr.any { it.jsonObject["name"]?.jsonPrimitive?.content?.equals("t2", ignoreCase = true) == true })
    }

    @Test
    fun `LIST with tableName returns columns and PK info`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT NOT NULL PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50) NOT NULL, age INT)")
        val result = TableHandler.columnList(config, buildJsonObject { put("tableName", "users") })
        val arr = result.jsonArray
        assertEquals(3, arr.size)
        // H2 默认把列名返回大写 — 用 ignoreCase
        val id = arr.first { it.jsonObject["name"]?.jsonPrimitive?.content?.equals("id", ignoreCase = true) == true }.jsonObject
        assertEquals(true, id["isPrimaryKey"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, id["nullable"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `CREATE table with columns including autoIncrement PK`() = runBlocking {
        val payload = buildJsonObject {
            put("tableName", "products")
            putJsonArray("columns") {
                add(buildJsonObject {
                    put("name", "id")
                    put("type", "INT")
                    put("nullable", false)
                    put("isPrimaryKey", true)
                    put("autoIncrement", true)
                })
                add(buildJsonObject {
                    put("name", "name")
                    put("type", "VARCHAR")
                    put("size", 100)
                    put("nullable", false)
                })
            }
        }
        TableHandler.create(config, payload)
        assertTrue(tableExists("products"))
    }

    @Test
    fun `UPDATE ADD_COLUMN adds new column`() = runBlocking {
        // 用大写 T 让 executeUpdate 和 TableHandler 引用同一张表
        executeUpdate("CREATE TABLE T (id INT)")
        TableHandler.update(config, buildJsonObject {
            put("tableName", "T")
            put("operation", "ADD_COLUMN")
            putJsonObject("column") {
                put("name", "DESCRIPTION")
                put("type", "VARCHAR")
                put("size", 255)
                put("nullable", true)
            }
        })
        withConnection { conn ->
            conn.metaData.getColumns(null, "PUBLIC", "T", "DESCRIPTION").use { rs ->
                assertTrue(rs.next(), "新列未创建")
            }
        }
    }

    @Test
    fun `UPDATE DROP_COLUMN removes column`() = runBlocking {
        executeUpdate("CREATE TABLE T (id INT, description VARCHAR(255))")
        TableHandler.update(config, buildJsonObject {
            put("tableName", "T")
            put("operation", "DROP_COLUMN")
            put("columnName", "description")
        })
        withConnection { conn ->
            conn.metaData.getColumns(null, "PUBLIC", "T", "DESCRIPTION").use { rs ->
                assertFalse(rs.next(), "列仍存在")
            }
        }
    }

    @Test
    fun `UPDATE MODIFY_COLUMN changes column type and renames`() = runBlocking {
        executeUpdate("CREATE TABLE T (id INT, price INT)")
        // H2 单条 ALTER 只能改一个属性 — 分两步：先 rename，再改 type
        TableHandler.update(config, buildJsonObject {
            put("tableName", "T")
            put("operation", "MODIFY_COLUMN")
            putJsonObject("column") {
                put("name", "PRICE")
                put("newName", "UNIT_PRICE")
            }
        })
        TableHandler.update(config, buildJsonObject {
            put("tableName", "T")
            put("operation", "MODIFY_COLUMN")
            putJsonObject("column") {
                put("name", "UNIT_PRICE")
                put("type", "DECIMAL")
                put("size", 10)
                put("nullable", false)
            }
        })
        withConnection { conn ->
            conn.metaData.getColumns(null, "PUBLIC", "T", "UNIT_PRICE").use { rs ->
                assertTrue(rs.next())
            }
        }
    }

    @Test
    fun `GET_DDL returns CREATE TABLE DDL`() = runBlocking {
        executeUpdate("CREATE TABLE users (id INT NOT NULL PRIMARY KEY, name VARCHAR(50))")
        val result = TableHandler.getDDL(config, buildJsonObject { put("tableName", "users") })
        val ddl = result.jsonPrimitive.content
        assertTrue(ddl.contains("CREATE TABLE"))
        assertTrue(ddl.contains("PRIMARY KEY"))
    }

    @Test
    fun `DELETE drops table`() = runBlocking {
        executeUpdate("CREATE TABLE t (id INT)")
        TableHandler.delete(config, buildJsonObject { put("tableName", "t") })
        assertFalse(tableExists("t"))
    }

    @Test
    fun `RENAME renames table`() = runBlocking {
        executeUpdate("CREATE TABLE old_t (id INT)")
        TableHandler.rename(config, buildJsonObject {
            put("oldName", "old_t")
            put("newName", "new_t")
        })
        assertTrue(tableExists("new_t"))
        assertFalse(tableExists("old_t"))
    }

    @Test
    fun `TRUNCATE empties table`() = runBlocking {
        executeUpdate("CREATE TABLE t (id INT)")
        executeUpdate("INSERT INTO t VALUES (1), (2), (3)")
        TableHandler.truncate(config, buildJsonObject { put("tableName", "t") })
        assertEquals("0", executeQuerySingle("SELECT COUNT(*) FROM t"))
    }
}