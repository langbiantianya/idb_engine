package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.dialect.DatabaseDialect
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.*
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.Lua
import party.iroiro.luajava.lua51.Lua51
import party.iroiro.luajava.lua52.Lua52
import party.iroiro.luajava.lua53.Lua53
import party.iroiro.luajava.lua54.Lua54
import party.iroiro.luajava.lua55.Lua55
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

    /**
     * 造数状态：跨 insert() / lastId() 共享的可变上下文
     */
    private class GenerateState(
        val conn: Connection,
        val dialect: DatabaseDialect,
        val scriptIndex: Int,
        val totalScripts: Int,
        val onProgress: (suspend (JsonElement) -> Unit)?,
        var currentTable: String = "",
        var currentStmt: PreparedStatement? = null,
        var currentColumns: List<String>? = null,
        var currentSql: String = "",
        var totalInserted: Long = 0,
        var scriptInserted: Long = 0,
        var lastGeneratedId: Long? = null
    ) {
        fun closeStmt() {
            currentStmt?.close()
            currentStmt = null
        }
    }

    suspend fun execute(
        config: ConnectionConfig,
        payload: JsonObject,
        onProgress: (suspend (JsonElement) -> Unit)? = null
    ): JsonElement = withContext(Dispatchers.IO) {
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val generatePayload = json.decodeFromJsonElement<GeneratePayload>(payload)
        if (generatePayload.tables.isEmpty()) {
            throw IllegalArgumentException("'tables' must not be empty")
        }

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        connection.use { conn ->
            try {
                for ((index, tableConfig) in generatePayload.tables.withIndex()) {
                    val state = GenerateState(
                        conn = conn, dialect = dialect,
                        scriptIndex = index, totalScripts = generatePayload.tables.size,
                        onProgress = onProgress
                    )

                    createLuaEngine(generatePayload.luaVersion).use { L ->
                        L.openLibraries()
                        applySandbox(L)
                        registerHelpers(L, state)
                        L.run(tableConfig.script)
                    }

                    state.closeStmt()
                }
            } catch (e: Exception) {
                throw e
            }
        }

        buildJsonObject {
            put("success", true)
            put("tablesProcessed", generatePayload.tables.size)
        }
    }

    private fun createLuaEngine(version: String): Lua = when (version.lowercase()) {
        "luajit", "jit" -> LuaJit()
        "5.1", "lua51", "lua5.1" -> Lua51()
        "5.2", "lua52", "lua5.2" -> Lua52()
        "5.3", "lua53", "lua5.3" -> Lua53()
        "5.4", "lua54", "lua5.4" -> Lua54()
        "5.5", "lua55", "lua5.5" -> Lua55()
        else -> throw IllegalArgumentException(
            "Unsupported luaVersion: '$version'. Use: luajit, 5.1, 5.2, 5.3, 5.4, 5.5"
        )
    }

    private fun applySandbox(L: Lua) {
        for (name in listOf(
            "os", "io", "debug", "package", "require",
            "loadfile", "dofile", "loadstring", "load",
            "rawget", "rawset", "rawequal",
            "setfenv", "getfenv", "newproxy"
        )) {
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

    private fun bindRow(stmt: PreparedStatement, columns: List<String>, row: Map<String, Any?>) {
        for ((i, col) in columns.withIndex()) {
            when (val v = row[col]) {
                is Long    -> stmt.setLong(i + 1, v)
                is Double  -> stmt.setDouble(i + 1, v)
                is Boolean -> stmt.setBoolean(i + 1, v)
                null       -> stmt.setNull(i + 1, java.sql.Types.NULL)
                else       -> stmt.setString(i + 1, v.toString())
            }
        }
    }

    private fun registerHelpers(L: Lua, state: GenerateState) {

        // ── insert(tableName, rowTable) — 逐条插入 + 实时流式回报 ──
        L.push(JFunction { lua ->
            if (!lua.isTable(2)) return@JFunction 0

            val tableName = lua.toString(1) ?: return@JFunction 0
            val row = readLuaTable(lua, 2)

            // 表名或列结构变化时重建 PreparedStatement
            val columns = state.currentColumns
            if (tableName != state.currentTable || columns == null || row.keys.toList() != columns) {
                state.closeStmt()
                val cols = row.keys.toList()
                val colList = cols.joinToString(", ") { state.dialect.quoteIdentifier(it) }
                val placeholders = cols.joinToString(", ", "(", ")") { "?" }
                val sql = "INSERT INTO ${state.dialect.quoteIdentifier(tableName)} ($colList) VALUES $placeholders"
                state.currentStmt = state.conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                state.currentTable = tableName
                state.currentColumns = cols
                state.currentSql = sql
            }

            bindRow(state.currentStmt!!, state.currentColumns!!, row)
            state.currentStmt!!.executeUpdate()

            // 获取自增 ID
            try {
                state.currentStmt!!.generatedKeys.use { rs ->
                    if (rs.next()) state.lastGeneratedId = rs.getLong(1)
                }
            } catch (e: Exception) {
                logger.debug("Could not retrieve generated key: ${e.message}")
            }

            state.totalInserted++
            state.scriptInserted++

            // 实时流式回报（包含 SQL 和实际插入的数据）
            state.onProgress?.let { cb ->
                runBlocking {
                    cb(buildJsonObject {
                        put("table", state.currentTable)
                        put("inserted", state.totalInserted)
                        put("scriptInserted", state.scriptInserted)
                        put("scriptIndex", state.scriptIndex + 1)
                        put("totalScripts", state.totalScripts)
                        put("sql", state.currentSql)
                        put("data", buildJsonObject {
                            row.forEach { (k, v) ->
                                when (v) {
                                    is Long -> put(k, v)
                                    is Double -> put(k, v)
                                    is Boolean -> put(k, v)
                                    null -> put(k, JsonNull)
                                    else -> put(k, v.toString())
                                }
                            }
                        })
                    })
                }
            }
            0
        })
        L.setGlobal("insert")

        // ── lastId() — 当前表最近一条插入的自增 ID ──
        L.push(JFunction { lua ->
            val id = state.lastGeneratedId
            if (id != null) lua.push(id) else lua.pushNil()
            1
        })
        L.setGlobal("lastId")

        // ── random 辅助函数 ──
        L.push(JFunction { lua ->
            lua.push(Random.nextLong(lua.toInteger(1), lua.toInteger(2) + 1))
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
            lua.push(start.plusDays((if (days > 0) Random.nextInt(0, days + 1) else 0).toLong())
                .format(DateTimeFormatter.ISO_LOCAL_DATE))
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
