package com.kxxnzstdsw.export

import kotlinx.serialization.Serializable

/**
 * 导出请求参数
 */
@Serializable
data class ExportRequest(
    /** 自定义 SELECT SQL */
    val sql: String,
    /** 输出目录路径 */
    val outputDir: String,
    /** 文件名前缀（不含扩展名） */
    val fileName: String,
    /** 导出格式 */
    val format: ExportFormat,
    /** 目标表名（仅 SQL 格式需要，用于生成 INSERT 语句） */
    val tableName: String? = null,
    /** 每次从数据库拉取的行数 */
    val fetchSize: Int = 1000
)

/**
 * 导出格式枚举
 */
@Serializable
enum class ExportFormat {
    /** CSV 格式 */
    CSV,
    /** JSON Lines 格式（每行一个 JSON 对象） */
    JSON_LINES,
    /** SQL INSERT 语句格式 */
    SQL_INSERT,
    /** Excel 格式（.xlsx） */
    EXCEL,
    /** Parquet 列式存储格式 */
    PARQUET
}

/**
 * 导出进度信息
 */
@Serializable
data class ExportProgress(
    /** 已导出行数 */
    val exportedRows: Long,
    /** 表头列数 */
    val columnCount: Int,
    /** 导出中是否完成 */
    val completed: Boolean = false,
    /** 导出完成后的文件路径 */
    val filePath: String? = null,
    /** 错误信息（如果有） */
    val error: String? = null
)
