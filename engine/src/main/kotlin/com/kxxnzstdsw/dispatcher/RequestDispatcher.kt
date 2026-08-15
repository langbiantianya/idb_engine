package com.kxxnzstdsw.dispatcher

import com.kxxnzstdsw.handlers.*
import com.kxxnzstdsw.models.Action
import com.kxxnzstdsw.models.Category
import com.kxxnzstdsw.models.Request
import com.kxxnzstdsw.models.Response
import com.kxxnzstdsw.proto.PayloadValue
import com.kxxnzstdsw.proto.ProtoConverters
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

object RequestDispatcher {
    private val logger = LoggerFactory.getLogger(RequestDispatcher::class.java)

    // wire 层用 ProtoBuf；业务层内部仍可使用 kotlinx.serialization.json 的 JsonObject
    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf {
        encodeDefaults = true
    }

    // 业务层（Handler 内部）继续使用 JsonObject，便于 handler 用 .jsonPrimitive?.intOrNull 等 API
    private val handlerPayload: (Request) -> JsonObject = { req ->
        ProtoConverters.toJsonObject(req.payload)
    }

    /**
     * 分发单个请求并把响应（已编码为 protobuf 字节）送入 outputChannel。
     *
     * - input: 已反序列化的 Request（由 Main.kt 从 stdin 帧解码得到）
     * - outputChannel: 主进程统一的输出管线（Channel<ByteArray>），Main.kt 负责写入 stdout
     */
    suspend fun dispatch(request: Request, outputChannel: Channel<ByteArray>) {
        try {
            logger.info("Processing request: ${request.id} - ${request.category}/${request.action}")

            // 业务层 payload（JsonObject）— Handler 内部继续使用 JsonObject 风格 API
            val payloadJson = handlerPayload(request)

            // SYSTEM 路由：INFO/TEST_CONNECTION/SERVER_INFO
            if (request.category == Category.SYSTEM) {
                handleSystem(request, payloadJson, outputChannel)
                return
            }

            // EXPORT 由 ExportHandler 统一处理（业务编排 + 子进程通信）
            if (request.category == Category.EXPORT && request.action == Action.EXPORT) {
                ExportHandler.execute(request.id, request.connection, payloadJson, outputChannel)
                return
            }

            // 流式 DATA LIST（pageSize == 0）
            if (request.category == Category.DATA && request.action == Action.LIST) {
                val pageSize = ProtoConverters.asIntOrNull(
                    request.payload["pageSize"] ?: PayloadValue.NULL
                ) ?: 50
                if (pageSize == 0) {
                    handleStreamDataList(request, payloadJson, outputChannel)
                    return
                }
            }

            // SQL EXECUTE 走流式路径（内部判断是否为 SELECT）
            if (request.category == Category.SQL && request.action == Action.EXECUTE) {
                handleStreamSqlExecute(request, payloadJson, outputChannel)
                return
            }

            // DATA GENERATE 走流式路径（造数进度回报）
            if (request.category == Category.DATA && request.action == Action.GENERATE) {
                handleStreamDataGenerate(request, payloadJson, outputChannel)
                return
            }

            val data = when (request.category) {
                Category.SCHEMA -> handleSchema(request, payloadJson)
                Category.TABLE -> handleTable(request, payloadJson)
                Category.DATA -> handleData(request, payloadJson)
                Category.USER -> handleUser(request, payloadJson)
                Category.FUNCTION -> handleFunction(request, payloadJson)
                Category.VIEW -> handleView(request, payloadJson)
                Category.INDEX -> handleIndex(request, payloadJson)
                Category.FOREIGN_KEY -> handleForeignKey(request, payloadJson)
                Category.TRIGGER -> handleTrigger(request, payloadJson)
                else -> throw UnsupportedOperationException("Unsupported request")
            }

            sendResponse(
                Response(
                    id = request.id,
                    success = true,
                    data = ProtoConverters.toPayloadValue(data)
                ),
                outputChannel
            )
        } catch (e: Exception) {
            logger.error("Error processing request", e)
            sendResponse(
                Response(
                    id = request.id,
                    success = false,
                    error = e.message ?: "Unknown error"
                ),
                outputChannel
            )
        }
    }

    /** 编码 Response 为 protobuf 字节并送入输出 Channel */
    private suspend fun sendResponse(response: Response, outputChannel: Channel<ByteArray>) {
        @OptIn(ExperimentalSerializationApi::class)
        val bytes = proto.encodeToByteArray(Response.serializer(), response)
        outputChannel.send(bytes)
    }

    // ============ 流式响应 ============

    private suspend fun handleStreamDataList(
        request: Request,
        payload: JsonObject,
        outputChannel: Channel<ByteArray>
    ) {
        val id = request.id
        val encode = { end: Boolean, data: JsonElement ->
            @OptIn(ExperimentalSerializationApi::class)
            proto.encodeToByteArray(
                Response.serializer(), Response(
                    id = id, success = true, stream = true, end = end,
                    data = ProtoConverters.toPayloadValue(data)
                )
            )
        }

        try {
            DataHandler.list(request.connection, payload) { row ->
                outputChannel.send(encode(false, row))
            }
            outputChannel.send(encode(true, JsonNull))
        } catch (e: Exception) {
            logger.error("Error in stream data list", e)
            @OptIn(ExperimentalSerializationApi::class)
            outputChannel.send(
                proto.encodeToByteArray(
                    Response.serializer(), Response(
                        id = id, success = false, error = e.message ?: "Unknown error"
                    )
                )
            )
        }
    }

    private suspend fun handleStreamSqlExecute(
        request: Request,
        payload: JsonObject,
        outputChannel: Channel<ByteArray>
    ) {
        val id = request.id
        val encode = { end: Boolean, data: JsonElement ->
            @OptIn(ExperimentalSerializationApi::class)
            proto.encodeToByteArray(
                Response.serializer(), Response(
                    id = id, success = true, stream = true, end = end,
                    data = ProtoConverters.toPayloadValue(data)
                )
            )
        }

        try {
            val result = SqlEngineHandler.execute(request.connection, payload) { row ->
                outputChannel.send(encode(false, row))
            }
            if (result is Boolean && result) {
                // SELECT 查询已流式发送，发送结束标记
                outputChannel.send(encode(true, JsonNull))
            } else {
                // 非 SELECT（INSERT/UPDATE/DELETE/DDL），单次响应
                @OptIn(ExperimentalSerializationApi::class)
                sendResponse(
                    Response(
                        id = id,
                        success = true,
                        data = ProtoConverters.toPayloadValue(result as JsonElement)
                    ),
                    outputChannel
                )
            }
        } catch (e: Exception) {
            logger.error("Error in SQL execute", e)
            @OptIn(ExperimentalSerializationApi::class)
            outputChannel.send(
                proto.encodeToByteArray(
                    Response.serializer(), Response(
                        id = id, success = false, error = e.message ?: "Unknown error"
                    )
                )
            )
        }
    }

    private suspend fun handleStreamDataGenerate(
        request: Request,
        payload: JsonObject,
        outputChannel: Channel<ByteArray>
    ) {
        val id = request.id
        val encode = { end: Boolean, data: JsonElement ->
            @OptIn(ExperimentalSerializationApi::class)
            proto.encodeToByteArray(
                Response.serializer(), Response(
                    id = id, success = true, stream = true, end = end,
                    data = ProtoConverters.toPayloadValue(data)
                )
            )
        }

        try {
            GenerateHandler.execute(request.connection, payload) { progress ->
                outputChannel.send(encode(false, progress))
            }
            outputChannel.send(encode(true, JsonNull))
        } catch (e: Exception) {
            logger.error("Error in data generate", e)
            @OptIn(ExperimentalSerializationApi::class)
            outputChannel.send(
                proto.encodeToByteArray(
                    Response.serializer(), Response(
                        id = id, success = false, error = e.message ?: "Unknown error"
                    )
                )
            )
        }
    }

    // ============ 非流式响应（按 Category 分发） ============

    private suspend fun handleSchema(request: Request, payload: JsonObject): JsonElement {
        return when (request.action) {
            Action.LIST -> SchemaHandler.list(request.connection, payload)
            Action.CREATE -> SchemaHandler.create(request.connection, payload)
            Action.DELETE -> SchemaHandler.delete(request.connection, payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for SCHEMA")
        }
    }

    private suspend fun handleTable(request: Request, payload: JsonObject): JsonElement {
        return when (request.action) {
            Action.LIST -> {
                if (payload.containsKey("tableName")) {
                    TableHandler.columnList(request.connection, payload)
                } else {
                    TableHandler.list(request.connection, payload)
                }
            }

            Action.CREATE -> TableHandler.create(request.connection, payload)
            Action.UPDATE -> TableHandler.update(request.connection, payload)
            Action.DELETE -> TableHandler.delete(request.connection, payload)
            Action.GET_DDL -> TableHandler.getDDL(request.connection, payload)
            Action.RENAME -> TableHandler.rename(request.connection, payload)
            Action.TRUNCATE -> TableHandler.truncate(request.connection, payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for TABLE")
        }
    }

    private suspend fun handleData(request: Request, payload: JsonObject): JsonElement {
        return when (request.action) {
            Action.LIST -> DataHandler.list(request.connection, payload) as JsonElement
            Action.CREATE -> DataHandler.create(request.connection, payload)
            Action.UPDATE -> DataHandler.update(request.connection, payload)
            Action.DELETE -> DataHandler.delete(request.connection, payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for DATA")
        }
    }

    private suspend fun handleUser(request: Request, payload: JsonObject): JsonElement {
        return when (request.action) {
            Action.LIST -> UserHandler.list(request.connection, payload)
            Action.CREATE -> UserHandler.create(request.connection, payload)
            Action.UPDATE -> UserHandler.updatePrivileges(request.connection, payload)
            Action.DELETE -> UserHandler.delete(request.connection, payload)
            Action.GRANTS -> UserHandler.listAllGrants(request.connection, payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for USER")
        }
    }

    private suspend fun handleFunction(request: Request, payload: JsonObject): JsonElement {
        return when (request.action) {
            Action.LIST -> FunctionHandler.list(request.connection, payload)
            Action.INFO -> FunctionHandler.info(request.connection, payload)
            Action.GET_DDL -> FunctionHandler.getDDL(request.connection, payload)
            Action.CREATE -> FunctionHandler.create(request.connection, payload)
            Action.DELETE -> FunctionHandler.delete(request.connection, payload)
            Action.CALL -> FunctionHandler.call(request.connection, payload)
            Action.DEBUG -> FunctionHandler.debug(request.connection, payload)
            Action.UPDATE -> FunctionHandler.validate(request.connection, payload) // UPDATE as validate
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for FUNCTION")
        }
    }

    private suspend fun handleView(request: Request, payload: JsonObject): JsonElement {
        return when (request.action) {
            Action.LIST -> ViewHandler.list(request.connection, payload)
            Action.CREATE -> ViewHandler.create(request.connection, payload)
            Action.DELETE -> ViewHandler.delete(request.connection, payload)
            Action.GET_DDL -> ViewHandler.getDDL(request.connection, payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for VIEW")
        }
    }

    private suspend fun handleIndex(request: Request, payload: JsonObject): JsonElement {
        return when (request.action) {
            Action.LIST -> IndexHandler.list(request.connection, payload)
            Action.CREATE -> IndexHandler.create(request.connection, payload)
            Action.DELETE -> IndexHandler.delete(request.connection, payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for INDEX")
        }
    }

    private suspend fun handleForeignKey(request: Request, payload: JsonObject): JsonElement {
        return when (request.action) {
            Action.LIST -> ForeignKeyHandler.list(request.connection, payload)
            Action.CREATE -> ForeignKeyHandler.create(request.connection, payload)
            Action.DELETE -> ForeignKeyHandler.delete(request.connection, payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for FOREIGN_KEY")
        }
    }

    private suspend fun handleTrigger(request: Request, payload: JsonObject): JsonElement {
        return when (request.action) {
            Action.LIST -> TriggerHandler.list(request.connection, payload)
            Action.GET_DDL -> TriggerHandler.getDDL(request.connection, payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for TRIGGER")
        }
    }

    private suspend fun handleSystem(
        request: Request,
        payload: JsonObject,
        outputChannel: Channel<ByteArray>
    ) {
        val data: JsonElement = when (request.action) {
            Action.INFO -> SystemHandler.info()
            Action.TEST_CONNECTION -> SystemHandler.testConnection(request.connection)
            Action.SERVER_INFO -> SystemHandler.serverInfo(request.connection)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for SYSTEM")
        }
        sendResponse(
            Response(id = request.id, success = true, data = ProtoConverters.toPayloadValue(data)),
            outputChannel
        )
    }
}