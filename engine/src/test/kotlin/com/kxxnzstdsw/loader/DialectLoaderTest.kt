package com.kxxnzstdsw.loader

import com.kxxnzstdsw.dialect.H2Dialect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DialectLoader 单元测试 — 验证 SPI 加载器能正确发现 dialects/ 目录中的 JAR，
 * 并能通过 registerForTesting 直接注入方言。
 */
class DialectLoaderTest {

    @AfterEach
    fun tearDown() {
        DialectLoader.closeAll()
    }

    @Test
    fun `loadFromDir discovers dialects from build artifacts`() {
        val dirs = listOf(
            File("engine/build/libs/dialects"),
            File("../engine/build/libs/dialects"),
            File("../../engine/build/libs/dialects"),
            File("build/libs/dialects")
        )
        val dir = dirs.firstOrNull { it.isDirectory }
        if (dir == null) {
            // 没有 dialects 目录，跳过（开发环境可能没构建）
            println("Skipping: no dialects/ directory found in build artifacts")
            return
        }

        DialectLoader.loadFromDir(dir)

        // 至少能拿到一个方言
        val drivers = listOf("Mysql", "Postgresql", "H2")
        val found = drivers.filter { try {
            DialectLoader.getDialect(it); true
        } catch (_: Exception) { false } }
        assertTrue(found.isNotEmpty(),
            "spike: 至少一个方言应被加载。在 ${dir.absolutePath} 下找到：${dir.listFiles()?.map { it.name }}")
    }

    @Test
    fun `registerForTesting accepts arbitrary dialect and getDialect returns it`() {
        // H2Dialect 是个真实实现（继承所有抽象方法），把它当 "test dialect" 用
        DialectLoader.registerForTesting("H2", H2Dialect())
        val got = DialectLoader.getDialect("H2")
        assertEquals("H2", got.driverName)
        assertNotNull(got)
    }

    @Test
    fun `getDialect throws when no plugin registered`() {
        try {
            DialectLoader.getDialect("NonexistentDB")
            fail("应抛 UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("NonexistentDB"))
        }
    }

    @Test
    fun `closeAll clears registered dialects`() {
        DialectLoader.registerForTesting("H2", H2Dialect())
        DialectLoader.closeAll()
        try {
            DialectLoader.getDialect("H2")
            fail("closeAll 后应找不到 H2")
        } catch (e: UnsupportedOperationException) {
            // 预期
        }
    }

    @Test
    fun `registerForTesting overwrites previous entry`() {
        val first = H2Dialect()
        DialectLoader.registerForTesting("H2", first)
        assertEquals(first, DialectLoader.getDialect("H2"))

        val second = H2Dialect()
        DialectLoader.registerForTesting("H2", second)
        assertEquals(second, DialectLoader.getDialect("H2"))
    }
}
