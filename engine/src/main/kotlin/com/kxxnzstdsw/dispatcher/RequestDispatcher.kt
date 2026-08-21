package com.kxxnzstdsw.dispatcher

import com.google.protobuf.Message
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.ColumnDef
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.DataCreateRequest
import com.kxxnzstdsw.grpc.DataDeleteRequest
import com.kxxnzstdsw.grpc.DataListRequest
import com.kxxnzstdsw.grpc.DataUpdateRequest
import com.kxxnzstdsw.grpc.ForeignKeyCreateRequest
import com.kxxnzstdsw.grpc.ForeignKeyDeleteRequest
import com.kxxnzstdsw.grpc.ForeignKeyListRequest
import com.kxxnzstdsw.grpc.FunctionCallRequest
import com.kxxnzstdsw.grpc.FunctionCreateRequest
import com.kxxnzstdsw.grpc.FunctionDebugRequest
import com.kxxnzstdsw.grpc.FunctionDeleteRequest
import com.kxxnzstdsw.grpc.FunctionGetDdlRequest
import com.kxxnzstdsw.grpc.FunctionInfoRequest
import com.kxxnzstdsw.grpc.FunctionListRequest
import com.kxxnzstdsw.grpc.FunctionValidateRequest
import com.kxxnzstdsw.grpc.IndexCreateRequest
import com.kxxnzstdsw.grpc.IndexDeleteRequest
import com.kxxnzstdsw.grpc.IndexListRequest
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.SchemaCreateRequest
import com.kxxnzstdsw.grpc.SchemaDeleteRequest
import com.kxxnzstdsw.grpc.SchemaListRequest
import com.kxxnzstdsw.grpc.SqlExplainRequest
import com.kxxnzstdsw.grpc.SqlRequest
import com.kxxnzstdsw.grpc.TableColumnListRequest
import com.kxxnzstdsw.grpc.TableCreateRequest
import com.kxxnzstdsw.grpc.TableDeleteRequest
import com.kxxnzstdsw.grpc.TableGetDdlRequest
import com.kxxnzstdsw.grpc.TableRenameRequest
import com.kxxnzstdsw.grpc.TableTruncateRequest
import com.kxxnzstdsw.grpc.TableUpdateRequest
import com.kxxnzstdsw.grpc.TriggerGetDdlRequest
import com.kxxnzstdsw.grpc.TriggerListRequest
import com.kxxnzstdsw.grpc.UserCreateRequest
import com.kxxnzstdsw.grpc.UserDeleteRequest
import com.kxxnzstdsw.grpc.UserGrantsRequest
import com.kxxnzstdsw.grpc.UserListRequest
import com.kxxnzstdsw.grpc.UserUpdateRequest
import com.kxxnzstdsw.grpc.ViewCreateRequest
import com.kxxnzstdsw.grpc.ViewDeleteRequest
import com.kxxnzstdsw.grpc.ViewGetDdlRequest
import com.kxxnzstdsw.grpc.ViewListRequest
import com.kxxnzstdsw.grpc.dataResponse
import com.kxxnzstdsw.grpc.foreignKeyResponse
import com.kxxnzstdsw.grpc.functionResponse
import com.kxxnzstdsw.grpc.generateTerminalResponse
import com.kxxnzstdsw.grpc.indexResponse
import com.kxxnzstdsw.grpc.response
import com.kxxnzstdsw.grpc.schemaResponse
import com.kxxnzstdsw.grpc.sqlResponse
import com.kxxnzstdsw.grpc.systemResponse
import com.kxxnzstdsw.grpc.tableResponse
import com.kxxnzstdsw.grpc.triggerResponse
import com.kxxnzstdsw.grpc.userResponse
import com.kxxnzstdsw.grpc.viewResponse
import com.kxxnzstdsw.handlers.DataHandler
import com.kxxnzstdsw.handlers.ExportHandler
import com.kxxnzstdsw.handlers.ForeignKeyHandler
import com.kxxnzstdsw.handlers.FunctionHandler
import com.kxxnzstdsw.handlers.GenerateHandler
import com.kxxnzstdsw.handlers.IndexHandler
import com.kxxnzstdsw.handlers.SchemaHandler
import com.kxxnzstdsw.handlers.SqlEngineHandler
import com.kxxnzstdsw.handlers.SystemHandler
import com.kxxnzstdsw.handlers.TableHandler
import com.kxxnzstdsw.handlers.TriggerHandler
import com.kxxnzstdsw.handlers.UserHandler
import com.kxxnzstdsw.handlers.ViewHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Table-driven gRPC request dispatcher (v2.6).
 *
 * Replaces the hand-rolled visitor pattern (9 handleX functions + 11 wrapTypedResponse when-blocks)
 * with a single typed route map. New (Category, Action) pairs are one map entry, not three synchronized
 * edits. Silent `else -> {}` fallbacks in the old code are impossible here — table lookup either finds
 * the entry or throws.
 *
 * Streams (EXPORT.RUN_EXPORT, DATA.LIST pageSize=0, SQL.EXECUTE, DATA.GENERATE) keep dedicated paths.
 */
object RequestDispatcher {
    private val logger = LoggerFactory.getLogger(RequestDispatcher::class.java)

    /**
     * 单次响应的 (Category, Action) 路由表 — invoke 返回 typed per-Action response message，
     * wrap 把 typed 消息塞入对应 oneof 分支。
     */
    private data class Route(
        val invoke: suspend (ConnectionConfig, Request) -> Message,
        val wrap: (Response.Builder, Message) -> Unit
    )

    /**
     * SQL.EXECUTE 共享一个 entry，但 wrap 对 SELECT 流式 / 非 SELECT 单次响应有不同语义，
     * 故 SQL.EXPLAIN 走 routes 表，SQL.EXECUTE 走 streamSqlExecute。
     */
    private val routes: Map<Pair<Category, Action>, Route> = buildMap {
        // ─────── SCHEMA ───────
        put(Category.SCHEMA to Action.LIST, Route(
            invoke = { c, r -> SchemaHandler.list(c, r.schemaRequest.list) },
            wrap = { b, m -> b.schema = schemaResponse { list = m as com.kxxnzstdsw.grpc.SchemaListResponse } }
        ))
        put(Category.SCHEMA to Action.CREATE, Route(
            invoke = { c, r -> SchemaHandler.create(c, r.schemaRequest.create) },
            wrap = { b, m -> b.schema = schemaResponse { create = m as com.kxxnzstdsw.grpc.SchemaCreateResponse } }
        ))
        put(Category.SCHEMA to Action.DELETE, Route(
            invoke = { c, r -> SchemaHandler.delete(c, r.schemaRequest.delete) },
            wrap = { b, m -> b.schema = schemaResponse { delete = m as com.kxxnzstdsw.grpc.SchemaDeleteResponse } }
        ))

        // ─────── USER ───────
        put(Category.USER to Action.LIST, Route(
            invoke = { c, r -> UserHandler.list(c, r.userRequest.list) },
            wrap = { b, m -> b.user = userResponse { list = m as com.kxxnzstdsw.grpc.UserListResponse } }
        ))
        put(Category.USER to Action.CREATE, Route(
            invoke = { c, r -> UserHandler.create(c, r.userRequest.create) },
            wrap = { b, m -> b.user = userResponse { create = m as com.kxxnzstdsw.grpc.UserCreateResponse } }
        ))
        put(Category.USER to Action.UPDATE, Route(
            invoke = { c, r -> UserHandler.updatePrivileges(c, r.userRequest.update) },
            wrap = { b, m -> b.user = userResponse { update = m as com.kxxnzstdsw.grpc.UserUpdateResponse } }
        ))
        put(Category.USER to Action.DELETE, Route(
            invoke = { c, r -> UserHandler.delete(c, r.userRequest.delete) },
            wrap = { b, m -> b.user = userResponse { delete = m as com.kxxnzstdsw.grpc.UserDeleteResponse } }
        ))
        put(Category.USER to Action.GRANTS, Route(
            invoke = { c, r -> UserHandler.listAllGrants(c, r.userRequest.grants) },
            wrap = { b, m -> b.user = userResponse { grants = m as com.kxxnzstdsw.grpc.UserGrantsResponse } }
        ))

        // ─────── TABLE ───────
        put(Category.TABLE to Action.LIST, Route(
            invoke = { c, r ->
                if (r.tableRequest.hasColumnList()) TableHandler.columnList(c, r.tableRequest.columnList)
                else TableHandler.list(c, r.tableRequest.list)
            },
            wrap = { b, m ->
                b.table = tableResponse {
                    if (m is com.kxxnzstdsw.grpc.TableColumnListResponse) columns = m
                    else list = m as com.kxxnzstdsw.grpc.TableListResponse
                }
            }
        ))
        put(Category.TABLE to Action.CREATE, Route(
            invoke = { c, r -> TableHandler.create(c, r.tableRequest.create) },
            wrap = { b, m -> b.table = tableResponse { create = m as com.kxxnzstdsw.grpc.TableCreateResponse } }
        ))
        put(Category.TABLE to Action.UPDATE, Route(
            invoke = { c, r -> TableHandler.update(c, r.tableRequest.update) },
            wrap = { b, m -> b.table = tableResponse { update = m as com.kxxnzstdsw.grpc.TableUpdateResponse } }
        ))
        put(Category.TABLE to Action.DELETE, Route(
            invoke = { c, r -> TableHandler.delete(c, r.tableRequest.delete) },
            wrap = { b, m -> b.table = tableResponse { delete = m as com.kxxnzstdsw.grpc.TableDeleteResponse } }
        ))
        put(Category.TABLE to Action.GET_DDL, Route(
            invoke = { c, r -> TableHandler.getDDL(c, r.tableRequest.getDdl) },
            wrap = { b, m -> b.table = tableResponse { getDdl = m as com.kxxnzstdsw.grpc.TableGetDdlResponse } }
        ))
        put(Category.TABLE to Action.RENAME, Route(
            invoke = { c, r -> TableHandler.rename(c, r.tableRequest.rename) },
            wrap = { b, m -> b.table = tableResponse { rename = m as com.kxxnzstdsw.grpc.TableRenameResponse } }
        ))
        put(Category.TABLE to Action.TRUNCATE, Route(
            invoke = { c, r -> TableHandler.truncate(c, r.tableRequest.truncate) },
            wrap = { b, m -> b.table = tableResponse { truncate = m as com.kxxnzstdsw.grpc.TableTruncateResponse } }
        ))

        // ─────── DATA (non-stream) ───────
        put(Category.DATA to Action.LIST, Route(
            invoke = { c, r -> DataHandler.list(c, r.dataRequest.list, null) },
            wrap = { b, m -> b.data = dataResponse { list = m as com.kxxnzstdsw.grpc.DataListPagedResponse } }
        ))
        put(Category.DATA to Action.CREATE, Route(
            invoke = { c, r -> DataHandler.create(c, r.dataRequest.create) },
            wrap = { b, m -> b.data = dataResponse { create = m as com.kxxnzstdsw.grpc.DataCreateResponse } }
        ))
        put(Category.DATA to Action.UPDATE, Route(
            invoke = { c, r -> DataHandler.update(c, r.dataRequest.update) },
            wrap = { b, m -> b.data = dataResponse { update = m as com.kxxnzstdsw.grpc.DataUpdateResponse } }
        ))
        put(Category.DATA to Action.DELETE, Route(
            invoke = { c, r -> DataHandler.delete(c, r.dataRequest.delete) },
            wrap = { b, m -> b.data = dataResponse { delete = m as com.kxxnzstdsw.grpc.DataDeleteResponse } }
        ))

        // ─────── SQL ───────
        put(Category.SQL to Action.EXPLAIN, Route(
            invoke = { c, r -> SqlEngineHandler.explain(c, r.sqlRequest.explain) },
            wrap = { b, m -> b.sql = sqlResponse { explain = m as com.kxxnzstdsw.grpc.SqlExplainResponse } }
        ))

        // ─────── SYSTEM ───────
        put(Category.SYSTEM to Action.INFO, Route(
            invoke = { c, _ -> SystemHandler.info() },
            wrap = { b, m -> b.system = systemResponse { info = m as com.kxxnzstdsw.grpc.SystemInfoResponse } }
        ))
        put(Category.SYSTEM to Action.TEST_CONNECTION, Route(
            invoke = { c, _ -> SystemHandler.testConnection(c) },
            wrap = { b, m -> b.system = systemResponse { testConnection = m as com.kxxnzstdsw.grpc.SystemTestConnectionResponse } }
        ))
        put(Category.SYSTEM to Action.SERVER_INFO, Route(
            invoke = { c, _ -> SystemHandler.serverInfo(c) },
            wrap = { b, m -> b.system = systemResponse { serverInfo = m as com.kxxnzstdsw.grpc.SystemServerInfoResponse } }
        ))

        // ─────── FUNCTION ───────
        put(Category.FUNCTION to Action.LIST, Route(
            invoke = { c, r -> FunctionHandler.list(c, r.functionRequest.list) },
            wrap = { b, m -> b.function = functionResponse { list = m as com.kxxnzstdsw.grpc.FunctionListResponse } }
        ))
        put(Category.FUNCTION to Action.INFO, Route(
            invoke = { c, r -> FunctionHandler.info(c, r.functionRequest.info) },
            wrap = { b, m -> b.function = functionResponse { info = m as com.kxxnzstdsw.grpc.FunctionInfoResponse } }
        ))
        put(Category.FUNCTION to Action.GET_DDL, Route(
            invoke = { c, r -> FunctionHandler.getDDL(c, r.functionRequest.getDdl) },
            wrap = { b, m -> b.function = functionResponse { getDdl = m as com.kxxnzstdsw.grpc.FunctionGetDdlResponse } }
        ))
        put(Category.FUNCTION to Action.CREATE, Route(
            invoke = { c, r -> FunctionHandler.create(c, r.functionRequest.create) },
            wrap = { b, m -> b.function = functionResponse { create = m as com.kxxnzstdsw.grpc.FunctionCreateResponse } }
        ))
        put(Category.FUNCTION to Action.UPDATE, Route(
            invoke = { c, r -> FunctionHandler.validate(c, r.functionRequest.update) },
            wrap = { b, m -> b.function = functionResponse { update = m as com.kxxnzstdsw.grpc.FunctionValidateResponse } }
        ))
        put(Category.FUNCTION to Action.DELETE, Route(
            invoke = { c, r -> FunctionHandler.delete(c, r.functionRequest.delete) },
            wrap = { b, m -> b.function = functionResponse { delete = m as com.kxxnzstdsw.grpc.FunctionDeleteResponse } }
        ))
        put(Category.FUNCTION to Action.CALL, Route(
            invoke = { c, r -> FunctionHandler.call(c, r.functionRequest.call) },
            wrap = { b, m -> b.function = functionResponse { call = m as com.kxxnzstdsw.grpc.FunctionCallResponse } }
        ))
        put(Category.FUNCTION to Action.DEBUG, Route(
            invoke = { c, r -> FunctionHandler.debug(c, r.functionRequest.debug) },
            wrap = { b, m -> b.function = functionResponse { debug = m as com.kxxnzstdsw.grpc.FunctionDebugResponse } }
        ))

        // ─────── VIEW ───────
        put(Category.VIEW to Action.LIST, Route(
            invoke = { c, r -> ViewHandler.list(c, r.viewRequest.list) },
            wrap = { b, m -> b.view = viewResponse { list = m as com.kxxnzstdsw.grpc.ViewListResponse } }
        ))
        put(Category.VIEW to Action.CREATE, Route(
            invoke = { c, r -> ViewHandler.create(c, r.viewRequest.create) },
            wrap = { b, m -> b.view = viewResponse { create = m as com.kxxnzstdsw.grpc.ViewCreateResponse } }
        ))
        put(Category.VIEW to Action.DELETE, Route(
            invoke = { c, r -> ViewHandler.delete(c, r.viewRequest.delete) },
            wrap = { b, m -> b.view = viewResponse { delete = m as com.kxxnzstdsw.grpc.ViewDeleteResponse } }
        ))
        put(Category.VIEW to Action.GET_DDL, Route(
            invoke = { c, r -> ViewHandler.getDDL(c, r.viewRequest.getDdl) },
            wrap = { b, m -> b.view = viewResponse { getDdl = m as com.kxxnzstdsw.grpc.ViewGetDdlResponse } }
        ))

        // ─────── INDEX ───────
        put(Category.INDEX to Action.LIST, Route(
            invoke = { c, r -> IndexHandler.list(c, r.indexRequest.list) },
            wrap = { b, m -> b.index = indexResponse { list = m as com.kxxnzstdsw.grpc.IndexListResponse } }
        ))
        put(Category.INDEX to Action.CREATE, Route(
            invoke = { c, r -> IndexHandler.create(c, r.indexRequest.create) },
            wrap = { b, m -> b.index = indexResponse { create = m as com.kxxnzstdsw.grpc.IndexCreateResponse } }
        ))
        put(Category.INDEX to Action.DELETE, Route(
            invoke = { c, r -> IndexHandler.delete(c, r.indexRequest.delete) },
            wrap = { b, m -> b.index = indexResponse { delete = m as com.kxxnzstdsw.grpc.IndexDeleteResponse } }
        ))

        // ─────── FOREIGN_KEY ───────
        put(Category.FOREIGN_KEY to Action.LIST, Route(
            invoke = { c, r -> ForeignKeyHandler.list(c, r.foreignKeyRequest.list) },
            wrap = { b, m -> b.foreignKey = foreignKeyResponse { list = m as com.kxxnzstdsw.grpc.ForeignKeyListResponse } }
        ))
        put(Category.FOREIGN_KEY to Action.CREATE, Route(
            invoke = { c, r -> ForeignKeyHandler.create(c, r.foreignKeyRequest.create) },
            wrap = { b, m -> b.foreignKey = foreignKeyResponse { create = m as com.kxxnzstdsw.grpc.ForeignKeyCreateResponse } }
        ))
        put(Category.FOREIGN_KEY to Action.DELETE, Route(
            invoke = { c, r -> ForeignKeyHandler.delete(c, r.foreignKeyRequest.delete) },
            wrap = { b, m -> b.foreignKey = foreignKeyResponse { delete = m as com.kxxnzstdsw.grpc.ForeignKeyDeleteResponse } }
        ))

        // ─────── TRIGGER ───────
        put(Category.TRIGGER to Action.LIST, Route(
            invoke = { c, r -> TriggerHandler.list(c, r.triggerRequest.list) },
            wrap = { b, m -> b.trigger = triggerResponse { list = m as com.kxxnzstdsw.grpc.TriggerListResponse } }
        ))
        put(Category.TRIGGER to Action.GET_DDL, Route(
            invoke = { c, r -> TriggerHandler.getDDL(c, r.triggerRequest.getDdl) },
            wrap = { b, m -> b.trigger = triggerResponse { getDdl = m as com.kxxnzstdsw.grpc.TriggerGetDdlResponse } }
        ))
    }

    /**
     * 分发单个 gRPC typed [Request]，返回 Flow [Response]。
     *
     * 优先级：envelope options (dryRun) → stream routes → table routes → 抛错
     *
     * - [RequestOptions.traceId] → SLF4J MDC（自动出现在每条日志行）
     * - [RequestOptions.dryRun] + write action → 短路，返回 success 响应；不调用 handler
     * - [RequestOptions.timeoutMs] → 协程超时（0 = 不限）；超时时返回 success=false, error="timeout"
     */
    fun dispatch(request: Request): Flow<Response> = flow {
        val traceId = if (request.hasOptions() && request.options.traceId.isNotBlank())
            request.options.traceId else null
        if (traceId != null) MDC.put("traceId", traceId)
        val timeoutMs = if (request.hasOptions() && request.options.timeoutMs > 0)
            request.options.timeoutMs.toLong() else null
        val dryRun = request.hasOptions() && request.options.dryRun
        try {
            logger.info("Processing request: ${request.id} - ${request.category}/${request.action}" +
                (if (dryRun) " [dryRun]" else "") +
                (if (timeoutMs != null) " [timeoutMs=$timeoutMs]" else ""))

            // ─────── dryRun short-circuit for write actions ───────
            if (dryRun && (request.category to request.action) in writeActions) {
                logger.info("dryRun: skipping ${request.category}/${request.action}")
                emit(
                    Response.newBuilder()
                        .setId(request.id)
                        .setSuccess(true)
                        .setStream(false)
                        .setEnd(true)
                        .setError("dryRun: ${request.category}/${request.action} would execute but was skipped")
                        .build()
                )
                return@flow
            }

            // ─────── Stream routes ───────
            when {
                request.category == Category.EXPORT && request.action == Action.RUN_EXPORT -> {
                    ExportHandler.executeInMainProcess(request).collect { emit(it) }
                    return@flow
                }
                request.category == Category.DATA && request.action == Action.LIST
                    && request.dataRequest.hasList()
                    && request.dataRequest.list.hasPageSize()
                    && request.dataRequest.list.pageSize == 0 -> {
                    streamDataList(request.id, request).collect { emit(it) }
                    return@flow
                }
                request.category == Category.SQL && request.action == Action.EXECUTE -> {
                    streamSqlExecute(request.id, request).collect { emit(it) }
                    return@flow
                }
                request.category == Category.DATA && request.action == Action.GENERATE -> {
                    streamDataGenerate(request.id, request).collect { emit(it) }
                    return@flow
                }
            }

            // ─────── Table-driven routes ───────
            val route = routes[request.category to request.action]
                ?: throw UnsupportedOperationException(
                    "No route for ${request.category}/${request.action} — add an entry to RequestDispatcher.routes"
                )
            val msg = if (timeoutMs != null) {
                withTimeoutOrNull(timeoutMs) { route.invoke(request.connection, request) }
                    ?: throw java.util.concurrent.TimeoutException(
                        "Handler ${request.category}/${request.action} exceeded ${timeoutMs}ms"
                    )
            } else {
                route.invoke(request.connection, request)
            }
            val builder = Response.newBuilder()
                .setId(request.id)
                .setSuccess(true)
            route.wrap(builder, msg)
            emit(builder.build())
        } catch (e: Exception) {
            logger.error("Error processing request", e)
            emit(
                Response.newBuilder()
                    .setId(request.id)
                    .setSuccess(false)
                    .setError(e.message ?: "Unknown error")
                    .build()
            )
        } finally {
            if (traceId != null) MDC.remove("traceId")
        }
    }

    /**
     * 写入操作 (Category, Action) 集合 — dryRun 时短路跳过，不调用 handler。
     *
     * READ 类操作 (LIST / INFO / GET_DDL / GRANTS / CALL / DEBUG / TEST_CONNECTION /
     * SERVER_INFO / EXPLAIN / 系统 INFO) 不在此集合；dryRun 对它们无意义（无副作用）。
     */
    private val writeActions: Set<Pair<Category, Action>> = setOf(
        // SCHEMA
        Category.SCHEMA to Action.CREATE,
        Category.SCHEMA to Action.DELETE,
        // USER
        Category.USER to Action.CREATE,
        Category.USER to Action.UPDATE,
        Category.USER to Action.DELETE,
        // TABLE
        Category.TABLE to Action.CREATE,
        Category.TABLE to Action.UPDATE,
        Category.TABLE to Action.DELETE,
        Category.TABLE to Action.RENAME,
        Category.TABLE to Action.TRUNCATE,
        // DATA
        Category.DATA to Action.CREATE,
        Category.DATA to Action.UPDATE,
        Category.DATA to Action.DELETE,
        // SQL (任意 SQL EXECUTE — SELECT 同样有副作用时考虑风险)
        Category.SQL to Action.EXECUTE,
        // FUNCTION
        Category.FUNCTION to Action.CREATE,
        Category.FUNCTION to Action.UPDATE,
        Category.FUNCTION to Action.DELETE,
        // VIEW
        Category.VIEW to Action.CREATE,
        Category.VIEW to Action.DELETE,
        // INDEX
        Category.INDEX to Action.CREATE,
        Category.INDEX to Action.DELETE,
        // FOREIGN_KEY
        Category.FOREIGN_KEY to Action.CREATE,
        Category.FOREIGN_KEY to Action.DELETE,
        // EXPORT
        Category.EXPORT to Action.RUN_EXPORT,
    )

    // ============ 流式响应 ============
    //
    // 三个流式 helper (streamDataList / streamSqlExecute / streamDataGenerate) 共用同一种模式：
    //   1. 启动 IO 协程跑业务，handler 在 onProgress/onRow 回调里通过 Channel 推帧
    //   2. 主 flow 协程从 Channel 读取并 emit 到下游
    //   3. handler 返回后发送 terminal/end 帧
    //
    // 这样 handler 回调即便在非协程上下文（runBlocking / Lua JNI）调用 channel.send 也能正确
    // 把帧转发到 flow 的下游。

    private fun streamDataList(id: String, request: Request): Flow<Response> = flow {
        coroutineScope {
            val ch = Channel<Response>(Channel.BUFFERED)
            val job = launch(Dispatchers.IO) {
                try {
                    DataHandler.list(request.connection, request.dataRequest.list) { frame ->
                        ch.send(
                            Response.newBuilder()
                                .setId(id).setSuccess(true).setStream(true).setEnd(false)
                                .setDataRowFrame(frame)
                                .build()
                        )
                    }
                    ch.send(
                        Response.newBuilder()
                            .setId(id).setSuccess(true).setStream(true).setEnd(true)
                            .build()
                    )
                } catch (e: Exception) {
                    logger.error("Error in stream data list", e)
                    ch.send(
                        Response.newBuilder()
                            .setId(id).setSuccess(false).setError(e.message ?: "Unknown error")
                            .build()
                    )
                } finally {
                    ch.close()
                }
            }
            for (msg in ch) emit(msg)
            job.join()
        }
    }

    private fun streamSqlExecute(id: String, request: Request): Flow<Response> = flow {
        coroutineScope {
            val ch = Channel<Response>(Channel.BUFFERED)
            val job = launch(Dispatchers.IO) {
                try {
                    var rowCount = 0
                    val result = SqlEngineHandler.execute(request.connection, request.sqlRequest.execute) { frame ->
                        rowCount++
                        ch.send(
                            Response.newBuilder()
                                .setId(id).setSuccess(true).setStream(true).setEnd(false)
                                .setSqlRowFrame(frame)
                                .build()
                        )
                    }
                    if (rowCount == 0) {
                        ch.send(
                            Response.newBuilder()
                                .setId(id).setSuccess(true).setStream(true).setEnd(true)
                                .setSql(sqlResponse { execute = result })
                                .build()
                        )
                    } else {
                        ch.send(
                            Response.newBuilder()
                                .setId(id).setSuccess(true).setStream(true).setEnd(true)
                                .build()
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Error in SQL execute", e)
                    ch.send(
                        Response.newBuilder()
                            .setId(id).setSuccess(false).setError(e.message ?: "Unknown error")
                            .build()
                    )
                } finally {
                    ch.close()
                }
            }
            for (msg in ch) emit(msg)
            job.join()
        }
    }

    private fun streamDataGenerate(id: String, request: Request): Flow<Response> = flow {
        coroutineScope {
            val ch = Channel<Response>(Channel.BUFFERED)
            val job = launch(Dispatchers.IO) {
                try {
                    GenerateHandler.execute(request.connection, request.dataRequest.generate) { frame ->
                        ch.send(
                            Response.newBuilder()
                                .setId(id).setSuccess(true).setStream(true).setEnd(false)
                                .setGenProgressFrame(frame)
                                .build()
                        )
                    }
                    ch.send(
                        Response.newBuilder()
                            .setId(id).setSuccess(true).setStream(true).setEnd(true)
                            .setGenerateTerminal(generateTerminalResponse { success = true })
                            .build()
                    )
                } catch (e: Exception) {
                    logger.error("Error in data generate", e)
                    ch.send(
                        Response.newBuilder()
                            .setId(id).setSuccess(false).setError(e.message ?: "Unknown error")
                            .build()
                    )
                } finally {
                    ch.close()
                }
            }
            for (msg in ch) emit(msg)
            job.join()
        }
    }
}