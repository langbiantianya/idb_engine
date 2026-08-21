package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.export.ExportEngine
import com.kxxnzstdsw.export.ExportFormat
import com.kxxnzstdsw.export.ExportProcessManager
import com.kxxnzstdsw.export.ExportRequest
import com.kxxnzstdsw.grpc.ExportHubResponse
import com.kxxnzstdsw.grpc.ExportResponse
import com.kxxnzstdsw.grpc.ExportRunRequest
import com.kxxnzstdsw.grpc.ExportStopResponse
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.exportHubResponse
import com.kxxnzstdsw.grpc.exportResponse
import com.kxxnzstdsw.grpc.exportStopResponse
import com.kxxnzstdsw.grpc.response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 数据导出 Handler（gRPC 统一入口）
 *
 * 双模式入口：
 *  - 主进程：`executeInMainProcess(request): Flow<Response>`（typed Response）
 *  - 子进程：`executeAsSubprocess(request): Flow<ExportHubResponse>`（subprocess wire shape）
 *
 * 两者共享 `parseExportRequest()`：把 typed `ExportRunRequest` → ExportRequest。
 */
object ExportHandler {

    /**
     * 主进程模式入口 — 由 [com.kxxnzstdsw.dispatcher.RequestDispatcher] 直接调用。
     * 编排子进程 + 收集子进程响应并组装为 typed Response 流。
     */
    fun executeInMainProcess(request: Request): Flow<Response> = flow {
        val id = request.id
        val config = request.connection
        val runReq = request.exportRequest.runExport

        val jarPath = findEngineJarPath()
        if (jarPath == null) {
            emit(
                response {
                    this.id = id
                    success = false
                    error = "Cannot find idb-engine.jar path"
                }
            )
            return@flow
        }

        // 停止导出分支
        val stopExportId = runReq.stopExportId.ifBlank { null }
        if (stopExportId != null) {
            ensureSubprocessRunning(jarPath)
            ExportProcessManager.stopExport(stopExportId)
            emit(
                response {
                    this.id = id
                    success = true
                    export = exportResponse {
                        stop = exportStopResponse { stopped = stopExportId }
                    }
                }
            )
            return@flow
        }

        // 启动导出分支
        ensureSubprocessRunning(jarPath)
        // 转发给子进程的 ExportCommand 仍然使用 map<string,Value> payload — 子进程 wire 协议保持旧形态
        ExportProcessManager.startExport(id, config, runReqToPayloadMap(runReq))

        // 收集子进程响应（已由 ExportProcessManager 转为 typed Response）转发给上游 gRPC StreamObserver
        ExportProcessManager.collectResponses(id).collect { emit(it) }
    }

    /**
     * 子进程模式入口 — 由 [com.kxxnzstdsw.export.ExportSubProcess] 直接调用。
     * 直接调用 ExportEngine.export 并 emit 子进程 wire 形态的 ExportHubResponse。
     * 不走 typed Response 通道，避免在子进程内部做 typed ↔ Value 的来回转换。
     */
    fun executeAsSubprocess(request: Request): Flow<ExportHubResponse> = flow {
        val id = request.id
        val config = request.connection
        val runReq = request.exportRequest.runExport
        try {
            val exportRequest = parseExportRequest(runReq)
            withContext(Dispatchers.IO) {
                ExportEngine.export(config, exportRequest) { progress ->
                    val data = buildJsonObject {
                        put("exportedRows", progress.exportedRows)
                        put("columnCount", progress.columnCount)
                        put("completed", progress.completed)
                        if (progress.filePath != null) put("filePath", progress.filePath)
                        if (progress.error != null) put("error", progress.error)
                    }
                    runBlocking {
                        emit(
                            exportHubResponse {
                                this.id = id
                                success = true
                                stream = true
                                end = progress.completed
                                this.data = PayloadAdapter.toValue(data)
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            emit(
                exportHubResponse {
                    this.id = id
                    success = false
                    error = e.message ?: "Export failed"
                    end = true
                }
            )
        }
    }

    /**
     * 启动或复用导出子进程
     */
    private suspend fun ensureSubprocessRunning(jarPath: String) {
        if (!ExportProcessManager.isRunning) {
            ExportProcessManager.start(jarPath)
            // 等待子进程初始化 + 连接到主进程 ExportHub
            delay(200.milliseconds)
        }
    }

    /**
     * 解析 typed `ExportRunRequest` → ExportRequest
     */
    private fun parseExportRequest(runReq: ExportRunRequest): ExportRequest {
        val sql = runReq.sql.ifBlank { throw IllegalArgumentException("缺少参数 'sql'") }
        val outputDir = runReq.outputDir.ifBlank { throw IllegalArgumentException("缺少参数 'outputDir'") }
        val fileName = runReq.fileName.ifBlank { throw IllegalArgumentException("缺少参数 'fileName'") }
        val formatStr = runReq.format.ifBlank { throw IllegalArgumentException("缺少参数 'format'") }.uppercase()
        val format = try {
            ExportFormat.valueOf(formatStr)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "不支持的格式: $formatStr，支持: CSV, JSON_LINES, SQL_INSERT, EXCEL, PARQUET"
            )
        }
        val tableName = runReq.tableName.ifBlank { null }
        val fetchSize = if (runReq.fetchSize == 0) 1000 else runReq.fetchSize

        return ExportRequest(
            sql = sql,
            outputDir = outputDir,
            fileName = fileName,
            format = format,
            tableName = tableName,
            fetchSize = fetchSize
        )
    }

    /**
     * 把 typed `ExportRunRequest` 打成子进程 wire 的 `map<string, Value>` 形态。
     */
    private fun runReqToPayloadMap(runReq: ExportRunRequest): Map<String, com.google.protobuf.Value> {
        val obj = JsonObject(
            linkedMapOf(
                "sql" to JsonPrimitive(runReq.sql),
                "outputDir" to JsonPrimitive(runReq.outputDir),
                "fileName" to JsonPrimitive(runReq.fileName),
                "format" to JsonPrimitive(runReq.format),
                "tableName" to JsonPrimitive(runReq.tableName),
                "fetchSize" to JsonPrimitive(runReq.fetchSize)
            )
        )
        return PayloadAdapter.toPayloadMap(obj)
    }

    /**
     * 通过 java.class.path 找到 idb-engine.jar
     */
    private fun findEngineJarPath(): String? {
        val classPath = System.getProperty("java.class.path", "")
        val paths = classPath.split(File.pathSeparator)
        val cwd = File(".").absoluteFile
        for (raw in paths) {
            val candidate = if (File(raw).isAbsolute) File(raw) else File(cwd, raw)
            if (candidate.exists() && candidate.name.contains("idb-engine.jar")) {
                return candidate.absolutePath
            }
        }
        return File(".", "idb-engine.jar").takeIf { it.exists() }?.absolutePath
    }
}