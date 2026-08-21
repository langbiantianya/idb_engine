package com.kxxnzstdsw.dispatcher

import com.google.protobuf.Message
import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.DataResponse
import com.kxxnzstdsw.grpc.DataRowFrame
import com.kxxnzstdsw.grpc.ExportResponse
import com.kxxnzstdsw.grpc.ForeignKeyResponse
import com.kxxnzstdsw.grpc.FunctionResponse
import com.kxxnzstdsw.grpc.GenerateProgressFrame
import com.kxxnzstdsw.grpc.GenerateTerminalResponse
import com.kxxnzstdsw.grpc.IndexResponse
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.SchemaResponse
import com.kxxnzstdsw.grpc.SqlResponse
import com.kxxnzstdsw.grpc.SqlSelectRowFrame
import com.kxxnzstdsw.grpc.SystemResponse
import com.kxxnzstdsw.grpc.TableResponse
import com.kxxnzstdsw.grpc.TriggerResponse
import com.kxxnzstdsw.grpc.UserResponse
import com.kxxnzstdsw.grpc.ViewResponse
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

object RequestDispatcher {
    private val logger = LoggerFactory.getLogger(RequestDispatcher::class.java)

    /**
     * 分发单个 gRPC typed [Request]，返回 Flow<Response>。
     *
     * 所有 handler 接收 typed per-Category proto 请求，返回 typed per-Action proto 消息；
     * dispatcher 负责把 typed 结果包到 [Response.body] 对应 oneof 分支里。
     */
    fun dispatch(request: Request): Flow<Response> = flow {
        try {
            logger.info("Processing request: ${request.id} - ${request.category}/${request.action}")

            // EXPORT 由 ExportHandler 统一编排（业务编排 + 子进程通信）
            if (request.category == Category.EXPORT && request.action == Action.RUN_EXPORT) {
                ExportHandler.executeInMainProcess(request).collect { emit(it) }
                return@flow
            }

            // 流式 DATA LIST（pageSize == 0）
            if (request.category == Category.DATA && request.action == Action.LIST
                && request.dataRequest.hasList()
                && request.dataRequest.list.hasPageSize()
                && request.dataRequest.list.pageSize == 0) {
                streamDataList(request.id, request).collect { emit(it) }
                return@flow
            }

            // SQL EXECUTE 走流式路径（内部判断是否为 SELECT）
            if (request.category == Category.SQL && request.action == Action.EXECUTE) {
                streamSqlExecute(request.id, request).collect { emit(it) }
                return@flow
            }

            // DATA GENERATE 走流式路径（造数进度回报）
            if (request.category == Category.DATA && request.action == Action.GENERATE) {
                streamDataGenerate(request.id, request).collect { emit(it) }
                return@flow
            }

            // 非流式路径 — 每个 (Category, Action) 直接调 handler，handler 返回 typed per-Action 消息
            val msg: Message = when (request.category) {
                Category.SCHEMA -> handleSchema(request.action, request.connection, request)
                Category.TABLE -> handleTable(request.action, request.connection, request)
                Category.DATA -> handleData(request.action, request.connection, request)
                Category.USER -> handleUser(request.action, request.connection, request)
                Category.FUNCTION -> handleFunction(request.action, request.connection, request)
                Category.VIEW -> handleView(request.action, request.connection, request)
                Category.INDEX -> handleIndex(request.action, request.connection, request)
                Category.FOREIGN_KEY -> handleForeignKey(request.action, request.connection, request)
                Category.TRIGGER -> handleTrigger(request.action, request.connection, request)
                Category.SYSTEM -> handleSystem(request.action, request.connection)
                else -> throw UnsupportedOperationException("Unsupported category: ${request.category}")
            }

            emit(wrapTypedResponse(request.id, request.category, request.action, msg))
        } catch (e: Exception) {
            logger.error("Error processing request", e)
            emit(
                Response.newBuilder()
                    .setId(request.id)
                    .setSuccess(false)
                    .setError(e.message ?: "Unknown error")
                    .build()
            )
        }
    }

    // ============ 流式响应 ============

    private fun streamDataList(id: String, request: Request): Flow<Response> = flow {
        try {
            val listReq = request.dataRequest.list
            DataHandler.list(request.connection, listReq) { frame ->
                emit(
                    Response.newBuilder()
                        .setId(id).setSuccess(true).setStream(true).setEnd(false)
                        .setDataRowFrame(frame)
                        .build()
                )
            }
            emit(
                Response.newBuilder()
                    .setId(id).setSuccess(true).setStream(true).setEnd(true).build()
            )
        } catch (e: Exception) {
            logger.error("Error in stream data list", e)
            emit(
                Response.newBuilder()
                    .setId(id).setSuccess(false).setError(e.message ?: "Unknown error")
                    .build()
            )
        }
    }

    private fun streamSqlExecute(id: String, request: Request): Flow<Response> = flow {
        try {
            val sqlReq = request.sqlRequest.execute
            var rowCount = 0
            val result = SqlEngineHandler.execute(request.connection, sqlReq) { frame ->
                rowCount++
                emit(
                    Response.newBuilder()
                        .setId(id).setSuccess(true).setStream(true).setEnd(false)
                        .setSqlRowFrame(frame)
                        .build()
                )
            }
            // 终帧：若 SELECT 走完了（rowCount > 0），标记结束；否则把 affectedRows 装回 SqlResponse.execute
            val terminalBuilder = Response.newBuilder()
                .setId(id).setSuccess(true).setStream(true).setEnd(true)
            if (rowCount == 0) {
                // 非 SELECT — 把 typed SqlExecuteResponse 装回 SqlResponse body
                terminalBuilder.setSql(
                    SqlResponse.newBuilder().setExecute(result)
                )
            }
            emit(terminalBuilder.build())
        } catch (e: Exception) {
            logger.error("Error in SQL execute", e)
            emit(
                Response.newBuilder()
                    .setId(id).setSuccess(false).setError(e.message ?: "Unknown error")
                    .build()
            )
        }
    }

    private fun streamDataGenerate(id: String, request: Request): Flow<Response> = flow {
        try {
            val genReq = request.dataRequest.generate
            GenerateHandler.execute(request.connection, genReq) { frame ->
                emit(
                    Response.newBuilder()
                        .setId(id).setSuccess(true).setStream(true).setEnd(false)
                        .setGenProgressFrame(frame)
                        .build()
                )
            }
            emit(
                Response.newBuilder()
                    .setId(id).setSuccess(true).setStream(true).setEnd(true)
                    .setGenerateTerminal(
                        GenerateTerminalResponse.newBuilder().setSuccess(true)
                    )
                    .build()
            )
        } catch (e: Exception) {
            logger.error("Error in data generate", e)
            emit(
                Response.newBuilder()
                    .setId(id).setSuccess(false).setError(e.message ?: "Unknown error")
                    .build()
            )
        }
    }

    // ============ 非流式响应（按 Category 分发） ============

    private suspend fun handleSchema(action: Action, config: ConnectionConfig, req: Request): Message =
        when (action) {
            Action.LIST -> SchemaHandler.list(config, req.schemaRequest.list)
            Action.CREATE -> SchemaHandler.create(config, req.schemaRequest.create)
            Action.DELETE -> SchemaHandler.delete(config, req.schemaRequest.delete)
            else -> throw UnsupportedOperationException("Action $action not supported for SCHEMA")
        }

    private suspend fun handleTable(action: Action, config: ConnectionConfig, req: Request): Message =
        when (action) {
            Action.LIST -> if (req.tableRequest.hasColumnList())
                TableHandler.columnList(config, req.tableRequest.columnList)
            else
                TableHandler.list(config, req.tableRequest.list)
            Action.CREATE -> TableHandler.create(config, req.tableRequest.create)
            Action.UPDATE -> TableHandler.update(config, req.tableRequest.update)
            Action.DELETE -> TableHandler.delete(config, req.tableRequest.delete)
            Action.GET_DDL -> TableHandler.getDDL(config, req.tableRequest.getDdl)
            Action.RENAME -> TableHandler.rename(config, req.tableRequest.rename)
            Action.TRUNCATE -> TableHandler.truncate(config, req.tableRequest.truncate)
            else -> throw UnsupportedOperationException("Action $action not supported for TABLE")
        }

    private suspend fun handleData(action: Action, config: ConnectionConfig, req: Request): Message =
        when (action) {
            Action.LIST -> DataHandler.list(config, req.dataRequest.list)
            Action.CREATE -> DataHandler.create(config, req.dataRequest.create)
            Action.UPDATE -> DataHandler.update(config, req.dataRequest.update)
            Action.DELETE -> DataHandler.delete(config, req.dataRequest.delete)
            else -> throw UnsupportedOperationException("Action $action not supported for DATA")
        }

    private suspend fun handleUser(action: Action, config: ConnectionConfig, req: Request): Message =
        when (action) {
            Action.LIST -> UserHandler.list(config, req.userRequest.list)
            Action.CREATE -> UserHandler.create(config, req.userRequest.create)
            Action.UPDATE -> UserHandler.updatePrivileges(config, req.userRequest.update)
            Action.DELETE -> UserHandler.delete(config, req.userRequest.delete)
            Action.GRANTS -> UserHandler.listAllGrants(config, req.userRequest.grants)
            else -> throw UnsupportedOperationException("Action $action not supported for USER")
        }

    private suspend fun handleFunction(action: Action, config: ConnectionConfig, req: Request): Message =
        when (action) {
            Action.LIST -> FunctionHandler.list(config, req.functionRequest.list)
            Action.INFO -> FunctionHandler.info(config, req.functionRequest.info)
            Action.GET_DDL -> FunctionHandler.getDDL(config, req.functionRequest.getDdl)
            Action.CREATE -> FunctionHandler.create(config, req.functionRequest.create)
            Action.DELETE -> FunctionHandler.delete(config, req.functionRequest.delete)
            Action.CALL -> FunctionHandler.call(config, req.functionRequest.call)
            Action.DEBUG -> FunctionHandler.debug(config, req.functionRequest.debug)
            Action.UPDATE -> FunctionHandler.validate(config, req.functionRequest.update)
            else -> throw UnsupportedOperationException("Action $action not supported for FUNCTION")
        }

    private suspend fun handleView(action: Action, config: ConnectionConfig, req: Request): Message =
        when (action) {
            Action.LIST -> ViewHandler.list(config, req.viewRequest.list)
            Action.CREATE -> ViewHandler.create(config, req.viewRequest.create)
            Action.DELETE -> ViewHandler.delete(config, req.viewRequest.delete)
            Action.GET_DDL -> ViewHandler.getDDL(config, req.viewRequest.getDdl)
            else -> throw UnsupportedOperationException("Action $action not supported for VIEW")
        }

    private suspend fun handleIndex(action: Action, config: ConnectionConfig, req: Request): Message =
        when (action) {
            Action.LIST -> IndexHandler.list(config, req.indexRequest.list)
            Action.CREATE -> IndexHandler.create(config, req.indexRequest.create)
            Action.DELETE -> IndexHandler.delete(config, req.indexRequest.delete)
            else -> throw UnsupportedOperationException("Action $action not supported for INDEX")
        }

    private suspend fun handleForeignKey(action: Action, config: ConnectionConfig, req: Request): Message =
        when (action) {
            Action.LIST -> ForeignKeyHandler.list(config, req.foreignKeyRequest.list)
            Action.CREATE -> ForeignKeyHandler.create(config, req.foreignKeyRequest.create)
            Action.DELETE -> ForeignKeyHandler.delete(config, req.foreignKeyRequest.delete)
            else -> throw UnsupportedOperationException("Action $action not supported for FOREIGN_KEY")
        }

    private suspend fun handleTrigger(action: Action, config: ConnectionConfig, req: Request): Message =
        when (action) {
            Action.LIST -> TriggerHandler.list(config, req.triggerRequest.list)
            Action.GET_DDL -> TriggerHandler.getDDL(config, req.triggerRequest.getDdl)
            else -> throw UnsupportedOperationException("Action $action not supported for TRIGGER")
        }

    private suspend fun handleSystem(action: Action, config: ConnectionConfig): Message = when (action) {
        Action.INFO -> SystemHandler.info()
        Action.TEST_CONNECTION -> SystemHandler.testConnection(config)
        Action.SERVER_INFO -> SystemHandler.serverInfo(config)
        else -> throw UnsupportedOperationException("Action $action not supported for SYSTEM")
    }

    // ============ 把 handler 返回的 typed per-Action 消息包到 Response.body ============

    private fun wrapTypedResponse(id: String, category: Category, action: Action, msg: Message): Response {
        val responseBuilder = Response.newBuilder().setId(id).setSuccess(true)
        when (category) {
            Category.SCHEMA -> {
                val wrapper = SchemaResponse.newBuilder()
                when (action) {
                    Action.LIST -> wrapper.list = msg as com.kxxnzstdsw.grpc.SchemaListResponse
                    Action.CREATE -> wrapper.create = msg as com.kxxnzstdsw.grpc.SchemaCreateResponse
                    Action.DELETE -> wrapper.delete = msg as com.kxxnzstdsw.grpc.SchemaDeleteResponse
                    else -> {}
                }
                responseBuilder.schema = wrapper.build()
            }
            Category.TABLE -> {
                val wrapper = TableResponse.newBuilder()
                when (action) {
                    Action.LIST -> {
                        // 双模式：columns (columnList) vs list
                        if (msg is com.kxxnzstdsw.grpc.TableColumnListResponse) {
                            wrapper.columns = msg
                        } else {
                            wrapper.list = msg as com.kxxnzstdsw.grpc.TableListResponse
                        }
                    }
                    Action.CREATE -> wrapper.create = msg as com.kxxnzstdsw.grpc.TableCreateResponse
                    Action.UPDATE -> wrapper.update = msg as com.kxxnzstdsw.grpc.TableUpdateResponse
                    Action.DELETE -> wrapper.delete = msg as com.kxxnzstdsw.grpc.TableDeleteResponse
                    Action.GET_DDL -> wrapper.getDdl = msg as com.kxxnzstdsw.grpc.TableGetDdlResponse
                    Action.RENAME -> wrapper.rename = msg as com.kxxnzstdsw.grpc.TableRenameResponse
                    Action.TRUNCATE -> wrapper.truncate = msg as com.kxxnzstdsw.grpc.TableTruncateResponse
                    else -> {}
                }
                responseBuilder.table = wrapper.build()
            }
            Category.DATA -> {
                val wrapper = DataResponse.newBuilder()
                when (action) {
                    Action.LIST -> wrapper.list = msg as DataListPagedResponseLocal
                    Action.CREATE -> wrapper.create = msg as com.kxxnzstdsw.grpc.DataCreateResponse
                    Action.UPDATE -> wrapper.update = msg as com.kxxnzstdsw.grpc.DataUpdateResponse
                    Action.DELETE -> wrapper.delete = msg as com.kxxnzstdsw.grpc.DataDeleteResponse
                    else -> {}
                }
                responseBuilder.data = wrapper.build()
            }
            Category.SQL -> {
                val wrapper = SqlResponse.newBuilder()
                when (action) {
                    Action.EXECUTE -> wrapper.execute = msg as com.kxxnzstdsw.grpc.SqlExecuteResponse
                    Action.EXPLAIN -> wrapper.explain = msg as com.kxxnzstdsw.grpc.SqlExplainResponse
                    else -> {}
                }
                responseBuilder.sql = wrapper.build()
            }
            Category.SYSTEM -> {
                val wrapper = SystemResponse.newBuilder()
                when (action) {
                    Action.INFO -> wrapper.info = msg as com.kxxnzstdsw.grpc.SystemInfoResponse
                    Action.TEST_CONNECTION -> wrapper.testConnection = msg as com.kxxnzstdsw.grpc.SystemTestConnectionResponse
                    Action.SERVER_INFO -> wrapper.serverInfo = msg as com.kxxnzstdsw.grpc.SystemServerInfoResponse
                    else -> {}
                }
                responseBuilder.system = wrapper.build()
            }
            Category.FUNCTION -> {
                val wrapper = FunctionResponse.newBuilder()
                when (action) {
                    Action.LIST -> wrapper.list = msg as com.kxxnzstdsw.grpc.FunctionListResponse
                    Action.INFO -> wrapper.info = msg as com.kxxnzstdsw.grpc.FunctionInfoResponse
                    Action.GET_DDL -> wrapper.getDdl = msg as com.kxxnzstdsw.grpc.FunctionGetDdlResponse
                    Action.CREATE -> wrapper.create = msg as com.kxxnzstdsw.grpc.FunctionCreateResponse
                    Action.DELETE -> wrapper.delete = msg as com.kxxnzstdsw.grpc.FunctionDeleteResponse
                    Action.CALL -> wrapper.call = msg as com.kxxnzstdsw.grpc.FunctionCallResponse
                    Action.DEBUG -> wrapper.debug = msg as com.kxxnzstdsw.grpc.FunctionDebugResponse
                    Action.UPDATE -> wrapper.update = msg as com.kxxnzstdsw.grpc.FunctionValidateResponse
                    else -> {}
                }
                responseBuilder.function = wrapper.build()
            }
            Category.USER -> {
                val wrapper = UserResponse.newBuilder()
                when (action) {
                    Action.LIST -> wrapper.list = msg as com.kxxnzstdsw.grpc.UserListResponse
                    Action.CREATE -> wrapper.create = msg as com.kxxnzstdsw.grpc.UserCreateResponse
                    Action.UPDATE -> wrapper.update = msg as com.kxxnzstdsw.grpc.UserUpdateResponse
                    Action.DELETE -> wrapper.delete = msg as com.kxxnzstdsw.grpc.UserDeleteResponse
                    Action.GRANTS -> wrapper.grants = msg as com.kxxnzstdsw.grpc.UserGrantsResponse
                    else -> {}
                }
                responseBuilder.user = wrapper.build()
            }
            Category.VIEW -> {
                val wrapper = ViewResponse.newBuilder()
                when (action) {
                    Action.LIST -> wrapper.list = msg as com.kxxnzstdsw.grpc.ViewListResponse
                    Action.CREATE -> wrapper.create = msg as com.kxxnzstdsw.grpc.ViewCreateResponse
                    Action.DELETE -> wrapper.delete = msg as com.kxxnzstdsw.grpc.ViewDeleteResponse
                    Action.GET_DDL -> wrapper.getDdl = msg as com.kxxnzstdsw.grpc.ViewGetDdlResponse
                    else -> {}
                }
                responseBuilder.view = wrapper.build()
            }
            Category.INDEX -> {
                val wrapper = IndexResponse.newBuilder()
                when (action) {
                    Action.LIST -> wrapper.list = msg as com.kxxnzstdsw.grpc.IndexListResponse
                    Action.CREATE -> wrapper.create = msg as com.kxxnzstdsw.grpc.IndexCreateResponse
                    Action.DELETE -> wrapper.delete = msg as com.kxxnzstdsw.grpc.IndexDeleteResponse
                    else -> {}
                }
                responseBuilder.index = wrapper.build()
            }
            Category.FOREIGN_KEY -> {
                val wrapper = ForeignKeyResponse.newBuilder()
                when (action) {
                    Action.LIST -> wrapper.list = msg as com.kxxnzstdsw.grpc.ForeignKeyListResponse
                    Action.CREATE -> wrapper.create = msg as com.kxxnzstdsw.grpc.ForeignKeyCreateResponse
                    Action.DELETE -> wrapper.delete = msg as com.kxxnzstdsw.grpc.ForeignKeyDeleteResponse
                    else -> {}
                }
                responseBuilder.foreignKey = wrapper.build()
            }
            Category.TRIGGER -> {
                val wrapper = TriggerResponse.newBuilder()
                when (action) {
                    Action.LIST -> wrapper.list = msg as com.kxxnzstdsw.grpc.TriggerListResponse
                    Action.GET_DDL -> wrapper.getDdl = msg as com.kxxnzstdsw.grpc.TriggerGetDdlResponse
                    else -> {}
                }
                responseBuilder.trigger = wrapper.build()
            }
            Category.EXPORT -> {
                val wrapper = ExportResponse.newBuilder()
                // Export 的非 RUN_EXPORT action 当前未对外暴露（仅 RUN_EXPORT 走 ExportHandler.executeInMainProcess）
                responseBuilder.export = wrapper.build()
            }
            else -> throw UnsupportedOperationException("Unsupported category: $category")
        }
        return responseBuilder.build()
    }

    // 仅为消除导入告警，类型别名
    private typealias DataListPagedResponseLocal = com.kxxnzstdsw.grpc.DataListPagedResponse
}