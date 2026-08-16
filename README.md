# IDB Engine — Database Management Backend

A headless, cross-platform database management engine written in Kotlin. Exposes a **gRPC** API (HTTP/2 + `google.protobuf.Value` payloads) over a configurable **IPC transport** (TCP loopback / Unix Domain Socket / Windows Named Pipe). Designed to be embedded in a Wails (Go) host with three pluggable dialects: MySQL, PostgreSQL, H2.

> **Current version: v2.1**
> - gRPC server on `:50051` by default (`IDB_ENGINE_PORT` override)
> - IPC transport selected via `IDB_ENGINE_IPC` (`tcp` / `unix` / `pipe`)
> - Pluggable dialect architecture (add a new DB by implementing `DatabaseDialect` and dropping a JAR in `dialects/`)
> - 159 tests passing across engine + H2 dialect modules

---

## 项目结构

```
idb_engine/
├── api/                  公共 SPI 接口（DatabaseDialect）
├── dialect-mysql/        MySQL 方言插件 JAR
├── dialect-postgresql/   PostgreSQL 方言插件 JAR
├── dialect-h2/           H2 方言插件 JAR（嵌入式数据库 + 测试）
└── engine/               主引擎
    ├── proto/            idb_engine.proto（gRPC service + message schemas）
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
drivers/                JDBC 驱动（MySQL / PostgreSQL / H2）
dialects/               方言插件（idb-dialect-{mysql,postgresql,h2}.jar）
```

### 运行

```bash
# TCP loopback（默认 :50051）
cd engine/build/libs && java -jar idb-engine.jar

# Unix Domain Socket（POSIX 自动检测）
IDB_ENGINE_IPC=unix java -jar idb-engine.jar

# 自定义 TCP 端口
IDB_ENGINE_PORT=60000 java -jar idb-engine.jar
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

| `IDB_ENGINE_IPC` | 传输 | 平台 | 说明 |
|---|---|---|---|
| `tcp`（默认） | TCP loopback `localhost:<port>` | 全平台 | 生产路径；`IDB_ENGINE_PORT` 控制端口 |
| `unix` | Unix Domain Socket | Linux / macOS / BSD | Linux 用 epoll native，macOS/BSD 用 NIO；UDS 文件权限 `rw-------` |
| `pipe` | Windows 命名管道 | Windows | 客户端可用；grpc-java 1.68 无 server-side API，`serverBuilder()` 抛 `UnsupportedOperationException` |

**自动检测**：未设置 `IDB_ENGINE_IPC` 时，Windows → `pipe`，POSIX → `unix`。

**环境变量**：
- `IDB_ENGINE_IPC` — `tcp` / `unix` / `pipe`
- `IDB_ENGINE_PORT` — TCP 端口（默认 `50051`）
- `IDB_ENGINE_UDS_PATH` — UDS 文件路径（默认 `${XDG_RUNTIME_DIR:-/tmp}/idb-engine.sock`）
- `IDB_ENGINE_PIPE_NAME` — 命名管道名称（默认 `idb-engine`）

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
})
for {
    resp, err := stream.Recv()
    if err == io.EOF { break }
    // 处理 resp — 流式响应检查 resp.End
}
```

### 请求格式

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 请求唯一 ID |
| `category` | enum | 13 个分类（详见下表） |
| `action` | enum | 17 个操作（详见下表） |
| `connection` | ConnectionConfig | 连接凭证（`driver`/`host`/`port`/`user`/`password`/`database`/`schema`/`use_ssl`/`properties`） |
| `payload` | `map<string, Value>` | 业务参数（`google.protobuf.Value`） |

**Category 枚举**：`SCHEMA` / `USER` / `TABLE` / `DATA` / `SQL` / `SYSTEM` / `FUNCTION` / `EXPORT` / `VIEW` / `INDEX` / `FOREIGN_KEY` / `TRIGGER`

**Action 枚举**：`LIST` / `CREATE` / `UPDATE` / `DELETE` / `EXECUTE` / `GET_DDL` / `INFO` / `GRANTS` / `GENERATE` / `DEBUG` / `CALL` / `RUN_EXPORT` / `RENAME` / `TRUNCATE` / `TEST_CONNECTION` / `SERVER_INFO`

> **重要**：`Action.EXPORT` 在 proto3 中与 `Category.EXPORT` 命名冲突，因此导出请求使用 **`Action.RUN_EXPORT`**（也是 `EXPORT` category 的唯一合法 action）。

### 响应格式

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 对应请求 ID |
| `success` | bool | 是否成功 |
| `error` | string | 错误信息；空串表示无错误 |
| `stream` | bool | 流式响应标记 |
| `end` | bool | 流式结束标记 |
| `data` | Value | 业务结果（NULL / NUMBER / STRING / BOOL / STRUCT / LIST） |

**PayloadValue（`google.protobuf.Value`）**：业务层的字符串 `"42"` 严格识别为 STRING，不会被自动转为 NUMBER。

---

## Handler 路由矩阵

| Category \ Action | LIST | CREATE | UPDATE | DELETE | EXECUTE | GET_DDL | INFO | GRANTS | GENERATE | DEBUG | CALL | RUN_EXPORT | RENAME | TRUNCATE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SCHEMA      | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — | — | — |
| USER        | ✓ | ✓ | ✓ | ✓ | — | — | — | ✓ | — | — | — | — | — | — |
| TABLE       | ✓ | ✓ | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | ✓ | ✓ |
| DATA        | ✓ | ✓ | ✓ | ✓ | — | — | — | — | ✓ | — | — | — | — | — |
| SQL         | — | — | — | — | ✓ | — | — | — | — | — | — | — | — | — |
| SYSTEM      | — | — | — | — | — | — | ✓ | — | — | — | — | — | — | — |
| FUNCTION    | ✓ | ✓ | ✓ | ✓ | — | ✓ | ✓ | — | — | ✓ | ✓ | — | — | — |
| EXPORT      | — | — | — | — | — | — | — | — | — | — | — | ✓ | — | — |
| VIEW        | ✓ | ✓ | — | ✓ | — | ✓ | — | — | — | — | — | — | — | — |
| INDEX       | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — | — | — |
| FOREIGN_KEY | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — | — | — |
| TRIGGER     | ✓ | — | — | — | — | ✓ | — | — | — | — | — | — | — | — |

SYSTEM 还支持 `TEST_CONNECTION` 和 `SERVER_INFO`（表中未列出）。

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

> `Action.EXPLAIN` 已在 proto 中定义但 dispatcher 暂未实现；执行计划请用 `SQL.EXECUTE` 直接提交 `EXPLAIN <sql>`。

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
// Response (PG): {"version":"PostgreSQL 16.0 ...","current_database":"myapp_db",...}
// Response (MySQL): {"version":"8.0.36","catalog":"def",...}
// Response (H2): {"version":"2.3.232","mode":"REGULAR",...}
```

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

**159 个测试全通过（0 失败，0 错误）**：

```bash
./gradlew test
```

测试报告：
- `engine/build/reports/tests/test/index.html`
- `dialect-h2/build/reports/tests/test/index.html`

| 模块 / 套件 | 测试数 | 范围 |
|---|---|---|
| `dialect-h2:test` | 63 | H2 方言 SPI 方法全量 |
| `engine:test` | 96 | — |
| └ `ipc/IpcConfigTest` | 9 | 环境变量解析 + 自动平台检测 |
| └ `ipc/IpcTransportTest` | 7 | SPI 各实现构造 |
| └ `ipc/TcpIpcTransportIntegrationTest` | 1 | TCP loopback + gRPC round-trip |
| └ `ipc/UnixSocketIpcTransportIntegrationTest` | 2 | UDS + gRPC round-trip（`@EnabledOnOs(LINUX, MAC, FREEBSD)`） |
| └ `ipc/NamedPipeIpcTransportIntegrationTest` | 2 | 客户端 channel + serverBuilder 限制 |
| └ `pool/PoolManagerTest` | 11 | SHA-256 key + closeAll |
| └ `loader/DialectLoaderTest` | 5 | SPI 自动发现 |
| └ `integration/*HandlerIntegrationTest` | 60 | 11 个 handler × H2Fixture |

---

## 添加新方言

1. 创建 Gradle 模块，依赖 `api` 项目
2. 实现 `DatabaseDialect` 接口，声明 `override val driverName = "YourDriver"`
3. 在 `src/main/resources/META-INF/services/com.kxxnzstdsw.dialect.DatabaseDialect` 中写入实现类全限定名
4. 构建后将 JAR 放入 `engine/build/libs/dialects/` 目录
5. 重启引擎即自动加载，无需修改主引擎代码

---

## 架构特性

- **gRPC + `google.protobuf.Value`**：所有 wire 上是标准 protobuf，payload 内部允许任意字节（含 `\n` / `\0` / UTF-8 多字节字符）
- **跨平台 IPC Transport**：TCP / UDS / Named Pipe 三实现，env var 选择，业务层零感知
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

---

## 技术栈

- Kotlin 2.4.0 / JDK 25
- grpc-netty-shaded 1.68.0 + grpc-stub + grpc-protobuf
- kotlinx-coroutines 1.11.0
- kotlinx-serialization-json 1.11.0
- protobuf-kotlin 4.28.2
- HikariCP 7.0.2
- MySQL Connector/J 9.7.0 / PostgreSQL JDBC 42.7.11 / H2 2.3.232
- SLF4J 2.0.18 + Logback 1.5.13
- LuaJIT 4.1.0（luajava）
- Apache POI 5.5.1（poi-ooxml — Excel 流式导出）
- Apache Parquet 1.17.1 + Hadoop 3.5.0
