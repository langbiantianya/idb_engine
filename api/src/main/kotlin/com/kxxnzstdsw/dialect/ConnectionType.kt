package com.kxxnzstdsw.dialect

/**
 * 方言连接模式 —— 前端用它决定连接表单的字段显隐与默认布局。
 */
enum class ConnectionType {
    /** 客户端/服务端模式（MySQL / PostgreSQL） —— 需要 host + port + user + password */
    CLIENT_SERVER,

    /** 嵌入式数据库（SQLite / DuckDB） —— host/port 忽略，database 承载路径或特殊值 */
    EMBEDDED,

    /** 文件型数据库（SQLite） —— database 即本地文件路径 */
    FILE_BASED,

    /** 纯内存模式（H2 / DuckDB `:memory:`） —— database 可为空 */
    IN_MEMORY,
}