package com.kxxnzstdsw.dialect

/**
 * 方言能力标签 —— 用于告知前端该方言支持哪些数据库特性（按钮/菜单显隐）。
 *
 * 前端拿到 DialectInfo.capabilities 后，按集合决定 UI 显隐：
 * - 包含 [USERS] → 显示"用户管理"导航
 * - 不包含 [TRIGGERS] → 隐藏"触发器"标签页
 * - 等等。
 */
enum class DialectCapability {
    /** 用户管理（CREATE/DELETE/UPDATE PASSWORD/LIST） */
    USERS,

    /** 权限管理（GRANT/REVOKE） */
    PRIVILEGES,

    /** 函数/存储过程 */
    ROUTINES,

    /** 视图 */
    VIEWS,

    /** 索引 */
    INDEXES,

    /** 外键 */
    FOREIGN_KEYS,

    /** 触发器 */
    TRIGGERS,

    /** 多 database（跨库查询） */
    CROSS_DATABASE,

    /** 多 schema（同一连接内 schema 切换） */
    MULTI_SCHEMA,

    /** 流式数据导出（CSV / JSON / SQL INSERT / Excel / Parquet 5 种） */
    EXPORT,

    /** 支持 DDL 事务回滚（rollback DDL） */
    DDL_TRANSACTION,

    /** 嵌入式 / 本地运行（无远端服务端） */
    EMBEDDED_MODE,
}