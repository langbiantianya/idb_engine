@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.export.ExportEngine
import com.kxxnzstdsw.export.ExportFormat
import com.kxxnzstdsw.export.ExportProcessManager
import com.kxxnzstdsw.export.ExportRequest
import com.kxxnzstdsw.export.GlobalOutputChannel
import com.kxxnzstdsw.models.ConnectionConfig
import com.kxxnzstdsw.models.Response
import com.kxxnzstdsw.proto.PayloadValue
import com.kxxnzstdsw.proto.PayloadValueKind
import com.kxxnzstdsw.proto.ProtoConverters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 数据导出 Handler（统一入口）
 *
 * 责任划分：
 * - 本类负责业务解析（payload → ExportRequest）
 * - 主进程调用：根据 -Dexport.subprocess 是否设置决定走子进程编排路径
 *   - 主进程模式：解析参数后通过 ExportProcessManager 启动子进程并投递命令
 *   - 子进程模式：直接调用 ExportEngine.export 并把进度写入 outputChannel
 * - 进度响应在子进程模式下走 outputChannel；在主进程模式下由子进程通过
 *   GlobalOutputChannel 流入主进程统一的 stdout 管线
 *
 * 输出元素类型为 ByteArray（protobuf 编码的 Response 帧），
 * 与 RequestDispatcher / Main.kt 的输出管线兼容。
 */
object ExportHandler {

    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { encodeDefaults = true }

    /**
     * 执行导出（业务编排）
     *
     * 该方法根据调用方环境有两种行为：
     * - 主进程调用：根据 payload 决定是启动导出还是停止已有任务，命令投递到子进程
     * - 子进程调用：直接执行 ExportEngine.export 并把进度写入 outputChannel
     *
     * @param id 请求 ID（用于响应匹配）
     * @param config 数据库连接配置
     * @param payload 导出参数（JsonObject — Handler 内部使用的统一格式）
     * @param outputChannel 输出 Channel（仅子进程场景使用，主进程调用时传 null 即可）
     */
    suspend fun execute(
        id: String,
        config: ConnectionConfig,
        payload: JsonObject,
        outputChannel: Channel<ByteArray>?
    ) {
        // 主进程模式：编排子进程（优先处理 stopExportId 分支，避免对其余字段的解析失败）
        if (isMainProcessMode()) {
            handleInMainProcess(id, config, payload, outputChannel)
            return
        }

        // 子进程模式：直接执行
        if (outputChannel != null) {
            val exportRequest = parseExportRequest(payload)
            withContext(Dispatchers.IO) {
                ExportEngine.export(config, exportRequest) { progress ->
                    val progressMap = mapOf(
                        "exportedRows" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = progress.exportedRows.toDouble()),
                        "columnCount" to PayloadValue(kind = PayloadValueKind.NUMBER, numberValue = progress.columnCount.toDouble()),
                        "completed" to PayloadValue(kind = PayloadValueKind.BOOL, boolValue = progress.completed),
                        "filePath" to (progress.filePath?.let { PayloadValue(kind = PayloadValueKind.STRING, stringValue = it) } ?: PayloadValue.NULL),
                        "error" to (progress.error?.let { PayloadValue(kind = PayloadValueKind.STRING, stringValue = it) } ?: PayloadValue.NULL)
                    )
                    val resp = Response(
                        id = id,
                        success = true,
                        stream = true,
                        end = progress.completed,
                        data = PayloadValue(kind = PayloadValueKind.STRUCT, structValue = progressMap)
                    )
                    val frame = proto.encodeToByteArray(Response.serializer(), resp)
                    outputChannel.send(frame)
                }
            }
        }
    }

    /**
     * 主进程模式：解析参数、启动/复用子进程、投递命令
     */
    private suspend fun handleInMainProcess(
        id: String,
        config: ConnectionConfig,
        payload: JsonObject,
        outputChannel: Channel<ByteArray>?
    ) {
        val jarPath = findEngineJarPath()
        if (jarPath == null) {
            sendError(id, "Cannot find idb-engine.jar path", outputChannel)
            return
        }

        // 停止导出分支
        val stopExportId = payload["stopExportId"]?.jsonPrimitive?.content
        if (stopExportId != null) {
            ensureSubprocessRunning(jarPath)
            ExportProcessManager.stopExport(stopExportId)
            val resp = Response(
                id = id,
                success = true,
                data = PayloadValue(
                    kind = PayloadValueKind.STRUCT,
                    structValue = mapOf("stopped" to PayloadValue(kind = PayloadValueKind.STRING, stringValue = stopExportId))
                )
            )
            sendResponse(id, resp, outputChannel)
            return
        }

        // 启动导出分支：把 JsonObject 转回 Map<String, PayloadValue> 投递到子进程
        ensureSubprocessRunning(jarPath)
        ExportProcessManager.startExport(id, config, ProtoConverters.toPayloadMap(payload))
    }

    /**
     * 启动或复用导出子进程
     */
    private suspend fun ensureSubprocessRunning(jarPath: String) {
        if (!ExportProcessManager.isRunning.value) {
            ExportProcessManager.start(jarPath)
            // 等待子进程初始化
            delay(100.milliseconds)
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
     * 判定当前是否运行在主进程（非子进程）。
     * 由 ExportProcessManager 启动子进程时设置 -Dexport.subprocess=true。
     */
    private fun isMainProcessMode(): Boolean {
        return System.getProperty("export.subprocess") != "true"
    }

    /**
     * 发送响应（优先使用调用方的 outputChannel，回退到 GlobalOutputChannel）
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun sendResponse(
        id: String,
        response: Response,
        outputChannel: Channel<ByteArray>?
    ) {
        val encoded = proto.encodeToByteArray(Response.serializer(), response)
        val target = outputChannel ?: GlobalOutputChannel.channel
        target?.send(encoded)
    }

    /**
     * 错误响应
     */
    private suspend fun sendError(
        id: String,
        message: String,
        outputChannel: Channel<ByteArray>?
    ) {
        sendResponse(id, Response(id = id, success = false, error = message), outputChannel)
    }
}