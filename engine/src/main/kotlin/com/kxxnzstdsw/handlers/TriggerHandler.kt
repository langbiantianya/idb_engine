package com.kxxnzstdsw.handlers

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.grpc.TriggerGetDdlRequest
import com.kxxnzstdsw.grpc.TriggerGetDdlResponse
import com.kxxnzstdsw.grpc.TriggerListRequest
import com.kxxnzstdsw.grpc.TriggerListResponse
import com.kxxnzstdsw.grpc.triggerGetDdlResponse
import com.kxxnzstdsw.grpc.triggerListItem
import com.kxxnzstdsw.grpc.triggerListResponse
import com.kxxnzstdsw.loader.DialectLoader
import com.kxxnzstdsw.pool.PoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 触发器管理 Handler。
 * - LIST/GET_DDL 直接调用 dialect
 * - CREATE/DELETE 复用 FunctionHandler.createRoutine / dropRoutine（routineType="TRIGGER"）
 */
object TriggerHandler {

    /**
     * LIST — 列出 schema 下的触发器
     */
    suspend fun list(config: ConnectionConfig, req: TriggerListRequest): TriggerListResponse = withContext(Dispatchers.IO) {
        val schema = req.schema
        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            val items = dialect.listTriggers(conn, schema)
            triggerListResponse {
                items.forEach { row ->
                    this.items += triggerListItem {
                        name = row["name"] ?: ""
                        table = row["table"] ?: ""
                        event = row["event"] ?: ""
                        timing = row["timing"] ?: ""
                        statement = row["statement"] ?: ""
                    }
                }
            }
        }
    }

    /**
     * GET_DDL — 获取触发器 DDL
     */
    suspend fun getDDL(config: ConnectionConfig, req: TriggerGetDdlRequest): TriggerGetDdlResponse = withContext(Dispatchers.IO) {
        if (req.name.isBlank()) throw IllegalArgumentException("缺少参数 'name'")
        val schema = req.schema

        val connection = PoolManager.getConnection(config, schema)
        val dialect = DialectLoader.getDialect(config.driver)
        return@withContext connection.use { conn ->
            triggerGetDdlResponse { ddl = dialect.getTriggerDDL(conn, req.name, schema) }
        }
    }
}