package com.kxxnzstdsw.export

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.example.GroupWriteSupport
import org.apache.parquet.schema.MessageType
import java.io.File
import java.math.BigDecimal
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Parquet 导出写入器
 * - 使用 ParquetWriter 直接写入
 */
class ParquetWriter(private val outputFile: File) : ExportWriter {

    private var writer: ParquetWriter<Group>? = null
    private var factory: SimpleGroupFactory? = null
    private var schema: MessageType? = null
    private var columns: List<String> = emptyList()
    private var exportedRows = 0L
    private var firstRowProcessed = false

    override fun writeHeader(columns: List<String>) {
        this.columns = columns
    }

    override fun writeRow(row: List<Any?>) {
        if (!firstRowProcessed) {
            initWriterAndFactory(row)
            firstRowProcessed = true
            writeRowToGroup(row)
        } else {
            writeRowToGroup(row)
        }
    }

    override fun flush() {}

    override fun close() {
        writer?.close()
        writer = null
        factory = null
        schema = null
    }

    private fun initWriterAndFactory(firstRow: List<Any?>) {
        // 构建 Schema - 全部使用 BINARY 类型以便通用
        val fields = columns.map { column ->
            org.apache.parquet.schema.Types.required(org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY).named(column)
        }
        schema = MessageType("export", fields)

        val conf = Configuration()
        GroupWriteSupport.setSchema(schema!!, conf)

        @Suppress("DEPRECATION")
        writer = ParquetWriter<Group>(Path(outputFile.absolutePath), conf, GroupWriteSupport())
        factory = SimpleGroupFactory(schema!!)
    }

    private fun writeRowToGroup(row: List<Any?>) {
        val group = factory!!.newGroup()
        columns.forEachIndexed { index, columnName ->
            val value = row.getOrNull(index)
            writeValue(group, columnName, value)
        }
        writer!!.write(group)
        exportedRows++
    }

    override fun getExportedRows(): Long = exportedRows

    private fun writeValue(group: Group, columnName: String, value: Any?) {
        val strValue = when (value) {
            null -> ""
            is Boolean -> value.toString()
            is Number -> value.toString()
            is Date -> value.toString()
            is Timestamp -> value.toString()
            is LocalDate -> value.toString()
            is LocalDateTime -> value.toString()
            is BigDecimal -> value.toString()
            else -> value.toString()
        }
        group.append(columnName, strValue)
    }
}
