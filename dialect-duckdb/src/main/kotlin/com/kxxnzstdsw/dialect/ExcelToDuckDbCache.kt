package com.kxxnzstdsw.dialect

import org.apache.poi.ss.usermodel.WorkbookFactory
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Excel 文件 → 临时 DuckDB 文件的预转换缓存。
 *
 * DuckDB 内核不原生支持 .xlsx 文件直查，community extension `excel` 需要网络下载。
 * 这里采用 POI 读取 + 临时 DuckDB 实例的方式把每个 sheet 写入为 VARCHAR 表，
 * 之后通过 [getOrCreate] 返回的临时 .duckdb 文件路径即可走标准的 DuckDB JDBC 流程。
 *
 * 线程安全：[cache] 用 ConcurrentHashMap 保证首次转换只发生一次。
 * 生命周期：临时目录在 JVM 关闭时由 [deleteOnExit] 兜底清理；可显式调 [cleanup] 提前释放。
 */
object ExcelToDuckDbCache {
    private val logger = LoggerFactory.getLogger(ExcelToDuckDbCache::class.java)
    private val cache = ConcurrentHashMap<String, File>()
    private val tempDirs = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()

    /**
     * 取得 [excelPath] 对应的 DuckDB 数据库文件路径；若未缓存则用 POI 读取并转换为临时 DuckDB。
     */
    fun getOrCreate(excelPath: String): String {
        val file = cache.getOrPut(excelPath) {
            convert(excelPath)
        }
        return file.absolutePath
    }

    /**
     * 显式清理所有缓存的临时目录。
     */
    fun cleanup() {
        tempDirs.forEach { dir ->
            try {
                dir.deleteRecursively()
            } catch (e: Exception) {
                logger.warn("清理临时目录失败: ${dir.absolutePath}", e)
            }
        }
        tempDirs.clear()
        cache.clear()
    }

    /**
     * 检查 [path] 是否为 Excel 文件路径（不读文件，仅根据后缀判断）。
     */
    fun isExcelFile(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".xlsx") || lower.endsWith(".xls")
    }

    private fun convert(excelPath: String): File {
        val src = File(excelPath)
        require(src.exists()) { "Excel 文件不存在: $excelPath" }
        val tempDir = Files.createTempDirectory("duckdb-excel-").toFile().apply { deleteOnExit() }
        tempDirs.add(tempDir)
        val dbFile = File(tempDir, "converted.duckdb")

        logger.info("开始将 Excel 转换为临时 DuckDB: ${src.absolutePath} → ${dbFile.absolutePath}")

        // 1. 用 POI 读取所有 sheet
        val sheetData: List<Pair<String, List<List<String>>>> = WorkbookFactory.create(src).use { wb ->
            (0 until wb.numberOfSheets).map { i ->
                val sheet = wb.getSheetAt(i)
                val sheetName = sheet.sheetName ?: "Sheet$i"
                val rows = mutableListOf<List<String>>()
                for (rowIdx in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIdx) ?: continue
                    val rowData = (0 until row.lastCellNum).map { cellIdx ->
                        row.getCell(cellIdx)?.toString()?.takeIf { it.isNotEmpty() } ?: ""
                    }
                    rows.add(rowData)
                }
                sheetName to rows
            }
        }

        // 2. 把每个 sheet 写入临时 DuckDB（每 sheet 一张表，全部 VARCHAR）
        DriverManager.getConnection("jdbc:duckdb:${dbFile.absolutePath}").use { conn ->
            for ((sheetName, rows) in sheetData) {
                if (rows.isEmpty()) continue
                val tableName = sanitizeTableName(sheetName)
                val colCount = rows.maxOf { it.size }
                if (colCount == 0) continue

                // 2.1 建表 — 列名 col1, col2, ...
                val cols = (1..colCount).joinToString(", ") { "col$it VARCHAR" }
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE $tableName ($cols)")
                }

                // 2.2 插数据（首行视为 header，其余为数据；如果首行内容看起来像 header）
                // 为了简单起见，全部按数据行写入；如果首行全是 VARCHAR，把首行作为数据保留
                val dataRows = rows
                for (row in dataRows) {
                    insertRow(conn, tableName, row, colCount)
                }
                logger.info("  ✓ Sheet '$sheetName' → 表 '$tableName' (${dataRows.size} 行 × $colCount 列)")
            }
        }

        return dbFile
    }

    private fun insertRow(conn: Connection, tableName: String, row: List<String>, colCount: Int) {
        val placeholders = (1..colCount).joinToString(", ") { "?" }
        conn.prepareStatement("INSERT INTO $tableName VALUES ($placeholders)").use { ps ->
            for (i in 0 until colCount) {
                val value = row.getOrNull(i) ?: ""
                ps.setString(i + 1, value)
            }
            ps.executeUpdate()
        }
    }

    /**
     * 把 sheet 名规整成合法的 DuckDB 标识符。
     * - 仅保留字母数字下划线
     * - 以字母或下划线开头
     * - 最大长度 63
     * - 首字符若为数字则前缀 `_`
     */
    private fun sanitizeTableName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9_]"), "_")
        val prefixed = if (sanitized.isEmpty() || sanitized[0].isDigit()) "_$sanitized" else sanitized
        return prefixed.take(63)
    }
}