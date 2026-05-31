package com.kxxnzstdsw.dispatcher

import com.kxxnzstdsw.handlers.*
import com.kxxnzstdsw.models.*
import kotlinx.serialization.builtins.serializer

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object RequestDispatcher {
    private val logger = LoggerFactory.getLogger(RequestDispatcher::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun dispatch(requestJson: String, outputChannel: kotlinx.coroutines.channels.Channel<String>) {
        try {
            val request = json.decodeFromString<Request>(requestJson)
            logger.info("Processing request: ${request.id} - ${request.category}/${request.action}")

            // 流式 DATA LIST（pageSize == 0）
            if (request.category == Category.DATA && request.action == Action.LIST) {
                val pageSize = request.payload["pageSize"]?.jsonPrimitive?.intOrNull ?: 50
                if (pageSize == 0) {
                    handleStreamDataList(request, outputChannel)
                    return
                }
            }

            // SQL EXECUTE 走流式路径（内部判断是否为 SELECT）
            if (request.category == Category.SQL && request.action == Action.EXECUTE) {
                handleStreamSqlExecute(request, outputChannel)
                return
            }

            val data = when (request.category) {
                Category.SCHEMA -> handleSchema(request)
                Category.TABLE -> handleTable(request)
                Category.DATA -> handleData(request)
                Category.USER -> handleUser(request)
                else -> throw UnsupportedOperationException("Unsupported request")
            }

            val response = Response(id = request.id, success = true, data = data)
            outputChannel.send(json.encodeToString(Response.serializer(), response))
        } catch (e: Exception) {
            logger.error("Error processing request", e)
            val errorResponse = Response(
                id = extractIdFromJson(requestJson),
                success = false,
                error = e.message ?: "Unknown error"
            )
            outputChannel.send(json.encodeToString(Response.serializer(), errorResponse))
        }
    }

    private suspend fun handleStreamDataList(request: Request, outputChannel: kotlinx.coroutines.channels.Channel<String>) {
        val id = request.id
        val encode = { end: Boolean, data: JsonElement ->
            json.encodeToString(Response.serializer(), Response(
                id = id, success = true, stream = true, end = end, data = data
            ))
        }

        try {
            DataHandler.list(request.connection, request.payload) { row ->
                outputChannel.send(encode(false, row))
            }
            outputChannel.send(encode(true, JsonNull))
        } catch (e: Exception) {
            logger.error("Error in stream data list", e)
            outputChannel.send(json.encodeToString(Response.serializer(), Response(
                id = id, success = false, error = e.message ?: "Unknown error"
            )))
        }
    }

    private suspend fun handleStreamSqlExecute(request: Request, outputChannel: kotlinx.coroutines.channels.Channel<String>) {
        val id = request.id
        val encode = { end: Boolean, data: JsonElement ->
            json.encodeToString(Response.serializer(), Response(
                id = id, success = true, stream = true, end = end, data = data
            ))
        }

        try {
            val result = SqlEngineHandler.execute(request.connection, request.payload) { row ->
                outputChannel.send(encode(false, row))
            }
            if (result is Boolean && result) {
                // SELECT 查询已流式发送，发送结束标记
                outputChannel.send(encode(true, JsonNull))
            } else {
                // 非 SELECT（INSERT/UPDATE/DELETE/DDL），单次响应
                val response = Response(id = id, success = true, data = result as JsonElement)
                outputChannel.send(json.encodeToString(Response.serializer(), response))
            }
        } catch (e: Exception) {
            logger.error("Error in SQL execute", e)
            outputChannel.send(json.encodeToString(Response.serializer(), Response(
                id = id, success = false, error = e.message ?: "Unknown error"
            )))
        }
    }

    private suspend fun handleSchema(request: Request): JsonElement {
        return when (request.action) {
            Action.LIST -> SchemaHandler.list(request.connection)
            Action.CREATE -> SchemaHandler.create(request.connection, request.payload)
            Action.DELETE -> SchemaHandler.delete(request.connection, request.payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for SCHEMA")
        }
    }

    private suspend fun handleTable(request: Request): JsonElement {
        return when (request.action) {
            Action.LIST -> {
                if (request.payload.containsKey("tableName")) {
                    TableHandler.columnList(request.connection, request.payload)
                } else {
                    TableHandler.list(request.connection)
                }
            }
            Action.CREATE -> TableHandler.create(request.connection, request.payload)
            Action.UPDATE -> TableHandler.update(request.connection, request.payload)
            Action.DELETE -> TableHandler.delete(request.connection, request.payload)
            Action.GET_DDL -> TableHandler.getDDL(request.connection, request.payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for TABLE")
        }
    }

    private suspend fun handleData(request: Request): JsonElement {
        return when (request.action) {
            Action.LIST -> DataHandler.list(request.connection, request.payload) as JsonElement
            Action.CREATE -> DataHandler.create(request.connection, request.payload)
            Action.UPDATE -> DataHandler.update(request.connection, request.payload)
            Action.DELETE -> DataHandler.delete(request.connection, request.payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for DATA")
        }
    }

    private suspend fun handleUser(request: Request): JsonElement {
        return when (request.action) {
            Action.LIST -> UserHandler.list(request.connection)
            Action.UPDATE -> UserHandler.updatePrivileges(request.connection, request.payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for USER")
        }
    }

    private fun extractIdFromJson(jsonString: String): String {
        return try {
            val jsonObject = json.parseToJsonElement(jsonString).jsonObject
            jsonObject["id"]?.jsonPrimitive?.content ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}