package com.kxxnzstdsw.export

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * SQL INSERT 语句导出写入器
 * - 逐行生成 INSERT 语句
 * - 自动处理字符串转义和 NULL 值
 */
class SqlInsertWriter(
    private val outputFile: File,
    private val tableName: String
) : ExportWriter {

    private var writer: BufferedWriter? = null
    private var columns: List<String> = emptyList()
    private var exportedRows = 0L

    override fun writeHeader(columns: List<String>) {
        this.columns = columns
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(outputFile), Charsets.UTF_8))
    }

    override fun writeRow(row: List<Any?>) {
        val values = row.map { formatValue(it) }
        val sql = "INSERT INTO ${tableName} (${columns.joinToString(", ")}) VALUES (${values.joinToString(", ")});"
        writer?.write(sql)
        writer?.newLine()
        exportedRows++
    }

    override fun flush() {
        writer?.flush()
    }

    override fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    override fun getExportedRows(): Long = exportedRows

    /**
     * 格式化 SQL 值
     * - 数字直接输出
     * - 字符串转义并加单引号
     * - 日期/时间转为 SQL 格式
     * - NULL 输出 NULL
     */
    private fun formatValue(value: Any?): String = when (value) {
        null -> "NULL"
        is Number -> value.toString()
        is Boolean -> if (value) "TRUE" else "FALSE"
        is String -> "'${escapeString(value)}'"
        is Date -> "'${value}'"
        is Timestamp -> "'${value}'"
        is LocalDate -> "'${value}'"
        is LocalDateTime -> "'${value}'"
        is BigDecimal -> value.toPlainString()
        is ByteArray -> "X'${value.joinToString("") { "%02x".format(it) }}'"
        else -> "'${escapeString(value.toString())}'"
    }

    /**
     * 转义 SQL 字符串中的单引号
     */
    private fun escapeString(value: String): String {
        return value.replace("'", "''")
    }
}
