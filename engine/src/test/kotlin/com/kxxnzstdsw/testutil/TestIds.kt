package com.kxxnzstdsw.testutil

import java.util.concurrent.atomic.AtomicLong

/**
 * 测试 ID 生成器 — 统一替换散落的 [System.nanoTime] / [UUID.randomUUID] 调用。
 *
 * ## 为什么需要它
 *
 * 测试中常见两种"唯一 ID"实现,各有坑:
 * - **`System.nanoTime()`** — 解析度纳秒但快机器两次连续调用可能返回同值(尤其
 *   JUnit 跑 [ParameterizedTest] / 顺序 [TestMethod] 时相邻方法的 ID 可能撞)。
 *   撞名 → 同名表/同 ID 请求 → 状态污染 → 偶发失败。
 * - **`UUID.randomUUID().toString().take(8)`** — 截 8 字符只有 32 位熵,生日
 *   悖论 ~50% 撞率在 65k 次。NamedPipeIpcTransportIntegrationTest 用过这种。
 *
 * ## 设计
 *
 * - 单 JVM 内**单调递增**的 [AtomicLong] 计数器,无撞名可能
 * - 三种命名风格对应三种典型场景:
 *   - [next] (中划线) — gRPC request id (`"r-fng-1"`)
 *   - [nextSql] (下划线) — SQL 标识符 (`"users_42"`)
 *   - [uniqueName] (短 hex) — 文件/UDS/DB 名(JVM 生命周期内单调)
 *
 * ## 用法
 *
 * ```kotlin
 * val reqId = TestIds.next("r-gen")
 * val tableName = TestIds.nextSql("users")
 * val tempDb = TestIds.uniqueName("test")
 * ```
 *
 * @see <a href="#">v2.9 #18 优化 — 统一测试 ID 工具</a>
 */
object TestIds {
    private val counter = AtomicLong(0)

    /**
     * 顺序 ID — 适合 gRPC request id、Trace ID 等"每调用唯一"场景。
     *
     * 格式: `"${prefix}-${seq}"`,例如 `"r-fng-1"` / `"r-fng-2"`
     */
    fun next(prefix: String = "id"): String {
        val seq = counter.incrementAndGet()
        return "$prefix-$seq"
    }

    /**
     * 顺序 SQL 标识符 — 适合表名/函数名/列名(下划线分隔,SQL 标识符合法字符)。
     *
     * 格式: `"${prefix}_${seq}"`,例如 `"users_42"`
     */
    fun nextSql(prefix: String): String {
        val seq = counter.incrementAndGet()
        return "${prefix}_$seq"
    }

    /**
     * JVM-唯一短名 — 适合文件名、UDS 路径、临时 DB 名。
     *
     * 用 12 hex 字符(48 位熵)而非单调计数器,以避免:
     * - 多 JVM 串行运行时文件名冲突(每个 JVM 都从 1 开始)
     * - 临时文件 delete 后下次 run 复用同名的边界场景
     *
     * 格式: `"${prefix}_${hex12}"`,例如 `"duckdb-it_3a4b5c6d7e8f"`
     *
     * 使用 `_` 而非 `-` 分隔:此 name 常被作为 H2/DuckDB 数据库名 →
     * JDBC catalog 字符必须 SQL 安全(`-` 在 H2Dialect.validateRoutineDDL
     * 的 schema 正则 `[A-Za-z_][A-Za-z0-9_]*` 中不通过)。`_` 对所有调用点
     * (文件名、UDS、pipe name、SQL catalog) 均合法。
     */
    fun uniqueName(prefix: String): String {
        // 直接用 [java.util.UUID] 的随机性 — counter 在 JVM 重启时归零,
        // 但文件命名场景需要跨 JVM 唯一。
        val raw = java.util.UUID.randomUUID().toString().replace("-", "")
        return "${prefix}_${raw.take(12)}"
    }
}