package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.MemoryInfo
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.SystemInfoResponse
import com.kxxnzstdsw.grpc.SystemServerInfoResponse
import com.kxxnzstdsw.grpc.SystemTestConnectionResponse
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

        val memory = MemoryInfo.newBuilder()
            .setMax(maxMemory)
            .setTotal(totalMemory)
            .setUsed(usedMemory)
            .setFree(freeMemory)
            .build()

        return SystemInfoResponse.newBuilder()
            .setJvmVersion(System.getProperty("java.version"))
            .setJvmVendor(System.getProperty("java.vendor"))
            .setJvmName(System.getProperty("java.vm.name"))
            .setOsName(osBean.name)
            .setOsArch(osBean.arch)
            .setOsVersion(osBean.version)
            .setAvailableProcessors(osBean.availableProcessors)
            .setMemory(memory)
            .setUptime(runtimeBean.uptime)
            .setPid(runtimeBean.pid)
            .build()
    }

    /**
     * TEST_CONNECTION — 测试数据库连接是否有效
     */
    suspend fun testConnection(config: ConnectionConfig): SystemTestConnectionResponse = withContext(Dispatchers.IO) {
        try {
            val connection = PoolManager.getConnection(config)
            connection.use { conn ->
                val ok = conn.isValid(5)
                SystemTestConnectionResponse.newBuilder()
                    .setOk(ok)
                    .setDriver(config.driver)
                    .setHost(config.host)
                    .setPort(config.port)
                    .setDatabase(config.database)
                    .build()
            }
        } catch (e: Exception) {
            SystemTestConnectionResponse.newBuilder()
                .setOk(false)
                .setError(e.message ?: "Unknown error")
                .build()
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
            val builder = SystemServerInfoResponse.newBuilder()
            val extras = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            info.forEach { (k, v) ->
                when (k) {
                    "version" -> builder.setVersion(v)
                    "catalog" -> builder.setCatalog(v)
                    "current_database" -> builder.setCurrentDatabase(v)
                    "mode" -> builder.setMode(v)
                    else -> extras[k] = JsonPrimitive(v)
                }
            }
            if (extras.isNotEmpty()) {
                builder.setExtras(
                    PayloadAdapter.toValue(
                        kotlinx.serialization.json.buildJsonObject {
                            extras.forEach { (k, v) -> put(k, v) }
                        }
                    )
                )
            }
            builder.build()
        }
    }
}