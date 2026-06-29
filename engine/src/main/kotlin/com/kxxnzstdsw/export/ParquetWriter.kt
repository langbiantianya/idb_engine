package com.kxxnzstdsw.export

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.example.GroupWriteSupport
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Parquet 导出写入器
 * - 使用 ParquetWriter 直接写入
 * - 根据 JDBC 类型智能推断 Parquet 类型
 */
class ParquetWriter(private val outputFile: File) : ExportWriter {

    private val logger = LoggerFactory.getLogger(ParquetWriter::class.java)

    private var writer: ParquetWriter<Group>? = null
    private var factory: SimpleGroupFactory? = null
    private var schema: MessageType? = null
    private var columns: List<ColumnInfo> = emptyList()
    private var exportedRows = 0L
    private var initialized = false

    override fun writeHeader(columns: List<String>) {
        // 列名列表（不包含类型信息，使用默认 STRING 类型）
        this.columns = columns.map { ColumnInfo(it, "VARCHAR", Types.VARCHAR) }
    }

    /**
     * 带类型信息的表头写入（由 ExportEngine 调用）
     */
    fun writeHeaderWithTypes(columnInfos: List<ColumnInfo>) {
        this.columns = columnInfos
        initWriterAndFactory()
        initialized = true
    }

    override fun writeRow(row: List<Any?>) {
        if (!initialized) {
            initWriterAndFactory()
            initialized = true
        }
        writeRowToGroup(row)
    }

    override fun flush() {}

    override fun close() {
        writer?.close()
        writer = null
        factory = null
        schema = null
    }

    private fun initWriterAndFactory() {
        try {
            // 构建 Schema - 根据 JDBC 类型推断 Parquet 类型
            val fields = columns.map { col ->
                val parquetType = inferParquetType(col)
                val builder = org.apache.parquet.schema.Types.required(parquetType.parquetType)
                parquetType.logicalType?.let { builder.`as`(it) } ?: builder
                builder.named(col.name)
            }
            schema = MessageType("export", fields)

            val conf = Configuration()
            GroupWriteSupport.setSchema(schema!!, conf)

            @Suppress("DEPRECATION")
            writer = ParquetWriter(Path(outputFile.absolutePath), conf, GroupWriteSupport())
            factory = SimpleGroupFactory(schema!!)
            logger.info("Parquet writer initialized: columns=${columns.size}, types=${columns.map { it.typeName }}")
        } catch (e: Exception) {
            logger.error("Failed to initialize Parquet writer", e)
            throw e
        }
    }

    private fun writeRowToGroup(row: List<Any?>) {
        try {
            val group = factory!!.newGroup()
            columns.forEachIndexed { index, columnInfo ->
                val value = row.getOrNull(index)
                writeValue(group, columnInfo, value)
            }
            writer!!.write(group)
            exportedRows++
        } catch (e: Exception) {
            logger.error("Failed to write row (exportedRows so far: $exportedRows)", e)
            throw e
        }
    }

    override fun getExportedRows(): Long = exportedRows

    /**
     * 根据 JDBC 类型推断 Parquet 类型
     */
    private fun inferParquetType(columnInfo: ColumnInfo): ParquetTypeInfo {
        return when (columnInfo.typeCode) {
            // 整数类型
            Types.BIT, Types.TINYINT, Types.SMALLINT, Types.INTEGER -> ParquetTypeInfo(PrimitiveType.PrimitiveTypeName.INT32, null)
            Types.BIGINT -> ParquetTypeInfo(PrimitiveType.PrimitiveTypeName.INT64, null)

            // 浮点类型
            Types.FLOAT, Types.REAL -> ParquetTypeInfo(PrimitiveType.PrimitiveTypeName.FLOAT, null)
            Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> ParquetTypeInfo(PrimitiveType.PrimitiveTypeName.DOUBLE, null)

            // 布尔类型
            Types.BOOLEAN -> ParquetTypeInfo(PrimitiveType.PrimitiveTypeName.BOOLEAN, null)

            // 字符串类型
            Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
            Types.CLOB, Types.NCLOB -> ParquetTypeInfo(PrimitiveType.PrimitiveTypeName.BINARY, LogicalTypeAnnotation.stringType())

            // 日期时间类型
            Types.DATE -> ParquetTypeInfo(PrimitiveType.PrimitiveTypeName.INT32, LogicalTypeAnnotation.dateType())
            Types.TIME, Types.TIME_WITH_TIMEZONE -> ParquetTypeInfo(
                PrimitiveType.PrimitiveTypeName.INT64,
                LogicalTypeAnnotation.timeType(false, LogicalTypeAnnotation.TimeUnit.MICROS)
            )
            Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> ParquetTypeInfo(
                PrimitiveType.PrimitiveTypeName.INT64,
                LogicalTypeAnnotation.timestampType(false, LogicalTypeAnnotation.TimeUnit.MICROS)
            )

            // 二进制类型
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                ParquetTypeInfo(PrimitiveType.PrimitiveTypeName.BINARY, null)

            // 其他类型默认用字符串
            else -> ParquetTypeInfo(PrimitiveType.PrimitiveTypeName.BINARY, LogicalTypeAnnotation.stringType())
        }
    }

    private fun writeValue(group: Group, columnInfo: ColumnInfo, value: Any?) {
        val parquetType = inferParquetType(columnInfo)
        val columnName = columnInfo.name

        if (value == null) {
            // 对于必需字段，写入零值
            when (parquetType.parquetType) {
                PrimitiveType.PrimitiveTypeName.INT32 -> group.append(columnName, 0)
                PrimitiveType.PrimitiveTypeName.INT64 -> group.append(columnName, 0L)
                PrimitiveType.PrimitiveTypeName.DOUBLE -> group.append(columnName, 0.0)
                PrimitiveType.PrimitiveTypeName.FLOAT -> group.append(columnName, 0.0f)
                PrimitiveType.PrimitiveTypeName.BOOLEAN -> group.append(columnName, false)
                else -> group.append(columnName, Binary.fromString(""))
            }
        } else {
            when (parquetType.parquetType) {
                PrimitiveType.PrimitiveTypeName.INT32 -> {
                    // DATE 类型使用 java.util.Date / java.sql.Date（java.sql.Date extends java.util.Date）
                    if (value is java.util.Date) {
                        // epoch days: milliseconds since 1970-01-01 / 86400000
                        group.append(columnName, (value.time / 86400000).toInt())
                    } else {
                        group.append(columnName, (value as Number).toInt())
                    }
                }
                PrimitiveType.PrimitiveTypeName.INT64 -> {
                    val longValue = when (value) {
                        is Long -> value
                        is Number -> value.toLong()
                        is Date -> value.time
                        is Timestamp -> value.time
                        is LocalDate -> value.toEpochDay() * 86400000L
                        is LocalDateTime -> value.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                        else -> value.toString().toLongOrNull() ?: 0L
                    }
                    group.append(columnName, longValue)
                }
                PrimitiveType.PrimitiveTypeName.DOUBLE -> {
                    val doubleValue = when (value) {
                        is BigDecimal -> value.toDouble()
                        is Double -> value
                        is Number -> value.toDouble()
                        else -> value.toString().toDoubleOrNull() ?: 0.0
                    }
                    group.append(columnName, doubleValue)
                }
                PrimitiveType.PrimitiveTypeName.FLOAT -> {
                    val floatValue = when (value) {
                        is Float -> value
                        is Number -> value.toFloat()
                        else -> value.toString().toFloatOrNull() ?: 0.0f
                    }
                    group.append(columnName, floatValue)
                }
                PrimitiveType.PrimitiveTypeName.BOOLEAN -> group.append(columnName, value as Boolean)
                else -> {
                    val strValue = when (value) {
                        is Date -> value.toString()
                        is Timestamp -> value.toString()
                        is LocalDate -> value.toString()
                        is LocalDateTime -> value.toString()
                        is BigDecimal -> value.toPlainString()
                        is ByteArray -> value.joinToString("") { "%02x".format(it) }
                        else -> value.toString()
                    }
                    group.append(columnName, Binary.fromString(strValue))
                }
            }
        }
    }

    private data class ParquetTypeInfo(
        val parquetType: PrimitiveType.PrimitiveTypeName,
        val logicalType: LogicalTypeAnnotation?
    )
}
