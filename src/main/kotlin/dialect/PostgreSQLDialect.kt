package com.kxxnzstdsw.dialect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

class PostgreSQLDialect : DatabaseDialect {
    override suspend fun listSchemas(conn: Connection): List<String> = withContext(Dispatchers.IO) {
        val schemas = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT schema_name FROM information_schema.schemata " +
                "WHERE schema_name NOT IN ('pg_catalog', 'information_schema')"
            ).use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString(1))
                }
            }
        }
        schemas
    }

    override suspend fun createSchema(conn: Connection, name: String): Boolean = withContext(Dispatchers.IO) {
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE SCHEMA \"$name\"")
        }
        true
    }

    override suspend fun deleteSchema(conn: Connection, name: String): Boolean = withContext(Dispatchers.IO) {
        conn.createStatement().use { stmt ->
            stmt.execute("DROP SCHEMA \"$name\" CASCADE")
        }
        true
    }

    override suspend fun listTables(conn: Connection, database: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val tables = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT table_name, table_type
                FROM information_schema.tables
                WHERE table_schema = '$database'
                AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    tables.add(mapOf(
                        "name" to rs.getString("table_name"),
                        "type" to rs.getString("table_type")
                    ))
                }
            }
        }
        tables
    }

    override suspend fun listUsers(conn: Connection): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val users = mutableListOf<Map<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT usename FROM pg_user").use { rs ->
                while (rs.next()) {
                    users.add(mapOf(
                        "user" to rs.getString("usename")
                    ))
                }
            }
        }
        users
    }

    override suspend fun updatePrivileges(
        conn: Connection,
        user: String,
        schema: String,
        privileges: List<String>,
        isGrant: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val privilegeList = privileges.joinToString(", ")
        val sql = if (isGrant) {
            "GRANT $privilegeList ON SCHEMA \"$schema\" TO \"$user\""
        } else {
            "REVOKE $privilegeList ON SCHEMA \"$schema\" FROM \"$user\""
        }
        conn.createStatement().use { stmt ->
            stmt.execute(sql)
        }
        true
    }

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override fun buildColumnDefinition(
        name: String,
        type: String,
        size: Int?,
        nullable: Boolean,
        isPrimaryKey: Boolean,
        defaultValue: String?
    ): String = buildString {
        append(quoteIdentifier(name))
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

    override fun buildAddColumnSQL(tableName: String, columnDef: String): String {
        return "ALTER TABLE ${quoteIdentifier(tableName)} ADD COLUMN $columnDef"
    }

    override fun buildDropColumnSQL(tableName: String, columnName: String): String {
        return "ALTER TABLE ${quoteIdentifier(tableName)} DROP COLUMN ${quoteIdentifier(columnName)}"
    }

    override fun buildModifyColumnSQL(
        tableName: String,
        name: String,
        type: String,
        size: Int?,
        nullable: Boolean,
        defaultValue: String?,
        newName: String?
    ): String {
        val table = quoteIdentifier(tableName)
        val col = quoteIdentifier(name)
        val typeSpec = buildTypeSpec(type, size)
        return if (newName != null) {
            "ALTER TABLE $table ALTER COLUMN $col TYPE $typeSpec, RENAME COLUMN $col TO ${quoteIdentifier(newName)}"
        } else {
            "ALTER TABLE $table ALTER COLUMN $col TYPE $typeSpec"
        }
    }

    private fun buildTypeSpec(type: String, size: Int?): String {
        return if (size != null && type.uppercase() in listOf("VARCHAR", "CHAR", "VARBINARY", "BINARY")) {
            "$type($size)"
        } else {
            type
        }
    }
}