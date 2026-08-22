package com.kxxnzstdsw.testutil

import com.kxxnzstdsw.dialect.SQLiteDialect
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite 测试基础类 — 为每个测试提供独立的临时 `.db` 文件。
 *
 * 为什么不用 `jdbc:sqlite::memory:`：SQLite 内存模式每连接私有，但 PoolManager /
 * 多连接场景需要共享同一实例。这里用临时 .db 文件，每个测试一个，结束后清理。
 *
 * 所有连接均走 PoolManager（与 handler 集成测试路径一致）。
 */
abstract class SQLiteFixture {

    protected val dialect = SQLiteDialect()
    protected val dbFile: File = Files.createTempFile("sqlite-it-", ".db").toFile().apply {
        deleteOnExit()
        delete()  // SQLite 拒绝 0 字节文件，先删除让 SQLite 首次连接时创建
    }

    protected val config: ConnectionConfig = ConnectionConfig.newBuilder()
        .setDriver("Sqlite")
        .setHost("embedded")
        .setPort(0)
        .setUser("")
        .setPassword("")
        .setDatabase(dbFile.absolutePath)
        .build()

    @BeforeEach
    fun registerSQLiteDialect() {
        DialectLoader.registerForTesting("Sqlite", dialect)
    }

    @AfterEach
    fun cleanupSQLite() {
        try { PoolManager.closeAll() } catch (_: Exception) {}
        try { dbFile.delete() } catch (_: Exception) {}
    }

    /** 走 PoolManager 的连接 */
    protected fun newConnection(): Connection = PoolManager.getConnection(config)

    protected inline fun <R> withConnection(block: (Connection) -> R): R =
        newConnection().use(block)

    protected fun executeUpdate(sql: String): Int = withConnection { conn ->
        conn.createStatement().use { it.executeUpdate(sql) }
    }

    protected fun executeQuerySingle(sql: String): String? = withConnection { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

    protected fun tableExists(tableName: String): Boolean = withConnection { conn ->
        conn.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?").use { ps ->
            ps.setString(1, tableName)
            ps.executeQuery().use { rs -> rs.next() }
        }
    }
}