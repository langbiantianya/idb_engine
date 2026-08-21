package com.kxxnzstdsw.server

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.IdbEngineGrpcKt
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import com.kxxnzstdsw.grpc.response
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * IdbEngine gRPC 服务实现（Kotlin 协程版）。
 *
 * 通过 [IdbEngineGrpcKt.IdbEngineCoroutineImplBase] 接入 gRPC-Kotlin stub 生成的服务基类，
 * `handle` 方法直接挂回 [RequestDispatcher.dispatch] 返回的 [Flow]；异常由 [catch] 收口为一条
 * error Response 后正常完成流。
 *
 * 相比 Java-style 实现（`IdbEngineGrpc.IdbEngineImplBase` + `StreamObserver`）省去了
 * 显式 `responseObserver.onNext/onCompleted` 样板；服务端 `addService` 仍调用 `.bindService()`。
 */
class IdbEngineImpl : IdbEngineGrpcKt.IdbEngineCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(IdbEngineImpl::class.java)

    override fun handle(request: Request): Flow<Response> = flow {
        logger.info("Handle request: id=${request.id} ${request.category}/${request.action}")
        // dispatcher 返回 Flow<Response>，直接桥接给 gRPC-Kotlin server-streaming
        RequestDispatcher.dispatch(request).collect { emit(it) }
    }.catch { e ->
        logger.error("Error processing request ${request.id}", e)
        emit(
            response {
                id = request.id
                success = false
                error = e.message ?: e.javaClass.simpleName
            }
        )
    }
}
