package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.export.ExportEngine
import com.kxxnzstdsw.export.ExportFormat
import com.kxxnzstdsw.export.ExportProgress
import com.kxxnzstdsw.export.ExportRequest
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 数据导出 Handler
 */
object ExportHandler {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 执行导出（流式进度回报）
     */
    suspend fun execute(
        config: ConnectionConfig,
        payload: JsonObject,
        outputChannel: Channel<String>
    ) {
        withContext(Dispatchers.IO) {
            // 1. 解析请求参数
            val sql = payload["sql"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("缺少参数 'sql'")
            val outputDir = payload["outputDir"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("缺少参数 'outputDir'")
            val fileName = payload["fileName"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("缺少参数 'fileName'")
            val formatStr = payload["format"]?.jsonPrimitive?.content?.uppercase()
                ?: throw IllegalArgumentException("缺少参数 'format'")
            val format = try {
                ExportFormat.valueOf(formatStr)
            } catch (e: Exception) {
                throw IllegalArgumentException("不支持的格式: $formatStr，支持: CSV, JSON_LINES, SQL_INSERT, EXCEL, PARQUET")
            }
            val tableName = payload["tableName"]?.jsonPrimitive?.content
            val fetchSize = payload["fetchSize"]?.jsonPrimitive?.int ?: 1000

            val request = ExportRequest(
                sql = sql,
                outputDir = outputDir,
                fileName = fileName,
                format = format,
                tableName = tableName,
                fetchSize = fetchSize
            )

            // 2. 执行导出
            ExportEngine.export(config, request) { progress ->
                // 3. 流式回报进度
                val progressJson = buildJsonObject {
                    put("exportedRows", progress.exportedRows)
                    put("columnCount", progress.columnCount)
                    put("completed", progress.completed)
                    progress.filePath?.let { put("filePath", it) }
                    progress.error?.let { put("error", it) }
                }
                val response = Response(
                    id = "export-progress",
                    success = true,
                    stream = true,
                    end = progress.completed,
                    data = progressJson
                )
                outputChannel.trySend(json.encodeToString(Response.serializer(), response))
            }
        }
    }
}
