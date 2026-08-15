package com.kxxnzstdsw.export

import com.google.protobuf.Value
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.ExportCommand
import com.kxxnzstdsw.grpc.ExportCommand.Kind
import com.kxxnzstdsw.grpc.ExportHubGrpc
import com.kxxnzstdsw.grpc.ExportResponse
import com.kxxnzstdsw.grpc.Response
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 导出子进程管理器（gRPC 模式 — 父进程作为 gRPC client）
 *
 * 拓扑：
 * ```
 *   [父进程]                                       [子进程]
 *   ManagedChannel                                gRPC Server
 *       │                                              ▲
 *   ExportHubCoroutineStub ──── bidi stream ────► ExportHubImpl
 *       │  sends: ExportCommand (START/STOP/SHUTDOWN)    │
 *       │  receives: ExportResponse (progress)           │
 * ```
 *
 * 子进程监听在固定端口（默认 50099），由父进程通过命令行参数告知端口号。
 * 每个 exportId 的进度响应通过内部的 SharedFlow 转发给上游 gRPC StreamObserver。
 */
object ExportProcessManager {

    private val logger = LoggerFactory.getLogger(ExportProcessManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 默认子进程监听端口
    private const val DEFAULT_EXPORT_HUB_PORT = 50099

    // 子进程 process
    @Volatile
    private var process: Process? = null

    // 子进程侧 hub 端口
    @Volatile
    private var hubPort: Int = DEFAULT_EXPORT_HUB_PORT

    // 父进程侧 gRPC channel（连接子进程）
    @Volatile
    private var channel: ManagedChannel? = null

    // 当前 bidi stream 的 request observer（用于向子进程发送 ExportCommand）
    @Volatile
    private var commandObserver: StreamObserver<ExportCommand>? = null

    // 每个 exportId 的响应 SharedFlow
    private val responseFlows = ConcurrentHashMap<String, MutableSharedFlow<Response>>()

    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    /**
     * 启动子进程并连接 gRPC channel
     *
     * @param jarPath idb-engine.jar 路径
     */
    fun start(jarPath: String): Int {
        if (_isRunning.get()) {
            logger.warn("Export subprocess already running")
            return hubPort
        }

        hubPort = System.getenv("IDB_EXPORT_HUB_PORT")?.toIntOrNull() ?: DEFAULT_EXPORT_HUB_PORT

        // 1. 启动子进程
        val libsDir = File(jarPath).parentFile?.absoluteFile
        val classPath = if (libsDir != null) {
            val libs = File(libsDir, "libs")
            if (libs.exists()) {
                val jars = libs.listFiles { f -> f.extension == "jar" }
                jars?.joinToString(File.pathSeparator) { it.absolutePath }
                    ?.let { "$jarPath${File.pathSeparator}$it" }
                    ?: jarPath
            } else {
                jarPath
            }
        } else {
            jarPath
        }

        try {
            val javaHome = System.getProperty("java.home")
            val javaExe: String = File(File(javaHome), "bin/java").takeIf { it.exists() }?.absolutePath
                ?: File(File(javaHome), "bin/java.exe").takeIf { it.exists() }?.absolutePath
                ?: "java"
            val parentMaxMem = Runtime.getRuntime().maxMemory()
            val childMaxMem = maxOf(parentMaxMem, 256L * 1024 * 1024)

            val builder = ProcessBuilder(
                javaExe,
                "-Xmx${formatMem(childMaxMem)}",
                "-Xms${formatMem(childMaxMem)}",
                "-XX:+UseSerialGC",
                "-Didb.subprocess=true",
                "-Didb.export.hub.port=$hubPort",
                "-cp", classPath,
                "com.kxxnzstdsw.export.ExportSubProcess"
            )
            builder.directory(libsDir ?: File("."))
            builder.redirectErrorStream(false)

            // Windows + Parquet: 设置 HADOOP_HOME 指向 libs/ 目录
            if (System.getProperty("os.name").lowercase().contains("win") && libsDir != null) {
                builder.environment()["HADOOP_HOME"] = libsDir.absolutePath
                builder.environment()["hadoop.home.dir"] = libsDir.absolutePath
            }

            process = builder.start()

            // 监控进程退出
            scope.launch {
                process?.waitFor()
                logger.info("Export subprocess exited with code: ${process?.exitValue()}")
                stop()
            }

            // 2. 连接 gRPC channel（短暂重试等待子进程 server 就绪）
            val ch = ManagedChannelBuilder.forAddress("localhost", hubPort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .build()
            channel = ch

            val stub = ExportHubGrpc.newStub(ch)
            val responseObserver = object : StreamObserver<ExportResponse> {
                override fun onNext(value: ExportResponse) {
                    publishResponse(value)
                }

                override fun onError(t: Throwable) {
                    logger.error("ExportHub bidi stream error", t)
                    commandObserver = null
                }

                override fun onCompleted() {
                    logger.info("Subprocess closed ExportHub stream")
                    commandObserver = null
                }
            }
            commandObserver = stub.stream(responseObserver)

            _isRunning.set(true)
            logger.info("Export subprocess started (ExportHub on :$hubPort, connected)")
        } catch (e: Exception) {
            logger.error("Failed to start export subprocess", e)
            stop()
            throw e
        }
        return hubPort
    }

    /**
     * 将子进程返回的 ExportResponse 转发到对应 exportId 的 SharedFlow
     */
    private fun publishResponse(exportResp: ExportResponse) {
        val flow = responseFlows.computeIfAbsent(exportResp.id) {
            MutableSharedFlow<Response>(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
        // 转换为对外暴露的 gRPC Response
        val response = Response.newBuilder()
            .setId(exportResp.id)
            .setSuccess(exportResp.success)
            .setError(exportResp.error)
            .setStream(exportResp.stream)
            .setEnd(exportResp.end)
            .setData(exportResp.data)
            .build()
        scope.launch { flow.emit(response) }
        if (exportResp.end) {
            scope.launch {
                delay(50)
                responseFlows.remove(exportResp.id)
            }
        }
    }

    /**
     * 获取指定 exportId 的响应流（供 ExportHandler.collectResponses 调用）
     */
    fun collectResponses(exportId: String): SharedFlow<Response> {
        val flow = responseFlows.computeIfAbsent(exportId) {
            MutableSharedFlow<Response>(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
        return flow.asSharedFlow()
    }

    /**
     * 发送导出启动命令到子进程
     */
    fun startExport(id: String, connection: ConnectionConfig, payload: Map<String, Value>) {
        val observer = commandObserver
        if (observer == null) {
            logger.error("Cannot start export: stream not open")
            return
        }
        try {
            val cmd = ExportCommand.newBuilder()
                .setKind(Kind.START_EXPORT)
                .setId(id)
                .setConnection(connection)
                .putAllPayload(payload)
                .build()
            observer.onNext(cmd)
            logger.info("Sent START_EXPORT command: $id")
        } catch (e: Exception) {
            logger.error("Failed to send START_EXPORT command", e)
        }
    }

    /**
     * 发送导出停止命令到子进程
     */
    fun stopExport(exportId: String) {
        val observer = commandObserver
        if (observer == null) {
            logger.warn("Cannot stop export: stream not open")
            return
        }
        try {
            val cmd = ExportCommand.newBuilder()
                .setKind(Kind.STOP_EXPORT)
                .setExportId(exportId)
                .build()
            observer.onNext(cmd)
            logger.info("Sent STOP_EXPORT command: $exportId")
        } catch (e: Exception) {
            logger.error("Failed to send STOP_EXPORT command", e)
        }
    }

    /**
     * 停止子进程并清理
     */
    fun stop() {
        if (!_isRunning.getAndSet(false)) {
            return
        }
        logger.info("Stopping export subprocess...")
        try {
            commandObserver?.let { obs ->
                try {
                    obs.onNext(ExportCommand.newBuilder().setKind(Kind.SHUTDOWN).build())
                    obs.onCompleted()
                } catch (_: Exception) { /* ignore */ }
            }
            commandObserver = null
        } catch (_: Exception) { /* ignore */ }

        try { channel?.shutdownNow()?.awaitTermination(5, TimeUnit.SECONDS) } catch (_: Exception) {}
        channel = null

        try { process?.destroyForcibly() } catch (_: Exception) {}
        process = null

        responseFlows.clear()
        logger.info("Export subprocess stopped")
    }

    private fun formatMem(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024 * 1024)}g"
            else -> "${bytes / (1024 * 1024)}m"
        }
    }
}