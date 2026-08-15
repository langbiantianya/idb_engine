package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonNull
import java.lang.management.ManagementFactory

object SystemHandler {

    fun info(): JsonElement {
        val runtime = Runtime.getRuntime()
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        val runtimeBean = ManagementFactory.getRuntimeMXBean()

        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usedMemory = totalMemory - freeMemory

        return buildJsonObject {
            put("jvmVersion", System.getProperty("java.version"))
            put("jvmVendor", System.getProperty("java.vendor"))
            put("jvmName", System.getProperty("java.vm.name"))
            put("osName", osBean.name)
            put("osArch", osBean.arch)
            put("osVersion", osBean.version)
            put("availableProcessors", osBean.availableProcessors)
            putJsonObject("memory") {
                put("max", maxMemory)
                put("total", totalMemory)
                put("used", usedMemory)
                put("free", freeMemory)
            }
            put("uptime", runtimeBean.uptime)
            put("pid", runtimeBean.pid)
        }
    }

    /**
     * TEST_CONNECTION — 测试数据库连接是否有效
     */
    suspend fun testConnection(config: ConnectionConfig): JsonElement = withContext(Dispatchers.IO) {
        try {
            val connection = PoolManager.getConnection(config)
            connection.use { conn ->
                val ok = conn.isValid(5)
                buildJsonObject {
                    put("ok", ok)
                    put("driver", config.driver)
                    put("host", config.host)
                    put("port", config.port)
                    put("database", config.database)
                }
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", false)
                put("error", e.message ?: "Unknown error")
            }
        }
    }

    /**
     * SERVER_INFO — 获取数据库服务端信息（版本、模式等）
     */
    suspend fun serverInfo(config: ConnectionConfig): JsonElement = withContext(Dispatchers.IO) {
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)
        connection.use { conn ->
            val info = dialect.getServerInfo(conn)
            buildJsonObject {
                info.forEach { (k, v) -> put(k, v) }
            }
        }
    }
}
