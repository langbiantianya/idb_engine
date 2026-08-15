package com.kxxnzstdsw.testutil

import com.kxxnzstdsw.dialect.H2Dialect
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * H2 测试基础类 — 为每个测试提供独立 in-memory H2 数据库。
 *
 * 用法：
 * ```kotlin
 * class MyHandlerTest : H2Fixture() {
 *     @Test fun something() = runBlocking {
 *         withConnection { conn ->
 *             // use conn
 *         }
 *     }
 * }
 * ```
 *
 * - 数据库 URL: `jdbc:h2:mem:test_<UUID>;DB_CLOSE_DELAY=-1`
 * - 每个测试方法用新的 DB，互不污染
 * - 通过 [dialect] 字段拿到 H2Dialect 单例
 * - 通过 [config] 字段拿到连接配置
 * - 通过 [withConnection] / [executeUpdate] / [executeQuery] 辅助函数访问连接
 *
 * 注意：不经过 PoolManager — 直接用 DriverManager 连接，因为测试中我们频繁开关，
 * HikariCP 反而是 overhead。
 */
abstract class H2Fixture {

    protected val dialect = H2Dialect()
    protected val dbName: String = "test_${UUID.randomUUID().toString().replace("-", "")}"
    protected val config: ConnectionConfig = ConnectionConfig(
        driver = "H2",
        host = "mem",
        port = 0,
        user = "sa",
        password = "",
        database = dbName
    )

    /** JDBC URL for direct connections */
    protected val jdbcUrl: String = dialect.buildJdbcUrl(config.host, config.port, config.database)

    /**
     * 测试前：注册 H2 dialect 到 DialectLoader，让 DialectLoader.getDialect("H2") 工作
     */
    @BeforeEach
    fun registerH2Dialect() {
        // H2Dialect 是 SPI 实现，但测试中我们直接 new() — 把它注册到 DialectLoader
        DialectLoader.registerForTesting("H2", dialect)
    }

    /**
     * 测试后：清理连接池（如果有注册过的话），关闭 DB
     */
    @AfterEach
    fun cleanupH2() {
        try {
            // 删除 H2 数据库防止同内存库的下一个测试出现脏数据
            DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP ALL OBJECTS")
                }
            }
        } catch (_: Exception) {
            // 忽略：库可能已被 DROP SCHEMA 删除
        }
        // 清理 HikariCP 缓存的池（如果有）
        try {
            PoolManager.closeAll()
        } catch (_: Exception) {}
    }

    /** 直接拿一个 Connection（不走 Pool） */
    protected fun newConnection(): Connection {
        return DriverManager.getConnection(jdbcUrl, "sa", "")
    }

    /**
     * 在 [block] 中获取连接并自动关闭。连接不经过 HikariCP，
     * 因为每个测试独立 DB，连接池反而引入状态干扰。
     */
    protected inline fun <R> withConnection(block: (Connection) -> R): R {
        return newConnection().use(block)
    }

    /** 执行更新语句并返回 affectedRows */
    protected fun executeUpdate(sql: String): Int = withConnection { conn ->
        conn.createStatement().use { it.executeUpdate(sql) }
    }

    /** 执行单值查询（取第一行第一列） */
    protected fun executeQuerySingle(sql: String): String? = withConnection { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

    /** 检查表是否存在（大小写不敏感 — H2 dialect 通过 quoteIdentifier 保留小写名，未引用的会自动大写） */
    protected fun tableExists(tableName: String): Boolean = withConnection { conn ->
        conn.metaData.getTables(null, "PUBLIC", "%", arrayOf("TABLE")).use { rs ->
            while (rs.next()) {
                if (rs.getString("TABLE_NAME").equals(tableName, ignoreCase = true)) return@withConnection true
            }
            false
        }
    }

    /** 检查 schema 是否存在 */
    protected fun schemaExists(schemaName: String): Boolean = withConnection { conn ->
        conn.metaData.schemas.use { rs ->
            while (rs.next()) {
                if (rs.getString("TABLE_SCHEM").equals(schemaName, ignoreCase = true)) return@withConnection true
            }
            false
        }
    }
}