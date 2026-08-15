package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.export.ExportEngine
import com.kxxnzstdsw.export.ExportFormat
import com.kxxnzstdsw.export.ExportProcessManager
import com.kxxnzstdsw.export.ExportRequest
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 数据导出 Handler（gRPC 统一入口）
 *
 * 责任划分：
 * - 本类负责业务解析（payload → ExportRequest）
 * - 主进程模式：根据 payload 决定启动导出还是停止已有任务，通过 ExportProcessManager
 *   投递命令到子进程，并订阅子进程返回的流式进度
 * - 子进程模式：直接调用 ExportEngine.export 并通过返回 Flow 推送进度
 *
 * Flow<Response> 返回：被 RequestDispatcher 直接 emit 给 gRPC StreamObserver
 */
object ExportHandler {

    /**
     * 入口：根据模式分流
     * - 主进程：编排子进程
     * - 子进程：直接执行 ExportEngine
     */
    fun execute(request: Request): Flow<Response> {
        return if (isSubprocessMode()) {
            executeAsSubprocess(request)
        } else {
            executeInMainProcess(request)
        }
    }

    /**
     * 子进程模式：在 Flow 中调用 ExportEngine 并 emit 进度
     */
    fun executeAsSubprocess(request: Request): Flow<Response> = flow {
        val id = request.id
        val config = request.connection
        val payload = PayloadAdapter.toJsonObject(request.payloadMap)
        try {
            val exportRequest = parseExportRequest(payload)
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
                            Response.newBuilder()
                                .setId(id).setSuccess(true).setStream(true).setEnd(progress.completed)
                                .setData(PayloadAdapter.toValue(data))
                                .build()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            emit(
                Response.newBuilder()
                    .setId(id).setSuccess(false)
                    .setError(e.message ?: "Export failed")
                    .build()
            )
        }
    }

    /**
     * 主进程模式：编排子进程 + 收集子进程响应
     */
    private fun executeInMainProcess(request: Request): Flow<Response> = flow {
        val id = request.id
        val config = request.connection
        val payload = PayloadAdapter.toJsonObject(request.payloadMap)

        val jarPath = findEngineJarPath()
        if (jarPath == null) {
            emit(
                Response.newBuilder().setId(id).setSuccess(false)
                    .setError("Cannot find idb-engine.jar path").build()
            )
            return@flow
        }

        // 停止导出分支
        val stopExportId = payload["stopExportId"]?.jsonPrimitive?.content
        if (stopExportId != null) {
            ensureSubprocessRunning(jarPath)
            ExportProcessManager.stopExport(stopExportId)
            emit(
                Response.newBuilder().setId(id).setSuccess(true)
                    .setData(
                        PayloadAdapter.toValue(
                            buildJsonObject { put("stopped", stopExportId) }
                        )
                    )
                    .build()
            )
            return@flow
        }

        // 启动导出分支
        ensureSubprocessRunning(jarPath)
        ExportProcessManager.startExport(id, config, PayloadAdapter.toPayloadMap(payload))

        // 收集子进程响应转发给上游 gRPC StreamObserver
        ExportProcessManager.collectResponses(id).collect { emit(it) }
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
     * 解析 payload → ExportRequest
     */
    private fun parseExportRequest(payload: JsonObject): ExportRequest {
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
            throw IllegalArgumentException(
                "不支持的格式: $formatStr，支持: CSV, JSON_LINES, SQL_INSERT, EXCEL, PARQUET"
            )
        }
        val tableName = payload["tableName"]?.jsonPrimitive?.content
        val fetchSize = payload["fetchSize"]?.jsonPrimitive?.intOrNull ?: 1000

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

    /**
     * 判定当前是否运行在子进程模式
     * 由 ExportProcessManager 启动子进程时设置 -Didb.subprocess=true
     */
    private fun isSubprocessMode(): Boolean {
        return System.getProperty("idb.subprocess") == "true"
    }
}