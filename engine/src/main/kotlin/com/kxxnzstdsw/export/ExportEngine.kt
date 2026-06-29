package com.kxxnzstdsw.export

import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.ResultSet

/**
 * 数据导出引擎
 * - 全链路流式处理，JDBC 游标逐行读取
 * - 支持 5 种格式：CSV、JSON Lines、SQL INSERT、Excel、Parquet
 */
object ExportEngine {

    private val logger = LoggerFactory.getLogger(ExportEngine::class.java)

    // 取消标志（用于跨协程取消）
    @Volatile
    var isCancelled = false

    /**
     * 执行导出（流式处理，进度通过回调实时回报）
     *
     * @param config 数据库连接配置
     * @param request 导出请求参数
     * @param onProgress 进度回调，每导出一行调用一次
     * @return 导出结果
     */
    suspend fun export(
        config: ConnectionConfig,
        request: ExportRequest,
        onProgress: suspend (ExportProgress) -> Unit
    ): ExportResult = withContext(Dispatchers.IO) {
        // 重置取消标志
        isCancelled = false

        val outputDir = File(request.outputDir)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val fileName = when (request.format) {
            ExportFormat.CSV -> "${request.fileName}.csv"
            ExportFormat.JSON_LINES -> "${request.fileName}.jsonl"
            ExportFormat.SQL_INSERT -> "${request.fileName}.sql"
            ExportFormat.EXCEL -> "${request.fileName}.xlsx"
            ExportFormat.PARQUET -> "${request.fileName}.parquet"
        }
        val outputFile = File(outputDir, fileName)

        val connection = PoolManager.getConnection(config, "")
        val dialect = DialectLoader.getDialect(config.driver)

        var statement: java.sql.Statement? = null
        var resultSet: ResultSet? = null
        var writer: ExportWriter? = null
        var exportedRows = 0L
        // 进度节流：行数阈值（每写入 N 行报一次）+ 时间阈值（即使行数未达也按 N 毫秒报一次）
        val progressRowInterval = 1000L
        val progressTimeIntervalMs = 200L
        var lastProgressTime = System.currentTimeMillis()
        var lastReportedRows = 0L

        try {
            // 1. 创建流式 Statement
            statement = connection.createStatement(
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
            )

            // 2. 配置 fetchSize
            val fetchSize = when (config.driver.lowercase()) {
                "mysql" -> Int.MIN_VALUE // MySQL 流式读取需要特殊值
                else -> request.fetchSize
            }
            statement.fetchSize = fetchSize

            // 3. PostgreSQL 需要关闭 autoCommit 以启用服务端游标
            if (config.driver.lowercase() == "postgresql") {
                connection.autoCommit = false
            }

            // 4. 执行 SQL
            logger.info("执行导出 SQL: ${request.sql}")
            resultSet = statement.executeQuery(request.sql)

            // 5. 获取元数据
            val metaData = resultSet.metaData
            val columnCount = metaData.columnCount

            // 构建列信息
            val columnInfos = (1..columnCount).map { i ->
                ColumnInfo(
                    name = metaData.getColumnLabel(i),
                    typeName = metaData.getColumnTypeName(i),
                    typeCode = metaData.getColumnType(i)
                )
            }
            val columns = columnInfos.map { it.name }

            // 6. 创建写入器
            writer = when (request.format) {
                ExportFormat.CSV -> CsvWriter(outputFile)
                ExportFormat.JSON_LINES -> JsonLinesWriter(outputFile)
                ExportFormat.SQL_INSERT -> SqlInsertWriter(
                    outputFile,
                    request.tableName ?: throw IllegalArgumentException("SQL INSERT 格式需要指定 tableName")
                )
                ExportFormat.EXCEL -> ExcelWriter(outputFile)
                ExportFormat.PARQUET -> {
                    val pw = ParquetWriter(outputFile)
                    // Parquet 需要先初始化 schema
                    pw.writeHeaderWithTypes(columnInfos)
                    pw
                }
            }

            // 7. 写入表头（仅非 Parquet 格式需要）
            if (request.format != ExportFormat.PARQUET) {
                writer.writeHeader(columns)
            }

            // 7.1 发送初始进度（确保前端收到开始通知）
            onProgress(ExportProgress(
                exportedRows = 0,
                columnCount = columnCount,
                completed = false
            ))

            // 8. 流式逐行读取并写入（支持取消检查）
            while (resultSet.next()) {
                // 检查是否被取消
                if (isCancelled) {
                    logger.info("导出被用户取消，已导出 ${writer.getExportedRows()} 行")
                    throw ExportCancelledException("Export cancelled by user")
                }

                val row = (1..columnCount).map { resultSet.getObject(it) }
                writer.writeRow(row)
                exportedRows = writer.getExportedRows()

                // 进度报告：行数阈值（每 N 行）或时间阈值（每 M 毫秒）任一触发即推送
                val now = System.currentTimeMillis()
                val rowDelta = exportedRows - lastReportedRows
                if (rowDelta >= progressRowInterval || (now - lastProgressTime) >= progressTimeIntervalMs) {
                    onProgress(ExportProgress(
                        exportedRows = exportedRows,
                        columnCount = columnCount,
                        completed = false
                    ))
                    lastProgressTime = now
                    lastReportedRows = exportedRows
                }
            }

            // 8.1 发送最终进度（确保即使最后余数不足 1000 行也发送正确总数）
            exportedRows = writer.getExportedRows()
            onProgress(ExportProgress(
                exportedRows = exportedRows,
                columnCount = columnCount,
                completed = true,
                filePath = outputFile.absolutePath
            ))

            // 10. 刷新并关闭
            writer.flush()
            writer.close()
            writer = null

            // 11. 恢复 PostgreSQL autoCommit
            if (config.driver.lowercase() == "postgresql") {
                connection.autoCommit = true
            }

            logger.info("导出完成，共 $exportedRows 行，文件: ${outputFile.absolutePath}")

            ExportResult(
                success = true,
                filePath = outputFile.absolutePath,
                exportedRows = exportedRows,
                columnCount = columnCount
            )

        } catch (e: ExportCancelledException) {
            throw e
        } catch (e: Exception) {
            logger.error("导出失败", e)
            ExportResult(
                success = false,
                error = e.message ?: "导出失败"
            )
        } finally {
            // 清理资源
            try { writer?.close() } catch (e: Exception) { /* ignore */ }
            try { resultSet?.close() } catch (e: Exception) { /* ignore */ }
            try { statement?.close() } catch (e: Exception) { /* ignore */ }
            try { connection.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * 导出取消异常
     */
    class ExportCancelledException(message: String) : Exception(message)

    /**
     * 导出结果
     */
    data class ExportResult(
        val success: Boolean,
        val filePath: String? = null,
        val exportedRows: Long = 0,
        val columnCount: Int = 0,
        val error: String? = null
    )
}
