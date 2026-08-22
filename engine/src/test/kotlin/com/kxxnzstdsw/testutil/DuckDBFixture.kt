package com.kxxnzstdsw.testutil

import com.kxxnzstdsw.dialect.DuckDBDialect
import com.kxxnzstdsw.dialect.ExcelToDuckDbCache
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.nio.file.Files
import java.sql.Connection

/**
 * DuckDB 测试基础类 — 为每个测试提供独立的临时 `.duckdb` 文件。
 *
 * 为什么不用 `:memory:`：DuckDB 内存模式每连接私有 DB，但 PoolManager / 多连接场景需要共享同一实例。
 * 这里用临时 .duckdb 文件，每个测试一个，结束后清理。
 *
 * 关键：所有连接均走 PoolManager（dialect buildJdbcUrl）以避免 DuckDB 同文件不同配置冲突。
 * DuckDB 1.5.5.1 严格校验同文件连接配置是否一致，混合 DriverManager + HikariCP 会失败。
 */
abstract class DuckDBFixture {

    protected val dialect = DuckDBDialect()
    // DuckDB 1.5.5.1 会拒绝打开 0 字节文件 —— 必须先建空路径再让 DuckDB自己创建
    protected val dbFile: File = Files.createTempFile("duckdb-it-", ".duckdb").toFile().apply {
        deleteOnExit()
        delete()  // 删掉空文件，让 DuckDB 首次连接时创建
    }

    protected val config: ConnectionConfig = ConnectionConfig.newBuilder()
        .setDriver("Duckdb")
        .setHost("embedded")
        .setPort(0)
        .setUser("")
        .setPassword("")
        .setDatabase(dbFile.absolutePath)
        .build()

    @BeforeEach
    fun registerDuckDBDialect() {
        DialectLoader.registerForTesting("Duckdb", dialect)
    }

    @AfterEach
    fun cleanupDuckDB() {
        try { PoolManager.closeAll() } catch (_: Exception) {}
        try { dbFile.delete() } catch (_: Exception) {}
        try { ExcelToDuckDbCache.cleanup() } catch (_: Exception) {}
    }

    /** 走 PoolManager（dialect）的连接 —— 与其他 handler 测试的连接路径一致 */
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
        conn.metaData.getTables(null, "main", "%", arrayOf("TABLE")).use { rs ->
            while (rs.next()) {
                if (rs.getString("TABLE_NAME").equals(tableName, ignoreCase = true)) return@withConnection true
            }
            false
        }
    }

    protected fun schemaExists(schemaName: String): Boolean = withConnection { conn ->
        conn.metaData.schemas.use { rs ->
            while (rs.next()) {
                if (rs.getString("TABLE_SCHEM").equals(schemaName, ignoreCase = true)) return@withConnection true
            }
            false
        }
    }

    /**
     * 创建一个唯一的 .duckdb 路径（用于并发测试避免共享）
     */
    protected fun newTempDbPath(): String {
        val prefix = TestIds.uniqueName("duckdb-it")
        return Files.createTempFile("$prefix-", ".duckdb").toFile().apply {
            deleteOnExit()
        }.absolutePath
    }
}
