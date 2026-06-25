package com.kxxnzstdsw.export

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * JSON Lines 导出写入器
 * 每行输出一个独立的 JSON 对象，零新增依赖
 */
class JsonLinesWriter(private val outputFile: File) : ExportWriter {

    private var writer: OutputStreamWriter? = null
    private var columns: List<String> = emptyList()
    private var exportedRows = 0L

    override fun writeHeader(columns: List<String>) {
        this.columns = columns
        writer = OutputStreamWriter(FileOutputStream(outputFile), Charsets.UTF_8)
    }

    override fun writeRow(row: List<Any?>) {
        val jsonObject = buildJsonObject {
            columns.forEachIndexed { index, column ->
                val value = row.getOrNull(index)
                put(column, value?.toJsonElement() ?: JsonNull)
            }
        }
        writer?.write(jsonObject.toString())
        writer?.write("\n")
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
     * 将任意值转换为 JsonElement
     */
    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(this.toString())
    }
}
