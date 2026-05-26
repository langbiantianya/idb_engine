package com.kxxnzstdsw.dispatcher

import com.kxxnzstdsw.handlers.*
import com.kxxnzstdsw.models.*
import kotlinx.serialization.builtins.serializer

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object RequestDispatcher {
    private val logger = LoggerFactory.getLogger(RequestDispatcher::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun dispatch(requestJson: String): String {
        return try {
            val request = json.decodeFromString<Request>(requestJson)
            logger.info("Processing request: ${request.id} - ${request.category}/${request.action}")

            val data = when (request.category) {
                Category.SCHEMA -> handleSchema(request)
                Category.TABLE -> handleTable(request)
                Category.DATA -> handleData(request)
                Category.USER -> handleUser(request)
                Category.SQL -> handleSql(request)
            }

            val response = Response(
                id = request.id,
                success = true,
                data = data
            )
            json.encodeToString(Response.serializer(), response)
        } catch (e: Exception) {
            logger.error("Error processing request", e)
            val errorResponse = Response(
                id = extractIdFromJson(requestJson),
                success = false,
                error = e.message ?: "Unknown error"
            )
            json.encodeToString(Response.serializer(), errorResponse)
        }
    }

    private fun handleSchema(request: Request): JsonElement {
        return when (request.action) {
            Action.LIST -> SchemaHandler.list(request.connection)
            Action.CREATE -> SchemaHandler.create(request.connection, request.payload)
            Action.DELETE -> SchemaHandler.delete(request.connection, request.payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for SCHEMA")
        }
    }

    private fun handleTable(request: Request): JsonElement {
        return when (request.action) {
            Action.LIST -> {
                if (request.payload.containsKey("tableName")) {
                    TableHandler.columnList(request.connection, request.payload)
                } else {
                    TableHandler.list(request.connection)
                }
            }

            else -> throw UnsupportedOperationException("Action ${request.action} not supported for TABLE")
        }
    }

    private fun handleData(request: Request): JsonElement {
        return when (request.action) {
            Action.LIST -> DataHandler.list(request.connection, request.payload)
            Action.CREATE -> DataHandler.create(request.connection, request.payload)
            Action.UPDATE -> DataHandler.update(request.connection, request.payload)
            Action.DELETE -> DataHandler.delete(request.connection, request.payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for DATA")
        }
    }

    private fun handleUser(request: Request): JsonElement {
        return when (request.action) {
            Action.LIST -> UserHandler.list(request.connection)
            Action.UPDATE -> UserHandler.updatePrivileges(request.connection, request.payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for USER")
        }
    }

    private fun handleSql(request: Request): JsonElement {
        return when (request.action) {
            Action.EXECUTE -> SqlEngineHandler.execute(request.connection, request.payload)
            else -> throw UnsupportedOperationException("Action ${request.action} not supported for SQL")
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