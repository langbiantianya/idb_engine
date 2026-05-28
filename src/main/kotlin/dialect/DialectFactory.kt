package com.kxxnzstdsw.dialect

import com.kxxnzstdsw.models.Driver

/**
 * Factory for creating database dialect instances
 */
object DialectFactory {
    fun getDialect(driver: Driver): DatabaseDialect {
        return when (driver) {
            Driver.Mysql -> MySQLDialect()
            Driver.Postgresql -> PostgreSQLDialect()
        }
    }
}