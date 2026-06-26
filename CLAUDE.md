# 📖 Wails-Kotlin 数据库管理端后端架构设计文档 (V1.0 完整版)

## 1. 架构总览 (Architecture Overview)

本项目采用 **Wails (Go) + Kotlin (JVM)** 混合架构，旨在开发一款高安全性、免安装、跨平台的桌面端数据库管理工具。
Kotlin 后端被设计为一个**无头 (Headless)**、**无状态 (Stateless)** 的底层"数据库算力引擎"。它不暴露任何网络端口（如 HTTP/WebSocket），完全依附于 Wails 主进程的生命周期，通过标准输入输出流（StdIn/StdOut）接收指令并返回结果。

**系统拓扑流：**
`[前端 Webview]` ↔ `[Wails Go 主进程]` ↔ `(StdIn/StdOut JSON 管道)` ↔ `[Kotlin 独立子进程]` ↔ `[MySQL / PostgreSQL]`

> **性能隔离**：数据导出模块运行在独立的子进程中，通过 `ExportProcessManager` 管理，防止大数据量导出时 OOM 影响主进程稳定性。

## 2. 技术栈选型 (Technology Stack)

- **核心语言**：Kotlin 2.4.0 / JDK 25
- **异步框架**：kotlinx-coroutines 1.11.0 (协程实现非阻塞并发)
- **数据库驱动**：原生 JDBC (MySQL Connector/J 9.7.0, PostgreSQL JDBC Driver 42.7.11)
- **连接池管理**：HikariCP 7.0.2 (业界最高性能、资源占用低的连接池)
- **数据序列化**：`kotlinx.serialization` 1.11.0 (无反射、轻量化、原生支持 Kotlin 协程与数据类)
- **日志框架**：SLF4J 2.0.18 + Logback 1.5.13 (日志输出到本地滚动文件，不污染 stdout)
- **构建与分发**：Gradle + ShadowJar 9.3.0+ (构建为瘦包 + 外部依赖，后续可通过 GraalVM Native Image 编译为无 JRE 依赖的二进制文件)
- **脚本引擎**：LuaJIT 4.1.0 + Lua 5.1~5.5 via luajava (嵌入式 Lua 脚本引擎，用于造数功能中的数据生成规则定义，支持多版本切换)
- **Excel 导出**：Apache POI 5.5.1 (poi-ooxml SXSSF 流式 API)
- **Parquet 导出**：Apache Parquet 1.17.1 + Hadoop 3.5.0 (列式存储，文件系统抽象)

## 3. 核心机制设计 (Core Mechanisms)

### 3.1 管道通信协议 (Pipeline I/O Protocol)

- **交互介质**：标准输入 (`System.in`) 与 标准输出 (`System.out`)。
- **数据格式**：单行压缩 JSON 字符串（Minified JSON）。
- **边界标识**：使用换行符 `\n` 作为单次请求和响应的结束符。
- **日志隔离**：Kotlin 内部的任何常规日志（如 `logger.info` 或异常堆栈）通过 Logback 写入本地滚动文件 (`~/.config/idb/logs/idb-engine.log`)，不输出到 stdout 或 stderr，绝对避免污染返回给 Go 进程的 JSON 结构。
- **异步处理**：使用 Kotlin 协程 (`kotlinx-coroutines`) 实现非阻塞并发处理，多个请求可同时执行互不阻塞。
- **输出串行化**：通过 `Channel<String>` 确保所有响应按顺序输出到 stdout，一次只有一个输出，避免交错混乱。
- **长驻运行**：主循环在 `runBlocking` 协程作用域中运行，使用 `BufferedReader.readLine()` 阻塞式读取输入，支持长期驻留运行，直到收到 `CMD_EXIT` 或 stdin 关闭（EOF）。

### 3.2 绝对无状态设计 (Stateless Design)

Kotlin 进程不维护"当前选中的数据库"等业务状态。**每一次**请求都必须在其 JSON 载荷中携带完整的数据库连接凭证（IP、端口、账号、密码、库名）。

### 3.3 动态连接池管理器 (Dynamic Pool Manager)

为了解决无状态带来的频繁 TCP 握手开销，Kotlin 内部实现基于 Hash Key 的智能缓存连接池。

1. **连接复用**：根据传入的凭证（driver + host + port + user + password + database）生成 SHA-256 Hash，若缓存中已有对应的 HikariCP 实例且活跃，则直接复用。
2. **资源自动回收**：针对桌面端场景极致调优，`idleTimeout` 设为 10 分钟，`minimumIdle` 为 0。若某个库 10 分钟无操作，该连接池将自动缩容直至完全销毁，释放本地内存资源。
3. **极限并发**：最大连接数 (`maximumPoolSize`) 限制为 5，足以应对单机用户的并发查询。
4. **连接超时**：`connectionTimeout` 设为 5 秒，快速失败避免界面卡死。
5. **最大生命周期**：`maxLifetime` 为 30 分钟，防止数据库服务端主动断开连接导致的失效连接复用。

### 3.4 导出子进程隔离机制 (Export Subprocess Isolation)

数据导出模块独立运行在子进程中，通过 `ExportProcessManager` 管理，实现与主进程的**进程级隔离**。

**设计目标**：
- 防止大数据量导出时 OOM 影响主进程稳定性
- 支持导出任务的手动停止（取消）
- 主进程关闭时自动停止导出子进程
- 子进程响应走主进程统一串行化管线，保证输出不丢失、不交错

**架构组件**：

| 组件 | 文件 | 职责 |
|---|---|---|
| `ExportProcessManager` | `engine/.../export/ExportProcessManager.kt` | 主进程管理器：启动/停止子进程、内部缓冲 Channel 转发响应 |
| `ExportSubProcess` | `engine/.../export/ExportSubProcess.kt` | 子进程入口：独立 JVM 运行，监听 stdin 执行导出任务 |
| `ExportEngine` | `engine/.../export/ExportEngine.kt` | 导出引擎：实际的数据导出逻辑（复用原有实现） |
| `GlobalOutputChannel` | `engine/.../export/GlobalOutputChannel.kt` | 全局输出 Channel：桥接子进程响应到主进程统一 stdout 管线 |

**响应数据流管线**：

```
[导出子进程 stdout]
    → readOutputLoop()
    → responseBuffer Channel (UNLIMITED)
    → forwardResponses()
    → GlobalOutputChannel.channel
    → Main.kt outputJob (for (response in outputChannel))
    → println(response)
    → [Go 进程 stdin]
```

- `readOutputLoop`：运行在 `Dispatchers.IO` 协程，读取子进程 stdout 每行写入 `responseBuffer`
- `forwardResponses`：运行在独立协程，从 `responseBuffer` 读取后发送到 `GlobalOutputChannel`
- `GlobalOutputChannel`：主进程注入的共享 Channel，确保子进程响应与主请求响应共享同一个串行化管线
- `Main.kt outputJob`：统一协程，`for (response in outputChannel)` 逐个 `println`，保证**所有 stdout 写入严格串行化**，不会有任何交错

**通信协议**：

```
[主引擎] --stdin/stdout--> [导出子进程]
```

子进程接收的命令格式：
```json
{"CMD": "START_EXPORT", "id": "req-xxx", "connection": {...}, "payload": {...}}
{"CMD": "STOP_EXPORT", "exportId": "req-xxx"}
{"CMD": "CMD_EXIT"}
```

子进程发送的响应格式（与主协议一致）：
```json
{"id": "req-xxx", "success": true, "stream": true, "end": false, "data": {...}}
```

**生命周期管理**：
1. 首次收到 EXPORT 请求时，`ExportProcessManager` 启动子进程
2. 子进程启动后加载 drivers/ 和 dialects/ 目录
3. 子进程内存限制 `-Xmx512m`，防止导出任务耗尽系统内存
4. 主进程关闭时，`ExportProcessManager.stop()` 发送 `CMD_EXIT`，子进程优雅退出
5. 支持单独停止某个导出任务（`STOP_EXPORT` 命令）

**停止导出**：
- 主进程收到新 EXPORT 请求且 `payload.stopExportId` 不为空时，向子进程发送 `STOP_EXPORT` 命令
- 子进程设置 `ExportEngine.isCancelled = true`，导出协程在下一次循环检查时抛出 `ExportCancelledException`
- 被取消的导出任务返回 `{"success": false, "error": "Export cancelled by user"}`

## 4. 数据交互契约 (JSON Protocol Spec)

### 4.1 统一请求体 (Request Envelope)

```json
{
  "id": "req-uuid-1234",
  "category": "SCHEMA | USER | TABLE | DATA | SQL | SYSTEM | FUNCTION | EXPORT",
  "action": "LIST | CREATE | UPDATE | DELETE | EXECUTE | GET_DDL | INFO | GRANTS | GENERATE | CALL | DEBUG | EXPORT",
  "connection": {
    "driver": "mysql | postgresql",
    "host": "127.0.0.1",
    "port": 3306,
    "user": "root",
    "password": "secret_password",
    "database": "target_db"
  },
  "payload": {
    // 具体的业务参数，详见功能模块
  }
}
```

### 4.2 统一响应体 (Response Envelope)

```json
{
  "id": "req-uuid-1234",
  "success": true,
  "error": null,
  "stream": false,
  "end": false,
  "data": {
    // 根据 action 返回对应的结果 (如 List<Map> 或受影响行数)
  }
}
```

错误时 `success` 为 `false`，`error` 为异常信息字符串，`data` 为 null。

**流式响应字段说明**：
- `stream: true` — 表示当前响应属于流式序列（一条请求产生多行响应）
- `end: true` — 流式序列的最后一行，`data` 为 null，Go 端收到后停止读取
- 普通（非流式）响应中 `stream` 和 `end` 均为 `false`（默认值），Go 端无需特殊处理

## 5. 功能模块详细设计 (Feature Modules)

为兼容 MySQL 与 PostgreSQL，采用**方言抽象层 (Dialect Abstraction Layer)** + **SPI 插件动态加载**设计模式：

- **DatabaseDialect SPI 接口**（`api` 模块）：定义所有数据库特定操作的抽象方法，通过 `driverName` 属性声明所处理的驱动
- **MySQLDialect / PostgreSQLDialect**（独立插件模块）：分别实现 MySQL 和 PostgreSQL 的具体方言，打包为独立 JAR
- **DialectLoader**（`engine` 模块）：启动时扫描 `dialects/` 目录，通过 `ServiceLoader<DatabaseDialect>` 自动发现并注册所有方言插件
- **Handler 层**：通过 `DialectLoader.getDialect(driverName)` 获取方言实例，调用统一接口

这种设计使得新增数据库支持（如 Oracle、SQL Server）只需：
1. 实现 `DatabaseDialect` 接口
2. 在 `META-INF/services/` 中声明实现类
3. 将 JAR 放入 `dialects/` 目录

无需修改或重新编译主引擎代码。

### 5.1 架构管理 (Schema Management) — `category: "SCHEMA"`

处理不同库的物理层级差异。MySQL 将其视为 `Database`，PG 将其视为 `Database -> Schema`。

**LIST** — 获取可用架构列表（支持两级查询）

- **MySQL**：`SHOW DATABASES`，忽略 `database` 参数
- **PostgreSQL 两级模式**：
  - payload 不含 `database`（或为空）→ 查 `pg_database`，返回**数据库列表**
  - payload 含 `database`（如 `"myapp_db"`）→ 查 `pg_namespace`，返回该数据库下的 **schema 列表**（过滤 `pg_%` 和 `information_schema`）

```json
// MySQL 请求（与之前一致，payload 可为空）
{"id":"req-001","category":"SCHEMA","action":"LIST","connection":{"driver":"Mysql","host":"127.0.0.1","port":3306,"user":"root","password":"secret","database":"mysql"},"payload":{}}

// MySQL 响应 data
["information_schema", "mysql", "my_app_db"]

// PostgreSQL 请求 — 获取数据库列表（payload 不含 database）
{"id":"req-001","category":"SCHEMA","action":"LIST","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"postgres"},"payload":{}}

// PostgreSQL 响应 data（数据库列表）
["postgres", "my_app_db"]

// PostgreSQL 请求 — 获取指定数据库下的 schema 列表（payload 含 database）
{"id":"req-002","category":"SCHEMA","action":"LIST","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"postgres"},"payload":{"database":"my_app_db"}}

// PostgreSQL 响应 data（schema 列表）
["public", "myschema"]
```

**CREATE** — 创建 Database / Schema

可选 `options` 对象（MySQL 支持 `charset`、`collate`，PostgreSQL 忽略）：

```json
// 请求 payload（基础）
{"name": "new_db"}

// 请求 payload（带字符集选项，仅 MySQL 有效）
{"name": "new_db", "options": {"charset": "utf8mb4", "collate": "utf8mb4_unicode_ci"}}

// 响应 data
{"created": "new_db"}
```

**DELETE** — 删除 Database / Schema（MySQL: `DROP DATABASE`，PG: `DROP SCHEMA ... CASCADE`）

```json
// 请求 payload
{"name": "old_db"}

// 响应 data
{"deleted": "old_db"}
```

### 5.2 用户权限 (User & Privilege) — `category: "USER"`

**LIST** — 查询数据库用户列表

- MySQL 查 `mysql.user`，返回 `user` + `host`
- PG 查 `pg_roles`（`rolcanlogin = true`），返回 `user`
- payload 为空对象

```json
// 请求
{"id":"req-002","category":"USER","action":"LIST","connection":{"driver":"mysql","host":"127.0.0.1","port":3306,"user":"root","password":"secret","database":"mysql"},"payload":{}}

// MySQL 响应 data
[{"user": "root", "host": "localhost"}, {"user": "app_user", "host": "%"}]

// PostgreSQL 响应 data
[{"user": "postgres"}, {"user": "app_user"}]
```

**LIST（查询指定用户权限）** — payload 含 `user` 字段时，返回该用户的权限列表

```json
// 请求 payload
{"user": "dev", "host": "%"}
```

- MySQL 走 `SHOW GRANTS FOR 'user'@'host'`，返回授权语句列表
- PostgreSQL 走 `information_schema.table_privileges`，返回 `schema` + `table` + `privilege`

```json
// MySQL 响应 data
[{"grant": "GRANT SELECT ON `test_db`.* TO 'dev'@'%'"}, {"grant": "GRANT INSERT ON `test_db`.* TO 'dev'@'%'"}]

// PostgreSQL 响应 data
[{"schema": "public", "table": "users", "privilege": "SELECT"}, {"schema": "public", "table": "users", "privilege": "INSERT"}]
```

**GRANTS** — 查询指定用户被授权的所有表及权限（按 schema + table 聚合）

```json
// 请求 payload（host 仅 MySQL 使用，PostgreSQL 忽略，默认 "%"）
{"user": "dev", "host": "%"}
```

- MySQL 解析 `SHOW GRANTS` 输出，过滤 `*.*` 全局授权，提取表级权限
- PostgreSQL 聚合 `information_schema.table_privileges`

```json
// MySQL 响应 data
[{"schema": "test_db", "table": "users", "privileges": "SELECT, INSERT"}, {"schema": "test_db", "table": "orders", "privileges": "SELECT"}]

// PostgreSQL 响应 data
[{"schema": "public", "table": "users", "privileges": "INSERT, SELECT"}, {"schema": "public", "table": "orders", "privileges": "SELECT"}]
```

**CREATE** — 创建数据库用户

```json
// 请求 payload（host 仅 MySQL 使用，PostgreSQL 忽略，默认 "%"）
{"user": "new_user", "password": "secret123", "host": "%"}

// 响应 data
{"created": "new_user"}
```

**DELETE** — 删除数据库用户

```json
// 请求 payload（host 仅 MySQL 使用，PostgreSQL 忽略，默认 "%"）
{"user": "old_user", "host": "%"}

// 响应 data
{"deleted": "old_user"}
```

**UPDATE** — 授予或回收权限（`GRANT ... ON schema.* TO user` / `REVOKE ...`）

```json
// 请求 payload（授权）
{"user": "dev", "schema": "my_app_db", "privileges": ["SELECT", "INSERT", "UPDATE"], "isGrant": true}

// 请求 payload（回收）
{"user": "dev", "schema": "my_app_db", "privileges": ["DELETE"], "isGrant": false}

// 响应 data（授权）
{"user": "dev", "action": "granted"}

// 响应 data（回收）
{"user": "dev", "action": "revoked"}
```

**UPDATE（修改密码）** — payload 含 `password` 且无 `privileges` 时走密码修改路径

- MySQL 走 `ALTER USER ... IDENTIFIED BY`
- PostgreSQL 走 `ALTER USER ... PASSWORD`

```json
// 请求 payload
{"user": "dev", "password": "new_secret", "host": "%"}

// 响应 data
{"user": "dev", "action": "password_changed"}
```

### 5.3 表结构元数据 (Table Metadata) — `category: "TABLE"`

**LIST（表列表）** — 通过 `connection.metaData.getTables` 获取，payload 不含 `tableName`

所有 TABLE / DATA / SQL 操作均支持可选 `schema` 参数（PostgreSQL 有效，MySQL 忽略）：
- 传入 `schema` 时，引擎自动 `SET search_path TO <schema>`，确保 SQL 在该 schema 上下文中执行
- 不传时使用 PostgreSQL 默认 search_path（通常含 `public`）

```json
// 请求 payload（基础，使用默认 schema）
{}

// 请求 payload（PostgreSQL 指定 schema）
{"schema": "public"}

// 响应 data
[{"name": "users", "type": "TABLE"}, {"name": "orders", "type": "TABLE"}]
```

**LIST（列与主键）** — payload 含 `tableName` 时自动路由到 `columnList`，使用 `metaData.getColumns` 和 `metaData.getPrimaryKeys`

```json
// 请求 payload
{"tableName": "users"}

// 响应 data
[
  {"name": "id",    "type": "INT",     "size": 10,  "nullable": false, "isPrimaryKey": true,  "defaultValue": null},
  {"name": "name",  "type": "VARCHAR", "size": 255, "nullable": true,  "isPrimaryKey": false, "defaultValue": null},
  {"name": "email", "type": "VARCHAR", "size": 255, "nullable": true,  "isPrimaryKey": false, "defaultValue": null}
]
```

**CREATE** — 创建表，支持主键定义和表级选项

可选 `options` 对象：
- MySQL 支持：`engine`、`charset`、`collate`、`comment`
- PostgreSQL 支持：`comment`（通过 `COMMENT ON TABLE` 实现，其余忽略）

列定义支持 `autoIncrement: true`（仅对主键列有效）：
- MySQL：生成 `INT AUTO_INCREMENT`
- PostgreSQL：将 `INT` 替换为 `SERIAL`（`BIGINT` → `BIGSERIAL`）

```json
// 请求 payload（基础）
{
  "tableName": "products",
  "columns": [
    {"name": "id", "type": "INT", "nullable": false, "isPrimaryKey": true},
    {"name": "name", "type": "VARCHAR", "size": 255, "nullable": false},
    {"name": "price", "type": "DECIMAL", "nullable": true, "defaultValue": "0.00"},
    {"name": "created_at", "type": "TIMESTAMP", "nullable": true, "defaultValue": "CURRENT_TIMESTAMP"}
  ]
}

// 请求 payload（带自增主键）
{
  "tableName": "products",
  "columns": [
    {"name": "id", "type": "INT", "nullable": false, "isPrimaryKey": true, "autoIncrement": true},
    {"name": "name", "type": "VARCHAR", "size": 255, "nullable": false}
  ]
}

// 请求 payload（带表级选项）
{
  "tableName": "products",
  "columns": [...],
  "options": {
    "engine": "InnoDB",
    "charset": "utf8mb4",
    "collate": "utf8mb4_unicode_ci",
    "comment": "商品表"
  }
}

// 响应 data
{"created": "products"}
```

**UPDATE** — 修改表结构（ADD_COLUMN / DROP_COLUMN / MODIFY_COLUMN）

添加列：
```json
// 请求 payload
{
  "tableName": "products",
  "operation": "ADD_COLUMN",
  "column": {"name": "description", "type": "TEXT", "nullable": true}
}

// 响应 data
{"tableName": "products", "operation": "ADD_COLUMN"}
```

删除列：
```json
// 请求 payload
{
  "tableName": "products",
  "operation": "DROP_COLUMN",
  "columnName": "description"
}

// 响应 data
{"tableName": "products", "operation": "DROP_COLUMN"}
```

修改列（MySQL 使用 `CHANGE COLUMN`，PostgreSQL 使用 `ALTER COLUMN ... TYPE / SET NOT NULL / SET DEFAULT` 多子命令），可选 `newName` 同时重命名：
```json
// 请求 payload（仅修改类型）
{
  "tableName": "products",
  "operation": "MODIFY_COLUMN",
  "column": {"name": "price", "type": "DECIMAL", "size": 10, "nullable": false}
}

// 请求 payload（修改类型 + 重命名，newName 放在 column 内）
{
  "tableName": "products",
  "operation": "MODIFY_COLUMN",
  "column": {"name": "price", "type": "DECIMAL", "size": 10, "nullable": false, "newName": "unit_price"}
}

// 响应 data
{"tableName": "products", "operation": "MODIFY_COLUMN"}
```

**GET_DDL** — 返回建表语句（CREATE TABLE DDL）

- MySQL 使用 `SHOW CREATE TABLE`；PostgreSQL 从 `information_schema` + `pg_catalog` 重建（含主键、UNIQUE、CHECK 约束及索引）

```json
// 请求 payload
{"tableName": "users"}

// 响应 data（字符串，即完整 DDL）
"CREATE TABLE `users` (\n  `id` INT NOT NULL,\n  `name` VARCHAR(255),\n  PRIMARY KEY (`id`)\n)"
```

**DELETE** — 删除表（`DROP TABLE`）

```json
// 请求 payload
{"tableName": "old_table"}

// 响应 data
{"deleted": "old_table"}
```

### 5.4 表数据运维 (Table Data CRUD) — `category: "DATA"`

为防止大数据量拖垮本地内存，强制实施分页和参数化查询（`PreparedStatement`）。

**LIST** — 分页查询，强制 `LIMIT ? OFFSET ?`；`BLOB/LONGTEXT/BYTEA/TEXT` 类型返回 `[LOB Data]` 占位符

可选参数：
- `where` — 原始 WHERE 条件字符串（不含 `WHERE` 关键字），含值需用单引号包裹
- `orderBy` — 原始 ORDER BY 排序字符串（不含 `ORDER BY` 关键字）

安全校验由方言层实现（`DatabaseDialect.validateSqlFragment` / `validateOrderBy`），按数据库类型执行不同规则：
- **通用**：去除单引号内容后禁止分号 `;`、注释 `--` `/*`，禁止引号外出现 `INSERT/UPDATE/DELETE/DROP/UNION/EXEC/CREATE/ALTER/GRANT/REVOKE/TRUNCATE`
- **MySQL 额外**：ORDER BY 标识符允许反引号 `` `col` ``
- **PostgreSQL 额外**：ORDER BY 标识符允许双引号 `"col"`；额外禁止 `COPY`、`DO` 关键词

```json
// 请求 payload（基础分页）
{"tableName": "users", "page": 1, "pageSize": 50}

// 请求 payload（带过滤与排序）
{
  "tableName": "users",
  "page": 1,
  "pageSize": 20,
  "where": "age > 18 AND name LIKE '%Alice%'",
  "orderBy": "created_at DESC"
}

// 响应 data（page/pageSize 默认值：1/50）
{
  "total": 120,
  "page": 1,
  "pageSize": 50,
  "rows": [
    {"id": "1", "name": "Alice", "avatar": "[LOB Data]"},
    {"id": "2", "name": "Bob",   "avatar": "[LOB Data]"}
  ]
}
```

**LIST（流式全量）** — `pageSize: 0` 触发流式模式，通过 JDBC 游标（`TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY` + `fetchSize=100`）逐行读取，防止 OOM。PostgreSQL 端需临时关闭 `autoCommit` 以启用服务端游标，读取完毕后恢复。一条请求产生多行响应：

```json
// 请求 payload
{"tableName": "users", "pageSize": 0}

// 响应序列（每行一条 JSON，以 \n 分隔，data 结构与分页一致）
{"id":"x","success":true,"stream":true,"end":false,"data":{"total":1000,"page":0,"pageSize":1,"rows":[{"id":"1","name":"Alice","avatar":"[LOB Data]"}]}}
{"id":"x","success":true,"stream":true,"end":false,"data":{"total":1000,"page":0,"pageSize":1,"rows":[{"id":"2","name":"Bob","avatar":"[LOB Data]"}]}}
...
{"id":"x","success":true,"stream":true,"end":true,"data":null}
```

Go 端读取逻辑：持续读取 stdout 行，检查 `stream` 和 `end` 字段；收到 `end: true` 后停止，表示本次请求数据全部传输完毕。

**CREATE** — 插入一行，`values` 中所有值均以字符串传递并通过 `stmt.setString` 绑定

```json
// 请求 payload
{"tableName": "users", "values": {"name": "Charlie", "email": "charlie@example.com"}}

// 响应 data
{"affectedRows": 1}
```

**UPDATE** — 按 `where` 条件更新 `changes` 字段

```json
// 请求 payload
{"tableName": "users", "changes": {"name": "Alex", "email": "alex@example.com"}, "where": {"id": "1"}}

// 响应 data
{"affectedRows": 1}
```

**DELETE** — 按 `where` 条件删除行

```json
// 请求 payload
{"tableName": "users", "where": {"id": "1"}}

// 响应 data
{"affectedRows": 1}
```

### 5.5 原生 SQL 引擎 (Arbitrary SQL Engine) — `category: "SQL"`

**EXECUTE** — 接收任意 SQL 字符串，通过 `statement.execute()` 执行

- 若返回结果集（SELECT）：走流式输出，data 结构与 DATA LIST 一致（`total: -1` 表示无法预知总行数），通过 JDBC 游标（`TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY` + `fetchSize=100`）防止 OOM，PostgreSQL 端临时关闭 `autoCommit` 启用服务端游标
- 若为更新操作（INSERT/UPDATE/DELETE/DDL）：返回单次响应 `{ "affectedRows": N }`
- **PostgreSQL schema 上下文**：payload 可选 `schema` 字段，引擎在执行 SQL 前自动 `SET search_path TO <schema>`，确保无前缀表名（如 `SELECT * FROM users`）能正确解析到目标 schema

```json
// 请求 payload（查询，PostgreSQL 带 schema 上下文）
{"sql": "SELECT id, name FROM users WHERE id > 10 LIMIT 5", "schema": "public"}

// 请求 payload（查询，MySQL 或不需要指定 schema 时）
{"sql": "SELECT id, name FROM users WHERE id > 10 LIMIT 5"}

// 响应（流式，每行一条 JSON）
{"id":"x","success":true,"stream":true,"end":false,"data":{"total":-1,"page":0,"pageSize":1,"rows":[{"id": "11", "name": "Dave"}]}}
{"id":"x","success":true,"stream":true,"end":false,"data":{"total":-1,"page":0,"pageSize":1,"rows":[{"id": "12", "name": "Eve"}]}}
...
{"id":"x","success":true,"stream":true,"end":true,"data":null}

// 请求 payload（更新）
{"sql": "UPDATE users SET name = 'Frank' WHERE id = 3"}

// 响应 data（更新，非流式）
{"affectedRows": 1}
```

### 5.6 系统信息 (System Info) — `category: "SYSTEM"`

**INFO** — 返回当前 JVM 运行时信息，无需数据库连接（`connection` 字段仍需传递，但会被忽略）

- 通过 `Runtime`、`OperatingSystemMXBean`、`RuntimeMXBean` 采集
- `memory` 中各字段单位为字节（Bytes）

```json
// 请求
{"id":"req-010","category":"SYSTEM","action":"INFO","connection":{"driver":"mysql","host":"127.0.0.1","port":3306,"user":"root","password":"secret","database":"mysql"},"payload":{}}

// 响应 data
{
  "jvmVersion": "21.0.2",
  "jvmVendor": "Oracle Corporation",
  "jvmName": "OpenJDK 64-Bit Server VM",
  "osName": "Windows 11",
  "osArch": "amd64",
  "osVersion": "10.0",
  "availableProcessors": 16,
  "memory": {
    "max": 4294967296,
    "total": 268435456,
    "used": 134217728,
    "free": 134217728
  },
  "uptime": 120000,
  "pid": 12345
}
```

### 5.7 造数引擎 (Data Generation) — `category: "DATA"`, `action: "GENERATE"`

基于嵌入式 LuaJIT 脚本引擎的造数功能，支持单表或多表按序造数（自动处理外键依赖）。Lua 脚本由调用方（Go/Wails 前端）提供，脚本内部自行控制循环次数。

**核心机制**：
- 每张表创建独立的 Lua 虚拟机，`insert()` 调用时**逐条写库**（`executeUpdate` 单条 INSERT），不在内存中积累数据
- 每条插入后实时流式回报进度（`stream: true`，`data` 含 `table`/`inserted`/`scriptInserted`/`scriptIndex`/`totalScripts`）
- 表按 `tables` 数组顺序执行，先创建的表数据先入库（满足外键约束）
- 通过 `RETURN_GENERATED_KEYS` 获取自增主键，`lastId()` 返回当前表最近一条插入的自增 ID，供后续表或行引用

**Lua 沙箱**：禁用 `os`、`io`、`debug`、`package`、`require`、`loadfile`、`dofile`、`loadstring`、`load`、`rawget`、`rawset`、`rawequal`、`setfenv`、`getfenv`、`newproxy` 等危险模块。保留 `math`、`string`、`table`、`tostring`、`tonumber`、`type`、`pairs`、`ipairs`、`pcall`、`error`、`assert` 等安全模块。

**Lua 内置辅助函数**：

| 函数 | 说明 |
|---|---|
| `insert(tableName, rowTable)` | 收集一行待插入数据（Lua table → JDBC 行），每调用一次立即执行 INSERT。列值支持 `string`/`number`/`boolean`/`nil`/`LocalDate`/`LocalDateTime`/`LocalTime`，日期/时间对象按 JDBC `DATE`/`TIMESTAMP`/`TIME` 类型绑定（避免 PG `date is of type date but expression is of type character varying`） |
| `lastId()` | 获取上一张表最后插入的自增 ID（用于外键引用，无自增 ID 时返回 nil） |
| `random_int(min, max)` | 随机整数 [min, max] |
| `random_float(min, max)` | 随机浮点数 [min, max) |
| `random_string(length)` | 指定长度的随机字母数字字符串 |
| `random_date(start, end)` | 两个日期之间的随机日期（参数格式 `YYYY-MM-DD`，返回 `LocalDate` 对象；插入时按 `setDate` 绑定） |
| `random_datetime(start, end)` | 两个日期之间的随机时间戳（参数格式 `YYYY-MM-DD`，返回 `LocalDateTime` 对象；插入时按 `setTimestamp` 绑定） |
| `random_time()` | 随机 `LocalTime` 对象（`HH:mm:ss`；插入时按 `setTime` 绑定） |
| `random_email()` | 随机邮箱地址（`user_<random>@example.com`） |
| `random_phone()` | 随机 11 位手机号 |
| `random_name()` | 随机姓名（内置中文 + 英文姓名池） |
| `random_enum(...)` | 从可变参数中随机选取一个值 |
| `random_uuid()` | 随机 UUID 字符串 |

**请求 payload 顶层字段**：
- `tables` — 表配置数组（必填，按顺序执行）
- `luaVersion` — Lua 引擎版本（可选，默认 `"luajit"`，支持 `"5.1"` / `"5.2"` / `"5.3"` / `"5.4"` / `"5.5"`）

```json
// 请求（脚本内部控制循环次数，不再传 count）
{
  "id": "req-gen-001",
  "category": "DATA",
  "action": "GENERATE",
  "connection": {"driver": "mysql", "host": "127.0.0.1", "port": 3306, "user": "root", "password": "secret", "database": "test_db"},
  "payload": {
    "luaVersion": "luajit",
    "schema": "public",
    "tables": [
      {
        "script": "for i = 1, 100 do\n  insert('users', {\n    name = 'user_' .. i,\n    email = random_email(),\n    age = random_int(18, 65),\n    phone = random_phone(),\n    created_at = random_date('2024-01-01', '2024-12-31')\n  })\nend"
      },
      {
        "script": "for i = 1, 500 do\n  insert('orders', {\n    user_id = random_int(1, 100),\n    amount = random_int(100, 99999) / 100.0,\n    status = random_enum('pending', 'paid', 'shipped', 'completed'),\n    created_at = random_date('2024-06-01', '2025-06-01')\n  })\nend"
      }
    ]
  }
}

// 响应序列（流式，每插入一行回报一次进度）
{"id":"req-gen-001","success":true,"stream":true,"end":false,"data":{"table":"users","inserted":1,"scriptInserted":1,"scriptIndex":1,"totalScripts":2,"sql":"INSERT INTO `users` (`name`, `email`, `age`, `phone`, `created_at`) VALUES (?, ?, ?, ?, ?)","data":{"name":"user_1","email":"user_123456@example.com","age":42,"phone":"13812345678","created_at":"2024-06-15"}}}
{"id":"req-gen-001","success":true,"stream":true,"end":false,"data":{"table":"users","inserted":2,"scriptInserted":2,"scriptIndex":1,"totalScripts":2,"sql":"INSERT INTO `users` (`name`, `email`, `age`, `phone`, `created_at`) VALUES (?, ?, ?, ?, ?)","data":{"name":"user_2","email":"user_234567@example.com","age":35,"phone":"13998765432","created_at":"2024-03-22"}}}
...
{"id":"req-gen-001","success":true,"stream":true,"end":false,"data":{"table":"orders","inserted":101,"scriptInserted":1,"scriptIndex":2,"totalScripts":2,"sql":"INSERT INTO `orders` (`user_id`, `amount`, `status`, `created_at`) VALUES (?, ?, ?, ?)","data":{"user_id":50,"amount":299.99,"status":"paid","created_at":"2024-12-01"}}}
...
{"id":"req-gen-001","success":true,"stream":true,"end":true,"data":null}
```

**外键引用示例**（先造父表，再造子表，通过 `lastId()` 获取父表自增 ID）：

```json
{
  "payload": {
    "tables": [
      {
        "script": "for i = 1, 10 do\n  insert('categories', {\n    name = '分类_' .. i,\n    description = random_string(20)\n  })\nend"
      },
      {
        "script": "local catId = lastId()\nfor i = 1, 100 do\n  insert('products', {\n    category_id = random_int(catId - 9, catId),\n    name = '商品_' .. random_string(6),\n    price = random_int(100, 99999) / 100.0\n  })\nend"
      }
    ]
  }
}
```

**日期/时间列示例（PG `date` / `timestamp` / `time` 列）**

`random_date` / `random_datetime` / `random_time` 返回 Java 时间对象，引擎在 `bindRow` 中按 JDBC `DATE` / `TIMESTAMP` / `TIME` 类型绑定到 `?` 占位符，**避免 PostgreSQL 报 `column "..." is of type date but expression is of type character varying`**：

```lua
for i = 1, 1000 do
  insert("biz_user", {
    username      = "user_" .. i,
    phone         = random_phone(),
    email         = random_email(),
    birthday      = random_date("1970-01-01", "2005-12-31"),      -- -> setDate (PG date 列)
    last_login_at = random_datetime("2024-01-01", "2026-06-30"), -- -> setTimestamp
    wake_up_at    = random_time(),                                 -- -> setTime
  })
end
```

> 旧的 `random_date(...) .. " " .. HH:MM:SS` 字符串拼接写法仍然兼容（`..` 走 `LocalDate.toString()` 得到 ISO 字符串），但推荐改用 `random_datetime` 直接传 `LocalDateTime`，类型更精准。

### 5.8 函数与存储过程管理 (Routine Management) — `category: "FUNCTION"`

PostgreSQL 函数和存储过程管理模块，支持创建、查询、调用、调试等功能。

> ⚠️ **注意**：MySQL 当前为占位实现，调用时会抛出 `UnsupportedOperationException`。

**LIST** — 获取函数/存储过程/触发器列表

```json
// 请求
{"id":"req-fn-001","category":"FUNCTION","action":"LIST","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"test_db"},"payload":{"schema":"public"}}

// 响应 data
[
  {
    "name": "get_user_by_id",
    "routine_type": "FUNCTION",
    "return_type": "SETOF users",
    "language": "plpgsql",
    "security_definer": "SECURITY INVOKER",
    "volatility": "STABLE",
    "arg_count": "1",
    "arg_names": "user_id",
    "schema": "public",
    "description": "根据ID获取用户信息",
    "trigger_table": ""
  },
  {
    "name": "create_order",
    "routine_type": "PROCEDURE",
    "return_type": "",
    "language": "plpgsql",
    "security_definer": "SECURITY INVOKER",
    "volatility": "VOLATILE",
    "arg_count": "3",
    "arg_names": "user_id, product_id, quantity",
    "schema": "public",
    "description": "",
    "trigger_table": ""
  },
  {
    "name": "sync_users_trigger",
    "routine_type": "TRIGGER",
    "return_type": "STATEMENT AFTER DELETE",
    "language": "plpgsql",
    "security_definer": "SECURITY INVOKER",
    "volatility": "VOLATILE",
    "arg_count": "0",
    "arg_names": "",
    "schema": "public",
    "description": "同步删除用户",
    "trigger_table": "users"
  }
]
```

**INFO** — 获取函数/存储过程/触发器的详细信息（后端自动解析 routineType）

```json
// 请求（函数/存储过程）
{"id":"req-fn-002","category":"FUNCTION","action":"INFO","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"test_db"},"payload":{"name":"func_sync_t2_to_t1","schema":"public"}}

// 响应 data（函数）
{
  "name": "func_sync_t2_to_t1",
  "routine_type": "FUNCTION",
  "schema": "public",
  "language": "plpgsql",
  "return_type": "TRIGGER",
  "volatility": "VOLATILE",
  "security_definer": "SECURITY INVOKER",
  "arg_count": "0",
  "arg_names": "",
  "description": "同步t2到t1",
  "trigger_table": ""
}

// 请求（触发器）
{"id":"req-fn-002b","category":"FUNCTION","action":"INFO","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"test_db"},"payload":{"name":"trg_t2_after_insert","schema":"public"}}

// 响应 data（触发器）
{
  "name": "trg_t2_after_insert",
  "routine_type": "TRIGGER",
  "schema": "public",
  "language": "plpgsql",
  "return_type": "ROW BEFORE INSERT",
  "volatility": "VOLATILE",
  "security_definer": "SECURITY INVOKER",
  "arg_count": "0",
  "arg_names": "",
  "description": "",
  "trigger_table": "t2"
}
```

**GET_DDL** — 获取函数/存储过程/触发器的 DDL 定义（后端自动解析类型）

```json
// 请求（函数/存储过程）
{"id":"req-fn-003","category":"FUNCTION","action":"GET_DDL","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"test_db"},"payload":{"name":"get_user_by_id","schema":"public"}}

// 响应 data
"CREATE OR REPLACE FUNCTION public.get_user_by_id(user_id integer)\n RETURNS SETOF users\n LANGUAGE plpgsql\n STABLE\nAS $function$\nBEGIN\n  RETURN QUERY SELECT * FROM users WHERE id = user_id;\nEND\n$function$"

// 请求（触发器）
{"id":"req-fn-003b","category":"FUNCTION","action":"GET_DDL","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"test_db"},"payload":{"name":"sync_users_trigger","schema":"public"}}

// 响应 data
"CREATE OR REPLACE TRIGGER sync_users_trigger\n  STATEMENT AFTER DELETE\n  ON users\n  FOR EACH ROW\n  EXECUTE FUNCTION public.func_sync_users();"
```

**CREATE** — 创建函数/存储过程（直接传递完整 DDL）

```json
// 请求 payload
{
  "ddl": "CREATE OR REPLACE FUNCTION calculate_total(price DECIMAL, tax_rate DECIMAL DEFAULT 0.1)\nRETURNS DECIMAL\nLANGUAGE plpgsql\nAS $$\nBEGIN\n  RETURN price * (1 + tax_rate);\nEND;\n$$"
}

// 创建触发器示例
{
  "ddl": "CREATE OR REPLACE FUNCTION func_sync_t2_to_t1()\nRETURNS TRIGGER AS $$\nBEGIN\n  INSERT INTO t1 (id, name) VALUES (NEW.id, NEW.name);\n  RETURN NEW;\nEND;\n$$ LANGUAGE plpgsql"
}

// 响应 data
{"success": true, "message": "函数/存储过程创建成功"}
```

**DELETE** — 删除函数/存储过程

```json
// 请求 payload
{"name": "old_function", "routineType": "FUNCTION", "schema": "public", "ifExists": true, "cascade": false}

// 响应 data
{"success": true, "message": "函数/存储过程删除成功", "name": "old_function", "routineType": "FUNCTION"}
```

**CALL** — 调用函数/存储过程

```json
// 调用函数
{"id":"req-fn-004","category":"FUNCTION","action":"CALL","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"test_db"},"payload":{"name":"calculate_total","routineType":"FUNCTION","schema":"public","args":["100.00","0.15"]}}

// 调用存储过程
{"id":"req-fn-005","category":"FUNCTION","action":"CALL","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"test_db"},"payload":{"name":"create_order","routineType":"PROCEDURE","schema":"public","args":["1","100","5"]}}

// 函数响应 data
{"result": 115.0, "row_count": 1}

// 存储过程响应 data
{"update_count": 1}
```

**DEBUG** — 调试函数（EXPLAIN、执行计划、依赖分析）

```json
// 请求
{"id":"req-fn-006","category":"FUNCTION","action":"DEBUG","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"test_db"},"payload":{"name":"get_user_by_id","schema":"public"}}

// 响应 data
[
  {"type": "EXPLAIN", "output": "[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Relation Name\":\"users\",\"Filter\":\"(id = $1)\"}}]"},
  {"type": "INFO", "output": "Function: get_user_by_id\nSchema: public\nLanguage: plpgsql\nReturn Type: SETOF users\nVolatility: STABLE\nSecurity: SECURITY INVOKER\nArguments: user_id integer"},
  {"type": "DEPENDENCIES", "output": "TABLE: users\nVIEW: user_summary"}
]
```

**UPDATE** — 验证 DDL 语法（不创建，用于编辑时的语法检查）

```json
// 请求 payload
{
  "ddl": "CREATE OR REPLACE FUNCTION test_func(x INTEGER)\nRETURNS INTEGER AS $$\nBEGIN\n  RETURN x * 2;\nEND;\n$$ LANGUAGE plpgsql"
}

// 响应 data
{"valid": true, "message": "DDL 语法验证通过"}
```

### 5.9 数据导出 (Data Export) — `category: "EXPORT"`, `action: "EXPORT"`

基于自定义 SQL 的 5 种格式数据导出，**独立子进程运行**，全链路流式处理，内存占用与数据总量无关，支持超大数据量稳定导出。

> **子进程隔离**：导出任务运行在独立的 JVM 子进程中（通过 `ExportProcessManager` 管理），即使导出千万级数据也不会导致主进程 OOM。主进程关闭时子进程自动停止。

> 📦 **依赖**：
> - POI：`org.apache.poi:poi-ooxml:5.5.1`（Excel 导出）
> - Parquet：`org.apache.parquet:parquet-hadoop:1.17.1`（Parquet 导出）
> - Hadoop：`org.apache.hadoop:hadoop-common:3.5.0`（Parquet 文件系统抽象）

**支持格式**：

| 格式 | 文件扩展名 | 依赖 | 说明 |
|---|---|---|---|
| CSV | .csv | 零依赖 | UTF-8 BOM 头，自动处理字段转义 |
| JSON Lines | .jsonl | 复用 kotlinx-serialization | 每行一个独立 JSON 对象 |
| SQL INSERT | .sql | 零依赖 | 逐行生成 INSERT 语句，自动转义 |
| Excel | .xlsx | POI SXSSF 流式 | 100 万数据行/Sheet 自动分页，1000 行内存窗口，表头仅首 Sheet 写入 |
| Parquet | .parquet | parquet-hadoop | 动态 Schema，智能推断类型（整数→INT32/INT64，浮点→FLOAT/DOUBLE，字符串→BINARY(UTF8)，DATE→INT32(DATE)，TIME/TIMESTAMP→INT64(TIME/TIMESTAMP_MICROS)） |

**请求 payload 顶层字段**：

```json
{
  "id": "req-export-001",
  "category": "EXPORT",
  "action": "EXPORT",
  "connection": {"driver": "mysql", "host": "127.0.0.1", "port": 3306, "user": "root", "password": "secret", "database": "test_db"},
  "payload": {
    "sql": "SELECT * FROM users",
    "outputDir": "D:/exports",
    "fileName": "users_2024",
    "format": "CSV",
    "tableName": "users",
    "fetchSize": 1000
  }
}
```

- `sql`（必填）— 自定义 SELECT SQL
- `outputDir`（必填）— 输出目录路径（不存在会自动创建）
- `fileName`（必填）— 文件名前缀（不含扩展名）
- `format`（必填）— 导出格式：`CSV` / `JSON_LINES` / `SQL_INSERT` / `EXCEL` / `PARQUET`
- `tableName`— SQL_INSERT 格式必填，用于生成 INSERT 语句前缀
- `fetchSize`— JDBC 游标拉取批次大小，默认 1000

**流式进度响应**：

```json
// 初始进度
{"id":"req-export-001","success":true,"stream":true,"end":false,"data":{"exportedRows":0,"columnCount":5,"completed":false,"filePath":null,"error":null}}
// 每 1000 行进度
{"id":"req-export-001","success":true,"stream":true,"end":false,"data":{"exportedRows":1000,"columnCount":5,"completed":false,"filePath":null,"error":null}}
{"id":"req-export-001","success":true,"stream":true,"end":false,"data":{"exportedRows":2000,"columnCount":5,"completed":false,"filePath":null,"error":null}}
...
// 结束标记（包含 filePath）
{"id":"req-export-001","success":true,"stream":true,"end":true,"data":{"exportedRows":13308,"columnCount":5,"completed":true,"filePath":"C:\\Users\\langb\\Desktop\\users_2024.csv","error":null}}
```

- 进度每 1000 行发送一次
- 收到 `end: true` 时表示导出完成，`filePath` 为导出文件完整路径

**MySQL 特殊配置**：MySQL 流式读取需要在 JDBC URL 追加 `useCursorFetch=true`，本引擎自动处理（`fetchSize = Integer.MIN_VALUE` 启用流式）。

**PostgreSQL 特殊配置**：自动关闭 `autoCommit` 以启用服务端游标，导出完成后自动恢复。

## 6. 安全与健壮性保障 (Security & Reliability)

1. **防进程孤儿 (Graceful Shutdown)**：
   - Kotlin 主循环使用 `BufferedReader.readLine()` 阻塞式读取标准输入流。
   - 当读到 `null` (EOF/输入流关闭) 或特定指令 `"CMD_EXIT"` 时，调用 `PoolManager.closeAll()` 清理所有连接池，并调用 `exitProcess(0)` 正常退出。
   - 添加 JVM Shutdown Hook，确保即使进程被强制终止也能清理资源。
   - Wails 的 Go 进程在关闭时负责切断管道。
   - 导出子进程由 `ExportProcessManager` 管理，主进程关闭时自动发送 `CMD_EXIT`，子进程优雅退出。

2. **连接超时管控 (Timeout Protection)**：
   在 HikariCP 中配置 `connectionTimeout = 5000` (5秒)。当用户输入了错误的 IP 或密码时，能在 5 秒内快速失败并把错误 JSON 返回给前端，避免界面长时间卡死。

3. **全局异常捕获 (Global Exception Handler)**：
   所有的 JDBC `SQLException`（如语法错误、主键冲突、权限不足）都会被 `RequestDispatcher` 拦截，提取 `e.message` 包装入 Response 的 `error` 字段，`success` 置为 `false`。**绝对禁止**应用因未捕获异常而崩溃退出。

4. **SQL 注入防护**：
   DATA 模块所有 CRUD 操作强制使用 `PreparedStatement` 绑定参数（`stmt.setString`），从 JDBC 驱动层物理隔绝 SQL 注入。DATA LIST 的 `where`/`orderBy` 原始片段在拼接前由方言层（`DatabaseDialect.validateSqlFragment` / `validateOrderBy`）执行注入校验，按数据库类型使用不同关键词列表和标识符格式规则。SQL 模块的 EXECUTE 接受原始 SQL，由调用方（Go 层）负责校验来源合法性。

## 7. 工程目录结构 (Directory Structure)

```
idb_engine/                          Gradle 多模块项目
├── settings.gradle.kts              模块注册
├── build.gradle.kts                 根项目（聚合）
│
├── api/                             公共 API 模块（零外部依赖）
│   └── src/main/kotlin/
│       ├── dialect/DatabaseDialect.kt   方言 SPI 接口（含 driverName）
│       └── models/Driver.kt             驱动枚举
│
├── dialect-mysql/                   MySQL 方言插件
│   └── src/main/kotlin/
│       └── dialect/MySQLDialect.kt
│       + META-INF/services/com.kxxnzstdsw.dialect.DatabaseDialect
│
├── dialect-postgresql/              PostgreSQL 方言插件
│   └── src/main/kotlin/
│       └── dialect/PostgreSQLDialect.kt
│       + META-INF/services/com.kxxnzstdsw.dialect.DatabaseDialect
│
└── engine/                          主引擎模块
    └── src/main/kotlin/
        ├── Main.kt                    入口点，协程主循环、outputChannel 串行化输出、GlobalOutputChannel 注入
        ├── dispatcher/
        │   └── RequestDispatcher.kt   解析 JSON，分发请求路由
        ├── pool/
        │   └── PoolManager.kt         HikariCP 动态管理与 SHA-256 缓存
        ├── export/                    导出模块
        │   ├── ExportEngine.kt         导出引擎（核心逻辑）
        │   ├── ExportProcessManager.kt 导出子进程管理器（主进程端）
        │   ├── ExportSubProcess.kt     导出子进程入口（子进程端）
        │   └── GlobalOutputChannel.kt  全局输出 Channel（桥接子进程响应到主进程管线）
        ├── handlers/                  业务处理层（通过 DialectLoader 获取方言）
        │   ├── SchemaHandler.kt
        │   ├── TableHandler.kt
        │   ├── DataHandler.kt
        │   ├── GenerateHandler.kt     造数引擎（LuaJIT 脚本 + 批量插入 + 事务）
        │   ├── FunctionHandler.kt     函数/存储过程管理（PostgreSQL 完整实现）
        │   ├── UserHandler.kt
        │   ├── SqlEngineHandler.kt
        │   └── SystemHandler.kt       JVM 系统信息采集
        ├── loader/                    动态加载
        │   ├── DriverLoader.kt        扫描 drivers/ 目录，ServiceLoader 加载 JDBC 驱动
        │   └── DialectLoader.kt       扫描 dialects/ 目录，ServiceLoader 加载方言插件
        └── models/                    数据契约
            ├── Request.kt             Request / Category / Action / ConnectionConfig
            ├── Response.kt             Response
            └── GenerateModels.kt       GeneratePayload / TableGenerateConfig

构建产物结构：
engine/build/libs/
├── idb-engine.jar       主引擎瘦包
├── libs/                运行时依赖（Kotlin、HikariCP、日志、api、LuaJIT）
├── drivers/             JDBC 驱动（mysql-connector-j、postgresql）
└── dialects/            方言插件
    ├── idb-dialect-mysql.jar
    └── idb-dialect-postgresql.jar
```

## 8. 构建与部署 (Build & Deploy)

### 8.1 构建

```bash
./gradlew engine:jar
```

产物结构（位于 `engine/build/libs/`）：
```
engine/build/libs/
├── idb-engine.jar       主引擎瘦包（Main-Class: com.kxxnzstdsw.MainKt）
├── libs/                运行时依赖
│   ├── luajava-4.1.0.jar
│   ├── luajit-4.1.0.jar
│   ├── luajit-platform-4.1.0-natives-desktop.jar
│   ├── lua51-4.1.0.jar
│   ├── lua51-platform-4.1.0-natives-desktop.jar
│   ├── lua52-4.1.0.jar
│   ├── lua52-platform-4.1.0-natives-desktop.jar
│   ├── lua53-4.1.0.jar
│   ├── lua53-platform-4.1.0-natives-desktop.jar
│   ├── lua54-4.1.0.jar
│   ├── lua54-platform-4.1.0-natives-desktop.jar
│   ├── lua55-4.1.0.jar
│   ├── lua55-platform-4.1.0-natives-desktop.jar
│   ├── HikariCP-7.0.2.jar
│   ├── ...
├── drivers/             JDBC 驱动
│   ├── mysql-connector-j-9.7.0.jar
│   └── postgresql-42.7.11.jar
└── dialects/            方言插件（SPI 动态加载）
    ├── idb-dialect-mysql.jar
    └── idb-dialect-postgresql.jar
```

> **注意**：`idb-engine.jar` 既是主引擎入口（`com.kxxnzstdsw.MainKt`），也是导出子进程的 classpath 入口。导出子进程通过相同的 JAR 运行 `com.kxxnzstdsw.export.ExportSubProcess`。

### 8.2 运行

```bash
cd engine/build/libs && java -jar idb-engine.jar
```

### 8.3 与 Wails 集成

Go 进程通过 `exec.Command` 启动 Kotlin 子进程，工作目录设为 `libs` 所在目录：

```go
cmd := exec.Command("java", "-jar", "idb-engine.jar")
cmd.Dir = "/path/to/build/libs" // 确保 libs/ 目录可被找到
stdin, _ := cmd.StdinPipe()
stdout, _ := cmd.StdoutPipe()
cmd.Start()

// 发送请求
stdin.Write([]byte(jsonRequest + "\n"))

// 读取响应
scanner := bufio.NewScanner(stdout)
scanner.Scan()
response := scanner.Text()
```

## 9. 实现状态 (Implementation Status)

✅ 已完成：
- 核心架构与通信协议（支持流式响应：stream/end 字段）
- 异步非阻塞处理（Kotlin 协程 + Channel 输出串行化）
- 数据库方言抽象层（DatabaseDialect SPI 接口 + MySQL/PostgreSQL 插件）
- 连接池管理（HikariCP + SHA-256 缓存）
- 五大业务模块（Schema/Table/Data/User/SQL）全部改为 suspend 函数
- 流式大数据输出（DATA LIST pageSize=0 / SQL SELECT，JDBC 游标模式防 OOM）
- 动态 JDBC 驱动加载（扫描 drivers/ 目录，URLClassLoader + ServiceLoader）
- 方言插件化动态加载（Gradle 多模块 + SPI，扫描 dialects/ 目录，DialectLoader 自动发现注册）
- MODIFY_COLUMN 支持同时重命名（可选 newName）
- 长驻运行机制（协程 + BufferedReader + Shutdown Hook）
- 日志隔离（滚动文件，不污染 stdout/stderr）
- 构建配置（Gradle 瘦包 + 外部依赖）
- GET_DDL 返回建表语句（MySQL: SHOW CREATE TABLE / PG: information_schema 重建）
- DATA LIST 支持 `where`/`orderBy` 原始 SQL 片段过滤与排序，方言级注入校验
- SYSTEM INFO 返回 JVM 运行时信息（版本、内存、CPU、PID、运行时长等）
- PostgreSQL 方言全面优化（listSchemas 支持两级查询：database 列表 + schema 列表、listTables 用 current_schemas(true)、所有 TABLE/DATA/SQL 操作支持 payload.schema 指定 search_path、listUsers 用 pg_roles、MODIFY_COLUMN 补齐 nullable/default、GET_DDL 含约束与索引、正则预编译）
- 用户管理完整 CRUD（CREATE/DELETE 用户、修改密码、查询指定用户权限，MySQL 与 PostgreSQL 均已实现）
- 造数引擎（LuaJIT 嵌入式脚本 + 多表按序造数 + 外键引用 `lastId()` + 批量插入 + 单事务 + Lua 沙箱 + 流式进度回报，`random_date`/`random_datetime`/`random_time` 返回 `LocalDate`/`LocalDateTime`/`LocalTime` 对象并按 JDBC 日期/时间类型绑定，避免 PG `varchar -> date/timestamp` 隐式转换报错）
- 函数与存储过程管理模块（PostgreSQL: LIST（含触发器）/INFO/GET_DDL/CREATE/DELETE/CALL/DEBUG/VALIDATE，MySQL: 占位实现）
- **数据导出引擎（5 种格式：CSV/JSON Lines/SQL INSERT/Excel/Parquet，全链路 JDBC 游标流式逐行处理，POI SXSSF 支持百万行分 Sheet，导出子进程隔离防 OOM）**

⏳ 待扩展：
- MySQL 函数/存储过程管理完整实现
- GraalVM Native Image 编译
- 更多数据库方言插件（Oracle, SQL Server, SQLite — 只需实现 SPI 接口，放入 dialects/ 即可）
- 性能监控与指标上报