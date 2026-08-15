package com.kxxnzstdsw.dispatcher

import com.kxxnzstdsw.grpc.Action
import com.kxxnzstdsw.grpc.Category
import com.kxxnzstdsw.grpc.PayloadAdapter
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

object RequestDispatcher {
    private val logger = LoggerFactory.getLogger(RequestDispatcher::class.java)

    /**
     * 分发单个 gRPC 请求，返回 Flow<Response>。
     *
     * - input: 已反序列化的 Request（来自 gRPC Stub）
     * - output: 流式响应（每个 Response.onNext 一帧）
     */
    fun dispatch(request: Request): Flow<Response> = flow {
        try {
            logger.info("Processing request: ${request.id} - ${request.category}/${request.action}")

            // 业务层 payload（JsonObject）— Handler 内部继续使用 JsonObject 风格 API
            val payloadJson = PayloadAdapter.toJsonObject(request.payloadMap)

            // EXPORT 由 ExportHandler 统一处理（业务编排 + 子进程通信）
            if (request.category == Category.EXPORT && request.action == Action.RUN_EXPORT) {
                ExportHandler.execute(request).collect { emit(it) }
                return@flow
            }

            // 流式 DATA LIST（pageSize == 0）
            if (request.category == Category.DATA && request.action == Action.LIST) {
                val pageSize = PayloadAdapter.asIntOrNull(request.payloadMap["pageSize"] ?: PayloadAdapter.nullValue()) ?: 50
                if (pageSize == 0) {
                    streamDataList(request.id, request, payloadJson).collect { emit(it) }
                    return@flow
                }
            }

            // SQL EXECUTE 走流式路径（内部判断是否为 SELECT）
            if (request.category == Category.SQL && request.action == Action.EXECUTE) {
                streamSqlExecute(request.id, request, payloadJson).collect { emit(it) }
                return@flow
            }

            // DATA GENERATE 走流式路径（造数进度回报）
            if (request.category == Category.DATA && request.action == Action.GENERATE) {
                streamDataGenerate(request.id, request, payloadJson).collect { emit(it) }
                return@flow
            }

            // 非流式路径
            val data: JsonElement = when (request.category) {
                Category.SCHEMA -> handleSchema(request.action, request.connection, payloadJson)
                Category.TABLE -> handleTable(request.action, request.connection, payloadJson)
                Category.DATA -> handleData(request.action, request.connection, payloadJson)
                Category.USER -> handleUser(request.action, request.connection, payloadJson)
                Category.FUNCTION -> handleFunction(request.action, request.connection, payloadJson)
                Category.VIEW -> handleView(request.action, request.connection, payloadJson)
                Category.INDEX -> handleIndex(request.action, request.connection, payloadJson)
                Category.FOREIGN_KEY -> handleForeignKey(request.action, request.connection, payloadJson)
                Category.TRIGGER -> handleTrigger(request.action, request.connection, payloadJson)
                Category.SYSTEM -> handleSystem(request.action, request.connection)
                else -> throw UnsupportedOperationException("Unsupported category: ${request.category}")
            }

            emit(
                Response.newBuilder()
                    .setId(request.id)
                    .setSuccess(true)
                    .setData(PayloadAdapter.toValue(data))
                    .build()
            )
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

    private fun streamDataList(id: String, request: Request, payload: JsonObject): Flow<Response> = flow {
        try {
            DataHandler.list(request.connection, payload) { row ->
                emit(
                    Response.newBuilder()
                        .setId(id).setSuccess(true).setStream(true).setEnd(false)
                        .setData(PayloadAdapter.toValue(row))
                        .build()
                )
            }
            emit(
                Response.newBuilder()
                    .setId(id).setSuccess(true).setStream(true).setEnd(true)
                    .setData(PayloadAdapter.toValue(JsonNull))
                    .build()
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

    private fun streamSqlExecute(id: String, request: Request, payload: JsonObject): Flow<Response> = flow {
        try {
            val result = SqlEngineHandler.execute(request.connection, payload) { row ->
                emit(
                    Response.newBuilder()
                        .setId(id).setSuccess(true).setStream(true).setEnd(false)
                        .setData(PayloadAdapter.toValue(row))
                        .build()
                )
            }
            if (result is Boolean && result) {
                // SELECT 查询已流式发送，发送结束标记
                emit(
                    Response.newBuilder()
                        .setId(id).setSuccess(true).setStream(true).setEnd(true)
                        .setData(PayloadAdapter.toValue(JsonNull))
                        .build()
                )
            } else {
                // 非 SELECT（INSERT/UPDATE/DELETE/DDL），单次响应
                emit(
                    Response.newBuilder()
                        .setId(id).setSuccess(true)
                        .setData(PayloadAdapter.toValue(result as JsonElement))
                        .build()
                )
            }
        } catch (e: Exception) {
            logger.error("Error in SQL execute", e)
            emit(
                Response.newBuilder()
                    .setId(id).setSuccess(false).setError(e.message ?: "Unknown error")
                    .build()
            )
        }
    }

    private fun streamDataGenerate(id: String, request: Request, payload: JsonObject): Flow<Response> = flow {
        try {
            GenerateHandler.execute(request.connection, payload) { progress ->
                emit(
                    Response.newBuilder()
                        .setId(id).setSuccess(true).setStream(true).setEnd(false)
                        .setData(PayloadAdapter.toValue(progress))
                        .build()
                )
            }
            emit(
                Response.newBuilder()
                    .setId(id).setSuccess(true).setStream(true).setEnd(true)
                    .setData(PayloadAdapter.toValue(JsonNull))
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

    private suspend fun handleSchema(
        action: Action,
        config: com.kxxnzstdsw.grpc.ConnectionConfig,
        payload: JsonObject
    ): JsonElement {
        return when (action) {
            Action.LIST -> SchemaHandler.list(config, payload)
            Action.CREATE -> SchemaHandler.create(config, payload)
            Action.DELETE -> SchemaHandler.delete(config, payload)
            else -> throw UnsupportedOperationException("Action $action not supported for SCHEMA")
        }
    }

    private suspend fun handleTable(
        action: Action,
        config: com.kxxnzstdsw.grpc.ConnectionConfig,
        payload: JsonObject
    ): JsonElement {
        return when (action) {
            Action.LIST -> {
                if (payload.containsKey("tableName")) {
                    TableHandler.columnList(config, payload)
                } else {
                    TableHandler.list(config, payload)
                }
            }
            Action.CREATE -> TableHandler.create(config, payload)
            Action.UPDATE -> TableHandler.update(config, payload)
            Action.DELETE -> TableHandler.delete(config, payload)
            Action.GET_DDL -> TableHandler.getDDL(config, payload)
            Action.RENAME -> TableHandler.rename(config, payload)
            Action.TRUNCATE -> TableHandler.truncate(config, payload)
            else -> throw UnsupportedOperationException("Action $action not supported for TABLE")
        }
    }

    private suspend fun handleData(
        action: Action,
        config: com.kxxnzstdsw.grpc.ConnectionConfig,
        payload: JsonObject
    ): JsonElement {
        return when (action) {
            Action.LIST -> DataHandler.list(config, payload) as JsonElement
            Action.CREATE -> DataHandler.create(config, payload)
            Action.UPDATE -> DataHandler.update(config, payload)
            Action.DELETE -> DataHandler.delete(config, payload)
            else -> throw UnsupportedOperationException("Action $action not supported for DATA")
        }
    }

    private suspend fun handleUser(
        action: Action,
        config: com.kxxnzstdsw.grpc.ConnectionConfig,
        payload: JsonObject
    ): JsonElement {
        return when (action) {
            Action.LIST -> UserHandler.list(config, payload)
            Action.CREATE -> UserHandler.create(config, payload)
            Action.UPDATE -> UserHandler.updatePrivileges(config, payload)
            Action.DELETE -> UserHandler.delete(config, payload)
            Action.GRANTS -> UserHandler.listAllGrants(config, payload)
            else -> throw UnsupportedOperationException("Action $action not supported for USER")
        }
    }

    private suspend fun handleFunction(
        action: Action,
        config: com.kxxnzstdsw.grpc.ConnectionConfig,
        payload: JsonObject
    ): JsonElement {
        return when (action) {
            Action.LIST -> FunctionHandler.list(config, payload)
            Action.INFO -> FunctionHandler.info(config, payload)
            Action.GET_DDL -> FunctionHandler.getDDL(config, payload)
            Action.CREATE -> FunctionHandler.create(config, payload)
            Action.DELETE -> FunctionHandler.delete(config, payload)
            Action.CALL -> FunctionHandler.call(config, payload)
            Action.DEBUG -> FunctionHandler.debug(config, payload)
            Action.UPDATE -> FunctionHandler.validate(config, payload)
            else -> throw UnsupportedOperationException("Action $action not supported for FUNCTION")
        }
    }

    private suspend fun handleView(
        action: Action,
        config: com.kxxnzstdsw.grpc.ConnectionConfig,
        payload: JsonObject
    ): JsonElement {
        return when (action) {
            Action.LIST -> ViewHandler.list(config, payload)
            Action.CREATE -> ViewHandler.create(config, payload)
            Action.DELETE -> ViewHandler.delete(config, payload)
            Action.GET_DDL -> ViewHandler.getDDL(config, payload)
            else -> throw UnsupportedOperationException("Action $action not supported for VIEW")
        }
    }

    private suspend fun handleIndex(
        action: Action,
        config: com.kxxnzstdsw.grpc.ConnectionConfig,
        payload: JsonObject
    ): JsonElement {
        return when (action) {
            Action.LIST -> IndexHandler.list(config, payload)
            Action.CREATE -> IndexHandler.create(config, payload)
            Action.DELETE -> IndexHandler.delete(config, payload)
            else -> throw UnsupportedOperationException("Action $action not supported for INDEX")
        }
    }

    private suspend fun handleForeignKey(
        action: Action,
        config: com.kxxnzstdsw.grpc.ConnectionConfig,
        payload: JsonObject
    ): JsonElement {
        return when (action) {
            Action.LIST -> ForeignKeyHandler.list(config, payload)
            Action.CREATE -> ForeignKeyHandler.create(config, payload)
            Action.DELETE -> ForeignKeyHandler.delete(config, payload)
            else -> throw UnsupportedOperationException("Action $action not supported for FOREIGN_KEY")
        }
    }

    private suspend fun handleTrigger(
        action: Action,
        config: com.kxxnzstdsw.grpc.ConnectionConfig,
        payload: JsonObject
    ): JsonElement {
        return when (action) {
            Action.LIST -> TriggerHandler.list(config, payload)
            Action.GET_DDL -> TriggerHandler.getDDL(config, payload)
            else -> throw UnsupportedOperationException("Action $action not supported for TRIGGER")
        }
    }

    private suspend fun handleSystem(
        action: Action,
        config: com.kxxnzstdsw.grpc.ConnectionConfig
    ): JsonElement {
        return when (action) {
            Action.INFO -> SystemHandler.info()
            Action.TEST_CONNECTION -> SystemHandler.testConnection(config)
            Action.SERVER_INFO -> SystemHandler.serverInfo(config)
            else -> throw UnsupportedOperationException("Action $action not supported for SYSTEM")
        }
    }
}