package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.dialect.DatabaseDialect
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.*
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.Lua
import party.iroiro.luajava.luajit.LuaJit
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random
import org.slf4j.LoggerFactory

object GenerateHandler {
    private val logger = LoggerFactory.getLogger(GenerateHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val NAMES = listOf(
        "张三", "李四", "王五", "赵六", "孙七", "周八", "吴九", "郑十",
        "Alice", "Bob", "Charlie", "Diana", "Eve", "Frank", "Grace", "Henry"
    )
    private val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** 每 accumulate BATCH_SIZE 行后 executeBatch + commit */
    private const val BATCH_SIZE = 1000

    /**
     * 造数状态：跨 insert() / flushBatch() 共享的可变上下文
     */
    private class GenerateState(
        val conn: Connection,
        val dialect: DatabaseDialect,
        val tableName: String,
        var currentStmt: PreparedStatement? = null,
        var currentColumns: List<String>? = null,
        var currentSql: String = "",
        var batchCount: Int = 0,
        var totalInserted: Long = 0,
        var lastGeneratedId: Long? = null
    ) {
        fun flushBatch() {
            val stmt = currentStmt ?: return
            if (batchCount == 0) return

            stmt.executeBatch()

            try {
                stmt.generatedKeys.use { rs ->
                    while (rs.next()) {
                        lastGeneratedId = rs.getLong(1)
                    }
                }
            } catch (e: Exception) {
                logger.debug("Could not retrieve generated keys: ${e.message}")
            }

            totalInserted += batchCount
            batchCount = 0

            // 每批立即 commit，避免长时间锁表
            conn.commit()
        }
    }

    suspend fun execute(
        config: ConnectionConfig,
        payload: JsonObject,
        onProgress: (suspend (JsonElement) -> Unit)? = null
    ): JsonElement = withContext(Dispatchers.IO) {
        val generatePayload = json.decodeFromJsonElement<GeneratePayload>(payload)
        if (generatePayload.tables.isEmpty()) {
            throw IllegalArgumentException("'tables' must not be empty")
        }

        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)

        connection.use { conn ->
            val originalAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                for ((index, tableConfig) in generatePayload.tables.withIndex()) {
                    val state = GenerateState(conn = conn, dialect = dialect, tableName = tableConfig.tableName)

                    // 创建 Lua VM，执行脚本（insert 实时写库）
                    LuaJit().use { L ->
                        L.openLibraries()
                        applySandbox(L)
                        registerHelpers(L, state)
                        L.push(tableConfig.count.toLong())
                        L.setGlobal("count")
                        L.run(tableConfig.script)
                    }

                    // 脚本执行完毕，刷掉剩余批次（内部已 commit）
                    state.flushBatch()
                    state.currentStmt?.close()

                    onProgress?.invoke(buildJsonObject {
                        put("table", tableConfig.tableName)
                        put("inserted", state.totalInserted)
                        put("total", generatePayload.tables.size)
                        put("index", index + 1)
                        put("sql", state.currentSql)
                    })
                }
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = originalAutoCommit
            }
        }

        buildJsonObject {
            put("success", true)
            put("tablesProcessed", generatePayload.tables.size)
        }
    }

    private fun applySandbox(L: Lua) {
        val dangerous = listOf(
            "os", "io", "debug", "package", "require",
            "loadfile", "dofile", "loadstring", "load",
            "rawget", "rawset", "rawequal",
            "setfenv", "getfenv", "newproxy"
        )
        for (name in dangerous) {
            L.pushNil()
            L.setGlobal(name)
        }
    }

    private fun readLuaTable(L: Lua, index: Int): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        L.pushNil()
        while (L.next(index) != 0) {
            val key = L.toString(index + 1) ?: L.toNumber(index + 1).toString()
            val value: Any? = when {
                L.isNil(index + 2) -> null
                L.isTable(index + 2) -> null
                L.isNumber(index + 2) -> {
                    val d = L.toNumber(index + 2)
                    if (d == d.toLong().toDouble()) d.toLong() else d
                }
                L.isBoolean(index + 2) -> L.toBoolean(index + 2)
                else -> L.toString(index + 2)
            }
            result[key] = value
            L.pop(1)
        }
        return result
    }

    /**
     * 将一行绑定到 PreparedStatement 的当前批次
     */
    private fun bindRow(stmt: PreparedStatement, columns: List<String>, row: Map<String, Any?>) {
        for ((i, col) in columns.withIndex()) {
            val v = row[col]
            when (v) {
                is Long    -> stmt.setLong(i + 1, v)
                is Double  -> stmt.setDouble(i + 1, v)
                is Boolean -> stmt.setBoolean(i + 1, v)
                null       -> stmt.setNull(i + 1, java.sql.Types.NULL)
                else       -> stmt.setString(i + 1, v.toString())
            }
        }
    }

    private fun registerHelpers(L: Lua, state: GenerateState) {

        // ── insert(tableName, rowTable) ─ 实时写库 ───────────────────────────
        L.push(JFunction { lua ->
            if (!lua.isTable(2)) return@JFunction 0

            val row = readLuaTable(lua, 2)
            val columns = state.currentColumns

            // 首次调用或列结构变化：刷旧批次，创建新 PreparedStatement
            if (columns == null || row.keys.toList() != columns) {
                state.flushBatch()
                state.currentStmt?.close()

                val cols = row.keys.toList()
                val colList = cols.joinToString(", ") { state.dialect.quoteIdentifier(it) }
                val placeholders = cols.joinToString(", ", "(", ")") { "?" }
                val sql = "INSERT INTO ${state.dialect.quoteIdentifier(state.tableName)} ($colList) VALUES $placeholders"

                state.currentStmt = state.conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                state.currentColumns = cols
                state.currentSql = sql
                state.batchCount = 0
            }

            bindRow(state.currentStmt!!, state.currentColumns!!, row)
            state.currentStmt!!.addBatch()
            state.batchCount++

            // 达到批次大小 → 执行并 commit
            if (state.batchCount >= BATCH_SIZE) {
                state.flushBatch()
            }
            0
        })
        L.setGlobal("insert")

        // ── lastId() ─ 当前表最近一次 flush 获取的自增 ID ──────────────────
        L.push(JFunction { lua ->
            val id = state.lastGeneratedId
            if (id != null) lua.push(id) else lua.pushNil()
            1
        })
        L.setGlobal("lastId")

        // ── random 辅助函数 ───────────────────────────────────────────────
        L.push(JFunction { lua ->
            val min = lua.toInteger(1).toInt()
            val max = lua.toInteger(2).toInt()
            lua.push(Random.nextLong(min.toLong(), max.toLong() + 1))
            1
        })
        L.setGlobal("random_int")

        L.push(JFunction { lua ->
            lua.push(Random.nextDouble(lua.toNumber(1), lua.toNumber(2)))
            1
        })
        L.setGlobal("random_float")

        L.push(JFunction { lua ->
            val len = lua.toInteger(1).toInt().coerceIn(1, 256)
            val sb = StringBuilder(len)
            repeat(len) { sb.append(ALPHANUMERIC[Random.nextInt(ALPHANUMERIC.length)]) }
            lua.push(sb.toString())
            1
        })
        L.setGlobal("random_string")

        L.push(JFunction { lua ->
            val start = LocalDate.parse(lua.toString(1) ?: "2020-01-01", DateTimeFormatter.ISO_LOCAL_DATE)
            val end   = LocalDate.parse(lua.toString(2) ?: "2025-12-31", DateTimeFormatter.ISO_LOCAL_DATE)
            val days  = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt()
            val d = if (days > 0) Random.nextInt(0, days + 1) else 0
            lua.push(start.plusDays(d.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE))
            1
        })
        L.setGlobal("random_date")

        L.push(JFunction { lua ->
            lua.push("user_${Random.nextLong(100000, 999999999)}@example.com")
            1
        })
        L.setGlobal("random_email")

        L.push(JFunction { lua ->
            val pfx = listOf("138","139","150","151","152","186","187","188","135","136")
            lua.push(pfx[Random.nextInt(pfx.size)] + (1..8).joinToString("") { "${Random.nextInt(10)}" })
            1
        })
        L.setGlobal("random_phone")

        L.push(JFunction { lua ->
            lua.push(NAMES[Random.nextInt(NAMES.size)])
            1
        })
        L.setGlobal("random_name")

        L.push(JFunction { lua ->
            val top = lua.getTop()
            if (top > 0) lua.pushValue(Random.nextInt(1, top + 1)) else lua.pushNil()
            1
        })
        L.setGlobal("random_enum")

        L.push(JFunction { lua ->
            lua.push(UUID.randomUUID().toString())
            1
        })
        L.setGlobal("random_uuid")
    }
}
