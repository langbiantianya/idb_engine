package com.kxxnzstdsw.loader

import com.kxxnzstdsw.dialect.DatabaseDialect
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.util.*

/**
 * 方言插件动态加载器。
 * 扫描指定目录中的 JAR 文件，通过 SPI（ServiceLoader）发现并注册所有 DatabaseDialect 实现。
 */
object DialectLoader {
    private val logger = LoggerFactory.getLogger(DialectLoader::class.java)
    private val dialects = mutableMapOf<String, DatabaseDialect>()
    private var dialectClassLoader: URLClassLoader? = null

    /**
     * 获取 JAR 包所在目录
     */
    private fun getJarDir(): File {
        val codeSource = DialectLoader::class.java.protectionDomain.codeSource
        return File(codeSource.location.toURI()).parentFile
    }

    /**
     * 加载指定目录下的方言插件，同时查找 JAR 同级 dialects/ 和 CWD dialects/
     */
    fun loadFromDir(dir: File) {
        val jarDialects = File(getJarDir(), "dialects")
        val candidates = listOf(dir.absoluteFile, jarDialects).filter { it.isDirectory }

        if (candidates.isEmpty()) {
            logger.debug("No dialects directory found (tried: ${dir.absolutePath}, ${jarDialects.absolutePath})")
            return
        }

        for (candidate in candidates) {
            loadFromSingleDir(candidate)
        }
    }

    private fun loadFromSingleDir(dir: File) {
        val jars = dir.listFiles { f -> f.extension == "jar" } ?: return
        if (jars.isEmpty()) {
            logger.debug("No dialect JARs found in ${dir.absolutePath}")
            return
        }

        val urls = jars.map { it.toURI().toURL() }.toTypedArray()
        val parent = dialectClassLoader ?: Thread.currentThread().contextClassLoader
        val classLoader = URLClassLoader(urls, parent)

        val serviceLoader = ServiceLoader.load(DatabaseDialect::class.java, classLoader)
        var count = 0
        for (dialect in serviceLoader) {
            try {
                dialects[dialect.driverName] = dialect
                count++
                logger.info("Registered dialect plugin: ${dialect.javaClass.name} (driver=${dialect.driverName})")
            } catch (e: Exception) {
                logger.warn("Failed to register dialect: ${dialect.javaClass.name}", e)
            }
        }

        if (count > 0) {
            dialectClassLoader = classLoader
            logger.info("Loaded $count dialect plugin(s) from ${dir.absolutePath}")
        } else {
            classLoader.close()
            logger.debug("No valid dialect plugins found in ${dir.absolutePath}")
        }
    }

    /**
     * 获取指定驱动名的方言实例
     * @param driverName 驱动枚举名（如 "Mysql"、"Postgresql"）
     */
    fun getDialect(driverName: String): DatabaseDialect {
        return dialects[driverName]
            ?: throw UnsupportedOperationException("No dialect plugin loaded for driver: $driverName")
    }

    /**
     * 列出所有已加载的方言实例（v2.8 新增 — 用于前端动态渲染连接表单）。
     *
     * 返回的是当前快照，按 driverName 排序保证稳定顺序。
     * 调用方不应持有此集合后假设它永远不会变（loadFromDir 可累积注册）；
     * 每次调用都会拿到最新注册表。
     */
    fun getAllDialects(): List<DatabaseDialect> {
        return dialects.values.sortedBy { it.driverName }
    }

    /**
     * 直接注册一个方言实例（用于测试 — 跳过 SPI 扫描）。
     */
    fun registerForTesting(driverName: String, dialect: DatabaseDialect) {
        dialects[driverName] = dialect
    }

    fun closeAll() {
        dialectClassLoader?.let {
            try { it.close() } catch (_: Exception) {}
        }
        dialectClassLoader = null
        dialects.clear()
    }
}
