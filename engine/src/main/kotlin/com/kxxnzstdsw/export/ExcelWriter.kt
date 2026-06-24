package com.kxxnzstdsw.export

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.streaming.SXSSFSheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Excel 导出写入器（POI SXSSF 流式 API）
 * - 单 Sheet 数据行上限 100 万，达到阈值后自动新建 Sheet
 * - 内存窗口 1000 行，超出自动刷入磁盘
 */
class ExcelWriter(private val outputFile: File) : ExportWriter {

    companion object {
        /** 单 Sheet 最大行数（含表头） */
        const val MAX_ROWS_PER_SHEET = 1_000_000
        /** 内存中保留的行数 */
        const val FLUSH_WINDOW_SIZE = 1000
        /** Sheet 名称前缀 */
        const val SHEET_NAME_PREFIX = "Sheet"
    }

    private var workbook: SXSSFWorkbook? = null
    private var currentSheet: SXSSFSheet? = null
    private var columns: List<String> = emptyList()
    private var progressCallback: ((Long) -> Unit)? = null
    private var exportedRows = 0L
    private var currentSheetRowIndex = 0
    private var sheetNumber = 0

    override fun writeHeader(columns: List<String>) {
        this.columns = columns
        ensureSheet()
        // 写入表头
        val headerRow = currentSheet!!.createRow(currentSheetRowIndex++)
        columns.forEachIndexed { index, column ->
            headerRow.createCell(index).apply {
                cellType = CellType.STRING
                setCellValue(column)
            }
        }
    }

    override fun writeRow(row: List<Any?>) {
        // 检查是否需要新建 Sheet
        if (currentSheetRowIndex >= MAX_ROWS_PER_SHEET) {
            nextSheet()
        }

        val dataRow = currentSheet!!.createRow(currentSheetRowIndex++)
        row.forEachIndexed { index, value ->
            setCellValue(dataRow.createCell(index), value)
        }
        exportedRows++
        progressCallback?.invoke(exportedRows)
    }

    override fun flush() {
        currentSheet?.flushRows()
    }

    override fun close() {
        workbook.use { workbook ->
            workbook?.write(FileOutputStream(outputFile))
        }
    }

    override fun setProgressCallback(callback: ((Long) -> Unit)?) {
        this.progressCallback = callback
    }

    /**
     * 确保当前有可用的 Sheet
     */
    private fun ensureSheet() {
        if (currentSheet == null) {
            workbook = SXSSFWorkbook(FLUSH_WINDOW_SIZE)
            nextSheet()
        }
    }

    /**
     * 创建新 Sheet 并写入表头
     */
    private fun nextSheet() {
        sheetNumber++
        currentSheet = workbook?.createSheet("$SHEET_NAME_PREFIX$sheetNumber")
        currentSheetRowIndex = 0
        // 新 Sheet 写入表头
        val headerRow = currentSheet!!.createRow(currentSheetRowIndex++)
        columns.forEachIndexed { index, column ->
            headerRow.createCell(index).apply {
                cellType = CellType.STRING
                setCellValue(column)
            }
        }
    }

    /**
     * 根据值类型设置单元格
     */
    private fun setCellValue(cell: Cell, value: Any?) {
        when (value) {
            null -> {
                cell.setCellValue("")
            }
            is String -> {
                cell.setCellValue(value)
            }
            is BigDecimal -> {
                cell.setCellValue(value.toString())
            }
            is Number -> {
                cell.setCellValue(value.toDouble())
            }
            is Boolean -> {
                cell.setCellValue(value)
            }
            is Date -> {
                cell.setCellValue(value)
            }
            is Timestamp -> {
                cell.setCellValue(value)
            }
            is LocalDate -> {
                cell.setCellValue(value.toString())
            }
            is LocalDateTime -> {
                cell.setCellValue(value.toString())
            }
            else -> {
                cell.setCellValue(value.toString())
            }
        }
    }
}
