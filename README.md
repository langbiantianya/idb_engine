# IDB Engine — 数据库管理后端引擎

一个使用 Kotlin 编写的**无头**、**跨平台**的数据库管理引擎。通过标准的 **gRPC** 接口（HTTP/2 + 强类型 per-Category protobuf 消息）和可配置的 **IPC 传输层**（TCP loopback / Unix Domain Socket / Windows Named Pipe）暴露能力。设计为内嵌在 Wails（Go）宿主进程中运行，目前已实现 **5 个** 可插拔方言：MySQL、PostgreSQL、H2、DuckDB、**SQLite（v2.8 新增）**。

> **当前版本：v2.8**
> - gRPC 服务端默认监听 `:50051`（可通过 `--port <int>` 覆盖）
> - IPC 传输方式通过 CLI 参数 `--ipc <tcp|unix|pipe>` 选择（默认按操作系统自动检测）
> - 可插拔方言架构（实现 `DatabaseDialect` 接口，将 JAR 放入 `dialects/` 目录即可注册新数据库）
> - **gRPC 1.83 + grpc-kotlin 协程服务端** —— 服务端继承 `IdbEngineCoroutineImplBase`（`suspend handle()` → `Flow<Response>`）；proto 工具链锁定：`protoc 3.25.5` + `protoc-gen-grpc-java 1.68.0` + `protoc-gen-grpc-kotlin 1.4.1`；`protobuf-kotlin-lite` 4.35.1
> - **端到端强类型 Handler** —— 无 `JsonObject` 编解码；dispatcher 将 typed per-Category proto 路由到 typed handler 方法，返回 typed `<Category><Action>Response` 消息
> - **业务层 Kotlin DSL（v2.5）** —— 13 个 handler + `RequestDispatcher` + 11 个集成测试全部使用 protoc-gen-grpc-kotlin + protobuf-kotlin-lite 生成的 DSL builder（`xxxRequest { ... }` / `xxxResponse { ... }` / `xxxItem { ... }` / `request { ... }` / `response { ... }`）；仅 `google.protobuf.Value`（Well-Known Type，无生成 DSL）仍使用 `Value.newBuilder()`
> - **强类型列表项 envelope** —— `TableListItem` / `ViewListItem` / `IndexListItem` / `ForeignKeyListItem` / `TriggerListItem` / `FunctionListItem` / `FunctionDebugItem` / `UserGrantItem` / typed `Row` 包装动态行；仅真正方言差异显著的 item shape（USER.LIST 重载、FUNCTION.CALL/INFO、SYSTEM.SERVER_INFO extras）保留 `google.protobuf.Value`
> - 376 个测试全通过（170 engine + 62 SQLite + 81 DuckDB + 63 H2 dialect；1 个 Windows-only `IpcConfigTest` 用例跳过）
> - **跨切面请求选项（v2.6）** —— `Request.options { traceId, dryRun, timeoutMs }` 统一应用到所有 (Category, Action) 路由；`traceId` 通过 SLF4J MDC 透传；`dryRun=true` 时 write action 直接短路返回 success（不调用 handler、不修改数据库）；`timeoutMs > 0` 时用 `withTimeoutOrNull` 包 handler 调用，超时返回 `success=false, error="timeout"`
> - **表驱动 dispatcher（v2.6）** —— `RequestDispatcher` 用单个 typed `routes` map（`Pair<Category, Action>` → `Route{invoke, wrap}`）替代原来的 9 个 `handleX` 函数 + 11 个 `wrapTypedResponse` `when` 分支；新增 (Category, Action) 仅需一个 map 条目；消除静默 `else -> {}` 兜底（不可能走到 —— 表查不到时直接抛 `UnsupportedOperationException`）；`SQL.EXPLAIN` 路由打通（之前 handler 存在但 dispatcher 从未路由）
> - **DuckDB 方言（v2.7）** —— 第 4 个方言插件，面向**本地嵌入式 OLAP** 场景（内存 / `.duckdb` / `.csv` / `.parquet` / `.json` / `.xlsx` via POI 预转换）；driver 名 `Duckdb`（JDBC `org.duckdb.DuckDBDriver` 1.5.5.1）；`host`/`port` 完全忽略，`database` 字段即路径；自增主键走 `SEQUENCE + DEFAULT nextval + 表级 PRIMARY KEY`（DuckDB 拒绝 `IDENTITY + 表级 PK` 组合，也不支持 `INTEGER PRIMARY KEY` ROWID 自填充）；FK 走 table-rebuild（DuckDB 无 `ALTER TABLE ADD/DROP CONSTRAINT`）；USER / PRIVILEGE / TRIGGER 抛 `UnsupportedOperationException`
> - **SQLite 方言（v2.8）** —— 第 5 个方言插件，面向**本地嵌入式关系型**场景（`:memory:` / `.db` 文件）；driver 名 `Sqlite`（JDBC `org.sqlite.JDBC` 3.46.1.3）；`host`/`port`/`user`/`password` 全部忽略，`database` 字段即路径；自增主键走 inline `INTEGER PRIMARY KEY AUTOINCREMENT`（`TableHandler.create` 检测到自增 PK 时跳过表级 `PRIMARY KEY` 子句，避免 SQLite "more than one primary key"）；FK 走 table-rebuild（SQLite 无 `ALTER TABLE ADD/DROP CONSTRAINT`）；`MODIFY_COLUMN` 仅支持 RENAME（无 ALTER COLUMN）；`TRUNCATE` 用 `DELETE FROM` + `sqlite_sequence` 重置模拟；多 database 走 `ATTACH/DETACH`；USER / PRIVILEGE / TRIGGER / FUNCTION（routines 概念）抛 `UnsupportedOperationException`
> - **SPI 连接元数据扩展（v2.8）** —— `DatabaseDialect` 新增 11 个属性（`displayName` / `connectionType` / `requiresHost` / `requiresPort` / `defaultPort` / `supportsUser` / `supportsPassword` / `supportsSchema` / `supportsCrossDatabase` / `jdbcUrlExample` / `capabilities`），全部带默认实现（**完全向后兼容**）；新增 `ConnectionType` 枚举（`CLIENT_SERVER` / `EMBEDDED` / `FILE_BASED` / `IN_MEMORY`）+ `DialectCapability` 枚举（12 个能力标签）；5 个方言全部声明各自元数据
> - **`SYSTEM.LIST_DRIVERS` action（v2.8）** —— 新增 action 18 枚举所有已加载方言，返回 `repeated DialectInfo`（`driver_name` / `display_name` / `jdbc_driver_class_name` / `jdbc_url_example` / `connection_type` / `requires_host` / `requires_port` / `default_port` / `supports_user` / `supports_password` / `supports_schema` / `supports_cross_database` / `capabilities[]`），供前端**动态渲染"新建连接"表单**而无需硬编码 driver/port/user 需求；`DialectLoader.getAllDialects()` 提供枚举入口；返回顺序按 `driverName` 字典序升序

---

## 项目结构

```
idb_engine/
├── api/                  公共 SPI 接口（DatabaseDialect + ConnectionType + DialectCapability，v2.8）
├── dialect-mysql/        MySQL 方言插件 JAR
├── dialect-postgresql/   PostgreSQL 方言插件 JAR
├── dialect-h2/           H2 方言插件 JAR（嵌入式数据库 + 测试）
├── dialect-duckdb/       DuckDB 方言插件 JAR（v2.7 新增 — 本地嵌入式 OLAP）
├── dialect-sqlite/       SQLite 方言插件 JAR（v2.8 新增 — 本地嵌入式关系型）
└── engine/               主引擎
    ├── proto/            idb_engine.proto（gRPC service + message schemas；含 DialectInfo + Action.LIST_DRIVERS，v2.8）
    ├── server/           gRPC 服务端（IdbEngineServer + IdbEngineImpl）
    ├── ipc/              跨平台 IPC 传输 SPI（TCP / UDS / Named Pipe）
    ├── dispatcher/       Request → handler 路由（按 Category.Action 分发）
    ├── pool/             HikariCP 连接池管理（SHA-256 key 缓存）
    ├── export/           数据导出（独立 JVM 子进程）
    ├── handlers/         13 个业务 handler
    └── loader/           ServiceLoader 动态加载 drivers/ + dialects/
```

---

## 构建与运行

### 构建

```bash
./gradlew engine:jar
```

产物位于 `engine/build/libs/`：
```
idb-engine.jar          主引擎瘦包（Main-Class: com.kxxnzstdsw.MainKt）
libs/                   运行时依赖（gRPC、HikariCP、LuaJIT、POI、Parquet…）
drivers/                JDBC 驱动（mysql-connector-j / postgresql / h2 / duckdb_jdbc / sqlite-jdbc）
dialects/               方言插件（idb-dialect-{mysql,postgresql,h2,duckdb,sqlite}.jar，5 个 SPI 动态加载）
```

### 运行

```bash
# TCP loopback（默认 :50051，POSIX 上自动 fallback 到 unix）
cd engine/build/libs && java -jar idb-engine.jar

# 显式指定 TCP 端口
java -jar idb-engine.jar --ipc tcp --port 60000

# 显式指定 Unix Domain Socket（路径可换）
java -jar idb-engine.jar --ipc unix --uds-path /run/idb/engine.sock

# Windows 命名管道（客户端需 --ipc=pipe；服务端在 Windows 上 grpc-java 暂未开放公共 API，详见 CLAUDE.md §3.6）
java -jar idb-engine.jar --ipc pipe --pipe-name idb-engine

# 打印完整 CLI 帮助
java -jar idb-engine.jar --help
```

---

## 通信协议（gRPC + IPC Transport）

引擎是 **gRPC 服务端**，通过 IPC Transport 抽象层接收调用方的连接：

```proto
service IdbEngine {
  rpc Handle(Request) returns (stream Response);
}
```

`Handle` 是服务端流式 RPC：客户端发送一条 `Request`，服务端按需返回 1..N 条 `Response`。

### IPC 传输方式

| `--ipc` | 传输 | 平台 | 说明 |
|---|---|---|---|
| `tcp`（默认） | TCP loopback `localhost:<port>` | 全平台 | 生产路径；`--port` 控制端口（默认 50051） |
| `unix` | Unix Domain Socket | Linux / macOS / BSD | Linux 用 epoll native，macOS/BSD 用 NIO；UDS 文件权限 `rw-------`；默认路径 `/tmp/idb-engine.sock` |
| `pipe` | Windows 命名管道 | Windows | 客户端可用；grpc-java 1.76 无 server-side API，`serverBuilder()` 抛 `UnsupportedOperationException`；管道名默认 `idb-engine` |

**自动检测**：`--ipc` 缺省时，Windows → `pipe`，POSIX → `unix`。

**所有 CLI 选项**：
```
--ipc <tcp|unix|pipe>     IPC 传输（默认自动检测）
--port <int>              TCP 端口（默认 50051，仅 tcp 模式生效）
--uds-path <path>         UDS 文件路径（默认 /tmp/idb-engine.sock）
--pipe-name <name>        命名管道名称（默认 idb-engine；需匹配 ^[A-Za-z0-9_.-]{1,64}$）
--help / -h               打印 CLI 用法并退出
```

解析失败抛 `IllegalStateException`（启动入口打印到 stderr，`exitProcess(2)`）。

### Go 端连接示例

```go
import (
    "google.golang.org/grpc"
    "google.golang.org/grpc/credentials/insecure"
    pb "your/proto/gen"  // 由 idb_engine.proto 生成
)

// TCP（默认）
conn, _ := grpc.Dial("localhost:50051", grpc.WithTransportCredentials(insecure.NewCredentials()))
defer conn.Close()

// UDS（POSIX）
// conn, _ := grpc.Dial("unix:///run/idb/idb-engine.sock",
//     grpc.WithTransportCredentials(insecure.NewCredentials()))

// Windows Named Pipe
// conn, _ := grpc.Dial("pipe:idb-engine",
//     grpc.WithTransportCredentials(insecure.NewCredentials()))

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
                List: &pb.TableListRequest{Schema: "public"},
            },
        },
    },
})
for {
    resp, err := stream.Recv()
    if err == io.EOF { break }
    // 处理 resp — 流式响应检查 resp.End
    // typed body 用 switch resp.GetBody().(type) 分发到 12 个 Category
}
```

### 请求格式

`Request` envelope（`id` / `category` / `action` / `connection` + `oneof body` 路由到 12 个 Category 强类型消息）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 请求唯一 ID |
| `category` | enum | 12 个分类（详见下表） |
| `action` | enum | 18 个操作（详见下表） |
| `connection` | ConnectionConfig | 连接凭证（`driver`/`host`/`port`/`user`/`password`/`database`/`schema`/`use_ssl`/`properties`）；`driver` 可为 `Mysql` / `Postgresql` / `H2` / `Duckdb` / `Sqlite` |
| `body` | `oneof` | 12 个 Category 强类型 message（`system_request` / `schema_request` / `user_request` / `table_request` / `data_request` / `sql_request` / `function_request` / `view_request` / `index_request` / `foreign_key_request` / `trigger_request` / `export_request`） |

每个 Category 消息内部也用 `oneof` 按 Action 派发（如 `TableRequest` → `list` / `column_list` / `create` / `update` / `get_ddl` / `rename` / `delete` / `truncate`）。每个 Action 子消息字段为 snake_case（protobuf 生成 camelCase getter）。`ColumnDef`、`GenerateTable` 等跨 Action 共享类型在顶层定义。

**Category 枚举**：`SCHEMA` / `USER` / `TABLE` / `DATA` / `SQL` / `SYSTEM` / `FUNCTION` / `EXPORT` / `VIEW` / `INDEX` / `FOREIGN_KEY` / `TRIGGER`

**Action 枚举**：`LIST` / `CREATE` / `UPDATE` / `DELETE` / `EXECUTE` / `GET_DDL` / `INFO` / `GRANTS` / `GENERATE` / `DEBUG` / `CALL` / `RUN_EXPORT` / `RENAME` / `TRUNCATE` / `TEST_CONNECTION` / `SERVER_INFO` / `LIST_DRIVERS`

> **重要**：`Action.EXPORT` 在 proto3 中与 `Category.EXPORT` 命名冲突，因此导出请求使用 **`Action.RUN_EXPORT`**（也是 `EXPORT` category 的唯一合法 action）。

### 响应格式

`Response` envelope（`id` / `success` / `error` / `stream` / `end` + `oneof body` 路由到 12 个 Category 强类型消息 + 4 个流式帧类型）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 对应请求 ID |
| `success` | bool | 是否成功 |
| `error` | string | 错误信息；空串表示无错误 |
| `stream` | bool | 流式响应标记 |
| `end` | bool | 流式结束标记 |
| `body` | `oneof` | 12 个 Category 强类型 message（`schema` / `user` / `table` / `data` / `sql` / `system` / `function` / `view` / `index` / `foreign_key` / `trigger` / `export`） + 4 个流式帧（`data_row_frame` / `sql_row_frame` / `gen_progress_frame`） + `generate_terminal` |

每个 Category 消息内部也用 `oneof` 按 Action 派发。**v2.4 起**，列表项已用强类型 per-item proto 替代 `repeated google.protobuf.Value`（`TableListItem` / `ViewListItem` / `IndexListItem` / `ForeignKeyListItem` / `TriggerListItem` / `FunctionListItem` / `FunctionDebugItem` / `UserGrantItem`），动态行使用 typed `Row { map<string, Value> values }` wrapper；仅确实按方言变化的 item shape（USER.LIST 在 MySQL 是 `{user, host}`、PG 是 `{user}`）与 dialect-specific extras（如 SYSTEM.SERVER_INFO 方言扩展、FUNCTION.CALL/INFO 返回）保留 `Value`。

**Handler 调用契约**：13 个业务 handler 全部直接接收 typed per-Category proto 消息、返回 typed per-Action proto 消息；`RequestDispatcher` 是 (Category, Action) → handler 的薄路由层，handler 返回的 typed 消息被装入 `Response.body` 对应 oneof 分支。**无 `JsonObject` 边界映射**。

---

## Handler 路由矩阵

| Category \ Action | LIST | CREATE | UPDATE | DELETE | EXECUTE | EXPLAIN | GET_DDL | INFO | GRANTS | GENERATE | DEBUG | CALL | RUN_EXPORT | RENAME | TRUNCATE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCHEMA      | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — | — | — | — |
| USER        | ✓ | ✓ | ✓ | ✓ | — | — | — | — | ✓ | — | — | — | — | — | — |
| TABLE       | ✓ | ✓ | ✓ | ✓ | — | — | ✓ | — | — | — | — | — | — | ✓ | ✓ |
| DATA        | ✓ | ✓ | ✓ | ✓ | — | — | — | — | — | ✓ | — | — | — | — | — |
| SQL         | — | — | — | — | ✓ | ✓ | — | — | — | — | — | — | — | — | — |
| SYSTEM      | — | — | — | — | — | — | — | ✓ | — | — | — | — | — | — | — |
| FUNCTION    | ✓ | ✓ | ✓ | ✓ | — | — | ✓ | ✓ | — | — | ✓ | ✓ | — | — | — |
| EXPORT      | — | — | — | — | — | — | — | — | — | — | — | — | ✓ | — | — |
| VIEW        | ✓ | ✓ | — | ✓ | — | — | ✓ | — | — | — | — | — | — | — | — |
| INDEX       | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — | — | — | — |
| FOREIGN_KEY | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — | — | — | — |
| TRIGGER     | ✓ | — | — | — | — | — | ✓ | — | — | — | — | — | — | — | — |

SYSTEM 还支持 `TEST_CONNECTION` / `SERVER_INFO` / **`LIST_DRIVERS`**（v2.8 新增 — 表中未列出）。

---

## API 参考

> 下方 `payload.data` 字段均为业务层逻辑结构（JSON 形式便于阅读），实际 wire 是 `google.protobuf.Value` 的二进制编码。所有 `Response.data` 默认 `success=true`；失败响应使用 `success=false, error=<message>, data=null` 模式。

### SCHEMA — 架构管理

#### LIST — 两级导航

```json
// 列出所有 database（默认 level="database"）
{
  "id": "r1", "category": "SCHEMA", "action": "LIST",
  "connection": {"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},
  "payload": {}
}
// Response data: {"level":"database", "items":["information_schema","mysql","test_db"]}

// 列出 PG 某 database 下的 schema（必须传 database）
{
  "id": "r1b", "category": "SCHEMA", "action": "LIST",
  "connection": {"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"postgres"},
  "payload": {"level":"schema", "database":"my_app_db"}
}
// Response data: {"level":"schema", "database":"my_app_db", "items":["public","myschema"]}
```

| 方言 | level=database | level=schema |
|---|---|---|
| MySQL | `SHOW DATABASES` 过滤系统库 | 单元素 `[database]` |
| PostgreSQL | `pg_database WHERE NOT datistemplate` | `pg_namespace` 过滤 `pg_%` / `information_schema` |
| H2 | `[conn.catalog]` 单元素 | `INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?` |
| DuckDB | 文件路径 / `memory` / 单元素 | `information_schema.schemata` |
| SQLite | 单元素（`database` 路径或 `:memory:`） | 单元素（`main`） |

#### CREATE / DELETE

```json
// CREATE
{"id":"r2","category":"SCHEMA","action":"CREATE","connection":{...},"payload":{"name":"new_db","options":{"charset":"utf8mb4","collate":"utf8mb4_unicode_ci"}}}
// Response data: {"created":"new_db"}

// DELETE
{"id":"r3","category":"SCHEMA","action":"DELETE","connection":{...},"payload":{"name":"old_db"}}
// Response data: {"deleted":"old_db"}
```

`options` 仅 MySQL 支持（`charset` / `collate`）；PG / H2 忽略。

---

### USER — 用户权限管理

#### LIST — 用户列表 / 用户权限

```json
// 用户列表
{"id":"r4","category":"USER","action":"LIST","connection":{"driver":"Mysql",...},"payload":{}}
// Response (MySQL): [{"user":"root","host":"localhost"},{"user":"app_user","host":"%"}]
// Response (PG):     [{"user":"postgres"},{"user":"app_user"}]

// 指定用户权限（payload 含 user 时路由）
{"id":"r4b","category":"USER","action":"LIST","connection":{"driver":"Mysql",...},"payload":{"user":"dev","host":"%"}}
// Response (MySQL): [{"grant":"GRANT SELECT ON `test_db`.* TO 'dev'@'%'"}, ...]
// Response (PG):     [{"schema":"public","table":"users","privilege":"SELECT"}, ...]
```

#### GRANTS — 聚合表级授权

```json
{"id":"r5","category":"USER","action":"GRANTS","connection":{...},"payload":{"user":"dev","host":"%"}}
// Response: [{"schema":"test_db","table":"users","privileges":"SELECT, INSERT"}, ...]
```

#### CREATE / DELETE

```json
// CREATE
{"id":"r6","category":"USER","action":"CREATE","connection":{"driver":"Mysql",...},"payload":{"user":"new_user","password":"secret123","host":"%"}}
// Response: {"created":"new_user"}

// DELETE
{"id":"r6b","category":"USER","action":"DELETE","connection":{...},"payload":{"user":"old_user","host":"%"}}
// Response: {"deleted":"old_user"}
```

#### UPDATE — 修改密码 / 授权

```json
// 修改密码（payload 含 password 但无 privileges）
{"id":"r7","category":"USER","action":"UPDATE","connection":{"driver":"Mysql",...},"payload":{"user":"dev","password":"new_secret","host":"%"}}
// Response: {"user":"dev","action":"password_changed"}

// 授权（payload 含 privileges + isGrant=true）
{"id":"r8","category":"USER","action":"UPDATE","connection":{"driver":"Mysql",...},"payload":{"user":"dev","schema":"my_app_db","privileges":["SELECT","INSERT"],"isGrant":true,"tableName":"users","withGrantOption":false}}
// Response: {"user":"dev","schema":"my_app_db","table":"users","withGrantOption":false,"action":"granted"}

// 回收（isGrant=false）
{"id":"r8b","category":"USER","action":"UPDATE","connection":{...},"payload":{"user":"dev","schema":"my_app_db","privileges":["DELETE"],"isGrant":false}}
// Response: {"user":"dev","schema":"my_app_db","action":"revoked"}
```

---

### TABLE — 表结构元数据

#### LIST — 表列表 / 列列表

```json
// 表列表
{"id":"r9","category":"TABLE","action":"LIST","connection":{...},"payload":{}}
{"id":"r9","category":"TABLE","action":"LIST","connection":{"driver":"Postgresql",...},"payload":{"schema":"public"}}
// Response: [{"name":"users","type":"TABLE"},{"name":"orders","type":"TABLE"}]

// 列列表（payload 含 tableName 时自动路由）
{"id":"r10","category":"TABLE","action":"LIST","connection":{...},"payload":{"tableName":"users"}}
// Response: [
//   {"name":"id","type":"INT","size":10,"nullable":false,"isPrimaryKey":true,"defaultValue":null},
//   {"name":"name","type":"VARCHAR","size":255,"nullable":true,"isPrimaryKey":false,"defaultValue":null}
// ]
```

#### CREATE

```json
{
  "id": "r11", "category": "TABLE", "action": "CREATE",
  "connection": {"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},
  "payload": {
    "tableName": "products",
    "columns": [
      {"name":"id","type":"INT","nullable":false,"isPrimaryKey":true,"autoIncrement":true},
      {"name":"name","type":"VARCHAR","size":255,"nullable":false},
      {"name":"price","type":"DECIMAL","nullable":true,"defaultValue":"0.00"},
      {"name":"created_at","type":"TIMESTAMP","nullable":true,"defaultValue":"CURRENT_TIMESTAMP"}
    ],
    "options": {"engine":"InnoDB","charset":"utf8mb4","collate":"utf8mb4_unicode_ci","comment":"商品表"}
  }
}
// Response: {"created":"products"}
```

**列定义字段**：`name` (必填)、`type` (必填)、`size`、`nullable` (默认 `true`)、`isPrimaryKey` (默认 `false`)、`defaultValue`、`autoIncrement` (默认 `false`)

**表选项**：`options` 支持 MySQL `engine`/`charset`/`collate`/`comment`；PG 仅 `comment`（走 `COMMENT ON TABLE`）。

#### UPDATE — ADD_COLUMN / DROP_COLUMN / MODIFY_COLUMN

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

#### GET_DDL / DELETE / RENAME / TRUNCATE

```json
// GET_DDL
{"id":"r14","category":"TABLE","action":"GET_DDL","connection":{...},"payload":{"tableName":"users"}}
// Response data: "CREATE TABLE `users` (\n  `id` INT NOT NULL,\n  `name` VARCHAR(255),\n  PRIMARY KEY (`id`)\n)"  (string)

// DELETE
{"id":"r15","category":"TABLE","action":"DELETE","connection":{...},"payload":{"tableName":"old_table"}}
// Response: {"deleted":"old_table"}

// RENAME（oldName 可用 tableName 作为别名）
{"id":"r16","category":"TABLE","action":"RENAME","connection":{...},"payload":{"oldName":"users","newName":"users_new"}}
// Response: {"renamed":"users","newName":"users_new"}

// TRUNCATE
{"id":"r17","category":"TABLE","action":"TRUNCATE","connection":{...},"payload":{"tableName":"users"}}
// Response: {"truncated":"users"}
```

---

### DATA — 表数据 CRUD

#### LIST — 分页 / 流式

```json
// 分页查询（默认 page=1, pageSize=50）
{"id":"r18","category":"DATA","action":"LIST","connection":{...},"payload":{"tableName":"users","page":1,"pageSize":20}}
// Response data: {"total":120,"page":1,"pageSize":50,"rows":[{"id":"1","name":"Alice","avatar":"[LOB Data]"}, ...]}

// 带过滤与排序（原始 SQL 片段，方言层注入校验）
{"id":"r18b","category":"DATA","action":"LIST","connection":{...},"payload":{"tableName":"users","page":1,"pageSize":20,"where":"age > 18 AND name LIKE '%Alice%'","orderBy":"created_at DESC"}}

// 流式全量查询（pageSize: 0 触发 JDBC 游标模式，PG 端临时 autoCommit=false）
{"id":"r18c","category":"DATA","action":"LIST","connection":{...},"payload":{"tableName":"users","pageSize":0}}
// 流式响应：
// {"id":"r18c","success":true,"stream":true,"end":false,"data":{"total":1000,"page":0,"pageSize":1,"rows":[{...}]}}
// {"id":"r18c","success":true,"stream":true,"end":false,"data":{"total":1000,"page":0,"pageSize":1,"rows":[{...}]}}
// ...
// {"id":"r18c","success":true,"stream":true,"end":true,"data":null}
```

**LOB 列**：`BLOB` / `LONGTEXT` / `BYTEA` / `TEXT` 一律返回 `"[LOB Data]"`。

**`where` / `orderBy` 注入校验**：
- 通用：去除引号内容后禁止 `;` / `--` / `/*` 注释；禁止引号外出现 `INSERT/UPDATE/DELETE/DROP/UNION/EXEC/CREATE/ALTER/GRANT/REVOKE/TRUNCATE`
- MySQL：`ORDER BY` 标识符允许反引号
- PostgreSQL：`ORDER BY` 标识符允许双引号；额外禁止 `COPY` / `DO`

#### CREATE / UPDATE / DELETE

```json
// 插入
{"id":"r19","category":"DATA","action":"CREATE","connection":{...},"payload":{"tableName":"users","values":{"name":"Charlie","email":"charlie@example.com"}}}
// Response: {"affectedRows":1}

// 更新
{"id":"r20","category":"DATA","action":"UPDATE","connection":{...},"payload":{"tableName":"users","changes":{"name":"Alex","email":"alex@example.com"},"where":{"id":"1"}}}
// Response: {"affectedRows":1}

// 删除
{"id":"r21","category":"DATA","action":"DELETE","connection":{...},"payload":{"tableName":"users","where":{"id":"1"}}}
// Response: {"affectedRows":1}
```

所有 `values` / `changes` / `where` 值以字符串形式通过 `PreparedStatement` 绑定；列类型由方言层解析后 dispatch 到 `setLong` / `setDouble` / `setBoolean` / `setDate` / `setTime` / `setTimestamp` / `setBytes` / `setString`。

---

### SQL — 原生 SQL 引擎

```json
// 查询（流式）
{"id":"r22","category":"SQL","action":"EXECUTE","connection":{"driver":"Mysql",...},"payload":{"sql":"SELECT id, name FROM users WHERE id > 10 LIMIT 5"}}
// 流式响应（每行一帧，total: -1 表示无法预知总行数）：
// {"id":"r22","success":true,"stream":true,"end":false,"data":{"total":-1,"page":0,"pageSize":1,"rows":[{"id":"11","name":"Dave"}]}}
// ...

// PG 带 schema 上下文（自动 SET search_path TO <schema>）
{"id":"r22b","category":"SQL","action":"EXECUTE","connection":{"driver":"Postgresql",...},"payload":{"sql":"SELECT * FROM users LIMIT 5","schema":"public"}}

// 更新 / DDL（非流式）
{"id":"r23","category":"SQL","action":"EXECUTE","connection":{...},"payload":{"sql":"UPDATE users SET name = 'Frank' WHERE id = 3"}}
// Response: {"affectedRows":1}
```

> `Action.EXPLAIN` 已在 proto 中定义，**v2.6 起已由 dispatcher 路由**；可走 `SQL.EXPLAIN` 或 `SQL.EXECUTE` 直接提交 `EXPLAIN <sql>`。

---

### SYSTEM — 系统信息

#### INFO — JVM 运行时信息（无需连接，connection 字段被忽略）

```json
{"id":"r24","category":"SYSTEM","action":"INFO","connection":{"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{}}
// Response:
// {
//   "jvmVersion": "21.0.2", "jvmVendor": "...", "jvmName": "...",
//   "osName": "...", "osArch": "amd64", "osVersion": "...",
//   "availableProcessors": 16,
//   "memory": {"max":4294967296,"total":268435456,"used":134217728,"free":134217728},
//   "uptime": 120000, "pid": 12345
// }
```

`memory` 各字段单位为字节。

#### TEST_CONNECTION — 测试连接（失败也返回 `ok=false`，不抛异常）

```json
{"id":"r25","category":"SYSTEM","action":"TEST_CONNECTION","connection":{"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{}}
// Success: {"ok":true,"driver":"Mysql","host":"localhost","port":3306,"database":"mysql"}
// Failure: {"ok":false,"error":"Communications link failure..."}
```

#### SERVER_INFO — 数据库服务器信息

```json
{"id":"r26","category":"SYSTEM","action":"SERVER_INFO","connection":{"driver":"Postgresql",...},"payload":{}}
// Response (PG):     {"version":"PostgreSQL 16.0 ...","current_database":"myapp_db",...}
// Response (MySQL):  {"version":"8.0.36","catalog":"def",...}
// Response (H2):     {"version":"2.3.232","mode":"REGULAR",...}
// Response (DuckDB): {"version":"v1.5.5","mode":"embedded",...}
// Response (SQLite): {"version":"3.46.1","mode":"embedded","product":"SQLite",...}
```

#### LIST_DRIVERS — 枚举已加载方言的连接元数据（v2.8 新增，无需 connection）

返回 `DialectLoader` 中所有已加载方言的连接元数据，**供前端动态渲染"新建连接"表单**——不需要硬编码 driver / port / 是否需要 user 等信息。

```json
{"id":"r26b","category":"SYSTEM","action":"LIST_DRIVERS","connection":{},"payload":{}}
// Response data: { items: [ DialectInfo, DialectInfo, ... ] }
```

`DialectInfo` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `driver_name` | string | 后端 driver 名（如 `"Mysql"` / `"Sqlite"`），用于 `ConnectionConfig.driver` |
| `display_name` | string | 前端展示用名（如 `"MySQL"` / `"SQLite (Embedded)"`） |
| `jdbc_driver_class_name` | string | JDBC driver 类全名（如 `"com.mysql.cj.jdbc.Driver"`） |
| `jdbc_url_example` | string | 示例 JDBC URL，用于前端 placeholder |
| `connection_type` | string | `CLIENT_SERVER` / `EMBEDDED` / `FILE_BASED` / `IN_MEMORY` |
| `requires_host` | bool | 是否需要 `host` 字段 |
| `requires_port` | bool | 是否需要 `port` 字段 |
| `default_port` | int32 | 默认端口（0 = 无） |
| `supports_user` | bool | 是否需要 `user` / `password` |
| `supports_password` | bool | 是否需要 `password` |
| `supports_schema` | bool | 是否需要 `schema` 字段（PG/H2 = true） |
| `supports_cross_database` | bool | 是否支持多 database 切换（PG = true） |
| `capabilities` | repeated string | 方言能力标签（`USERS` / `VIEWS` / `INDEXES` / `ROUTINES` / `TRIGGERS` / `FOREIGN_KEYS` / `EXPORT` / `EMBEDDED_MODE` / `MULTI_SCHEMA` / `CROSS_DATABASE` / `DDL_TRANSACTION` / `PRIVILEGES`） |

**5 个方言元数据快照**（按 `driverName` 字典序升序）：

| driver_name | display_name | connection_type | default_port | requiresHost | supportsUser | supportsSchema | 关键 capabilities |
|---|---|---|---|---|---|---|---|
| `Duckdb` | `DuckDB (Embedded OLAP)` | `EMBEDDED` | 0 | ✗ | ✗ | ✓ | `VIEWS, INDEXES, FOREIGN_KEYS, MULTI_SCHEMA, EXPORT, EMBEDDED_MODE` |
| `H2` | `H2 (In-Memory)` | `IN_MEMORY` | 0 | ✗ | ✗ | ✓ | `+ EMBEDDED_MODE` (无 `USERS`/`TRIGGERS`) |
| `Mysql` | `MySQL` | `CLIENT_SERVER` | 3306 | ✓ | ✓ | ✗ | `USERS, PRIVILEGES, ROUTINES, VIEWS, INDEXES, FOREIGN_KEYS, TRIGGERS, EXPORT, DDL_TRANSACTION` |
| `Postgresql` | `PostgreSQL` | `CLIENT_SERVER` | 5432 | ✓ | ✓ | ✓ | `+ MULTI_SCHEMA, CROSS_DATABASE` |
| `Sqlite` | `SQLite (Embedded)` | `FILE_BASED` | 0 | ✗ | ✗ | ✗ | `VIEWS, INDEXES, FOREIGN_KEYS, EXPORT, EMBEDDED_MODE` (无 `USERS`/`TRIGGERS`/`ROUTINES`) |

---

### DATA.GENERATE — 造数引擎

基于嵌入式 Lua 脚本（LuaJIT + Lua 5.1~5.5），按表顺序逐条 INSERT，每条插入后回报进度。

```json
{
  "id": "r27", "category": "DATA", "action": "GENERATE",
  "connection": {"driver":"Mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},
  "payload": {
    "luaVersion": "luajit",
    "schema": "public",
    "tables": [
      {"script": "for i = 1, 100 do\n  insert('users', {name='user_'..i, email=random_email(), age=random_int(18,65), phone=random_phone()})\nend"},
      {"script": "local catId = lastId()\nfor i = 1, 500 do\n  insert('orders', {user_id=random_int(1,100), amount=random_int(100,99999)/100.0, status=random_enum('pending','paid','shipped')})\nend"}
    ]
  }
}
```

**流式进度响应**：
```json
{"id":"r27","success":true,"stream":true,"end":false,"data":{"table":"users","inserted":1,"scriptInserted":1,"scriptIndex":1,"totalScripts":2,"sql":"INSERT INTO `users` (...) VALUES (?, ?, ?, ?)","data":{"name":"user_1","email":"user_...","age":42,"phone":"138..."}}}
...
{"id":"r27","success":true,"stream":true,"end":true,"data":null}
```

**Lua 内置函数**：

| 函数 | 签名 | 行为 |
|---|---|---|
| `insert` | `insert(tableName, rowTable)` | 立即执行单条 INSERT |
| `lastId` | `lastId()` | 返回上一条 INSERT 的自增 ID |
| `random_int` | `random_int(min, max)` | `[min, max]` 随机整数 |
| `random_float` | `random_float(min, max)` | `[min, max)` 随机浮点 |
| `random_string` | `random_string(length)` | 随机字母数字串（1..256） |
| `random_date` | `random_date(start, end)` | `YYYY-MM-DD` 区间随机 `LocalDate` |
| `random_datetime` | `random_datetime(start, end)` | `YYYY-MM-DD` 区间随机 `LocalDateTime` |
| `random_time` | `random_time()` | 随机 `LocalTime` |
| `random_email` | `random_email()` | `user_<random>@example.com` |
| `random_phone` | `random_phone()` | 11 位手机号 |
| `random_name` | `random_name()` | 中文 + 英文姓名池 |
| `random_enum` | `random_enum(...)` | 从参数中随机选一个 |
| `random_uuid` | `random_uuid()` | 标准 UUID |

**Lua 沙箱**：禁用 `os` / `io` / `debug` / `package` / `require` / `loadfile` / `dofile` / `loadstring` / `load` / `rawget` / `rawset` / `rawequal` / `setfenv` / `getfenv` / `newproxy`。

> ⚠️ 嵌套 Lua table 通过 `insert()` 传递时会丢失（`readLuaTable` 中 `isTable → null`），列值必须使用 string / number / boolean / nil / java.time 类型。

---

### FUNCTION — 函数与存储过程

> MySQL / PostgreSQL / H2 **三个方言均完整实现** Routine 管理。

#### LIST — 函数/存储过程/触发器列表

```json
{"id":"r28","category":"FUNCTION","action":"LIST","connection":{"driver":"Postgresql",...},"payload":{"schema":"public"}}
// Response: [
//   {"name":"get_user_by_id","routine_type":"FUNCTION","return_type":"SETOF users","language":"plpgsql","security_definer":"SECURITY INVOKER","volatility":"STABLE","arg_count":"1","arg_names":"user_id","schema":"public","description":"...","trigger_table":""},
//   {"name":"create_order","routine_type":"PROCEDURE",...},
//   {"name":"sync_users_trigger","routine_type":"TRIGGER","trigger_table":"users",...}
// ]
```

#### INFO / GET_DDL / CREATE / DELETE / CALL / DEBUG / UPDATE

```json
// INFO（自动解析 routineType）
{"id":"r29","category":"FUNCTION","action":"INFO","connection":{...},"payload":{"name":"func_sync_t2_to_t1","schema":"public"}}
// Response: {"name":"...","routine_type":"FUNCTION","schema":"public","language":"plpgsql","return_type":"TRIGGER","volatility":"VOLATILE","security_definer":"SECURITY INVOKER","arg_count":"0","arg_names":"","description":"...","trigger_table":""}

// GET_DDL
{"id":"r30","category":"FUNCTION","action":"GET_DDL","connection":{...},"payload":{"name":"get_user_by_id","schema":"public"}}
// Response data: "CREATE OR REPLACE FUNCTION public.get_user_by_id(...) ..." (string)

// CREATE（直接传完整 DDL）
{"id":"r31","category":"FUNCTION","action":"CREATE","connection":{...},"payload":{"ddl":"CREATE OR REPLACE FUNCTION calculate_total(price DECIMAL, tax_rate DECIMAL DEFAULT 0.1) RETURNS DECIMAL LANGUAGE plpgsql AS $$ BEGIN RETURN price * (1 + tax_rate); END; $$"}}
// Response: {"success":true,"message":"函数/存储过程创建成功"}

// DELETE
{"id":"r32","category":"FUNCTION","action":"DELETE","connection":{...},"payload":{"name":"old_function","routineType":"FUNCTION","schema":"public","ifExists":true,"cascade":false}}
// Response: {"success":true,"message":"函数/存储过程删除成功","name":"old_function","routineType":"FUNCTION"}

// CALL 函数
{"id":"r33","category":"FUNCTION","action":"CALL","connection":{...},"payload":{"name":"calculate_total","routineType":"FUNCTION","schema":"public","args":["100.00","0.15"]}}
// Response: {"result":115.0,"row_count":1}

// CALL 存储过程
{"id":"r33b","category":"FUNCTION","action":"CALL","connection":{...},"payload":{"name":"create_order","routineType":"PROCEDURE","schema":"public","args":["1","100","5"]}}
// Response: {"update_count":1}

// DEBUG（EXPLAIN + INFO + DEPENDENCIES）
{"id":"r34","category":"FUNCTION","action":"DEBUG","connection":{...},"payload":{"name":"get_user_by_id","schema":"public"}}
// Response: [
//   {"type":"EXPLAIN","output":"[{\"Plan\":...}]"},
//   {"type":"INFO","output":"Function: ..."},
//   {"type":"DEPENDENCIES","output":"TABLE: users\nVIEW: user_summary"}
// ]

// UPDATE — 验证 DDL 语法（不创建）
{"id":"r35","category":"FUNCTION","action":"UPDATE","connection":{...},"payload":{"ddl":"CREATE OR REPLACE FUNCTION test_func(x INTEGER) RETURNS INTEGER AS $$ BEGIN RETURN x * 2; END; $$ LANGUAGE plpgsql"}}
// Response: {"valid":true,"message":"DDL 语法验证通过"}
```

---

### VIEW / INDEX / FOREIGN_KEY / TRIGGER — 对象管理

#### VIEW

```json
// LIST
{"id":"r36","category":"VIEW","action":"LIST","connection":{...},"payload":{"schema":"public"}}
// CREATE
{"id":"r37","category":"VIEW","action":"CREATE","connection":{...},"payload":{"name":"v_users","definition":"SELECT id, name FROM users WHERE active = true","schema":"public"}}
// Response: {"created":"v_users"}
// DELETE
{"id":"r38","category":"VIEW","action":"DELETE","connection":{...},"payload":{"name":"v_users","ifExists":true,"schema":"public"}}
// GET_DDL
{"id":"r39","category":"VIEW","action":"GET_DDL","connection":{...},"payload":{"name":"v_users","schema":"public"}}
```

#### INDEX

```json
// LIST
{"id":"r40","category":"INDEX","action":"LIST","connection":{...},"payload":{"tableName":"users","schema":"public"}}
// CREATE（unique 默认 false）
{"id":"r41","category":"INDEX","action":"CREATE","connection":{...},"payload":{"tableName":"users","indexName":"idx_email","columns":["email"],"unique":false,"schema":"public"}}
// Response: {"created":"idx_email","tableName":"users"}
// DELETE
{"id":"r42","category":"INDEX","action":"DELETE","connection":{...},"payload":{"indexName":"idx_email","tableName":"users","schema":"public"}}
```

#### FOREIGN_KEY

```json
// LIST
{"id":"r43","category":"FOREIGN_KEY","action":"LIST","connection":{...},"payload":{"tableName":"orders","schema":"public"}}
// CREATE
{"id":"r44","category":"FOREIGN_KEY","action":"CREATE","connection":{...},"payload":{"tableName":"orders","fkName":"fk_orders_user","columns":["user_id"],"refTable":"users","refColumns":["id"],"onDelete":"CASCADE","onUpdate":"RESTRICT","schema":"public"}}
// Response: {"created":"fk_orders_user","tableName":"orders"}
// DELETE
{"id":"r45","category":"FOREIGN_KEY","action":"DELETE","connection":{...},"payload":{"tableName":"orders","fkName":"fk_orders_user","schema":"public"}}
```

#### TRIGGER

> Trigger 创建/删除通过 `category=FUNCTION, routineType="TRIGGER"` 完成（见上节）。

```json
// LIST
{"id":"r46","category":"TRIGGER","action":"LIST","connection":{...},"payload":{"schema":"public"}}
// GET_DDL
{"id":"r47","category":"TRIGGER","action":"GET_DDL","connection":{...},"payload":{"name":"sync_users_trigger","schema":"public"}}
// Response data: "CREATE OR REPLACE TRIGGER sync_users_trigger\n  STATEMENT AFTER DELETE\n  ON users ..." (string)
```

---

### EXPORT — 数据导出

独立子进程运行，5 种格式，全链路 JDBC 游标流式，内存占用与数据总量无关。

**支持格式**：

| 格式 | 扩展名 | 说明 |
|---|---|---|
| `CSV` | `.csv` | UTF-8 BOM，字段自动转义 |
| `JSON_LINES` | `.jsonl` | 每行一个独立 JSON 对象 |
| `SQL_INSERT` | `.sql` | 必传 `tableName` |
| `EXCEL` | `.xlsx` | POI SXSSF，100 万行/Sheet 自动分页 |
| `PARQUET` | `.parquet` | 动态 Schema |

**请求 payload**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sql` | string | ✓ | 自定义 SELECT SQL |
| `outputDir` | string | ✓ | 输出目录 |
| `fileName` | string | ✓ | 文件名前缀（不含扩展名） |
| `format` | string | ✓ | `CSV` / `JSON_LINES` / `SQL_INSERT` / `EXCEL` / `PARQUET` |
| `tableName` | string | 条件 | `SQL_INSERT` 必填 |
| `fetchSize` | int | — | JDBC 拉取批次，默认 1000 |
| `stopExportId` | string | — | 传入则停止指定导出任务 |

```json
// 启动导出
{"id":"r48","category":"EXPORT","action":"RUN_EXPORT","connection":{...},"payload":{"sql":"SELECT * FROM users","outputDir":"D:/exports","fileName":"users_2024","format":"CSV","fetchSize":1000}}
// 流式响应（每 1000 行或 200ms 一帧）：
// {"id":"r48","success":true,"stream":true,"end":false,"data":{"exportedRows":1000,"columnCount":5,"completed":false,"filePath":null,"error":null}}
// {"id":"r48","success":true,"stream":true,"end":false,"data":{"exportedRows":2000,"columnCount":5,"completed":false}}
// ...
// {"id":"r48","success":true,"stream":true,"end":true,"data":{"exportedRows":13308,"columnCount":5,"completed":true,"filePath":"D:\\exports\\users_2024.csv","error":null}}

// 停止导出
{"id":"r48stop","category":"EXPORT","action":"RUN_EXPORT","connection":{...},"payload":{"stopExportId":"r48"}}
// Response: {"stopped":"r48"}
```

**MySQL 特殊处理**：自动 `fetchSize = Integer.MIN_VALUE` 启用服务端流式游标。
**PostgreSQL 特殊处理**：自动临时 `autoCommit = false` 启用服务端游标，导出完成后恢复。

---

## 错误响应

```json
{"id":"req-99","success":false,"error":"Communications link failure: Unable to connect to host","stream":false,"end":false,"data":null}
```

---

## 测试

**376 个测试全通过（0 失败 / 0 错误，1 个 Windows-only `IpcConfigTest` 用例 skip）**：

```bash
./gradlew test
```

测试报告：
- `engine/build/reports/tests/test/index.html`
- `dialect-h2/build/reports/tests/test/index.html`
- `dialect-duckdb/build/reports/tests/test/index.html`
- `dialect-sqlite/build/reports/tests/test/index.html`

| 模块 / 套件 | 测试数 | 范围 |
|---|---|---|
| `dialect-h2:test` | 63 | H2 方言 SPI 方法全量 |
| `dialect-duckdb:test` | 81 | DuckDB 方言 SPI 方法全量（v2.7 新增） |
| `dialect-sqlite:test` | **62** | **SQLite 方言 SPI 方法全量（v2.8 新增）** |
| `engine:test` | 170 | — |
| └ `ipc/IpcConfigTest` | 20 | CLI 参数解析 + 自动平台检测 + 错误路径（1 个 Windows-only 跳过） |
| └ `ipc/IpcTransportTest` | 7 | SPI 各实现构造 |
| └ `ipc/TcpIpcTransportIntegrationTest` | 1 | TCP loopback + gRPC round-trip |
| └ `ipc/UnixSocketIpcTransportIntegrationTest` | 2 | UDS + gRPC round-trip（`@EnabledOnOs(LINUX, MAC, FREEBSD)`） |
| └ `ipc/NamedPipeIpcTransportIntegrationTest` | 2 | 客户端 channel + serverBuilder 限制 |
| └ `pool/PoolManagerTest` | 11 | SHA-256 key + closeAll |
| └ `loader/DialectLoaderTest` | 5 | SPI 自动发现 |
| └ `integration/*HandlerIntegrationTest` | 60 | 11 个 handler × H2Fixture（typed proto builders 直接调 handler） |
| └ `integration/TypedRequestEnvelopeIntegrationTest` | 7 | 端到端 typed Request → dispatcher → typed Response |
| └ `integration/UserGrantsIntegrationTest` | 2 | USER.GRANTS 路由，H2 限制场景 |
| └ `integration/DataGenerateIntegrationTest` | 2 | DATA.GENERATE 流式进度 + 错误路径 |
| └ `integration/FunctionGetDdlIntegrationTest` | 2 | FUNCTION.GET_DDL dispatcher 路由 + H2 限制 |
| └ `integration/SqlExplainRouteIntegrationTest` | 2 | SQL.EXPLAIN 端到端路由（v2.6 之前未实现） |
| └ `integration/EnvelopeOptionsIntegrationTest` | 4 | dryRun / timeoutMs envelope 跨切面 |
| └ `integration/DuckDBHandlerIntegrationTest` | 27 | DuckDB 端到端：SCHEMA/TABLE/DATA/SQL/VIEW/INDEX/FK/FUNCTION/SYSTEM/LOCAL FILES/EXPORT（v2.7 新增） |
| └ `integration/SQLiteHandlerIntegrationTest` | **7** | **SQLite 端到端：SCHEMA/TABLE/DATA/VIEW/INDEX/SYSTEM（v2.8 新增）** |
| └ `integration/SystemListDriversIntegrationTest` | **8** | **LIST_DRIVERS 元数据枚举 + 5 方言分别验证（v2.8 新增）** |

---

## 添加新方言

1. 创建 Gradle 模块，依赖 `api` 项目
2. 实现 `DatabaseDialect` 接口，声明 `override val driverName = "YourDriver"`
3. 在 `src/main/resources/META-INF/services/com.kxxnzstdsw.dialect.DatabaseDialect` 中写入实现类全限定名
4. 构建后将 JAR 放入 `engine/build/libs/dialects/` 目录
5. 重启引擎即自动加载，无需修改主引擎代码

**DuckDB 连接示例**（v2.7 新增 — 仅本地嵌入式，`host`/`port` 完全忽略，`database` 字段就是路径）：

```json
// 内存模式
{"connection": {"driver":"Duckdb", "database":""}}

// .duckdb 文件
{"connection": {"driver":"Duckdb", "database":"/data/analytics.duckdb"}}

// CSV 直查（无需 ETL；DuckDB 自动 attach）
{"connection": {"driver":"Duckdb", "database":"/data/events.csv"}}

// Parquet / JSON
{"connection": {"driver":"Duckdb", "database":"/data/events.parquet"}}
{"connection": {"driver":"Duckdb", "database":"/data/payload.json"}}

// Excel 走 Apache POI 预转换 → 临时 DuckDB（缓存避免重复转换）
{"connection": {"driver":"Duckdb", "database":"/data/report.xlsx"}}
```

DuckDB 方言支持：SCHEMA / TABLE（含自增 PK 走 `SEQUENCE + DEFAULT nextval`）/ DATA CRUD / SQL EXECUTE+EXPLAIN / VIEW / INDEX / FOREIGN_KEY（table-rebuild）/ FUNCTION（仅 MACRO）/ SYSTEM / EXPORT（5 种格式全链路透传）。**不支持**：USER / PRIVILEGE / TRIGGER（抛 `UnsupportedOperationException`）；FK `ON DELETE/UPDATE CASCADE/SET NULL/SET DEFAULT`（自动改写 `NO ACTION`）；PG/MySQL 风格 `$$ ... $$` 函数体。

**SQLite 连接示例**（v2.8 新增 — 仅本地嵌入式，`host`/`port`/`user`/`password` 全部忽略，`database` 字段就是路径）：

```json
// 内存模式
{"connection": {"driver":"Sqlite", "database":":memory:"}}

// SQLite 文件
{"connection": {"driver":"Sqlite", "database":"/data/local.db"}}
```

SQLite 方言支持：SCHEMA / TABLE（含自增 PK 走 inline `INTEGER PRIMARY KEY AUTOINCREMENT`）/ DATA CRUD / SQL EXECUTE+EXPLAIN / VIEW / INDEX / FOREIGN_KEY（table-rebuild）/ SYSTEM / EXPORT（5 种格式全链路透传）。**不支持**：USER / PRIVILEGE / TRIGGER / FUNCTION（routines 概念，抛 `UnsupportedOperationException`）；`ALTER COLUMN`（MODIFY_COLUMN 仅 RENAME）；`ALTER TABLE ADD/DROP CONSTRAINT`（FK 走 table-rebuild）；`$$ ... $$` PG 函数体；MySQL/PG `ENUM` 类型。

### `SYSTEM.LIST_DRIVERS` —— 前端动态渲染"新建连接"表单

```json
{"id":"ui-1","category":"SYSTEM","action":"LIST_DRIVERS","connection":{},"payload":{}}
// Response: { items: [{driver_name:"Mysql", display_name:"MySQL", connection_type:"CLIENT_SERVER", default_port:3306, requires_host:true, ...}, {driver_name:"Sqlite", ...}, ...] }
```

**为什么需要**：前端不需要硬编码"哪些 driver 需要 host / port / user"，而是在启动时调一次 `LIST_DRIVERS`，拿到所有已加载方言的元数据，按 `displayName` 渲染表单、按 `requiresHost` 等标志决定输入框显隐、按 `capabilities` 决定哪些按钮（如 TRIGGER.LIST）可用。**返回顺序按 `driverName` 字典序升序**，保证前端渲染顺序稳定。

---

## 架构特性

- **gRPC + 强类型 per-Category 消息**：12 个 Category 各有自己的 `oneof body` 消息，wire 上是标准 protobuf，无 stringly-typed payload；`repeated google.protobuf.Value` 承载方言差异化的 item 形状
- **gRPC 1.76 + grpc-kotlin 协程服务端（v2.5）**：`IdbEngineCoroutineImplBase` + suspend `handle()` → `Flow<Response>`；`addService(IdbEngineImpl().bindService())` 挂载服务
- **业务层 Kotlin DSL end-to-end（v2.5）**：13 个 handler + `RequestDispatcher` + 11 个集成测试全部以 `xxxRequest { ... }` / `xxxResponse { ... }` / `request { ... }` / `response { ... }` DSL 形态编写；`google.protobuf.Value` 因属 Well-Known Type 无生成 DSL，仍走 `Value.newBuilder()`
- **跨平台 IPC Transport**：TCP / UDS / Named Pipe 三实现，CLI `--ipc` 参数选择，业务层零感知
- **方言插件化**：方言以独立 JAR 通过 SPI 动态加载
- **绝对无状态**：每次请求携带完整连接凭证
- **连接池复用**：基于 SHA-256 Hash（包含 password）缓存 HikariCP 实例，10 分钟空闲自动释放
- **流式大数据**：DATA LIST（pageSize=0）/ SQL SELECT / GENERATE / EXPORT 均通过 JDBC 游标逐行拉取
- **SQL 注入防护**：DATA CRUD 强制 `PreparedStatement`；`where`/`orderBy` 片段方言级校验
- **损坏输入容错**：grpc 框架自动将传输层错误转为 `StatusException`
- **日志隔离**：所有日志输出到滚动文件 (`~/.config/idb/logs/idb-engine.log`)
- **导出子进程隔离**：防止大数据量导出时 OOM 主进程
- **LuaJIT 造数引擎**：LuaJIT + Lua 5.1~5.5 多版本切换、沙箱隔离、流式进度
- **完整 Routine / View / Index / FK / Trigger 管理**
- **端到端强类型 Handler**：13 个 handler 全部接收 typed per-Category proto 消息、返回 typed `<Category><Action>Response` 消息；无 `JsonObject` payload 解析；`RequestDispatcher` 是 (Category, Action) → handler 的薄路由层

---

## 技术栈

- Kotlin 2.4.0 / JDK 25
- grpc-netty-shaded 1.83.1 + grpc-stub + grpc-protobuf + grpc-kotlin-stub 1.5.0（协程服务端 + Kotlin DSL 生成）
- protoc 3.25.5 + protoc-gen-grpc-java 1.68.0 + protoc-gen-grpc-kotlin 1.4.1（工具链锁定）
- kotlinx-coroutines 1.11.0
- kotlinx-serialization-json 1.11.0
- protobuf-kotlin-lite 4.35.1（生成 *Kt DSL builder：`xxxRequest { ... }` / `xxxResponse { ... }` / `xxxItem { ... }`）
- HikariCP 7.0.2
- MySQL Connector/J 9.7.0 / PostgreSQL JDBC 42.7.11 / H2 2.3.232 / DuckDB JDBC 1.5.5.1（v2.7 新增） / **SQLite JDBC 3.46.1.3（v2.8 新增，driver `Sqlite`）**
- SLF4J 2.0.18 + Logback 1.5.13
- LuaJIT 4.1.0（luajava）
- Apache POI 5.5.1（poi-ooxml — Excel 流式导出 + DuckDB Excel 预转换）
- Apache Parquet 1.17.1 + Hadoop 3.5.0

---

## 架构升级历史 (Architecture Migration Log)

| 版本 | 通信协议 | 备注 |
|---|---|---|
| v1.0 | stdin/stdout + 4-byte BE uint32 长度前缀 + 自定义 kotlinx-serialization-protobuf | 旧版管道协议 |
| v2.0 | gRPC over HTTP/2 + 标准 google.protobuf.Value | 替换为标准 gRPC；导出子进程同样切换为 gRPC；移除 stdin/stdout 依赖 |
| v2.1 | gRPC + 跨平台 IPC Transport SPI | 在 gRPC 之上抽象 `IpcTransport` 接口，默认 TCP；CLI 参数 `--ipc` 切换 UDS / Named Pipe |
| v2.2 | gRPC + 强类型 Request/Response + CLI Args | `Request.payload` 与 `Response.data` 由 `map<string, Value>` 替换为 per-Category typed protobuf 消息；IPC 选择改为 CLI 参数 |
| v2.3 | gRPC + 强类型 Handlers end-to-end | 13 个 handler 全部接收 typed proto 消息、返回 typed `<Category><Action>Response` 消息；`TypedRequestMapper` / `TypedResponseMapper` 删除；`RequestDispatcher` 简化为薄路由层；179 测试全通过 |
| v2.4 | gRPC + 强类型 per-list-item 消息 | 8 个 typed per-list-item 消息（`TableListItem` 等）取代遗留 `repeated google.protobuf.Value`；动态行用 typed `Row` wrapper |
| v2.5 | gRPC 1.76 + grpc-kotlin 协程服务端 + Kotlin DSL end-to-end | gRPC 依赖 `1.68.0` → `1.76.0`，接入 `grpc-kotlin-stub 1.4.1`；服务端 `IdbEngineCoroutineImplBase`（suspend `handle()` → `Flow<Response>`）；protoc 工具链锁定 `protoc 3.25.5` + `protoc-gen-grpc-java 1.68.0` + `protoc-gen-grpc-kotlin 1.4.1`；启用 `protobuf-kotlin-lite` 生成 Kotlin DSL；13 个 handler + `RequestDispatcher` + 11 个集成测试全部切到 DSL 形态；移除 `grpc-core` / `protobuf-java-util` / `ksp` / `kotlinx-serialization-protobuf` 无用依赖；179 测试全通过 |
| v2.6 | 表驱动 Dispatcher + 跨切面 Envelope Options | `RequestDispatcher` 重构：9 个 `handleX` 函数 + 11 个 `wrapTypedResponse` when 分支 → 单个 typed `routes` map（`Pair<Category, Action>` → `Route`）；新 (Category, Action) 仅需一个 map 条目；消除 `wrapTypedResponse` 中静默 `else -> {}` 兜底；`SQL.EXPLAIN` 路由打通（之前 handler 存在但 dispatcher 未路由）。新增 `RequestOptions { trace_id, dry_run, timeout_ms }`：MDC 注入 `trace_id`；`dryRun=true` + write action 直接短路返回 success（不修改数据库）；`timeoutMs>0` 包 `withTimeoutOrNull` 超时返回 `error="timeout"`。`if_exists` / `if_not_exists` 在 SCHEMA/TABLE/INDEX/FOREIGN_KEY 路径下贯通（v2.6 之前仅 VIEW/FUNCTION.DELETE 支持）。191 测试全通过（128 engine + 63 H2）|
| **v2.7** | **DuckDB 方言插件（本地嵌入式 OLAP）** | 新增 `dialect-duckdb` 模块（driver `Duckdb`，JDBC `org.duckdb.DuckDBDriver` 1.5.5.1），仅本地嵌入式（内存 / `.duckdb` / `.csv` / `.parquet` / `.json` / `.xlsx`）；`host`/`port` 完全忽略，`database` 字段即路径。Excel 走 Apache POI 5.5.1 预转换为临时 DuckDB（`ExcelToDuckDbCache` 缓存避免重复转换）。**自增主键**用 `SEQUENCE + DEFAULT nextval + 表级 PRIMARY KEY` 兜底（因 DuckDB 1.5.5.1 不接受 `IDENTITY`+表级 PK 组合，也不支持 `INTEGER PRIMARY KEY` ROWID 自填充）。**FK** 走 table-rebuild（因 DuckDB 不支持 `ALTER TABLE ADD/DROP CONSTRAINT` + 忽略 `CONSTRAINT fk_name`，自动生成 `<table>_<cols>_fkey`）。`SHOW CREATE TABLE/VIEW` 解析失败 → 手动从 `information_schema` 重建 DDL。`duckdb_functions()` MACRO 列名 `macro_definition`（不是 `definition`/`description`）。`duckdb_constraints()` 无 `column_name`，只有 `constraint_column_names` (LIST)。FK `ON DELETE/UPDATE CASCADE/SET NULL/SET DEFAULT` 不支持 → 全部改写为 `NO ACTION`。同文件不同连接配置冲突 → 测试 fixture 强制走 `PoolManager`（不混用 `DriverManager`）。**新 SPI 方法** `DatabaseDialect.buildPreCreateStatements(tableName, autoIncrementColumns): List<String>`（默认空实现）。gRPC 依赖 `1.76.0` → `1.83.1`，`grpc-kotlin-stub` `1.4.1` → `1.5.0`，`protobuf-kotlin-lite` `3.25.8` → `4.35.1`。299 测试全通过（81 DuckDB + 63 H2 + 155 engine）|
| **v2.8 (当前)** | **SQLite 方言插件 + SPI 连接元数据扩展 + SYSTEM.LIST_DRIVERS** | 新增 `dialect-sqlite` 模块（driver `Sqlite`，JDBC `org.sqlite.JDBC` 3.46.1.3），仅本地嵌入式（`:memory:` / `.db` 文件）；`host`/`port`/`user`/`password` 全部忽略，`database` 字段即路径。**自增主键**走 inline `INTEGER PRIMARY KEY AUTOINCREMENT`（必须 INTEGER 类型；`TableHandler.create` 检测到 `autoIncrementColumns` 时跳过表级 `PRIMARY KEY` 子句避免 "more than one primary key"）。**FK** 走 table-rebuild（CREATE temp AS SELECT → DROP → CREATE with FK → INSERT → DROP temp），`addForeignKey` 不支持 CONSTRAINT 子句名（SQLite CREATE TABLE 语法）。`MODIFY_COLUMN` 仅支持 RENAME（SQLite 无 ALTER COLUMN）。`TRUNCATE` 用 `DELETE FROM` + 重置 `sqlite_sequence`。多 database 走 `ATTACH/DETACH`。USER / PRIVILEGE / TRIGGER / FUNCTION（routines）抛 `UnsupportedOperationException`。SQL 危险关键词额外禁用 `ATTACH` / `DETACH` / `PRAGMA` / `REPLACE` / `VACUUM` / `REINDEX`。**SPI 元数据扩展**：`DatabaseDialect` 新增 `displayName` / `connectionType` / `requiresHost` / `requiresPort` / `defaultPort` / `supportsUser` / `supportsPassword` / `supportsSchema` / `supportsCrossDatabase` / `jdbcUrlExample` / `capabilities` 11 个属性（默认实现，**完全向后兼容**）。新增 `ConnectionType` 枚举（`CLIENT_SERVER` / `EMBEDDED` / `FILE_BASED` / `IN_MEMORY`）+ `DialectCapability` 枚举（12 个能力标签）。**`SYSTEM.LIST_DRIVERS` action（Action 18）**：枚举所有已加载方言并返回 `repeated DialectInfo` 元数据，供前端**动态渲染"新建连接"表单**；`RequestDispatcher` 表驱动新增 `(SYSTEM, LIST_DRIVERS) → Route` 一条；`DialectLoader.getAllDialects()` 提供枚举入口；`items` 按 `driverName` 字典序升序。**376 测试全通过（1 个 Windows-only skip），0 失败 / 0 错误**（62 SQLite + 81 DuckDB + 63 H2 + 170 engine）|
