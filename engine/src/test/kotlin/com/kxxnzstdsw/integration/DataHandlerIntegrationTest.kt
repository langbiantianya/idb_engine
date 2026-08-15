package com.kxxnzstdsw.integration

import com.kxxnzstdsw.handlers.DataHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataHandlerIntegrationTest : H2Fixture() {

    private fun seed() {
        executeUpdate("CREATE TABLE users (id INT AUTO_INCREMENT PRIMARY KEY, age INT NOT NULL, name VARCHAR(50))")
        for (i in 1..20) {
            executeUpdate("INSERT INTO users (age, name) VALUES (${i + 10}, 'name_$i')")
        }
    }

    @Test
    fun `LIST returns paginated data with total count`() = runBlocking {
        seed()
        val result = DataHandler.list(config, buildJsonObject {
            put("tableName", "users")
            put("page", 1)
            put("pageSize", 5)
        }) as JsonElement
        val obj = result.jsonObject
        assertEquals(20L, obj["total"]?.jsonPrimitive?.longOrNull)
        assertEquals(5, (obj["rows"] as JsonArray).size)
    }

    @Test
    fun `LIST with where clause filters rows`() = runBlocking {
        seed()
        val result = DataHandler.list(config, buildJsonObject {
            put("tableName", "users")
            put("page", 1)
            put("pageSize", 50)
            put("where", "age > 25")
        }) as JsonElement
        val total = result.jsonObject["total"]?.jsonPrimitive?.longOrNull
        assertNotNull(total)
        assertEquals(5L, total) // age > 25 → 26..30 = 5 rows
    }

    @Test
    fun `LIST with orderBy sorts results`() = runBlocking {
        seed()
        val result = DataHandler.list(config, buildJsonObject {
            put("tableName", "users")
            put("page", 1)
            put("pageSize", 3)
            put("orderBy", "age DESC")
        }) as JsonElement
        val rows = result.jsonObject["rows"] as JsonArray
        // H2 把列名返回大写 — 查找 key 时大小写不敏感
        val firstAge = rows[0].jsonObject.entries.firstOrNull { it.key.equals("age", ignoreCase = true) }?.value?.jsonPrimitive?.intOrNull
        val thirdAge = rows[2].jsonObject.entries.firstOrNull { it.key.equals("age", ignoreCase = true) }?.value?.jsonPrimitive?.intOrNull
        assertTrue(firstAge!! > thirdAge!!)
    }

    @Test
    fun `LIST with pageSize 0 streams rows via onRow callback`() = runBlocking {
        seed()
        val collected = mutableListOf<JsonElement>()
        DataHandler.list(config, buildJsonObject {
            put("tableName", "users")
            put("pageSize", 0)
        }) { row -> collected.add(row) }
        assertEquals(20, collected.size)
    }

    @Test
    fun `LIST rejects WHERE with dangerous keyword`() = runBlocking {
        seed()
        try {
            DataHandler.list(config, buildJsonObject {
                put("tableName", "users")
                put("where", "1=1 OR DROP TABLE x")
            })
            error("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("DROP"))
        }
    }

    @Test
    fun `CREATE inserts a row and returns affectedRows`() = runBlocking {
        executeUpdate("CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50), age INT)")
        val result = DataHandler.create(config, buildJsonObject {
            put("tableName", "t")
            putJsonObject("values") {
                put("name", "Alice")
                put("age", "30")
            }
        })
        assertEquals(1, result.jsonObject["affectedRows"]?.jsonPrimitive?.intOrNull)
        assertEquals("Alice", executeQuerySingle("SELECT name FROM t LIMIT 1"))
    }

    @Test
    fun `UPDATE changes a row by where clause`() = runBlocking {
        executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))")
        executeUpdate("INSERT INTO t VALUES (1, 'old')")
        val result = DataHandler.update(config, buildJsonObject {
            put("tableName", "t")
            putJsonObject("changes") {
                put("name", "new")
            }
            putJsonObject("where") { put("id", "1") }
        })
        assertEquals(1, result.jsonObject["affectedRows"]?.jsonPrimitive?.intOrNull)
        assertEquals("new", executeQuerySingle("SELECT name FROM t WHERE id=1"))
    }

    @Test
    fun `DELETE removes rows by where clause`() = runBlocking {
        executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))")
        executeUpdate("INSERT INTO t VALUES (1, 'a'), (2, 'b')")
        val result = DataHandler.delete(config, buildJsonObject {
            put("tableName", "t")
            putJsonObject("where") { put("id", "1") }
        })
        assertEquals(1, result.jsonObject["affectedRows"]?.jsonPrimitive?.intOrNull)
        assertEquals("1", executeQuerySingle("SELECT COUNT(*) FROM t"))
    }

    @Test
    fun `INSERT binds LocalDate type for DATE column`() = runBlocking {
        executeUpdate("CREATE TABLE events (id INT PRIMARY KEY, event_date DATE)")
        DataHandler.create(config, buildJsonObject {
            put("tableName", "events")
            putJsonObject("values") {
                put("id", "1")
                put("event_date", "2024-06-15")
            }
        })
        assertEquals("2024-06-15", executeQuerySingle("SELECT event_date FROM events"))
    }
}