package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Driver
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object TableHandler {
    suspend fun list(config: ConnectionConfig): JsonElement = withContext(Dispatchers.IO) {
        val connection = PoolManager.getConnection(config)
        return@withContext connection.use { conn ->
            val tables = mutableListOf<Map<String, String>>()
            val metaData = conn.metaData
            metaData.getTables(config.database, null, "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) {
                    tables.add(mapOf(
                        "name" to rs.getString("TABLE_NAME"),
                        "type" to rs.getString("TABLE_TYPE")
                    ))
                }
            }
            Json.encodeToJsonElement(tables)
        }
    }

    suspend fun columnList(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'tableName' in payload")
        val connection = PoolManager.getConnection(config)
        return@withContext connection.use { conn ->
            val columns = mutableListOf<JsonObject>()
            val metaData = conn.metaData

            // Get primary keys
            val primaryKeys = mutableSetOf<String>()
            metaData.getPrimaryKeys(config.database, null, tableName).use { rs ->
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"))
                }
            }

            // Get columns
            metaData.getColumns(config.database, null, tableName, "%").use { rs ->
                while (rs.next()) {
                    val columnName = rs.getString("COLUMN_NAME")
                    val defaultValue = rs.getString("COLUMN_DEF")

                    columns.add(buildJsonObject {
                        put("name", columnName)
                        put("type", rs.getString("TYPE_NAME"))
                        put("size", rs.getInt("COLUMN_SIZE"))
                        put("nullable", rs.getInt("NULLABLE") == 1)
                        put("isPrimaryKey", primaryKeys.contains(columnName))
                        if (defaultValue != null) {
                            put("defaultValue", defaultValue)
                        } else {
                            put("defaultValue", JsonNull)
                        }
                    })
                }
            }
            Json.encodeToJsonElement(columns)
        }
    }

    suspend fun create(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'tableName'")
        val columns = payload["columns"]?.jsonArray ?: throw IllegalArgumentException("Missing 'columns' array")

        val connection = PoolManager.getConnection(config)
        return@withContext connection.use { conn ->
            val columnDefs = columns.map { col ->
                val colObj = col.jsonObject
                val name = colObj["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Column missing 'name'")
                val type = colObj["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Column missing 'type'")
                val size = colObj["size"]?.jsonPrimitive?.intOrNull
                val nullable = colObj["nullable"]?.jsonPrimitive?.booleanOrNull ?: true
                val isPrimaryKey = colObj["isPrimaryKey"]?.jsonPrimitive?.booleanOrNull ?: false
                val defaultValue = colObj["defaultValue"]?.jsonPrimitive?.contentOrNull

                buildColumnDefinition(name, type, size, nullable, isPrimaryKey, defaultValue, config.driver)
            }

            val primaryKeys = columns.mapNotNull { col ->
                val colObj = col.jsonObject
                if (colObj["isPrimaryKey"]?.jsonPrimitive?.booleanOrNull == true) {
                    colObj["name"]?.jsonPrimitive?.content
                } else null
            }

            val sql = buildString {
                append("CREATE TABLE ")
                append(quoteIdentifier(tableName, config.driver))
                append(" (")
                append(columnDefs.joinToString(", "))
                if (primaryKeys.isNotEmpty()) {
                    append(", PRIMARY KEY (")
                    append(primaryKeys.joinToString(", ") { quoteIdentifier(it, config.driver) })
                    append(")")
                }
                append(")")
            }

            conn.createStatement().use { it.execute(sql) }
            buildJsonObject { put("created", tableName) }
        }
    }

    suspend fun update(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'tableName'")
        val operation = payload["operation"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'operation' (ADD_COLUMN|DROP_COLUMN|MODIFY_COLUMN)")

        val connection = PoolManager.getConnection(config)
        return@withContext connection.use { conn ->
            val sql = when (operation) {
                "ADD_COLUMN" -> {
                    val column = payload["column"]?.jsonObject ?: throw IllegalArgumentException("Missing 'column' object")
                    val name = column["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Column missing 'name'")
                    val type = column["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Column missing 'type'")
                    val size = column["size"]?.jsonPrimitive?.intOrNull
                    val nullable = column["nullable"]?.jsonPrimitive?.booleanOrNull ?: true
                    val defaultValue = column["defaultValue"]?.jsonPrimitive?.contentOrNull

                    val colDef = buildColumnDefinition(name, type, size, nullable, false, defaultValue, config.driver)
                    "ALTER TABLE ${quoteIdentifier(tableName, config.driver)} ADD COLUMN $colDef"
                }

                "DROP_COLUMN" -> {
                    val columnName = payload["columnName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'columnName'")
                    "ALTER TABLE ${quoteIdentifier(tableName, config.driver)} DROP COLUMN ${quoteIdentifier(columnName, config.driver)}"
                }

                "MODIFY_COLUMN" -> {
                    val column = payload["column"]?.jsonObject ?: throw IllegalArgumentException("Missing 'column' object")
                    val name = column["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Column missing 'name'")
                    val type = column["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Column missing 'type'")
                    val size = column["size"]?.jsonPrimitive?.intOrNull
                    val nullable = column["nullable"]?.jsonPrimitive?.booleanOrNull ?: true
                    val defaultValue = column["defaultValue"]?.jsonPrimitive?.contentOrNull

                    val colDef = buildColumnDefinition(name, type, size, nullable, false, defaultValue, config.driver)
                    when (config.driver) {
                        Driver.mysql -> "ALTER TABLE ${quoteIdentifier(tableName, config.driver)} MODIFY COLUMN $colDef"
                        Driver.postgresql -> "ALTER TABLE ${quoteIdentifier(tableName, config.driver)} ALTER COLUMN ${quoteIdentifier(name, config.driver)} TYPE ${buildTypeSpec(type, size)}"
                    }
                }

                else -> throw IllegalArgumentException("Unknown operation: $operation")
            }

            conn.createStatement().use { it.execute(sql) }
            buildJsonObject {
                put("tableName", tableName)
                put("operation", operation)
            }
        }
    }

    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing 'tableName'")
        val connection = PoolManager.getConnection(config)
        return@withContext connection.use { conn ->
            val sql = "DROP TABLE ${quoteIdentifier(tableName, config.driver)}"
            conn.createStatement().use { it.execute(sql) }
            buildJsonObject { put("deleted", tableName) }
        }
    }

    private fun buildColumnDefinition(
        name: String,
        type: String,
        size: Int?,
        nullable: Boolean,
        isPrimaryKey: Boolean,
        defaultValue: String?,
        driver: Driver
    ): String {
        return buildString {
            append(quoteIdentifier(name, driver))
            append(" ")
            append(buildTypeSpec(type, size))
            if (!nullable) append(" NOT NULL")
            if (defaultValue != null) {
                append(" DEFAULT ")
                append(if (defaultValue.matches(Regex("\\d+(\\.\\d+)?|NULL|CURRENT_TIMESTAMP", RegexOption.IGNORE_CASE))) {
                    defaultValue
                } else {
                    "'$defaultValue'"
                })
            }
        }
    }

    private fun buildTypeSpec(type: String, size: Int?): String {
        return if (size != null && type.uppercase() in listOf("VARCHAR", "CHAR", "VARBINARY", "BINARY")) {
            "$type($size)"
        } else {
            type
        }
    }

    private fun quoteIdentifier(identifier: String, driver: Driver): String {
        return when (driver) {
            Driver.mysql -> "`$identifier`"
            Driver.postgresql -> "\"$identifier\""
        }
    }
}