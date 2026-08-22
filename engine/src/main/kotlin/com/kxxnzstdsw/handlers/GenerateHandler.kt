package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.dialect.DatabaseDialect
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.DataGenerateRequest
import com.kxxnzstdsw.grpc.GenerateProgressFrame
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.generateProgressFrame
import com.kxxnzstdsw.grpc.row
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.random.Random

object GenerateHandler {
    private val logger = LoggerFactory.getLogger(GenerateHandler::class.java)

    private val NAMES = listOf(
        "张三", "李四", "王五", "赵六", "孙七", "周八", "吴九", "郑十",
        "Alice", "Bob", "Charlie", "Diana", "Eve", "Frank", "Grace", "Henry"
    )
    private val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** 单批 INSERT 行数 — addBatch/executeBatch 减少往返 */
    private const val BATCH_SIZE = 100

    /** Lua 回调与 collector 之间传递进度帧的通道容量 */
    private const val PROGRESS_CHANNEL_CAPACITY = 1024

    /**
     * 造数状态：跨 insert() / lastId() 共享的可变上下文。
     *
     * 注：[onProgress] 不再使用 — 进度通过 [progressChannel] trySend 传给 collector,
     * 避免 JFunction 内 runBlocking { } 每行分配一个 EventLoop(1M 行 = 1M EventLoop)。
     */
    private class GenerateState(
        val conn: Connection,
        val dialect: DatabaseDialect,
        val scriptIndex: Int,
        val totalScripts: Int,
        val progressChannel: Channel<GenerateProgressFrame>,
        var currentTable: String = "",
        var currentStmt: PreparedStatement? = null,
        var currentColumns: List<String>? = null,
        var currentSql: String = "",
        var totalInserted: Long = 0,
        var scriptInserted: Long = 0,
        var lastGeneratedId: Long? = null,
        var batchPending: Int = 0
    ) {
        fun closeStmt() {
            // 关闭前 flush 当前批 — 否则最后 < BATCH_SIZE 行的 INSERT 不会执行
            if (batchPending > 0) {
                flushBatch(this, final = true)
            }
            currentStmt?.close()
            currentStmt = null
            currentColumns = null
            batchPending = 0
        }
    }

    /**
     * 流式造数：每条 INSERT 实时通过 [onProgress] 回调一帧 [GenerateProgressFrame]。
     *
     * 性能优化(v2.8+):
     * - JFunction 内不再 runBlocking 桥接 suspend,而是 [Channel.trySend] 到一个
     *   独立 collector 协程 — 消除每行 EventLoop 分配。
     * - INSERT 用 addBatch + executeBatch,每 BATCH_SIZE 行一次往返。
     */
    suspend fun execute(
        config: ConnectionConfig,
        req: DataGenerateRequest,
        onProgress: (suspend (GenerateProgressFrame) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        if (req.tablesList.isEmpty()) {
            throw IllegalArgumentException("'tables' must not be empty")
        }

        val connection = PoolManager.getConnection(config, req.schema)
        val dialect = DialectLoader.getDialect(config.driver)

        connection.use { conn ->
            for ((index, tableConfig) in req.tablesList.withIndex()) {
                val channel = Channel<GenerateProgressFrame>(
                    capacity = PROGRESS_CHANNEL_CAPACITY,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
                val state = GenerateState(
                    conn = conn, dialect = dialect,
                    scriptIndex = index, totalScripts = req.tablesList.size,
                    progressChannel = channel
                )

                // Collector: 从 channel 读帧,调用 caller 提供的 onProgress 回调
                val collectorJob = launch(Dispatchers.IO) {
                    for (frame in channel) {
                        try {
                            onProgress?.invoke(frame)
                        } catch (e: Exception) {
                            logger.warn("Progress callback failed: ${e.message}")
                        }
                    }
                }

                try {
                    createLuaEngine(req.luaVersion.ifBlank { "luajit" }).use { L ->
                        L.openLibraries()
                        applySandbox(L)
                        registerHelpers(L, state)
                        L.run(tableConfig.script)
                    }
                    state.closeStmt()
                } finally {
                    // 关闭通道让 collector 退出
                    channel.close()
                    collectorJob.join()
                }
            }
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
                L.isJavaObject(index + 2) -> L.toJavaObject(index + 2)
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
                is LocalDate     -> stmt.setDate(i + 1, java.sql.Date.valueOf(v))
                is LocalDateTime -> stmt.setTimestamp(i + 1, java.sql.Timestamp.valueOf(v))
                is LocalTime     -> stmt.setTime(i + 1, java.sql.Time.valueOf(v))
                else       -> stmt.setString(i + 1, v.toString())
            }
        }
    }

    /** 表/列变化或批满时调用 — flush 当前批,可选地重建 stmt */
    private fun flushBatch(state: GenerateState, final: Boolean = false) {
        val stmt = state.currentStmt ?: return
        if (state.batchPending == 0) return
        stmt.executeBatch()
        state.batchPending = 0
        // 取最后一条的 generated key (lastId 仍只关心最后一张表)
        if (final) {
            try {
                stmt.generatedKeys.use { rs ->
                    var last: Long? = null
                    while (rs.next()) last = rs.getLong(1)
                    if (last != null) state.lastGeneratedId = last
                }
            } catch (e: Exception) {
                logger.debug("Could not retrieve generated key: ${e.message}")
            }
        }
    }

    private fun registerHelpers(L: Lua, state: GenerateState) {

        // ── insert(tableName, rowTable) — 逐条绑定 + addBatch + 流式回报 ──
        L.push(JFunction { lua ->
            if (!lua.isTable(2)) return@JFunction 0

            val tableName = lua.toString(1) ?: return@JFunction 0
            val row = readLuaTable(lua, 2)

            // 表名或列结构变化时 flush 当前批 + 重建 PreparedStatement
            val columns = state.currentColumns
            if (tableName != state.currentTable || columns == null || row.keys.toList() != columns) {
                flushBatch(state, final = false)
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
            state.currentStmt!!.addBatch()
            state.batchPending++

            // 批满 flush
            if (state.batchPending >= BATCH_SIZE) {
                flushBatch(state, final = false)
            }

            state.totalInserted++
            state.scriptInserted++

            // 非阻塞 trySend — JFunction 内不分配 EventLoop
            val rowProto = row {
                row.forEach { (k, v) ->
                    val value = when (v) {
                        null -> PayloadAdapter.toValue(JsonNull)
                        is Long -> PayloadAdapter.toValue(JsonPrimitive(v))
                        is Double -> PayloadAdapter.toValue(JsonPrimitive(v))
                        is Boolean -> PayloadAdapter.toValue(JsonPrimitive(v))
                        else -> PayloadAdapter.toValue(JsonPrimitive(v.toString()))
                    }
                    values.put(k, value)
                }
            }
            val frame = generateProgressFrame {
                table = state.currentTable
                inserted = state.totalInserted
                scriptInserted = state.scriptInserted
                scriptIndex = state.scriptIndex + 1
                totalScripts = state.totalScripts
                sql = state.currentSql
                data = rowProto
            }
            state.progressChannel.trySend(frame)

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
            val date = start.plusDays((if (days > 0) Random.nextInt(0, days + 1) else 0).toLong())
            lua.pushJavaObject(date)
            1
        })
        L.setGlobal("random_date")

        L.push(JFunction { lua ->
            val start = LocalDateTime.parse(
                (lua.toString(1) ?: "2020-01-01") + "T00:00:00",
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
            )
            val end = LocalDateTime.parse(
                (lua.toString(2) ?: "2025-12-31") + "T23:59:59",
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
            )
            val seconds = java.time.temporal.ChronoUnit.SECONDS.between(start, end).toInt()
            val dt = start.plusSeconds(
                if (seconds > 0) Random.nextLong(0L, seconds.toLong() + 1L) else 0L
            )
            lua.pushJavaObject(dt)
            1
        })
        L.setGlobal("random_datetime")

        L.push(JFunction { lua ->
            val hour = Random.nextInt(0, 24)
            val minute = Random.nextInt(0, 60)
            val second = Random.nextInt(0, 60)
            val t = LocalTime.of(hour, minute, second)
            lua.pushJavaObject(t)
            1
        })
        L.setGlobal("random_time")

        L.push(JFunction { lua ->
            lua.push("user_${Random.nextLong(100000, 999999999)}@example.com")
            1
        })
        L.setGlobal("random_email")

        L.push(JFunction { lua ->
            val pfx = listOf("138","139","150","151","152","186","187","188","135","136")
            // 用 CharArray 避免每字符 toString() 分配
            val digits = CharArray(8)
            for (i in 0 until 8) digits[i] = ('0' + Random.nextInt(10))
            lua.push(pfx[Random.nextInt(pfx.size)] + String(digits))
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