package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object TableHandler {
    suspend fun list(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val tables = dialect.listTables(conn, config.database, schema)
            Json.encodeToJsonElement(tables)
        }
    }

    suspend fun columnList(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'tableName' in payload")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
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
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'tableName'")
        val columns = payload["columns"]?.jsonArray
            ?: throw IllegalArgumentException("Missing 'columns' array")
        val options = payload["options"]?.jsonObject?.let { obj ->
            obj.entries.associate { it.key to it.value.jsonPrimitive.content }
        } ?: emptyMap()
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val columnDefs = columns.map { col ->
                val colObj = col.jsonObject
                val name = colObj["name"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Column missing 'name'")
                val type = colObj["type"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Column missing 'type'")
                val size = colObj["size"]?.jsonPrimitive?.intOrNull
                val nullable = colObj["nullable"]?.jsonPrimitive?.booleanOrNull ?: true
                val isPrimaryKey = colObj["isPrimaryKey"]?.jsonPrimitive?.booleanOrNull ?: false
                val defaultValue = colObj["defaultValue"]?.jsonPrimitive?.contentOrNull
                val autoIncrement = colObj["autoIncrement"]?.jsonPrimitive?.booleanOrNull ?: false

                dialect.buildColumnDefinition(name, type, size, nullable, isPrimaryKey, defaultValue, autoIncrement)
            }

            val primaryKeys = columns.mapNotNull { col ->
                val colObj = col.jsonObject
                if (colObj["isPrimaryKey"]?.jsonPrimitive?.booleanOrNull == true) {
                    colObj["name"]?.jsonPrimitive?.content
                } else null
            }

            val sql = buildString {
                append("CREATE TABLE ")
                append(dialect.quoteIdentifier(tableName))
                append(" (")
                append(columnDefs.joinToString(", "))
                if (primaryKeys.isNotEmpty()) {
                    append(", PRIMARY KEY (")
                    append(primaryKeys.joinToString(", ") { dialect.quoteIdentifier(it) })
                    append(")")
                }
                append(")")
                append(dialect.buildTableOptionsSQL(options))
            }

            conn.createStatement().use { it.execute(sql) }

            // 执行后续语句（如 PostgreSQL 的 COMMENT ON TABLE）
            for (stmt in dialect.buildPostCreateStatements(dialect.quoteIdentifier(tableName), options)) {
                conn.createStatement().use { it.execute(stmt) }
            }

            buildJsonObject { put("created", tableName) }
        }
    }

    suspend fun update(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'tableName'")
        val operation = payload["operation"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'operation' (ADD_COLUMN|DROP_COLUMN|MODIFY_COLUMN)")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val sql = when (operation) {
                "ADD_COLUMN" -> {
                    val column = payload["column"]?.jsonObject
                        ?: throw IllegalArgumentException("Missing 'column' object")
                    val name = column["name"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Column missing 'name'")
                    val type = column["type"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Column missing 'type'")
                    val size = column["size"]?.jsonPrimitive?.intOrNull
                    val nullable = column["nullable"]?.jsonPrimitive?.booleanOrNull ?: true
                    val defaultValue = column["defaultValue"]?.jsonPrimitive?.contentOrNull

                    val colDef = dialect.buildColumnDefinition(name, type, size, nullable, false, defaultValue)
                    dialect.buildAddColumnSQL(tableName, colDef)
                }

                "DROP_COLUMN" -> {
                    val columnName = payload["columnName"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing 'columnName'")
                    dialect.buildDropColumnSQL(tableName, columnName)
                }

                "MODIFY_COLUMN" -> {
                    val column = payload["column"]?.jsonObject
                        ?: throw IllegalArgumentException("Missing 'column' object")
                    val name = column["name"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Column missing 'name'")
                    val type = column["type"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Column missing 'type'")
                    val size = column["size"]?.jsonPrimitive?.intOrNull
                    val nullable = column["nullable"]?.jsonPrimitive?.booleanOrNull ?: true
                    val defaultValue = column["defaultValue"]?.jsonPrimitive?.contentOrNull
                    val newName = column["newName"]?.jsonPrimitive?.contentOrNull

                    dialect.buildModifyColumnSQL(tableName, name, type, size, nullable, defaultValue, newName)
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

    suspend fun getDDL(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'tableName' in payload")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            JsonPrimitive(dialect.getCreateTableDDL(conn, tableName))
        }
    }

    suspend fun delete(config: ConnectionConfig, payload: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val tableName = payload["tableName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'tableName'")
        val schema = payload["schema"]?.jsonPrimitive?.contentOrNull ?: ""
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)

        return@withContext connection.use { conn ->
            val sql = "DROP TABLE ${dialect.quoteIdentifier(tableName)}"
            conn.createStatement().use { it.execute(sql) }
            buildJsonObject { put("deleted", tableName) }
        }
    }
}