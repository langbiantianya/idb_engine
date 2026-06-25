package com.kxxnzstdsw.export

import java.io.Closeable

/**
 * 导出写入器接口
 * 所有格式实现该接口，仅关注单行列数据的写入逻辑
 */
interface ExportWriter : Closeable {

    /**
     * 写入表头
     * @param columns 列名列表
     */
    fun writeHeader(columns: List<String>)

    /**
     * 写入一行数据
     * @param row 行数据，索引顺序与表头对应
     */
    fun writeRow(row: List<Any?>)

    /**
     * 刷新缓冲区（可选实现）
     */
    fun flush() {}

    /**
     * 获取当前已导出的行数
     * @return 已导出的行数
     */
    fun getExportedRows(): Long = 0
}
