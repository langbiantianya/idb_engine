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
 * - 单 Sheet 数据行上限 100 万，达到阈值后自动新建 Sheet（不含表头）
 * - 内存窗口 1000 行，超出自动刷入磁盘
 * - close() 时自动清理临时文件（POI close() 已内置 dispose）
 */
class ExcelWriter(private val outputFile: File) : ExportWriter {

    companion object {
        /** 单 Sheet 最大数据行数（不含表头） */
        const val MAX_DATA_ROWS_PER_SHEET = 1_000_000
        /** 内存中保留的行数 */
        const val FLUSH_WINDOW_SIZE = 1000
        /** Sheet 名称前缀 */
        const val SHEET_NAME_PREFIX = "Sheet"
    }

    private var workbook: SXSSFWorkbook? = null
    private var currentSheet: SXSSFSheet? = null
    private var columns: List<String> = emptyList()
    private var exportedRows = 0L
    /** 当前 Sheet 的数据行数（不含表头），达到上限时创建新 Sheet */
    private var currentSheetDataRowIndex = 0
    private var sheetNumber = 0

    override fun writeHeader(columns: List<String>) {
        this.columns = columns
        ensureSheet()
        // 写入第一张 Sheet 的表头
        writeHeaderRow(currentSheet!!)
        currentSheetDataRowIndex = 0
    }

    override fun writeRow(row: List<Any?>) {
        // 检查是否需要新建 Sheet（MAX_DATA_ROWS_PER_SHEET 是数据行上限，不含表头）
        if (currentSheetDataRowIndex >= MAX_DATA_ROWS_PER_SHEET) {
            nextSheet()
        }

        val dataRow = currentSheet!!.createRow(currentSheetDataRowIndex++)
        row.forEachIndexed { index, value ->
            setCellValue(dataRow.createCell(index), value)
        }
        exportedRows++
    }

    override fun flush() {
        // 刷新当前 Sheet 的行数据到临时文件
        currentSheet?.flushRows()
    }

    override fun close() {
        workbook?.use { wb ->
            // 写入最终文件，close() 会自动调用 dispose() 清理临时文件
            FileOutputStream(outputFile).use { fos ->
                wb.write(fos)
            }
        }
        workbook = null
        currentSheet = null
    }

    override fun getExportedRows(): Long = exportedRows

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
     * 创建新 Sheet（不写入表头，数据连续）
     */
    private fun nextSheet() {
        sheetNumber++
        currentSheet = workbook?.createSheet("$SHEET_NAME_PREFIX$sheetNumber")
        currentSheetDataRowIndex = 0
    }

    /**
     * 写入表头行
     */
    private fun writeHeaderRow(sheet: SXSSFSheet) {
        val headerRow = sheet.createRow(0)
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
