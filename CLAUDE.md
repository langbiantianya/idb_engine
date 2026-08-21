# 📖 Kotlin 数据库管理端后端架构设计文档 (V2.6)

## 1. 架构总览 (Architecture Overview)

Kotlin 后端被设计为一个**无头 (Headless)**、**无状态 (Stateless)** 的底层"数据库算力引擎"。它不暴露网络端口，完全依附于 Wails 主进程的生命周期，通过 **gRPC over IPC 抽象层** 接收指令并返回结果。

**系统拓扑流（gRPC 模式）：**
`[前端 Webview]` ↔ `[Wails Go 主进程]` ↔ `(gRPC HTTP/2 + Protobuf)` ↔ `[Kotlin 引擎进程]` ↔ `[MySQL / PostgreSQL / H2]`

**IPC 传输抽象**（v2.1 起新增，详见 §3.6）：引擎与 Wails 主进程之间的 gRPC 通信可通过 CLI 参数 `--ipc` 在三种传输间切换：
- `tcp`（默认） — TCP loopback `localhost:<port>`，跨平台
- `unix` — Unix Domain Socket，Linux/macOS/BSD，Linux 使用 epoll native，macOS/BSD 走 NIO
- `pipe` — Windows 命名管道（grpc-java 1.76 客户端支持；服务端暂未开放公共 API，抛 `UnsupportedOperationException`）

> **架构升级**：自 v2.0 起，引擎以 gRPC 服务端方式运行（默认 `:50051`，可通过 `--port` 覆盖）。客户端通过 gRPC streaming 调用 `IdbEngine.Handle(Request)`，接收 `stream<Response>`（流式响应使用 `stream`/`end` 字段分帧）。自 v2.2 起，所有 `Request.payload` / `Response.data` 字段都已替换为强类型 per-Category protobuf 消息（`oneof body`）。自 v2.3 起，13 个业务 handler 全部接收 typed per-Category proto 消息、返回 typed `<Category><Action>Response` 消息；`TypedRequestMapper` / `TypedResponseMapper` 已删除，业务层不再经过 `JsonObject` 转换。自 v2.4 起，列表项 envelope 也已 typed（per-list `*ListItem` 消息 + typed `Row` wrapper）；仅方言差异显著的 item shape（`USER.LIST` overloaded、FUNCTION.CALL/INFO、SYSTEM.SERVER_INFO extras）保留 `google.protobuf.Value`。旧的 stdin/stdout 长度前缀 Protobuf 帧协议已彻底移除。自 v2.5 起，gRPC 依赖从 `1.68.0` 升至 `1.76.0`，并接入 `grpc-kotlin` 协程 stub（`IdbEngineCoroutineImplBase`）+ Kotlin DSL 生成器（`protobuf-kotlin-lite`）；13 个 handler + `RequestDispatcher` + 11 个集成测试全部以 Kotlin DSL 形态（`xxxRequest { ... }` / `xxxResponse { ... }` / `xxxItem { ... }` / `request { ... }` / `response { ... }`）编写，业务层无 `Request.newBuilder()...build()` 残留。自 v2.6 起，`RequestDispatcher` 重构为表驱动（`Map<Pair<Category, Action>, Route>`），新增跨切面 `RequestOptions { trace_id, dry_run, timeout_ms }`，新增 `SQL.EXPLAIN` 路由、`if_exists` / `if_not_exists` 贯通到 SCHEMA/TABLE/INDEX/FOREIGN_KEY。
>
> **性能隔离**：数据导出模块运行在独立的子进程中，通过 `ExportProcessManager` 管理（同样通过 gRPC 连接到主进程的 side server），防止大数据量导出时 OOM 影响主进程稳定性。

## 2. 技术栈选型 (Technology Stack)

- **核心语言**：Kotlin 2.4.0 / JDK 25
- **异步框架**：kotlinx-coroutines 1.11.0
- **gRPC**：grpc-netty-shaded 1.76.0 + grpc-stub + grpc-protobuf + grpc-kotlin-stub 1.4.1（Kotlin 协程服务端 + Kotlin DSL 生成）
- **Protobuf**：com.google.protobuf:protobuf-kotlin-lite 3.25.8（生成 *Kt DSL builder）+ protoc 3.25.5 + protoc-gen-grpc-java 1.68.0 + protoc-gen-grpc-kotlin 1.4.1（proto3 + 强类型 per-Category 消息 + 强类型 per-list-item 消息（v2.4）；少量遗留字段如 `MemoryInfo.extras` 仍使用 `google.protobuf.Value` 包装方言特定扩展；业务层全部以 Kotlin DSL 形态编写（v2.5））
- **数据库驱动**：MySQL Connector/J 9.7.0、PostgreSQL JDBC Driver 42.7.11、H2 2.3.232
- **连接池管理**：HikariCP 7.0.2
- **数据序列化**：kotlinx-serialization-json 1.11.0（业务层）
- **日志框架**：SLF4J 2.0.18 + Logback 1.5.13
- **构建与分发**：Gradle + ShadowJar 9.3.0+
- **脚本引擎**：LuaJIT 4.1.0 + Lua 5.1~5.5 via luajava
- **Excel 导出**：Apache POI 5.5.1（poi-ooxml SXSSF 流式 API）
- **Parquet 导出**：Apache Parquet 1.17.1 + Hadoop 3.5.0
- **测试框架**：JUnit 5 + kotlin.test — 191 测试全量通过（0 失败 / 0 错误），其中 H2 dialect 63 项 + engine 128 项

## 3. 核心机制设计 (Core Mechanisms)

### 3.1 通信协议（gRPC）

Kotlin 引擎以 **gRPC 服务端** 方式运行（端口默认 `:50051`，可通过 `--port` 覆盖），调用方通过标准 gRPC stub 与引擎通信。

- **服务定义**（`engine/src/main/proto/idb_engine.proto`）：
  ```proto
  service IdbEngine {
    rpc Handle(Request) returns (stream Response);
  }
  ```
- **传输**：HTTP/2 + 标准 protobuf（强类型 per-Category 消息），由 `grpc-netty-shaded` 驱动
- **消息边界**：gRPC 自动处理帧切分，无需手动 length-prefix
- **Wire 类型**：自 v2.2 起，`Request` 与 `Response` 均为强类型 — `Request` 使用 `oneof body { schema_request, user_request, ... }` 路由到 per-Category 消息（共 12 个：`SystemRequest`/`SchemaRequest`/`UserRequest`/`TableRequest`/`DataRequest`/`SqlRequest`/`FunctionRequest`/`ViewRequest`/`IndexRequest`/`ForeignKeyRequest`/`TriggerRequest`/`ExportRequest`）；`Response` 同样使用 `oneof body` 镜像 12 个 per-Category 响应消息 + 3 个流式帧类型（`DataRowFrame`/`SqlSelectRowFrame`/`GenerateProgressFrame`）+ `GenerateTerminalResponse`
- **业务层**：13 个 handler 全部直接接收 typed per-Category proto 消息（`ConnectionConfig` + `<Category><Action>Request`），返回 typed per-Action proto 消息（`<Category><Action>Response`）；`RequestDispatcher` 是按 (Category, Action) 路由的薄层，把 typed handler 返回值装入 `Response.body` 对应 oneof 分支。**无 `JsonObject` 边界映射**
- **Kotlin DSL（v2.5 起）**：业务层全部使用 protoc-gen-grpc-kotlin + protobuf-kotlin-lite 生成的 Kotlin DSL builder（`xxxRequest { ... }` / `xxxResponse { ... }` / `xxxItem { ... }` / `request { ... }` / `response { ... }`），无 `Request.newBuilder()...build()` 残留；`google.protobuf.Value` 因属 Well-Known Type 仍用 `Value.newBuilder()`（无生成 DSL）
- **最大消息大小**：`maxInboundMessageSize = 256 MiB`
- **异步处理**：服务端基于 grpc-kotlin `IdbEngineCoroutineImplBase`（suspend `handle()` → `Flow<Response>`）+ Kotlin 协程 (`kotlinx-coroutines`)；`addService` 改为 `.addService(IdbEngineImpl().bindService())`
- **错误响应**：业务异常被 `RequestDispatcher` 拦截，提取 `e.message` 包装入 `Response.error`，`success` 置为 `false`，`id` 保持请求的 id

### 3.2 绝对无状态设计 (Stateless Design)

Kotlin 进程不维护"当前选中的数据库"等业务状态。**每一次**请求都必须在其 protobuf `connection` 字段中携带完整的数据库连接凭证（driver / host / port / user / password / database）。

### 3.3 动态连接池管理器 (Dynamic Pool Manager)

为了解决无状态带来的频繁 TCP 握手开销，Kotlin 内部实现基于 SHA-256 Hash Key 的智能缓存连接池。

1. **连接复用**：根据传入的凭证（driver + host + port + user + password + database）生成 SHA-256 Hash，若缓存中已有对应的 HikariCP 实例且活跃，则直接复用。
2. **资源自动回收**：`idleTimeout` 设为 10 分钟，`minimumIdle` 为 0。若某个库 10 分钟无操作，该连接池将自动缩容直至完全销毁。
3. **极限并发**：最大连接数 (`maximumPoolSize`) 限制为 5。
4. **连接超时**：`connectionTimeout` 设为 5 秒。
5. **最大生命周期**：`maxLifetime` 为 30 分钟。

### 3.4 导出子进程隔离机制 (Export Subprocess Isolation)

数据导出模块独立运行在子进程中，通过 `ExportProcessManager` 管理。

- **设计目标**：防止大数据量导出时 OOM 影响主进程稳定性；支持任务取消；主进程关闭时自动停止子进程
- **子进程模式**：`java -Didb.subprocess=true -jar idb-engine.jar`，由 `ExportProcessManager` 通过 gRPC `ExportHub` side server 编排
- **内存限制**：子进程 `-Xmx512m`
- **响应流管线**：子进程通过 `ExportHub` 将进度帧转发回主进程，主进程再 emit 到上游 gRPC StreamObserver

### 3.5 Schema 导航层级 (Navigation Hierarchy)

数据库连接后，前端导航分为两级（PG/H2 支持两级，MySQL 仅支持 database）：

| 方言 | level=database | level=schema |
|---|---|---|
| MySQL | `SHOW DATABASES` 过滤系统库（information_schema / mysql / performance_schema / sys） | 不支持 — 单元素列表（database == schema） |
| PostgreSQL | `pg_database WHERE NOT datistemplate` | `pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname != 'information_schema'` |
| H2 | `[conn.catalog]` 单元素 | `INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?` |

`SchemaHandler.list` 按 `payload["level"]` 分发（默认 `"database"`）：
- `level="database"` → `dialect.listDatabases(conn)` → 返回 `{level: "database", items: [...]}`
- `level="schema"` → 必须同时传 `payload["database"]` → `dialect.listSchemas(conn, database)` → 返回 `{level: "schema", database, items: [...]}`

所有 TABLE / DATA / SQL / FUNCTION / VIEW / INDEX / FOREIGN_KEY / TRIGGER 操作均支持可选 `schema` 参数（PostgreSQL 有效，MySQL 忽略，H2 默认 PUBLIC）；引擎自动 `SET search_path TO <schema>`（PG）或 `SET SCHEMA <schema>`（H2），确保无前缀表名能正确解析。

### 3.6 跨平台 IPC 传输抽象 (Cross-Platform IPC Transport Abstraction)

引擎在 gRPC 之上抽象了一层 **IPC Transport SPI**（`com.kxxnzstdsw.ipc.IpcTransport`），允许通过 CLI 参数在三种传输方式之间切换，无需修改业务代码。`IdbEngineServer` 在启动时调用 `IpcConfig.fromArgs(args)` 解析配置，再通过 `IpcTransportRegistry.resolve(cfg)` 获取传输实例，进而拿到 `ServerBuilder<*>` / `ManagedChannelBuilder<*>`，业务层完全不感知底层传输。

**三种传输方式**：

| `--ipc` | 传输方式 | 适用平台 | 服务端实现 | 客户端实现 |
|---|---|---|---|---|
| `tcp`（默认） | TCP loopback | 全平台 | `NettyServerBuilder.forPort(port)`（生产路径，失败回退 `ServerBuilder.forPort`） | `ManagedChannelBuilder.forAddress("localhost", port).usePlaintext()` |
| `unix` | Unix Domain Socket（filesystem namespace） | Linux / macOS / BSD | Linux + epoll native 可用：`EpollServerDomainSocketChannel` + `EpollEventLoopGroup`；其它：`NioServerDomainSocketChannel` + `NioEventLoopGroup` | `NettyChannelBuilder.forAddress(DomainSocketAddress(path))` + 对应 channelType + eventLoopGroup |
| `pipe` | Windows 命名管道 | Windows | grpc-java 1.68（及最新 1.83）**无公开 server-side API**，`serverBuilder()` 抛 `UnsupportedOperationException` | `Grpc.newChannelBuilder("pipe:<name>", InsecureChannelCredentials.create())` |

**CLI 参数**：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--ipc <kind>` | OS 自动检测 | `tcp` / `unix` / `pipe`；未传时 Windows → `pipe`，POSIX → `unix` |
| `--port <int>` | `50051` | TCP 端口（仅 `tcp` 模式生效） |
| `--uds-path <path>` | `/tmp/idb-engine.sock` | UDS 文件路径（POSIX） |
| `--pipe-name <name>` | `idb-engine` | 命名管道名称（Windows） |
| `--help` / `-h` | — | 打印用法到 stdout 并退出 0 |

**SPI 接口**（`engine/.../ipc/IpcTransport.kt`）：

| 方法 | 职责 |
|---|---|
| `scheme()` | 诊断用标识（`tcp` / `unix` / `pipe`） |
| `displayTarget()` | 显示用地址（端口号 / UDS 路径 / `pipe:<name>`） |
| `prepare()` | 启动前资源准备（UDS 删除 stale 文件并 `Files.createFile` + `chmod 600`；Pipe 校验名称格式） |
| `serverBuilder()` | 返回 `ServerBuilder<*>`（UDS 路径下需额外配置 `bossEventLoopGroup` + `workerEventLoopGroup`） |
| `channelBuilder()` | 返回 `ManagedChannelBuilder<*>`（UDS 路径下需配置 `eventLoopGroup`） |
| `cleanup()` | shutdown 后资源回收（UDS 删除 socket 文件） |

**资源隔离**：
- EventLoopGroup 通过 `NettyServerBuilder.bossEventLoopGroup()` / `workerEventLoopGroup()` 设置后，grpc 在 `server.shutdown()` 时自动释放
- UDS 文件权限：默认 `rw-------`（POSIX）；启动时若存在则删除后重建
- 命名管道名称校验：`^[A-Za-z0-9_.-]{1,64}$`，由 `prepare()` 抛出 `IllegalArgumentException` 终止启动

**为什么不用 Linux abstract namespace UDS**：macOS / BSD 不支持 abstract namespace。

**Windows 服务端限制**：grpc-java 1.76.0（当前依赖版本，最新 1.83.0 亦未公开）未暴露用于 Windows Named Pipes 的 server-side API。`NamedPipeIpcTransport.serverBuilder()` 抛 `UnsupportedOperationException`，提示需 JNA + Win32 `CreateNamedPipe` 自实现（标记为未来工作）；客户端 `channelBuilder()` 可用。Engine 在 Windows 上仍可通过切换到 `tcp` 模式运行（`--ipc tcp`）。

## 4. 数据交互契约 (gRPC Wire Contract)

引擎与调用方之间通过标准 gRPC 传递强类型结构化数据。所有 `Request` / `Response` 的字段含义通过 proto3 schema 定义；下文中的 JSON 示例仅为业务层理解用的逻辑表示，**实际 wire 上是 typed protobuf 的二进制紧凑编码**（不再使用 `map<string, Value>`）。

### 4.0 Wire 传输 (Wire Transport)

- **传输层**：gRPC over HTTP/2（`grpc-netty-shaded`）
- **消息类型**：强类型 per-Category protobuf 消息（`oneof body` 路由）
- **最大消息大小**：`maxInboundMessageSize = 256 MiB`
- **消息边界**：由 gRPC HTTP/2 帧头管理
- **流式响应**：客户端调用 `Handle(req)` 后持续 `stream.Recv()`，直到收到 `end: true` 的最后一帧 `Response`

### 4.1 统一请求体 (Request Envelope)

`Request` 的 protobuf schema（`engine/src/main/proto/idb_engine.proto`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `string` | 请求唯一 ID |
| `category` | `enum Category` | `SCHEMA` / `USER` / `TABLE` / `DATA` / `SQL` / `SYSTEM` / `FUNCTION` / `EXPORT` / `VIEW` / `INDEX` / `FOREIGN_KEY` / `TRIGGER` |
| `action` | `enum Action` | `LIST` / `CREATE` / `UPDATE` / `DELETE` / `EXECUTE` / `GET_DDL` / `INFO` / `GRANTS` / `GENERATE` / `DEBUG` / `CALL` / `RUN_EXPORT` / `RENAME` / `TRUNCATE` / `TEST_CONNECTION` / `SERVER_INFO` |
| `connection` | `ConnectionConfig` | 见下表 |
| `body` | `oneof` | per-Category typed 消息（`schema_request`/`user_request`/.../`export_request`），见下方表格 |

> **注意**：`Action.EXPORT` 在 proto3 中与 `EXPORT` category 命名冲突，因此历史 wire 协议中的 `EXPORT` action 在新协议中重命名为 **`RUN_EXPORT`**（12），仍是 `EXPORT` category 的唯一合法 action。

**Per-Category Request 消息**（`Request.body` 选择项）：

| Category | Request body | 关键字段 |
|---|---|---|
| `SYSTEM` | `SystemRequest` | （无字段；INFO/TEST_CONNECTION/SERVER_INFO 均无 payload） |
| `SCHEMA` | `SchemaRequest` | `list { level, database }` / `create { name, options }` / `delete { name }` |
| `USER` | `UserRequest` | `list { user, host }` / `create { user, password, host }` / `update { user, password, host, schema, privileges[], is_grant, table_name, with_grant_option }` / `delete { user, host }` / `grants { user, host }` |
| `TABLE` | `TableRequest` | `list { schema }` / `column_list { table_name, schema }` / `create { table_name, columns[], options, schema }` / `update { table_name, operation, column, column_name, schema }` / `get_ddl { table_name, schema }` / `rename { old_name, table_name, new_name, schema }` / `delete { table_name, schema }` / `truncate { table_name, schema }` |
| `DATA` | `DataRequest` | `list { table_name, page, page_size, where, order_by, schema }` / `create { table_name, values, schema }` / `update { table_name, changes, where, schema }` / `delete { table_name, where, schema }` / `generate { schema, tables[], lua_version }` |
| `SQL` | `SqlRequest` | `execute { sql, schema }` / `explain { sql, schema }` |
| `FUNCTION` | `FunctionRequest` | `list { schema }` / `info { name, schema }` / `get_ddl { name, schema }` / `create { ddl }` / `delete { name, routine_type, schema, if_exists, cascade }` / `call { name, routine_type, schema, args[] }` / `debug { name, schema }` / `update { ddl }` |
| `VIEW` | `ViewRequest` | `list { schema }` / `create { name, definition, schema }` / `delete { name, if_exists, schema }` / `get_ddl { name, schema }` |
| `INDEX` | `IndexRequest` | `list { table_name, schema }` / `create { table_name, index_name, columns[], unique, schema }` / `delete { index_name, table_name, schema }` |
| `FOREIGN_KEY` | `ForeignKeyRequest` | `list { table_name, schema }` / `create { table_name, fk_name, columns[], ref_table, ref_columns[], on_delete, on_update, schema }` / `delete { table_name, fk_name, schema }` |
| `TRIGGER` | `TriggerRequest` | `list { schema }` / `get_ddl { name, schema }` |
| `EXPORT` | `ExportRequest` | `run_export { sql, output_dir, file_name, format, table_name, fetch_size, stop_export_id }` |

**共享子消息**：

| 消息 | 字段 |
|---|---|
| `ColumnDef` | `name, type, size, nullable (optional, default true), is_primary_key, default_value, auto_increment, new_name` |
| `GenerateTable` | `script` |

`ConnectionConfig`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `driver` | `string` | `Mysql` / `Postgresql` / `H2` |
| `host` | `string` | 数据库主机 |
| `port` | `int32` | 数据库端口 |
| `user` | `string` | 用户名 |
| `password` | `string` | 密码（参与 `toHashKey()` 连接池缓存 key） |
| `database` | `string` | 数据库名（PG 也可作为 schema 容器） |
| `schema` | `string` | 可选 — PG search_path 上下文；H2 视为 schema 名；MySQL 忽略 |
| `use_ssl` | `bool` | 是否启用 SSL |
| `properties` | `map<string,string>` | JDBC 扩展参数 / SSH 配置 |

### 4.2 统一响应体 (Response Envelope)

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | `string` | — | 对应请求的 `id` |
| `success` | `bool` | `false` | 是否成功 |
| `error` | `string` | `""` | 错误信息；非空即视为错误响应 |
| `stream` | `bool` | `false` | 流式响应标记 |
| `end` | `bool` | `false` | 流式结束标记 |
| `body` | `oneof` | （空） | 业务结果 — per-Category typed 消息（`schema`/`user`/...） + 3 个流式帧（`data_row_frame`/`sql_row_frame`/`gen_progress_frame`） + `generate_terminal` |

**Per-Category Response 消息**（`Response.body` 单次响应选项）：

| Category | Response body | 关键字段 |
|---|---|---|
| `SCHEMA` | `SchemaResponse` | `list { level, database, items[] }` / `create { created }` / `delete { deleted }` |
| `USER` | `UserResponse` | `list { items[] }` / `create { created }` / `update { user, schema, table, action, with_grant_option }` / `delete { deleted }` / `grants { items[] }` |
| `TABLE` | `TableResponse` | `list { items[] }` / `columns { items[] }` / `create { created }` / `update { table_name, operation }` / `get_ddl { ddl }` / `rename { renamed, new_name }` / `delete { deleted }` / `truncate { truncated }` |
| `DATA` | `DataResponse` | `list { total, page, page_size, rows[] }` / `create { affected_rows }` / `update { affected_rows }` / `delete { affected_rows }` |
| `SQL` | `SqlResponse` | `execute { affected_rows }` / `explain { rows[] }` |
| `SYSTEM` | `SystemResponse` | `info { jvm_version, ..., memory { max, total, used, free }, uptime, pid }` / `test_connection { ok, driver, host, port, database, error }` / `server_info { version, catalog, current_database, mode, extras }` |
| `FUNCTION` | `FunctionResponse` | `list { items[] }` / `info { info }` / `get_ddl { ddl }` / `create { success, message }` / `delete { success, message, name, routine_type }` / `call { result }` / `debug { items[] }` / `update { valid, message }` |
| `VIEW` | `ViewResponse` | `list { items[] }` / `create { created }` / `delete { deleted }` / `get_ddl { ddl }` |
| `INDEX` | `IndexResponse` | `list { items[] }` / `create { created, table_name }` / `delete { deleted }` |
| `FOREIGN_KEY` | `ForeignKeyResponse` | `list { items[] }` / `create { created, table_name }` / `delete { deleted }` |
| `TRIGGER` | `TriggerResponse` | `list { items[] }` / `get_ddl { ddl }` |
| `EXPORT` | `ExportResponse` | `progress { exported_rows, column_count, completed, file_path, error }` / `stop { stopped }` |

**流式帧类型**（`Response.body` 流式选项）：

| Frame | 字段 | 用途 |
|---|---|---|
| `DataRowFrame` | `total, page=0, page_size=1, row` | `DATA.LIST pageSize=0` 每行一帧 |
| `SqlSelectRowFrame` | `total=-1, page, page_size=1, row` | `SQL.EXECUTE SELECT` 每行一帧 |
| `GenerateProgressFrame` | `table, inserted, script_inserted, script_index, total_scripts, sql, data` | `DATA.GENERATE` 每条 INSERT 一帧 |
| `GenerateTerminalResponse` | `success, tables_processed` | `DATA.GENERATE` 终止帧（end=true） |
| `ExportProgressFrame` | （见 `EXPORT.progress`） | `EXPORT.RUN_EXPORT` 进度帧 |

**流式响应字段说明**：
- `stream: true` — 当前响应属于流式序列（一条请求产生多帧响应）
- `end: true` — 流式序列的最后一帧
- 普通（非流式）响应中 `stream` 和 `end` 均为 `false`

### 4.3 流式 vs 单次响应矩阵

| Category.Action | 响应模式 | 备注 |
|---|---|---|
| `DATA.LIST`（`pageSize == 0`） | **流式** | 每行一帧；JDBC 游标防 OOM；`end=true` 终止 |
| `SQL.EXECUTE`（SELECT） | **流式** | 每行一帧；PG 临时 `autoCommit=false` 启用服务端游标 |
| `DATA.GENERATE` | **流式** | 每条 INSERT 回报一次进度 |
| `EXPORT.RUN_EXPORT` | **流式** | 进度帧 throttled（每 1000 行或每 200ms） |
| 其它所有 `(Category, Action)` | 单次 | 一次性 Response |
| `SQL.EXPLAIN`（Action 15） | 未路由 | proto 定义但 dispatcher 未实现 — 调用会抛 `UnsupportedOperationException` |

## 5. 功能模块详细设计 (Feature Modules)

采用 **方言抽象层 (Dialect Abstraction Layer) + SPI 插件动态加载** 设计模式：

- **DatabaseDialect SPI**（`api` 模块）：定义所有数据库特定操作的抽象方法
- **MySQLDialect / PostgreSQLDialect / H2Dialect**（独立插件模块）：具体方言实现
- **DialectLoader**（`engine` 模块）：启动时扫描 `dialects/` 目录，通过 `ServiceLoader<DatabaseDialect>` 自动发现并注册

**关键事实**（v2.1 验证）：三个方言均完整实现了 SPI 接口的**全部方法**（`listRoutines`、`getRoutineInfo`、`callRoutine`、`validateRoutineDDL`、`debugRoutine`、`createRoutine`、`dropRoutine` 等），不再有占位实现。

### 5.1 架构管理 (Schema Management) — `category: "SCHEMA"`

**支持的 Action**：`LIST` / `CREATE` / `DELETE`

**所有 Action 均需要 connection。**

> **Wire vs 业务层**：下面示例中的 JSON `payload` 字段展示的是**业务参数**的逻辑形状，便于理解业务参数。**实际 wire 上** 这些字段是强类型 per-Category protobuf 消息（`Request.body.schema_request.list.level` / `Request.body.schema_request.list.database` 等），handler 直接读取这些 typed 字段，不再经过任何 `JsonObject` 中间层。

#### LIST — 两级导航

```json
// 请求 — 列出所有 database（默认 level）
{"id":"r1","category":"SCHEMA","action":"LIST","connection":{"driver":"Mysql","host":"127.0.0.1","port":3306,"user":"root","password":"secret","database":"mysql"},"payload":{}}

// 请求 — 列出指定 database 下的 schema（PG 两级模式）
{"id":"r1","category":"SCHEMA","action":"LIST","connection":{"driver":"Postgresql","host":"127.0.0.1","port":5432,"user":"postgres","password":"secret","database":"postgres"},"payload":{"level":"schema","database":"my_app_db"}}
```

| 方言 | level=database | level=schema |
|---|---|---|
| MySQL | `SHOW DATABASES` 过滤系统库 | 单元素 `[database]` |
| PostgreSQL | `pg_database WHERE NOT datistemplate` | `pg_namespace` 过滤 `pg_%` / `information_schema` |
| H2 | `[conn.catalog]` 单元素 | `INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?` |

**Response data**：
- database 级别：`{"level":"database", "items":["postgres","my_app_db"]}`
- schema 级别：`{"level":"schema", "database":"my_app_db", "items":["public","myschema"]}`

#### CREATE — 创建 Database / Schema

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | ✓ | schema 名称 |
| `options` | object | — | MySQL 支持 `charset` / `collate`；PG 忽略 |

```json
{"id":"r2","category":"SCHEMA","action":"CREATE","connection":{...},"payload":{"name":"new_db"}}
{"id":"r2","category":"SCHEMA","action":"CREATE","connection":{...},"payload":{"name":"new_db","options":{"charset":"utf8mb4","collate":"utf8mb4_unicode_ci"}}}
```

**Response data**：`{"created":"new_db"}`

#### DELETE — 删除 Database / Schema

```json
{"id":"r3","category":"SCHEMA","action":"DELETE","connection":{...},"payload":{"name":"old_db"}}
```

**Response data**：`{"deleted":"old_db"}`

### 5.2 用户权限 (User & Privilege) — `category: "USER"`

**支持的 Action**：`LIST` / `CREATE` / `UPDATE` / `DELETE` / `GRANTS`

#### LIST — 用户列表 或 用户权限列表

- payload 不含 `user` → 返回用户列表
- payload 含 `user` → 返回该用户的权限列表

```json
// 用户列表
{"id":"r4","category":"USER","action":"LIST","connection":{...},"payload":{}}
// Response: [{"user":"root","host":"localhost"}, {"user":"app_user","host":"%"}]  (MySQL)
// Response: [{"user":"postgres"}, {"user":"app_user"}]  (PG)

// 指定用户的权限
{"id":"r4b","category":"USER","action":"LIST","connection":{...},"payload":{"user":"dev","host":"%"}}
// Response (MySQL): [{"grant":"GRANT SELECT ON `test_db`.* TO 'dev'@'%'"}, ...]
// Response (PG):     [{"schema":"public","table":"users","privilege":"SELECT"}, ...]
```

#### GRANTS — 聚合表级授权

```json
{"id":"r5","category":"USER","action":"GRANTS","connection":{...},"payload":{"user":"dev","host":"%"}}
// Response: [{"schema":"test_db","table":"users","privileges":"SELECT, INSERT"}, ...]
```

#### CREATE / DELETE — 创建/删除用户

```json
// CREATE
{"id":"r6","category":"USER","action":"CREATE","connection":{...},"payload":{"user":"new_user","password":"secret123","host":"%"}}
// Response: {"created":"new_user"}

// DELETE
{"id":"r6b","category":"USER","action":"DELETE","connection":{...},"payload":{"user":"old_user","host":"%"}}
// Response: {"deleted":"old_user"}
```

#### UPDATE — 两种模式

**模式 1：修改密码**（payload 含 `password` 但无 `privileges`）

```json
{"id":"r7","category":"USER","action":"UPDATE","connection":{...},"payload":{"user":"dev","password":"new_secret","host":"%"}}
// Response: {"user":"dev","action":"password_changed"}
```

**模式 2：授予/回收权限**（payload 含 `privileges`）

```json
// 授予
{"id":"r8","category":"USER","action":"UPDATE","connection":{...},"payload":{"user":"dev","schema":"my_app_db","privileges":["SELECT","INSERT","UPDATE"],"isGrant":true,"tableName":"users","withGrantOption":false}}
// Response: {"user":"dev","schema":"my_app_db","table":"users","withGrantOption":false,"action":"granted"}

// 回收
{"id":"r8b","category":"USER","action":"UPDATE","connection":{...},"payload":{"user":"dev","schema":"my_app_db","privileges":["DELETE"],"isGrant":false}}
// Response: {"user":"dev","schema":"my_app_db","action":"revoked"}
```

### 5.3 表结构元数据 (Table Metadata) — `category: "TABLE"`

**支持的 Action**：`LIST` / `CREATE` / `UPDATE` / `DELETE` / `GET_DDL` / `RENAME` / `TRUNCATE`

#### LIST — 表列表（payload 无 `tableName`）

```json
{"id":"r9","category":"TABLE","action":"LIST","connection":{...},"payload":{}}
{"id":"r9","category":"TABLE","action":"LIST","connection":{...},"payload":{"schema":"public"}}  // PG
// Response: [{"name":"users","type":"TABLE"}, {"name":"orders","type":"TABLE"}]
```

#### LIST — 列与主键（payload 含 `tableName`，自动路由到 `columnList`）

```json
{"id":"r10","category":"TABLE","action":"LIST","connection":{...},"payload":{"tableName":"users"}}
// Response: [{"name":"id","type":"INT","size":10,"nullable":false,"isPrimaryKey":true,"defaultValue":null}, ...]
```

#### CREATE — 创建表

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tableName` | string | ✓ | 表名 |
| `columns` | array | ✓ | 列定义数组 |
| `options` | object | — | MySQL: `engine`/`charset`/`collate`/`comment`；PG: `comment`（走 `COMMENT ON TABLE`） |
| `schema` | string | — | PG schema |

列定义（每个 column 对象）：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `name` | string | ✓ | — | 列名 |
| `type` | string | ✓ | — | SQL 类型（如 `INT` / `VARCHAR` / `DECIMAL` / `TIMESTAMP`） |
| `size` | int | — | — | 类型尺寸（如 VARCHAR 长度） |
| `nullable` | bool | — | `true` | 是否可空 |
| `isPrimaryKey` | bool | — | `false` | 是否主键 |
| `defaultValue` | string | — | — | 默认值（字符串字面量，如 `"0.00"` / `"CURRENT_TIMESTAMP"`） |
| `autoIncrement` | bool | — | `false` | 自增主键（PG: `INT` → `SERIAL`，`BIGINT` → `BIGSERIAL`） |

```json
// 基础
{"id":"r11","category":"TABLE","action":"CREATE","connection":{...},"payload":{"tableName":"products","columns":[{"name":"id","type":"INT","nullable":false,"isPrimaryKey":true},{"name":"name","type":"VARCHAR","size":255,"nullable":false}]}}
// 带自增
{"id":"r11","category":"TABLE","action":"CREATE","connection":{...},"payload":{"tableName":"products","columns":[{"name":"id","type":"INT","nullable":false,"isPrimaryKey":true,"autoIncrement":true},...]}}
// 带表选项
{"id":"r11","category":"TABLE","action":"CREATE","connection":{...},"payload":{"tableName":"products","columns":[...],"options":{"engine":"InnoDB","charset":"utf8mb4","collate":"utf8mb4_unicode_ci","comment":"商品表"}}}
// Response: {"created":"products"}
```

#### UPDATE — 修改表结构（ADD_COLUMN / DROP_COLUMN / MODIFY_COLUMN）

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tableName` | string | ✓ | 目标表 |
| `operation` | string | ✓ | `ADD_COLUMN` / `DROP_COLUMN` / `MODIFY_COLUMN` |
| `schema` | string | — | PG schema |
| **ADD_COLUMN**：`column` | object | ✓ | 列定义（`name`, `type`, 可选 `size`/`nullable`/`defaultValue`） |
| **DROP_COLUMN**：`columnName` | string | ✓ | 要删除的列名 |
| **MODIFY_COLUMN**：`column` | object | ✓ | `name` 必填；`type` 或 `newName` 至少有其一；可选 `size`/`nullable`/`defaultValue` |

```json
// ADD_COLUMN
{"id":"r12","category":"TABLE","action":"UPDATE","connection":{...},"payload":{"tableName":"products","operation":"ADD_COLUMN","column":{"name":"description","type":"TEXT","nullable":true}}}
// Response: {"tableName":"products","operation":"ADD_COLUMN"}

// DROP_COLUMN
{"id":"r12b","category":"TABLE","action":"UPDATE","connection":{...},"payload":{"tableName":"products","operation":"DROP_COLUMN","columnName":"description"}}

// MODIFY_COLUMN（仅改类型）
{"id":"r13","category":"TABLE","action":"UPDATE","connection":{...},"payload":{"tableName":"products","operation":"MODIFY_COLUMN","column":{"name":"price","type":"DECIMAL","size":10,"nullable":false}}}
// MODIFY_COLUMN（改类型 + 重命名）
{"id":"r13b","category":"TABLE","action":"UPDATE","connection":{...},"payload":{"tableName":"products","operation":"MODIFY_COLUMN","column":{"name":"price","type":"DECIMAL","size":10,"nullable":false,"newName":"unit_price"}}}
```

#### GET_DDL — 获取建表语句

```json
{"id":"r14","category":"TABLE","action":"GET_DDL","connection":{...},"payload":{"tableName":"users"}}
// Response: "CREATE TABLE `users` (\n  `id` INT NOT NULL,\n  `name` VARCHAR(255),\n  PRIMARY KEY (`id`)\n)"  (string)
```

MySQL 用 `SHOW CREATE TABLE`；PG 从 `information_schema` + `pg_catalog` 重建（含主键、UNIQUE、CHECK 约束及索引）；H2 列重建 + `TABLE_CONSTRAINTS` 过滤掉同名系统表。

#### DELETE — 删除表

```json
{"id":"r15","category":"TABLE","action":"DELETE","connection":{...},"payload":{"tableName":"old_table"}}
// Response: {"deleted":"old_table"}
```

#### RENAME — 重命名表

```json
{"id":"r16","category":"TABLE","action":"RENAME","connection":{...},"payload":{"oldName":"users","newName":"users_new"}}
// 也支持 tableName 作为 oldName 的别名
{"id":"r16","category":"TABLE","action":"RENAME","connection":{...},"payload":{"tableName":"users","newName":"users_new"}}
// Response: {"renamed":"users","newName":"users_new"}
```

#### TRUNCATE — 清空表

```json
{"id":"r17","category":"TABLE","action":"TRUNCATE","connection":{...},"payload":{"tableName":"users"}}
// Response: {"truncated":"users"}
```

### 5.4 表数据运维 (Table Data CRUD) — `category: "DATA"`

**支持的 Action**：`LIST` / `CREATE` / `UPDATE` / `DELETE`（外加 `GENERATE` 造数，详见 §5.7）

#### LIST — 分页查询 / 流式查询

**分页模式**（`pageSize > 0`，默认 50）：

| payload 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `tableName` | string | ✓ | — | 表名 |
| `page` | int | — | 1 | 页码 |
| `pageSize` | int | — | 50 | 每页行数 |
| `where` | string | — | — | 原始 WHERE 片段（不含 `WHERE` 关键字） |
| `orderBy` | string | — | — | 原始 ORDER BY 片段（不含 `ORDER BY` 关键字） |
| `schema` | string | — | — | PG schema |

**流式模式**（`pageSize == 0`）：逐行流式返回，使用 JDBC 游标（`TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY` + `fetchSize=100`），PostgreSQL 端临时关闭 `autoCommit` 启用服务端游标。

**LOB 列**（`BLOB` / `LONGTEXT` / `BYTEA` / `TEXT`）始终返回 `"[LOB Data]"`。

**安全校验**（方言层 `validateSqlFragment` / `validateOrderBy`）：
- **通用**：去除单引号内容后禁止分号 `;`、注释 `--` `/*`，禁止引号外出现 `INSERT/UPDATE/DELETE/DROP/UNION/EXEC/CREATE/ALTER/GRANT/REVOKE/TRUNCATE`
- **MySQL 额外**：ORDER BY 标识符允许反引号 `` `col` ``
- **PostgreSQL 额外**：ORDER BY 标识符允许双引号 `"col"`；额外禁止 `COPY`、`DO`

```json
// 分页查询
{"id":"r18","category":"DATA","action":"LIST","connection":{...},"payload":{"tableName":"users","page":1,"pageSize":50}}
// Response: {"total":120,"page":1,"pageSize":50,"rows":[{"id":"1","name":"Alice","avatar":"[LOB Data]"}, ...]}

// 带过滤与排序
{"id":"r18b","category":"DATA","action":"LIST","connection":{...},"payload":{"tableName":"users","page":1,"pageSize":20,"where":"age > 18 AND name LIKE '%Alice%'","orderBy":"created_at DESC"}}

// 流式全量
{"id":"r18c","category":"DATA","action":"LIST","connection":{...},"payload":{"tableName":"users","pageSize":0}}
// 流式响应序列：
// {"id":"r18c","success":true,"stream":true,"end":false,"data":{"total":1000,"page":0,"pageSize":1,"rows":[{"id":"1","name":"Alice"}]}}
// {"id":"r18c","success":true,"stream":true,"end":false,"data":{"total":1000,"page":0,"pageSize":1,"rows":[{"id":"2","name":"Bob"}]}}
// ...
// {"id":"r18c","success":true,"stream":true,"end":true,"data":null}
```

#### CREATE — 插入一行

```json
{"id":"r19","category":"DATA","action":"CREATE","connection":{...},"payload":{"tableName":"users","values":{"name":"Charlie","email":"charlie@example.com"}}}
// Response: {"affectedRows":1}
```

所有 `values` 值以字符串形式通过 `PreparedStatement.setString` 绑定；列类型由方言层 `listColumns` 解析后按类型 dispatch（详见 `DataHandler.bindTypedValue`：整数 → `setLong`，浮点 → `setDouble`，布尔 → `setBoolean`，日期/时间 → `setDate`/`setTime`/`setTimestamp`，二进制 → `setBytes`）。

#### UPDATE — 按 where 条件更新

```json
{"id":"r20","category":"DATA","action":"UPDATE","connection":{...},"payload":{"tableName":"users","changes":{"name":"Alex","email":"alex@example.com"},"where":{"id":"1"}}}
// Response: {"affectedRows":1}
```

#### DELETE — 按 where 条件删除

```json
{"id":"r21","category":"DATA","action":"DELETE","connection":{...},"payload":{"tableName":"users","where":{"id":"1"}}}
// Response: {"affectedRows":1}
```

### 5.5 原生 SQL 引擎 (Arbitrary SQL Engine) — `category: "SQL"`

**支持的 Action**：`EXECUTE`

#### EXECUTE — 接收任意 SQL

- **SELECT**：流式输出（每行一帧），`total: -1`，JDBC 游标防 OOM
- **非 SELECT**（INSERT/UPDATE/DELETE/DDL）：单次响应 `{"affectedRows": N}`
- **PostgreSQL schema 上下文**：payload 可选 `schema` 字段，引擎在执行 SQL 前自动 `SET search_path TO <schema>`
- **LOB 列**：`BLOB`/`LONGTEXT`/`BYTEA`/`TEXT` 返回 `"[LOB Data]"`

```json
// 查询
{"id":"r22","category":"SQL","action":"EXECUTE","connection":{...},"payload":{"sql":"SELECT id, name FROM users WHERE id > 10 LIMIT 5"}}
// 响应（流式）：
// {"id":"r22","success":true,"stream":true,"end":false,"data":{"total":-1,"page":0,"pageSize":1,"rows":[{"id":"11","name":"Dave"}]}}
// ...

// PG 带 schema 上下文
{"id":"r22b","category":"SQL","action":"EXECUTE","connection":{...},"payload":{"sql":"SELECT * FROM users LIMIT 5","schema":"public"}}

// 更新 / DDL
{"id":"r23","category":"SQL","action":"EXECUTE","connection":{...},"payload":{"sql":"UPDATE users SET name = 'Frank' WHERE id = 3"}}
// Response: {"affectedRows":1}
```

> **注意**：`Action.EXPLAIN`（15）在 proto 中定义，但 `RequestDispatcher.handleSql` 未实现 EXPLAIN 路径；调用会抛 `UnsupportedOperationException("Action EXPLAIN not supported for SQL")`。SQL 执行计划请使用 `SQL.EXECUTE` 直接提交 `EXPLAIN <sql>` 语句。

### 5.6 系统信息 (System Info) — `category: "SYSTEM"`

**支持的 Action**：`INFO` / `TEST_CONNECTION` / `SERVER_INFO`

#### INFO — JVM 运行时信息（无需 connection，字段被忽略）

```json
{"id":"r24","category":"SYSTEM","action":"INFO","connection":{"driver":"mysql","host":"127.0.0.1","port":3306,"user":"root","password":"secret","database":"mysql"},"payload":{}}
// Response:
// {
//   "jvmVersion": "21.0.2",
//   "jvmVendor": "Oracle Corporation",
//   "jvmName": "OpenJDK 64-Bit Server VM",
//   "osName": "Windows 11", "osArch": "amd64", "osVersion": "10.0",
//   "availableProcessors": 16,
//   "memory": {"max":4294967296,"total":268435456,"used":134217728,"free":134217728},
//   "uptime": 120000, "pid": 12345
// }
```

`memory` 各字段单位为字节（Bytes）。

#### TEST_CONNECTION — 测试连接（成功返回 `ok=true`，失败返回 `ok=false`，不抛异常）

```json
{"id":"r25","category":"SYSTEM","action":"TEST_CONNECTION","connection":{"driver":"Mysql","host":"127.0.0.1","port":3306,"user":"root","password":"secret","database":"mysql"},"payload":{}}
// 成功 Response: {"ok":true,"driver":"Mysql","host":"127.0.0.1","port":3306,"database":"mysql"}
// 失败 Response: {"ok":false,"error":"Communications link failure..."}
```

#### SERVER_INFO — 数据库服务器信息

```json
{"id":"r26","category":"SYSTEM","action":"SERVER_INFO","connection":{...},"payload":{}}
// PG Response: {"version":"PostgreSQL 16.0 ...","current_database":"myapp_db",...}
// MySQL Response: {"version":"8.0.36","catalog":"def",...}
// H2 Response: {"version":"2.3.232","mode":"REGULAR",...}
```

### 5.7 造数引擎 (Data Generation) — `category: "DATA"`, `action: "GENERATE"`

基于嵌入式 Lua 脚本引擎的造数功能，支持单表或多表按序造数（自动处理外键依赖）。

**核心机制**：
- 每张表创建独立的 Lua 虚拟机，`insert()` 调用时**逐条写库**（`executeUpdate` 单条 INSERT），不在内存中积累数据
- 每条插入后实时流式回报进度（`stream: true`）
- 表按 `tables` 数组顺序执行
- 通过 `RETURN_GENERATED_KEYS` 获取自增主键

**Lua 沙箱**：禁用 `os`、`io`、`debug`、`package`、`require`、`loadfile`、`dofile`、`loadstring`、`load`、`rawget`、`rawset`、`rawequal`、`setfenv`、`getfenv`、`newproxy` 等危险模块。

**Lua 版本**（通过 `payload.luaVersion` 选择，默认 `"luajit"`，支持 `"5.1"` / `"5.2"` / `"5.3"` / `"5.4"` / `"5.5"` 及短名 `"lua51"` 等）。

**Lua 内置辅助函数**：

| 函数 | 说明 |
|---|---|
| `insert(tableName, rowTable)` | 收集一行待插入数据，立即执行 INSERT。列值支持 `string`/`number`/`boolean`/`nil`/`LocalDate`/`LocalDateTime`/`LocalTime` |
| `lastId()` | 获取上一张表最后插入的自增 ID |
| `random_int(min, max)` | 随机整数 `[min, max]` |
| `random_float(min, max)` | 随机浮点数 `[min, max)` |
| `random_string(length)` | 指定长度的随机字母数字字符串（`a-zA-Z0-9`，长度 clamp 到 `[1, 256]`） |
| `random_date(start, end)` | 两个日期之间的随机日期（参数 `YYYY-MM-DD`，返回 `LocalDate`） |
| `random_datetime(start, end)` | 两个日期之间的随机时间戳（参数 `YYYY-MM-DD`，返回 `LocalDateTime`） |
| `random_time()` | 随机 `LocalTime`（`HH:mm:ss`） |
| `random_email()` | `user_<random>@example.com` |
| `random_phone()` | 11 位手机号 |
| `random_name()` | 随机姓名（中文 + 英文姓名池） |
| `random_enum(...)` | 从可变参数中随机选取一个值 |
| `random_uuid()` | 随机 UUID 字符串 |

> **注意**：嵌套 Lua table 通过 `insert()` 传递时会丢失（`readLuaTable` 中 `isTable -> null`），列值必须使用扁平 string / number / boolean / nil / java.time 类型。

**请求 payload**：
```json
{
  "luaVersion": "luajit",
  "schema": "public",
  "tables": [
    {"script": "for i = 1, 100 do\n  insert('users', {name='user_'..i, email=random_email(), age=random_int(18,65)})\nend"},
    {"script": "local catId = lastId()\nfor i = 1, 500 do\n  insert('orders', {user_id=random_int(1,100), amount=random_int(100,99999)/100.0})\nend"}
  ]
}
```

**流式进度响应**：
```json
{"id":"r27","success":true,"stream":true,"end":false,"data":{"table":"users","inserted":1,"scriptInserted":1,"scriptIndex":1,"totalScripts":2,"sql":"INSERT INTO `users` ...","data":{"name":"user_1","email":"user_...","age":42}}}
...
{"id":"r27","success":true,"stream":true,"end":true,"data":null}
```

### 5.8 函数与存储过程管理 (Routine Management) — `category: "FUNCTION"`

**支持的 Action**：`LIST` / `INFO` / `GET_DDL` / `CREATE` / `DELETE` / `CALL` / `DEBUG` / `UPDATE`（语法验证）

**所有三个方言均完整实现** Routine 管理（MySQL / PostgreSQL / H2 都通过 `INFORMATION_SCHEMA.ROUTINES` + `PARAMETERS` + `TRIGGERS` 查询）。

#### LIST — 函数/存储过程列表

```json
{"id":"r28","category":"FUNCTION","action":"LIST","connection":{...},"payload":{"schema":"public"}}
// Response: [
//   {"name":"get_user_by_id","routine_type":"FUNCTION","return_type":"SETOF users","language":"plpgsql","security_definer":"SECURITY INVOKER","volatility":"STABLE","arg_count":"1","arg_names":"user_id","schema":"public","description":"...","trigger_table":""},
//   {"name":"create_order","routine_type":"PROCEDURE",...},
//   {"name":"sync_users_trigger","routine_type":"TRIGGER","trigger_table":"users",...}
// ]
```

`routine_type` 字段取值：`FUNCTION` / `PROCEDURE` / `TRIGGER`。

#### INFO — 详细信息（自动解析类型）

```json
{"id":"r29","category":"FUNCTION","action":"INFO","connection":{...},"payload":{"name":"func_sync_t2_to_t1","schema":"public"}}
// Response: {"name":"...","routine_type":"FUNCTION","schema":"public","language":"plpgsql","return_type":"TRIGGER","volatility":"VOLATILE","security_definer":"SECURITY INVOKER","arg_count":"0","arg_names":"","description":"...","trigger_table":""}
```

#### GET_DDL — 完整 DDL 定义

```json
{"id":"r30","category":"FUNCTION","action":"GET_DDL","connection":{...},"payload":{"name":"get_user_by_id","schema":"public"}}
// Response: "CREATE OR REPLACE FUNCTION public.get_user_by_id(user_id integer)\n RETURNS SETOF users\n LANGUAGE plpgsql ..."  (string)
```

#### CREATE — 创建函数/存储过程（直接传递完整 DDL）

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `ddl` | string | ✓ | 完整的 `CREATE OR REPLACE FUNCTION/PROCEDURE ...` 语句 |

```json
{"id":"r31","category":"FUNCTION","action":"CREATE","connection":{...},"payload":{"ddl":"CREATE OR REPLACE FUNCTION calculate_total(price DECIMAL, tax_rate DECIMAL DEFAULT 0.1) RETURNS DECIMAL LANGUAGE plpgsql AS $$ BEGIN RETURN price * (1 + tax_rate); END; $$"}}
// Response: {"success":true,"message":"函数/存储过程创建成功"}
```

#### DELETE — 删除函数/存储过程

| payload 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `name` | string | ✓ | — | 名称 |
| `routineType` | string | ✓ | — | `FUNCTION` / `PROCEDURE` |
| `schema` | string | — | — | schema 名 |
| `ifExists` | bool | — | `false` | `IF EXISTS` |
| `cascade` | bool | — | `false` | `CASCADE` |

```json
{"id":"r32","category":"FUNCTION","action":"DELETE","connection":{...},"payload":{"name":"old_function","routineType":"FUNCTION","schema":"public","ifExists":true,"cascade":false}}
// Response: {"success":true,"message":"函数/存储过程删除成功","name":"old_function","routineType":"FUNCTION"}
```

#### CALL — 调用函数/存储过程

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | ✓ | 名称 |
| `routineType` | string | ✓ | `FUNCTION` / `PROCEDURE` |
| `schema` | string | — | schema 名 |
| `args` | array<string> | — | 参数列表（字符串形式） |

```json
// 调用函数
{"id":"r33","category":"FUNCTION","action":"CALL","connection":{...},"payload":{"name":"calculate_total","routineType":"FUNCTION","schema":"public","args":["100.00","0.15"]}}
// Response: {"result":115.0,"row_count":1}

// 调用存储过程
{"id":"r33b","category":"FUNCTION","action":"CALL","connection":{...},"payload":{"name":"create_order","routineType":"PROCEDURE","schema":"public","args":["1","100","5"]}}
// Response: {"update_count":1}
```

#### DEBUG — 调试函数（EXPLAIN + INFO + DEPENDENCIES）

```json
{"id":"r34","category":"FUNCTION","action":"DEBUG","connection":{...},"payload":{"name":"get_user_by_id","schema":"public"}}
// Response: [
//   {"type":"EXPLAIN","output":"[{\"Plan\":...}]"},
//   {"type":"INFO","output":"Function: ..."},
//   {"type":"DEPENDENCIES","output":"TABLE: users\nVIEW: user_summary"}
// ]
```

#### UPDATE — 验证 DDL 语法（不创建）

```json
{"id":"r35","category":"FUNCTION","action":"UPDATE","connection":{...},"payload":{"ddl":"CREATE OR REPLACE FUNCTION test_func(x INTEGER) RETURNS INTEGER AS $$ BEGIN RETURN x * 2; END; $$ LANGUAGE plpgsql"}}
// Response: {"valid":true,"message":"DDL 语法验证通过"}
```

### 5.9 视图管理 (View Management) — `category: "VIEW"`

**支持的 Action**：`LIST` / `CREATE` / `DELETE` / `GET_DDL`

```json
// LIST
{"id":"r36","category":"VIEW","action":"LIST","connection":{...},"payload":{"schema":"public"}}
// Response: [{"name":"v_users","type":"VIEW","definition":"SELECT ..."}]

// CREATE
{"id":"r37","category":"VIEW","action":"CREATE","connection":{...},"payload":{"name":"v_users","definition":"SELECT id, name FROM users WHERE active = true","schema":"public"}}
// Response: {"created":"v_users"}

// DELETE
{"id":"r38","category":"VIEW","action":"DELETE","connection":{...},"payload":{"name":"v_users","ifExists":true,"schema":"public"}}
// Response: {"deleted":"v_users"}

// GET_DDL
{"id":"r39","category":"VIEW","action":"GET_DDL","connection":{...},"payload":{"name":"v_users","schema":"public"}}
// Response: "CREATE VIEW public.v_users AS SELECT ..."  (string)
```

### 5.10 索引管理 (Index Management) — `category: "INDEX"`

**支持的 Action**：`LIST` / `CREATE` / `DELETE`

```json
// LIST
{"id":"r40","category":"INDEX","action":"LIST","connection":{...},"payload":{"tableName":"users","schema":"public"}}
// Response: [{"name":"idx_email","table":"users","columns":["email"],"unique":false,"type":"BTREE"}]

// CREATE
{"id":"r41","category":"INDEX","action":"CREATE","connection":{...},"payload":{"tableName":"users","indexName":"idx_email","columns":["email"],"unique":false,"schema":"public"}}
// Response: {"created":"idx_email","tableName":"users"}

// DELETE
{"id":"r42","category":"INDEX","action":"DELETE","connection":{...},"payload":{"indexName":"idx_email","tableName":"users","schema":"public"}}
// Response: {"deleted":"idx_email"}
```

### 5.11 外键管理 (Foreign Key Management) — `category: "FOREIGN_KEY"`

**支持的 Action**：`LIST` / `CREATE` / `DELETE`

```json
// LIST
{"id":"r43","category":"FOREIGN_KEY","action":"LIST","connection":{...},"payload":{"tableName":"orders","schema":"public"}}
// Response: [{"name":"fk_orders_user","table":"orders","columns":["user_id"],"ref_table":"users","ref_columns":["id"],"on_delete":"CASCADE","on_update":"RESTRICT"}]

// CREATE
{"id":"r44","category":"FOREIGN_KEY","action":"CREATE","connection":{...},"payload":{"tableName":"orders","fkName":"fk_orders_user","columns":["user_id"],"refTable":"users","refColumns":["id"],"onDelete":"CASCADE","onUpdate":"RESTRICT","schema":"public"}}
// Response: {"created":"fk_orders_user","tableName":"orders"}

// DELETE
{"id":"r45","category":"FOREIGN_KEY","action":"DELETE","connection":{...},"payload":{"tableName":"orders","fkName":"fk_orders_user","schema":"public"}}
// Response: {"deleted":"fk_orders_user"}
```

### 5.12 触发器管理 (Trigger Management) — `category: "TRIGGER"`

**支持的 Action**：`LIST` / `GET_DDL`

> Trigger 的创建/删除通过 `category=FUNCTION, routineType="TRIGGER"` 完成（见 §5.8）。

```json
// LIST
{"id":"r46","category":"TRIGGER","action":"LIST","connection":{...},"payload":{"schema":"public"}}
// Response: [{"name":"trg_xxx","table":"users","event":"INSERT","timing":"BEFORE","statement":"..."}]

// GET_DDL
{"id":"r47","category":"TRIGGER","action":"GET_DDL","connection":{...},"payload":{"name":"sync_users_trigger","schema":"public"}}
// Response: "CREATE OR REPLACE TRIGGER sync_users_trigger\n  STATEMENT AFTER DELETE\n  ON users ..."  (string)
```

### 5.13 数据导出 (Data Export) — `category: "EXPORT"`, `action: "RUN_EXPORT"`

基于自定义 SQL 的 5 种格式数据导出，**独立子进程运行**，全链路流式处理。

> **子进程隔离**：导出任务运行在独立的 JVM 子进程中（通过 `ExportProcessManager` 管理），即使导出千万级数据也不会导致主进程 OOM。

**支持格式**：

| 格式 | 文件扩展名 | 依赖 | 说明 |
|---|---|---|---|
| `CSV` | .csv | 零依赖 | UTF-8 BOM 头，自动处理字段转义 |
| `JSON_LINES` | .jsonl | kotlinx-serialization | 每行一个独立 JSON 对象 |
| `SQL_INSERT` | .sql | 零依赖 | 逐行生成 INSERT 语句（**必须**传 `tableName`） |
| `EXCEL` | .xlsx | POI SXSSF 流式 | 100 万数据行/Sheet 自动分页，1000 行内存窗口 |
| `PARQUET` | .parquet | parquet-hadoop | 动态 Schema，类型智能推断 |

**请求 payload 字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sql` | string | ✓ | 自定义 SELECT SQL |
| `outputDir` | string | ✓ | 输出目录路径（不存在会自动创建） |
| `fileName` | string | ✓ | 文件名前缀（不含扩展名） |
| `format` | string | ✓ | `CSV` / `JSON_LINES` / `SQL_INSERT` / `EXCEL` / `PARQUET` |
| `tableName` | string | 条件 | `SQL_INSERT` 格式必填，用于生成 INSERT 语句前缀 |
| `fetchSize` | int | — | JDBC 游标拉取批次大小，默认 1000 |
| `stopExportId` | string | — | 传入则停止指定 ID 的导出任务（见下文"停止导出"） |

**流式进度响应**（每 1000 行或每 200ms 一帧）：
```json
{"id":"r48","success":true,"stream":true,"end":false,"data":{"exportedRows":0,"columnCount":5,"completed":false,"filePath":null,"error":null}}
{"id":"r48","success":true,"stream":true,"end":false,"data":{"exportedRows":1000,"columnCount":5,"completed":false}}
...
{"id":"r48","success":true,"stream":true,"end":true,"data":{"exportedRows":13308,"columnCount":5,"completed":true,"filePath":"C:\\Users\\langb\\Desktop\\users_2024.csv","error":null}}
```

**MySQL 特殊配置**：MySQL 流式读取需 `fetchSize = Integer.MIN_VALUE` 启用服务端流式游标（引擎自动处理）。
**PostgreSQL 特殊配置**：自动临时关闭 `autoCommit` 启用服务端游标，导出完成后自动恢复。

**停止导出**：在新 EXPORT 请求中指定 `stopExportId` 即可取消对应的导出任务：

```json
{"id":"r48stop","category":"EXPORT","action":"RUN_EXPORT","connection":{...},"payload":{"stopExportId":"r48"}}
// Response: {"stopped":"r48"}
```

被取消的导出任务返回 `{"success": false, "error": "Export cancelled by user"}`。

## 6. 安全与健壮性保障 (Security & Reliability)

1. **防进程孤儿 (Graceful Shutdown)**：引擎由 `IdbEngineServer` 阻塞在 `server.awaitTermination()`；JVM Shutdown Hook 在终止时调用 `transport.cleanup()` + `PoolManager.closeAll()` + `DriverLoader.closeAll()` + `DialectLoader.closeAll()`。

2. **连接超时管控**：HikariCP `connectionTimeout = 5000`（5 秒）。

3. **全局异常捕获**：`RequestDispatcher.dispatch` 顶层 `try/catch`，所有 JDBC `SQLException` 提取 `e.message` 包装为 `Response(success=false, error=...)`。**绝对禁止**应用因未捕获异常而崩溃退出。

4. **SQL 注入防护**：DATA 模块强制 `PreparedStatement` 绑定参数；`where` / `orderBy` 原始片段由方言层 `validateSqlFragment` / `validateOrderBy` 校验。SQL 模块的 EXECUTE 接受原始 SQL，由调用方负责校验来源合法性。

## 7. 工程目录结构 (Directory Structure)

```
idb_engine/                          Gradle 多模块项目
├── settings.gradle.kts              模块注册
├── build.gradle.kts                 根项目（聚合）
│
├── api/                             公共 SPI 接口（零外部依赖）
│   └── src/main/kotlin/com/kxxnzstdsw/dialect/DatabaseDialect.kt
│
├── dialect-mysql/                   MySQL 方言插件
├── dialect-postgresql/              PostgreSQL 方言插件
├── dialect-h2/                      H2 方言插件（嵌入式 + 测试）
│   └── src/test/kotlin/com/kxxnzstdsw/dialect/H2DialectTest.kt   (63 tests)
│
└── engine/                          主引擎模块
    └── src/main/kotlin/com/kxxnzstdsw/
        ├── Main.kt                  入口点
        ├── grpc/                    gRPC protobuf 边界
        │   └── PayloadAdapter.kt    google.protobuf.Value ↔ JsonElement
        ├── dispatcher/
        │   └── RequestDispatcher.kt 路由 (Category, Action) → Handler
        ├── pool/PoolManager.kt      HikariCP + SHA-256 缓存
        ├── export/                  导出模块
        │   ├── ExportEngine.kt
        │   ├── ExportProcessManager.kt
        │   ├── ExportSubProcess.kt
        │   └── ExportModels.kt
        ├── ipc/                     跨平台 IPC 传输抽象（SPI）
        │   ├── IpcTransport.kt
        │   ├── IpcConfig.kt
        │   ├── IpcTransportRegistry.kt
        │   └── impl/{Tcp,UnixSocket,NamedPipe}IpcTransport.kt
        ├── server/                  gRPC 服务端
        │   ├── IdbEngineServer.kt
        │   └── IdbEngineImpl.kt
        ├── handlers/                业务处理层
        │   ├── SchemaHandler.kt
        │   ├── TableHandler.kt      含 RENAME/TRUNCATE
        │   ├── DataHandler.kt
        │   ├── GenerateHandler.kt   造数引擎（LuaJIT 脚本）
        │   ├── FunctionHandler.kt
        │   ├── UserHandler.kt
        │   ├── SqlEngineHandler.kt
        │   ├── SystemHandler.kt     含 TEST_CONNECTION / SERVER_INFO
        │   ├── ExportHandler.kt
        │   ├── ViewHandler.kt
        │   ├── IndexHandler.kt
        │   ├── ForeignKeyHandler.kt
        │   └── TriggerHandler.kt
        ├── loader/                  动态加载
        │   ├── DriverLoader.kt
        │   └── DialectLoader.kt
        └── models/
            └── GenerateModels.kt

    └── src/main/proto/
        └── idb_engine.proto         gRPC service + Request/Response/ConnectionConfig + Category/Action enums

    └── src/test/kotlin/com/kxxnzstdsw/   96 tests
        ├── ipc/
        │   ├── IpcConfigTest.kt                  (9)
        │   ├── IpcTransportTest.kt               (7)
        │   ├── TcpIpcTransportIntegrationTest.kt (1)
        │   ├── UnixSocketIpcTransportIntegrationTest.kt (2)
        │   └── NamedPipeIpcTransportIntegrationTest.kt   (2)
        ├── pool/PoolManagerTest.kt               (11)
        ├── loader/DialectLoaderTest.kt           (5)
        └── integration/                          (60, 11 个 handler × H2)
```

构建产物结构（`engine/build/libs/`）：
```
idb-engine.jar       主引擎瘦包
libs/                运行时依赖（Kotlin、gRPC、HikariCP、日志、api、LuaJIT）
drivers/             JDBC 驱动（mysql-connector-j、postgresql、h2）
dialects/            方言插件（SPI 动态加载）
```

## 8. 构建与部署 (Build & Deploy)

### 8.1 构建

```bash
./gradlew engine:jar
```

### 8.2 运行

```bash
# TCP（默认；OS 自动检测）
cd engine/build/libs && java -jar idb-engine.jar

# Unix Domain Socket（POSIX 自动检测同上；显式指定）
java -jar idb-engine.jar --ipc unix

# TCP 自定义端口
java -jar idb-engine.jar --ipc tcp --port 60000

# UDS 自定义路径
java -jar idb-engine.jar --ipc unix --uds-path /var/run/idb.sock

# Windows 命名管道（服务端受限，Windows 自动用 pipe 但需自己实现服务）
java -jar idb-engine.jar --ipc pipe --pipe-name my-pipe

# 查看帮助
java -jar idb-engine.jar --help
```

### 8.3 与 Wails 集成（gRPC）

```go
import (
    "google.golang.org/grpc"
    "google.golang.org/grpc/credentials/insecure"
    pb "your/proto/gen"
)

cmd := exec.Command("java", "-jar", "idb-engine.jar", "--ipc", "tcp", "--port", "50051")
cmd.Dir = "/path/to/build/libs"
cmd.Start()

// TCP（默认）
conn, _ := grpc.Dial("localhost:50051", grpc.WithTransportCredentials(insecure.NewCredentials()))
defer conn.Close()

client := pb.NewIdbEngineClient(conn)
stream, _ := client.Handle(ctx, &pb.Request{
    Id:       "req-001",
    Category: pb.Category_TABLE,
    Action:   pb.Action_LIST,
    Connection: &pb.ConnectionConfig{
        Driver: "Mysql", Host: "127.0.0.1", Port: 3306,
        User: "root", Password: "secret", Database: "test",
    },
    Body: &pb.Request_TableRequest{
        TableRequest: &pb.TableRequest{
            Body: &pb.TableRequest_List{
                List: &pb.TableListRequest{},
            },
        },
    },
})
for {
    resp, err := stream.Recv()
    if err == io.EOF { break }
    // 处理 resp — 流式响应检查 resp.End
}
```

## 9. 实现状态 (Implementation Status)

✅ 已完成：
- 核心架构与通信协议（gRPC over HTTP/2 + Protobuf），支持流式响应
- 强类型 Request/Response envelopes（per-Category `oneof body`，12 + 12 = 24 个 typed 消息 + 3 个流式帧 + 1 个 terminal + 8 个 per-list-item 消息 + typed `Row` wrapper），13 个业务 handler 全部直接接收 typed proto 消息、返回 typed proto 消息；`TypedRequestMapper` / `TypedResponseMapper` 已删除，业务层无 `JsonObject` 边界映射
- 异步非阻塞处理（grpc-kotlin `IdbEngineCoroutineImplBase` + Kotlin 协程 `suspend handle()` → `Flow<Response>`）
- **业务层 Kotlin DSL end-to-end（v2.5）**：13 个 handler + `RequestDispatcher` + 11 个集成测试全部以 Kotlin DSL 形态编写（`xxxRequest { ... }` / `xxxResponse { ... }` / `xxxItem { ... }` / `request { ... }` / `response { ... }`）；`google.protobuf.Value` 因属 Well-Known Type 仍用 `Value.newBuilder()`（无生成 DSL）
- **gRPC 1.76 + protoc 工具链对齐（v2.5）**：`grpc-netty-shaded 1.76.0` + `grpc-kotlin-stub 1.4.1`，protoc 锁 `3.25.5` / `protoc-gen-grpc-java` 锁 `1.68.0` / `protoc-gen-grpc-kotlin` 锁 `1.4.1`，`protobuf-java` 强制 `3.25.8`（grpc-protobuf 1.76 传递依赖，不可强制升 4.x）
- **表驱动 RequestDispatcher（v2.6）**：9 个 `handleX` 函数 + 11 个 `wrapTypedResponse` `when` 分支 → 单个 typed `routes` map（`Pair<Category, Action>` → `Route{invoke, wrap}`）；新 (Category, Action) 仅需一个 map 条目；消除 `wrapTypedResponse` 中静默 `else -> {}` 兜底；`SQL.EXPLAIN` 路由打通（之前 `SqlEngineHandler.explain` 已实现但 dispatcher 从未路由）
- **跨切面 Envelope Options（v2.6）**：新增 `RequestOptions { trace_id, dry_run, timeout_ms }` 跨切面；MDC 注入 `trace_id`；`dryRun=true` + write action 直接短路返回 success（不修改数据库）；`timeoutMs > 0` 包 `withTimeoutOrNull`，超时返回 `success=false, error="timeout"`
- **`if_exists` / `if_not_exists` 贯通（v2.6）**：SCHEMA/TABLE/INDEX/FOREIGN_KEY 路径全部支持 `optional bool if_exists` / `optional bool if_not_exists`；方言层 SPI 与 handler 实现已贯通
- 数据库方言抽象层（DatabaseDialect SPI + MySQL/PostgreSQL/H2 插件，**所有 SPI 方法三个方言均完整实现**）
- 连接池管理（HikariCP + SHA-256 缓存，key 包含 password）
- 13 个 handler 全部使用 suspend 协程
- 流式大数据输出（DATA LIST pageSize=0 / SQL SELECT / DATA GENERATE / EXPORT，JDBC 游标防 OOM）
- 动态 JDBC 驱动加载（扫描 `drivers/` 目录，URLClassLoader + ServiceLoader）
- 方言插件化动态加载（Gradle 多模块 + SPI，`DialectLoader` 自动发现注册）
- 4 个新对象分类（VIEW / INDEX / FOREIGN_KEY / TRIGGER），独立 handler 路由
- 5 个新操作（RENAME / TRUNCATE / TEST_CONNECTION / SERVER_INFO / RUN_EXPORT）
- GET_DDL 返回建表语句（MySQL `SHOW CREATE TABLE` / PG 重建 / H2 重建）
- DATA LIST 支持 `where` / `orderBy` 原始 SQL 片段过滤与排序，方言级注入校验
- SCHEMA 两级导航（`level=database` / `level=schema`，PG/H2 支持两级）
- PostgreSQL 方言全面优化（listSchemas 两级查询 / listTables current_schemas / search_path 自动设置 / MODIFY_COLUMN 补齐 nullable/default / GET_DDL 含约束与索引 / 正则预编译）
- 用户管理完整 CRUD（CREATE / DELETE / 修改密码 / 查询指定用户权限 / 聚合 GRANTS），MySQL + PostgreSQL + H2 全实现
- 造数引擎（LuaJIT + Lua 5.1~5.5 + 多表按序造数 + 外键引用 `lastId()` + Lua 沙箱 + 流式进度）
- 函数与存储过程管理（LIST / INFO / GET_DDL / CREATE / DELETE / CALL / DEBUG / VALIDATE，**三个方言全部完整实现**，包括 TRIGGER 类型）
- 视图管理（LIST / CREATE / DELETE / GET_DDL）
- 索引管理（LIST / CREATE / DROP，支持 UNIQUE）
- 外键管理（LIST / CREATE / DROP，支持 CASCADE / SET NULL）
- 触发器管理（LIST / GET_DDL）
- 数据导出引擎（5 种格式：CSV / JSON Lines / SQL INSERT / Excel / Parquet，独立子进程隔离）
- 跨平台 IPC 传输抽象（IpcTransport SPI + TCP / UDS / Named Pipe 三实现 + Linux epoll native，CLI 参数切换）
- 191 个测试全通过，0 失败 / 0 错误：
  - `:dialect-h2:test` — H2 方言 63 测试
  - `:engine:test` — 128 测试
    - `pool/PoolManagerTest` — 11
    - `loader/DialectLoaderTest` — 5
    - `ipc/IpcConfigTest` — 20
    - `ipc/IpcTransportTest` — 7
    - `ipc/TcpIpcTransportIntegrationTest` — 1
    - `ipc/UnixSocketIpcTransportIntegrationTest` — 2（`@EnabledOnOs(LINUX, MAC, FREEBSD)`）
    - `ipc/NamedPipeIpcTransportIntegrationTest` — 2
    - `integration/*HandlerIntegrationTest` — 60（11 个 handler × H2Fixture，使用 typed proto Kotlin DSL builders）
    - `integration/TypedRequestEnvelopeIntegrationTest` — 7（端到端 typed envelope）
    - `integration/UserGrantsIntegrationTest` — 2（USER.GRANTS 路由，H2 限制场景）
    - `integration/DataGenerateIntegrationTest` — 2（DATA.GENERATE 流式进度 + 错误路径）
    - `integration/FunctionGetDdlIntegrationTest` — 2（FUNCTION.GET_DDL dispatcher 路由 + H2 限制）
    - `integration/SqlExplainRouteIntegrationTest` — 2（SQL.EXPLAIN 端到端路由，v2.6 之前未实现）
    - `integration/EnvelopeOptionsIntegrationTest` — 4（dryRun / timeoutMs envelope 跨切面）

⏳ 待扩展：
- GraalVM Native Image 编译
- 更多数据库方言插件（Oracle, SQL Server, SQLite — 实现 SPI 接口即可）
- 性能监控与指标上报
- Windows 命名管道服务端（需 JNA + Win32 CreateNamedPipe 自实现）

## 10. 架构升级历史 (Architecture Migration Log)

| 版本 | 通信协议 | 备注 |
|---|---|---|
| v1.0 | stdin/stdout + 4-byte BE uint32 长度前缀 + 自定义 kotlinx-serialization-protobuf | 旧版管道协议 |
| **v2.0** | **gRPC over HTTP/2 + 标准 google.protobuf.Value** | 替换为标准 gRPC；导出子进程同样切换为 gRPC；移除 stdin/stdout 依赖 |
| v2.1 | gRPC + 跨平台 IPC Transport SPI（TCP / UDS / Named Pipe） | 在 gRPC 之上抽象 `IpcTransport` 接口，默认 TCP；通过环境变量 `IDB_ENGINE_IPC` / `IDB_ENGINE_PORT` / `IDB_ENGINE_UDS_PATH` / `IDB_ENGINE_PIPE_NAME` 切换 UDS（Linux/macOS/BSD，Linux epoll native）或 Windows 命名管道；业务层零感知（v2.2 起环境变量入口已废弃，改为 CLI 参数） |
| v2.2 | gRPC + 强类型 Request/Response + CLI Args | `Request.payload` 与 `Response.data` 由 `map<string, Value>` 替换为 per-Category typed protobuf 消息（`oneof body` 路由）；IPC 选择由环境变量改为 CLI 参数（`--ipc` / `--port` / `--uds-path` / `--pipe-name` / `--help`）；`TypedRequestMapper` / `TypedResponseMapper` 在 dispatcher 边界做 typed proto ↔ JsonObject 转换 |
| v2.3 | gRPC + 强类型 Handlers end-to-end | 13 个业务 handler 全部接收 typed per-Category proto 消息、返回 typed `<Category><Action>Response` 消息；`TypedRequestMapper` / `TypedResponseMapper` 删除（67 个对应测试一并删除）；`RequestDispatcher` 简化为 (Category, Action) → handler 的薄路由层；11 个 handler 集成测试改用 typed proto builders；179 个测试全通过（116 engine + 63 H2） |
| v2.4 | gRPC + 强类型 per-list-item 消息 | 8 个 typed per-list-item 消息（`TableListItem` / `ViewListItem` / `IndexListItem` / `ForeignKeyListItem` / `TriggerListItem` / `FunctionListItem` / `FunctionDebugItem` / `UserGrantItem`）取代 list/response 中遗留的 `repeated google.protobuf.Value`；动态行使用 typed `Row { map<string, Value> values }` wrapper；方言差异显著的 item shape（USER.LIST overloaded、FUNCTION.CALL/INFO、SYSTEM.SERVER_INFO extras）保留 `google.protobuf.Value`；11 个 handler 集成测试改用 typed accessor（`item.name` 替代 `item.structValue.fieldsMap["name"]?.stringValue`）；179 个测试全通过 |
| v2.5 | gRPC 1.76 + grpc-kotlin 协程服务端 + Kotlin DSL end-to-end | gRPC 依赖 `1.68.0` → `1.76.0`，接入 `grpc-kotlin-stub 1.4.1`；服务端 `IdbEngineImpl : IdbEngineGrpcKt.IdbEngineCoroutineImplBase()`（suspend `handle()` → `Flow<Response>`）；protoc 工具链锁定 `protoc 3.25.5` + `protoc-gen-grpc-java 1.68.0` + `protoc-gen-grpc-kotlin 1.4.1`；启用 `protobuf-kotlin-lite` 生成 Kotlin DSL（`xxxRequest { ... }` / `xxxResponse { ... }` / `xxxItem { ... }` / `request { ... }` / `response { ... }`）；13 个 handler + `RequestDispatcher` + 11 个集成测试全部切到 DSL，业务层无 `Request.newBuilder()...build()` 残留；移除 `grpc-core` / `protobuf-java-util` / `ksp` / `kotlinx-serialization-protobuf` 等无用依赖；179 个测试全通过 |
| **v2.6 (当前)** | **表驱动 Dispatcher + 跨切面 Envelope Options** | `RequestDispatcher` 重构：9 个 `handleX` 函数 + 11 个 `wrapTypedResponse` `when` 分支 → 单个 typed `routes` map（`Pair<Category, Action>` → `Route{invoke, wrap}`）；新 (Category, Action) 仅需一个 map 条目；消除 `wrapTypedResponse` 中静默 `else -> {}` 兜底；`SQL.EXPLAIN` 路由打通（之前 `SqlEngineHandler.explain` 已实现但 dispatcher 从未路由）。新增 `RequestOptions { trace_id, dry_run, timeout_ms }`：MDC 注入 `trace_id`；`dryRun=true` + write action 短路返回 success（不修改数据库）；`timeoutMs > 0` 用 `withTimeoutOrNull` 包 handler 调用，超时返回 `success=false, error="timeout"`。`if_exists` / `if_not_exists` 在 SCHEMA/TABLE/INDEX/FOREIGN_KEY 路径下贯通（v2.6 之前仅 VIEW/FUNCTION.DELETE 支持）。191 测试全通过（128 engine + 63 H2） |
