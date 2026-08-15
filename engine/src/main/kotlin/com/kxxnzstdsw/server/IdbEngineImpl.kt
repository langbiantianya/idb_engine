package com.kxxnzstdsw.server

import com.kxxnzstdsw.dispatcher.RequestDispatcher
import com.kxxnzstdsw.grpc.IdbEngineGrpc
import com.kxxnzstdsw.grpc.Request
import com.kxxnzstdsw.grpc.Response
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * IdbEngine gRPC 服务实现
 *
 * 把 IdbEngineGrpc.IdbEngineImplBase 适配到 RequestDispatcher（返回 Flow<Response>）：
 * - 每个 Handle 请求启动一个独立协程
 * - 协程消费 RequestDispatcher 返回的 Flow，逐帧推给 StreamObserver
 * - 异常时统一发送一条错误 Response 并完成流
 */
class IdbEngineImpl : IdbEngineGrpc.IdbEngineImplBase() {

    private val logger = LoggerFactory.getLogger(IdbEngineImpl::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun handle(request: Request, responseObserver: StreamObserver<Response>) {
        scope.launch {
            try {
                logger.info("Handle request: id=${request.id} ${request.category}/${request.action}")
                RequestDispatcher.dispatch(request).collect { response ->
                    responseObserver.onNext(response)
                }
                responseObserver.onCompleted()
            } catch (e: Exception) {
                logger.error("Error processing request ${request.id}", e)
                responseObserver.onNext(
                    Response.newBuilder()
                        .setId(request.id)
                        .setSuccess(false)
                        .setError(e.message ?: e.javaClass.simpleName)
                        .build()
                )
                responseObserver.onCompleted()
            }
        }
    }
}