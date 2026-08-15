package com.kxxnzstdsw.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 长度前缀帧协议（Length-Prefixed Frame Protocol）
 *
 * 格式：[4 字节 BE uint32 长度][N 字节 protobuf 编码数据]
 *
 * 使用场景：
 * - Main.kt 用 readFrame(System.in) / writeFrame(System.out, ...) 替换原来的 JSON 行协议
 * - ExportSubProcess 与 ExportProcessManager 之间的 stdin/stdout 通信
 *
 * 设计要点：
 * - 单帧最大 256 MiB（足够承载任何单条 Request/Response protobuf 消息）
 * - 长度字段固定 4 字节大端，无魔数、无版本号（保持协议极简）
 * - readFrame 阻塞式读取一帧；返回 null 表示对端关闭（EOF）
 */
object Framing {
    /** 单帧最大字节数（防止对端声明超大长度耗尽内存） */
    const val MAX_FRAME_SIZE = 256 * 1024 * 1024

    /**
     * 从输入流读取一帧，返回 null 表示 EOF（对端关闭）。
     *
     * 注意：本方法一次性读取完整消息体（适用于 protobuf 这种小到中等消息）。
     * 若未来需要流式超大帧，可改为读 header → 循环 fill payload。
     */
    fun readFrame(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = input.read(header, read, 4 - read)
            if (n < 0) {
                return if (read == 0) null else error("Unexpected EOF while reading frame header (got $read bytes)")
            }
            read += n
        }
        val length = ((header[0].toInt() and 0xFF) shl 24) or
                     ((header[1].toInt() and 0xFF) shl 16) or
                     ((header[2].toInt() and 0xFF) shl 8) or
                     (header[3].toInt() and 0xFF)
        if (length < 0) {
            error("Negative frame length: $length")
        }
        if (length > MAX_FRAME_SIZE) {
            error("Frame too large: $length bytes (max $MAX_FRAME_SIZE)")
        }
        val payload = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(payload, got, length - got)
            if (n < 0) {
                error("Unexpected EOF while reading frame payload (got $got of $length bytes)")
            }
            got += n
        }
        return payload
    }

    /**
     * 向输出流写入一帧（4 字节 BE 长度 + payload）。
     *
     * 不负责 flush — 调用方控制 flush 策略（Main.kt 每帧单独 flush）。
     */
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        val length = payload.size
        require(length >= 0) { "Negative payload length: $length" }
        require(length <= MAX_FRAME_SIZE) { "Payload too large: $length bytes (max $MAX_FRAME_SIZE)" }
        val header = byteArrayOf(
            ((length shr 24) and 0xFF).toByte(),
            ((length shr 16) and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte()
        )
        output.write(header)
        output.write(payload)
    }

    /** DataInputStream 便捷封装（不缓存整帧 header 之外的额外字节） */
    fun readFrame(input: DataInputStream): ByteArray? = readFrame(input as InputStream)

    /** DataOutputStream 便捷封装 */
    fun writeFrame(output: DataOutputStream, payload: ByteArray) = writeFrame(output as OutputStream, payload)
}