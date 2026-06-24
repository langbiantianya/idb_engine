package com.kxxnzstdsw.export

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * CSV 导出写入器
 * - 输出 UTF-8 BOM 头，保证 Excel 打开中文不乱码
 * - 字段包含逗号、双引号、换行符时自动转义
 */
class CsvWriter(private val outputFile: File) : ExportWriter {

    private var writer: BufferedWriter? = null
    private var progressCallback: ((Long) -> Unit)? = null
    private var exportedRows = 0L

    override fun writeHeader(columns: List<String>) {
        writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(outputFile), StandardCharsets.UTF_8)
        )
        // 写入 UTF-8 BOM
        writer?.write("﻿")
        writer?.write(columns.joinToString(",") { escapeField(it) })
        writer?.newLine()
    }

    override fun writeRow(row: List<Any?>) {
        val line = row.joinToString(",") { escapeField(it?.toString() ?: "") }
        writer?.write(line)
        writer?.newLine()
        exportedRows++
        progressCallback?.invoke(exportedRows)
    }

    override fun flush() {
        writer?.flush()
    }

    override fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    /**
     * 转义 CSV 字段
     * - 包含逗号、双引号、换行符的字段用双引号包裹
     * - 字段内双引号用两个双引号转义
     */
    private fun escapeField(field: String): String {
        return when {
            field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r') -> {
                "\"${field.replace("\"", "\"\"")}\""
            }
            else -> field
        }
    }
}
