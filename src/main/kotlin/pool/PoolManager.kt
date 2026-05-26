package com.kxxnzstdsw.pool

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Driver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

object PoolManager {
    private val logger = LoggerFactory.getLogger(PoolManager::class.java)
    private val pools = ConcurrentHashMap<String, HikariDataSource>()

    fun getConnection(config: ConnectionConfig): Connection {
        val hashKey = generateHashKey(config)
        val dataSource = pools.computeIfAbsent(hashKey) { createDataSource(config) }
        return dataSource.connection
    }

    private fun generateHashKey(config: ConnectionConfig): String {
        val input = "${config.driver}:${config.host}:${config.port}:${config.user}:${config.password}:${config.database}"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun createDataSource(config: ConnectionConfig): HikariDataSource {
        logger.info("Creating new connection pool for ${config.toHashKey()}")

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = buildJdbcUrl(config)
            username = config.user
            password = config.password

            // Performance tuning for desktop app
            maximumPoolSize = 5
            minimumIdle = 0
            idleTimeout = 600_000 // 10 minutes
            connectionTimeout = 5_000 // 5 seconds
            maxLifetime = 1_800_000 // 30 minutes

            // Connection test
            connectionTestQuery = when (config.driver) {
                Driver.mysql -> "SELECT 1"
                Driver.postgresql -> "SELECT 1"
            }
        }

        return HikariDataSource(hikariConfig)
    }

    private fun buildJdbcUrl(config: ConnectionConfig): String {
        return when (config.driver) {
            Driver.mysql -> "jdbc:mysql://${config.host}:${config.port}/${config.database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
            Driver.postgresql -> "jdbc:postgresql://${config.host}:${config.port}/${config.database}"
        }
    }

    fun closeAll() {
        logger.info("Closing all connection pools")
        pools.values.forEach { it.close() }
        pools.clear()
    }
}