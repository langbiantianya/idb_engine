package com.kxxnzstdsw.loader

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object DriverLoader {
    private val logger = LoggerFactory.getLogger(DriverLoader::class.java)
    private val classLoaders = CopyOnWriteArrayList<URLClassLoader>()

    fun loadFromDir(dir: File) {
        if (!dir.isDirectory) {
            logger.debug("Drivers directory not found: ${dir.absolutePath}")
            return
        }

        val jars = dir.listFiles { f -> f.extension == "jar" } ?: return
        if (jars.isEmpty()) {
            logger.debug("No driver JARs found in ${dir.absolutePath}")
            return
        }

        val urls = jars.map { it.toURI().toURL() }.toTypedArray()
        val classLoader = URLClassLoader(urls, this::class.java.classLoader)

        val drivers = ServiceLoader.load(Driver::class.java, classLoader)
        var count = 0
        for (driver in drivers) {
            try {
                DriverManager.registerDriver(DriverDelegate(driver))
                count++
                logger.info("Registered dynamic JDBC driver: ${driver.javaClass.name}")
            } catch (e: Exception) {
                logger.warn("Failed to register driver: ${driver.javaClass.name}", e)
            }
        }

        if (count > 0) {
            classLoaders.add(classLoader)
            logger.info("Loaded $count dynamic JDBC driver(s) from ${dir.absolutePath}")
        } else {
            classLoader.close()
            logger.warn("No valid JDBC drivers found in ${dir.absolutePath}")
        }
    }

    fun closeAll() {
        classLoaders.forEach { cl ->
            try { cl.close() } catch (_: Exception) {}
        }
        classLoaders.clear()
    }

    private class DriverDelegate(private val delegate: Driver) : Driver {
        override fun connect(url: String?, info: Properties?) = delegate.connect(url, info)
        override fun acceptsURL(url: String?) = delegate.acceptsURL(url)
        override fun getPropertyInfo(url: String?, info: Properties?) = delegate.getPropertyInfo(url, info)
        override fun getMajorVersion() = delegate.majorVersion
        override fun getMinorVersion() = delegate.minorVersion
        override fun jdbcCompliant() = delegate.jdbcCompliant()
        override fun getParentLogger() = delegate.parentLogger
    }
}
