package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.SystemInfoResponse
import com.kxxnzstdsw.grpc.SystemServerInfoResponse
import com.kxxnzstdsw.grpc.SystemTestConnectionResponse
import com.kxxnzstdsw.grpc.memoryInfo
import com.kxxnzstdsw.grpc.systemInfoResponse
import com.kxxnzstdsw.grpc.systemServerInfoResponse
import com.kxxnzstdsw.grpc.systemTestConnectionResponse
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.lang.management.ManagementFactory

object SystemHandler {

    fun info(): SystemInfoResponse {
        val runtime = Runtime.getRuntime()
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        val runtimeBean = ManagementFactory.getRuntimeMXBean()

        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usedMemory = totalMemory - freeMemory

        return systemInfoResponse {
            jvmVersion = System.getProperty("java.version")
            jvmVendor = System.getProperty("java.vendor")
            jvmName = System.getProperty("java.vm.name")
            osName = osBean.name
            osArch = osBean.arch
            osVersion = osBean.version
            availableProcessors = osBean.availableProcessors
            memory = memoryInfo {
                max = maxMemory
                total = totalMemory
                used = usedMemory
                free = freeMemory
            }
            uptime = runtimeBean.uptime
            pid = runtimeBean.pid
        }
    }

    /**
     * TEST_CONNECTION — 测试数据库连接是否有效
     */
    suspend fun testConnection(config: ConnectionConfig): SystemTestConnectionResponse = withContext(Dispatchers.IO) {
        try {
            val connection = PoolManager.getConnection(config)
            connection.use { conn ->
                val ok = conn.isValid(5)
                systemTestConnectionResponse {
                    this.ok = ok
                    driver = config.driver
                    host = config.host
                    port = config.port
                    database = config.database
                }
            }
        } catch (e: Exception) {
            systemTestConnectionResponse {
                ok = false
                error = e.message ?: "Unknown error"
            }
        }
    }

    /**
     * SERVER_INFO — 获取数据库服务端信息（版本、模式等）
     *
     * 方言返回的 Map 字段直接映射到 SystemServerInfoResponse 的已知字段（version/catalog/current_database/mode），
     * 未知字段打包进 extras: google.protobuf.Value。
     */
    suspend fun serverInfo(config: ConnectionConfig): SystemServerInfoResponse = withContext(Dispatchers.IO) {
        val connection = PoolManager.getConnection(config)
        val dialect = DialectLoader.getDialect(config.driver)
        connection.use { conn ->
            val info = dialect.getServerInfo(conn)
            val extras = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            val response = systemServerInfoResponse {
                info.forEach { (k, v) ->
                    when (k) {
                        "version" -> version = v
                        "catalog" -> catalog = v
                        "current_database" -> currentDatabase = v
                        "mode" -> mode = v
                        else -> extras[k] = JsonPrimitive(v)
                    }
                }
            }
            if (extras.isNotEmpty()) {
                response.toBuilder().setExtras(
                    PayloadAdapter.toValue(
                        kotlinx.serialization.json.buildJsonObject {
                            extras.forEach { (k, v) -> put(k, v) }
                        }
                    )
                ).build()
            } else {
                response
            }
        }
    }
}