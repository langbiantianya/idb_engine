package com.kxxnzstdsw.loader

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.sql.Driver
import java.sql.DriverManager
import java.util.*

object DriverLoader {
    private val logger = LoggerFactory.getLogger(DriverLoader::class.java)
    private var driverClassLoader: URLClassLoader? = null

    fun getClassLoader(): ClassLoader? = driverClassLoader

    /**
     * 获取 JAR 包所在目录
     */
    private fun getJarDir(): File {
        val codeSource = DriverLoader::class.java.protectionDomain.codeSource
        return File(codeSource.location.toURI()).parentFile
    }

    /**
     * 加载指定目录下的 JDBC 驱动，同时查找 JAR 同级 drivers/ 和 CWD drivers/
     */
    fun loadFromDir(dir: File) {
        // 优先从 JAR 同级目录查找，其次从 CWD 查找
        val jarDrivers = File(getJarDir(), "drivers")
        val candidates = listOf(dir.absoluteFile, jarDrivers).filter { it.isDirectory }

        if (candidates.isEmpty()) {
            logger.debug("No drivers directory found (tried: ${dir.absolutePath}, ${jarDrivers.absolutePath})")
            return
        }

        for (candidate in candidates) {
            loadFromSingleDir(candidate)
        }
    }

    private fun loadFromSingleDir(dir: File) {
        val jars = dir.listFiles { f -> f.extension == "jar" } ?: return
        if (jars.isEmpty()) {
            logger.debug("No driver JARs found in ${dir.absolutePath}")
            return
        }

        val urls = jars.map { it.toURI().toURL() }.toTypedArray()
        val parent = driverClassLoader ?: Thread.currentThread().contextClassLoader
        val classLoader = URLClassLoader(urls, parent)

        val drivers = ServiceLoader.load(Driver::class.java, classLoader)
        var count = 0
        for (driver in drivers) {
            try {
                DriverManager.registerDriver(driver)
                count++
                logger.info("Registered dynamic JDBC driver: ${driver.javaClass.name}")
            } catch (e: Exception) {
                logger.warn("Failed to register driver: ${driver.javaClass.name}", e)
            }
        }

        if (count > 0) {
            driverClassLoader = classLoader
            Thread.currentThread().contextClassLoader = classLoader
            logger.info("Loaded $count dynamic JDBC driver(s) from ${dir.absolutePath}")
        } else {
            classLoader.close()
            logger.warn("No valid JDBC drivers found in ${dir.absolutePath}")
        }
    }

    fun closeAll() {
        driverClassLoader?.let {
            try { it.close() } catch (_: Exception) {}
        }
        driverClassLoader = null
    }
}
