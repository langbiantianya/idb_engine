# IDB Engine - Database Management Backend

基于 Kotlin + JDBC 的无头数据库管理引擎，通过 **gRPC**（HTTP/2 + Protobuf） 与 Wails 前端通信。方言层采用 SPI 插件化架构，支持动态加载。

> **架构升级**：自 v2.0 起，引擎使用 **gRPC 服务端**模式运行（默认端口 `:50051`，可通过 `IDB_ENGINE_PORT` 覆盖），完全替代旧的 stdin/stdout 长度前缀 Protobuf 帧协议。客户端通过 gRPC streaming 调用发送 `Request`，接收多条 `Response`（流式响应使用 `stream`/`end` 字段分帧）。

## 项目结构

Gradle 多模块项目，方言与引擎解耦：

```
idb_engine/
├── api/                        公共 SPI 接口（DatabaseDialect + Driver 枚举）
├── dialect-mysql/              MySQL 方言插件 JAR
├── dialect-postgresql/         PostgreSQL 方言插件 JAR
├── dialect-h2/                 H2 方言插件 JAR（嵌入式数据库 + 测试）
└── engine/                     主引擎（业务逻辑 + gRPC 服务端 + Protobuf wire）
    ├── proto/                  .proto 源文件（idb_engine.proto / idb_export.proto）
    ├── server/                 gRPC 服务端（IdbEngineServer + IdbEngineImpl）
    ├── dispatcher/             Request → handler 路由 + Flow<Response>
    ├── pool/                   HikariCP 连接池管理
    ├── export/                 数据导出子进程（独立 JVM，gRPC 客户端）
    ├── handlers/               业务处理层（SchemaHandler / TableHandler / ...）
    └── loader/                 ServiceLoader 动态加载 drivers/ + dialects/
```

引擎启动时通过 `ServiceLoader` 自动扫描 `dialects/` 目录，发现并注册所有方言插件，无需硬编码。

## 构建

```bash
./gradlew engine:jar
```

产物位于 `engine/build/libs/`：
```
engine/build/libs/
├── idb-engine.jar              ← 主引擎瘦包
├── libs/                       ← 运行时依赖（Kotlin、HikariCP、日志、api）
├── drivers/                    ← JDBC 驱动（内置 MySQL/PostgreSQL，可追加）
└── dialects/                   ← 方言插件（SPI 动态加载）
    ├── idb-dialect-mysql.jar
    └── idb-dialect-postgresql.jar
```

## 运行

```bash
cd engine/build/libs && java -jar idb-engine.jar
```

程序启动后自动加载 `dialects/` 目录中的方言插件和 `drivers/` 目录中的 JDBC 驱动，然后启动 gRPC 服务监听端口（默认 `:50051`，可通过 `IDB_ENGINE_PORT` 环境变量覆盖）。

## 添加新方言

1. 创建 Gradle 模块，依赖 `api` 项目
2. 实现 `DatabaseDialect` 接口，声明 `override val driverName = "YourDriver"`
3. 在 `src/main/resources/META-INF/services/com.kxxnzstdsw.dialect.DatabaseDialect` 中写入实现类全限定名
4. 构建后将 JAR 放入 `engine/build/libs/dialects/` 目录
5. 无需修改主引擎代码，重启即自动加载

## 通信协议（gRPC）

引擎是一个 **gRPC 服务端**，通过 HTTP/2 上的标准 Protobuf 消息与调用方通信（替代了旧的 stdin/stdout 长度前缀帧协议）。

### 服务定义

```proto
service IdbEngine {
  rpc Handle(Request) returns (stream Response);
}
```

- **Handle** 是双向流式 RPC：客户端发送一条 `Request`，服务端按需返回多条 `Response`（普通响应一条；流式响应多条，最后一条 `end: true`）。

### 端口

- 默认端口 `:50051`（可通过 `IDB_ENGINE_PORT` 环境变量覆盖）
- 最大入站消息大小：256 MiB（`maxInboundMessageSize`）
- 底层传输：Netty（生产路径），回退到 JDK `ServerBuilder`

### Go 端示例

```go
import (
    "google.golang.org/grpc"
    "google.golang.org/grpc/credentials/insecure"
    pb "your/proto/gen"  // 由 .proto 编译生成
)

conn, _ := grpc.Dial("localhost:50051",
    grpc.WithTransportCredentials(insecure.NewCredentials()))
defer conn.Close()

client := pb.NewIdbEngineClient(conn)
stream, _ := client.Handle(ctx, &pb.Request{
    Id:       "req-001",
    Category: pb.Category_TABLE,
    Action:   pb.Action_LIST,
    Connection: &pb.ConnectionConfig{Driver: "Mysql", Host: "127.0.0.1", Port: 3306, User: "root", Password: "secret", Database: "test"},
})
for {
    resp, err := stream.Recv()
    if err == io.EOF { break }
    // 处理 resp — 流式响应检查 resp.End
}
```

### 请求格式（Request）

`Request` 的 protobuf schema：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `string` | 请求唯一 ID |
| `category` | `enum` | `SCHEMA` / `USER` / `TABLE` / `DATA` / `SQL` / `SYSTEM` / `FUNCTION` / `EXPORT` |
| `action` | `enum` | `LIST` / `CREATE` / `UPDATE` / `DELETE` / `EXECUTE` / `GET_DDL` / `INFO` / `GRANTS` / `GENERATE` / `CALL` / `DEBUG` / `EXPORT` |
| `connection` | `ConnectionConfig` | 见下表 |
| `payload` | `map<string, PayloadValue>` | 业务参数，结构由 `category+action` 决定 |

`ConnectionConfig`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `driver` | `string` | `Mysql` / `Postgresql` |
| `host` | `string` | 数据库主机 |
| `port` | `int32` | 数据库端口 |
| `user` | `string` | 用户名 |
| `password` | `string` | 密码（参与连接池缓存 key） |
| `database` | `string` | 数据库名 |

### PayloadValue 类型

`Request.payload` 和 `Response.data` 都是 `PayloadValue` 类型，模拟 `google.protobuf.Value` 的语义：

| Kind | wire 字段 | 业务含义 | 示例 |
|---|---|---|---|
| `NULL` | — | 空值 | `null` |
| `NUMBER` | `numberValue: double` | 数字（整数/浮点统一为 Double） | `42`, `3.14` |
| `STRING` | `stringValue: string` | 字符串 | `"hello"`, `"42"` (严格区分于数字) |
| `BOOL` | `boolValue: bool` | 布尔 | `true`, `false` |
| `STRUCT` | `structValue: map<string, PayloadValue>` | 嵌套对象 | `{"name": "alice", "age": 30}` |
| `LIST` | `listValue: list<PayloadValue>` | 数组 | `[1, "two", true, null]` |

> **重要**：JSON 字符串 `"42"` / `"true"` 会被严格识别为 STRING，不会被自动转换为 NUMBER / BOOL。所有数字统一为 Double（protobuf wire 协议不区分 int/long/double）。

为方便阅读，下方业务示例以 JSON 形式给出 `PayloadValue` 的逻辑结构（实际传输为二进制 protobuf）：

### 响应格式（Response）

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | `string` | — | 对应请求的 `id`（错误时也可能为 `"unknown"`） |
| `success` | `bool` | `false` | 是否成功 |
| `error` | `string` | `""` | 错误信息；空串表示无错误，使用 `isError` 扩展属性判断 |
| `stream` | `bool` | `false` | 流式响应标记 |
| `end` | `bool` | `false` | 流式结束标记 |
| `data` | `PayloadValue` | `NULL` | 业务结果 |

**流式响应字段说明**：
- `stream: true` — 当前响应属于流式序列（一条请求产生多帧响应）
- `end: true` — 流式序列的最后一帧，`data` 为 `NULL`，调用方收到后停止读取
- 普通（非流式）响应中 `stream` 和 `end` 均为 `false`（默认值），调用方无需特殊处理

## 功能示例

> 以下示例以 JSON 形式给出 `PayloadValue` 的逻辑结构，便于阅读。实际传输为长度前缀 Protobuf 帧，**不包含 JSON 文本**。所有响应帧均为单条 protobuf 消息。

### SCHEMA — 架构管理

**列出所有数据库/Schema**

MySQL（与之前一致）：
```json
{"id":"1","category":"SCHEMA","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{}}
```

响应：
```json
{"id":"1","success":true,"error":null,"data":["information_schema","mysql","test_db"]}
```

PostgreSQL — 获取数据库列表（payload 不含 `database`）：
```json
{"id":"1a","category":"SCHEMA","action":"LIST","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"postgres"},"payload":{}}
```

响应：
```json
{"id":"1a","success":true,"error":null,"data":["postgres","my_app_db"]}
```

PostgreSQL — 获取指定数据库下的 schema 列表（payload 含 `database`）：
```json
{"id":"1b","category":"SCHEMA","action":"LIST","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"postgres"},"payload":{"database":"my_app_db"}}
```

响应：
```json
{"id":"1b","success":true,"error":null,"data":["public","myschema"]}
```

**创建数据库**

可选 `options` 对象（MySQL 支持 `charset`、`collate`，PostgreSQL 忽略）。

```json
{"id":"2","category":"SCHEMA","action":"CREATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"name":"new_db"}}
```

带字符集选项（仅 MySQL）：
```json
{"id":"2b","category":"SCHEMA","action":"CREATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"name":"new_db","options":{"charset":"utf8mb4","collate":"utf8mb4_unicode_ci"}}}
```

响应：
```json
{"id":"2","success":true,"error":null,"data":{"created":"new_db"}}
```

**删除数据库**

```json
{"id":"3","category":"SCHEMA","action":"DELETE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"name":"old_db"}}
```

响应：
```json
{"id":"3","success":true,"error":null,"data":{"deleted":"old_db"}}
```

---

### USER — 用户权限管理

**列出所有用户（MySQL）**

```json
{"id":"4","category":"USER","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{}}
```

响应：
```json
{"id":"4","success":true,"error":null,"data":[{"user":"root","host":"localhost"},{"user":"app_user","host":"%"}]}
```

**列出所有用户（PostgreSQL）**

```json
{"id":"5","category":"USER","action":"LIST","connection":{"driver":"postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"postgres"},"payload":{}}
```

响应：
```json
{"id":"5","success":true,"error":null,"data":[{"user":"postgres"},{"user":"app_user"}]}
```

**查询指定用户权限（MySQL）**

payload 含 `user` 字段时返回该用户的权限列表（`host` 可选，默认 `"%"`）：

```json
{"id":"4b","category":"USER","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"user":"app_user"}}
```

响应：
```json
{"id":"4b","success":true,"error":null,"data":[{"grant":"GRANT SELECT ON `test_db`.* TO 'app_user'@'%'"},{"grant":"GRANT INSERT ON `test_db`.* TO 'app_user'@'%'"}]}
```

**查询指定用户权限（PostgreSQL）**

```json
{"id":"5b","category":"USER","action":"LIST","connection":{"driver":"postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"postgres"},"payload":{"user":"app_user"}}
```

响应：
```json
{"id":"5b","success":true,"error":null,"data":[{"schema":"public","table":"users","privilege":"SELECT"},{"schema":"public","table":"users","privilege":"INSERT"}]}
```

**查询用户被授权的所有表与权限（GRANTS）**

按 schema + table 聚合返回。`host` 仅 MySQL 使用。

```json
{"id":"5c","category":"USER","action":"GRANTS","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"user":"app_user"}}
```

响应（MySQL）：
```json
{"id":"5c","success":true,"error":null,"data":[{"schema":"test_db","table":"users","privileges":"SELECT, INSERT"},{"schema":"test_db","table":"orders","privileges":"SELECT"}]}
```

```json
{"id":"5d","category":"USER","action":"GRANTS","connection":{"driver":"postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"postgres"},"payload":{"user":"app_user"}}
```

响应（PostgreSQL）：
```json
{"id":"5d","success":true,"error":null,"data":[{"schema":"public","table":"users","privileges":"INSERT, SELECT"},{"schema":"public","table":"orders","privileges":"SELECT"}]}
```

**创建用户**

`host` 仅 MySQL 使用（PostgreSQL 忽略），默认 `"%"`。

```json
{"id":"4c","category":"USER","action":"CREATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"user":"new_user","password":"secret123"}}
```

响应：
```json
{"id":"4c","success":true,"error":null,"data":{"created":"new_user"}}
```

**删除用户**

```json
{"id":"4d","category":"USER","action":"DELETE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"user":"old_user"}}
```

响应：
```json
{"id":"4d","success":true,"error":null,"data":{"deleted":"old_user"}}
```

**授予权限**

```json
{"id":"6","category":"USER","action":"UPDATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"user":"app_user","schema":"test_db","privileges":["SELECT","INSERT","UPDATE"],"isGrant":true}}
```

响应：
```json
{"id":"6","success":true,"error":null,"data":{"user":"app_user","action":"granted"}}
```

**回收权限**

```json
{"id":"7","category":"USER","action":"UPDATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"user":"app_user","schema":"test_db","privileges":["DELETE"],"isGrant":false}}
```

响应：
```json
{"id":"7","success":true,"error":null,"data":{"user":"app_user","action":"revoked"}}
```

**修改密码**

payload 含 `password` 且无 `privileges` 字段时走密码修改路径。`host` 仅 MySQL 使用。

```json
{"id":"7b","category":"USER","action":"UPDATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"user":"app_user","password":"new_secret"}}
```

```json
{"id":"7c","category":"USER","action":"UPDATE","connection":{"driver":"postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"postgres"},"payload":{"user":"app_user","password":"new_secret"}}
```

响应：
```json
{"id":"7b","success":true,"error":null,"data":{"user":"app_user","action":"password_changed"}}
```

---

### TABLE — 表结构元数据

**列出所有表**

MySQL：
```json
{"id":"8","category":"TABLE","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{}}
```

PostgreSQL — 使用 search_path 默认 schema（通常含 `public`）：
```json
{"id":"8a","category":"TABLE","action":"LIST","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"my_app_db"},"payload":{}}
```

PostgreSQL — 指定 schema：
```json
{"id":"8b","category":"TABLE","action":"LIST","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"my_app_db"},"payload":{"schema":"public"}}
```

响应：
```json
{"id":"8","success":true,"error":null,"data":[{"name":"users","type":"TABLE"},{"name":"orders","type":"TABLE"}]}
```

**查看表列结构与主键（payload 含 `tableName` 时自动路由）**

```json
{"id":"9","category":"TABLE","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"users"}}
```

响应：
```json
{"id":"9","success":true,"error":null,"data":[{"name":"id","type":"INT","size":10,"nullable":false,"isPrimaryKey":true,"defaultValue":null},{"name":"name","type":"VARCHAR","size":255,"nullable":true,"isPrimaryKey":false,"defaultValue":null},{"name":"email","type":"VARCHAR","size":255,"nullable":true,"isPrimaryKey":false,"defaultValue":null}]}
```

**创建表**

可选 `options` 对象：MySQL 支持 `engine`/`charset`/`collate`/`comment`，PostgreSQL 支持 `comment`。

列定义支持 `"autoIncrement": true`（仅对主键有效）：MySQL 生成 `AUTO_INCREMENT`，PostgreSQL `INT` → `SERIAL`，`BIGINT` → `BIGSERIAL`；`SERIAL` 自带 `NOT NULL` 和 `DEFAULT`。

```json
{"id":"10","category":"TABLE","action":"CREATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"products","columns":[{"name":"id","type":"INT","nullable":false,"isPrimaryKey":true},{"name":"name","type":"VARCHAR","size":255,"nullable":false},{"name":"price","type":"DECIMAL","nullable":true,"defaultValue":"0.00"}]}}
```

带自增主键：
```json
{"id":"10a","category":"TABLE","action":"CREATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"products","columns":[{"name":"id","type":"INT","nullable":false,"isPrimaryKey":true,"autoIncrement":true},{"name":"name","type":"VARCHAR","size":255,"nullable":false}]}}
```

带表级选项（仅 MySQL 有效）：
```json
{"id":"10b","category":"TABLE","action":"CREATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"products","columns":[{"name":"id","type":"INT","nullable":false,"isPrimaryKey":true,"autoIncrement":true},{"name":"name","type":"VARCHAR","size":255,"nullable":false}],"options":{"engine":"InnoDB","charset":"utf8mb4","collate":"utf8mb4_unicode_ci","comment":"商品表"}}}
```

响应：
```json
{"id":"10","success":true,"error":null,"data":{"created":"products"}}
```

**修改表结构 — 添加列**

```json
{"id":"11","category":"TABLE","action":"UPDATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"products","operation":"ADD_COLUMN","column":{"name":"description","type":"TEXT","nullable":true}}}
```

响应：
```json
{"id":"11","success":true,"error":null,"data":{"tableName":"products","operation":"ADD_COLUMN"}}
```

**修改表结构 — 删除列**

```json
{"id":"12","category":"TABLE","action":"UPDATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"products","operation":"DROP_COLUMN","columnName":"description"}}
```

响应：
```json
{"id":"12","success":true,"error":null,"data":{"tableName":"products","operation":"DROP_COLUMN"}}
```

**修改表结构 — 修改列类型**

```json
{"id":"13","category":"TABLE","action":"UPDATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"products","operation":"MODIFY_COLUMN","column":{"name":"price","type":"DECIMAL","size":10,"nullable":false}}}
```

响应：
```json
{"id":"13","success":true,"error":null,"data":{"tableName":"products","operation":"MODIFY_COLUMN"}}
```

**修改表结构 — 修改列类型并重命名（可选 `newName` 字段）**

```json
{"id":"13b","category":"TABLE","action":"UPDATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"products","operation":"MODIFY_COLUMN","column":{"name":"price","type":"DECIMAL","size":10,"nullable":false,"newName":"unit_price"}}}
```

响应：
```json
{"id":"13b","success":true,"error":null,"data":{"tableName":"products","operation":"MODIFY_COLUMN"}}
```

**删除表**

```json
{"id":"14","category":"TABLE","action":"DELETE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"old_table"}}
```

响应：
```json
{"id":"14","success":true,"error":null,"data":{"deleted":"old_table"}}
```

**获取建表语句（GET_DDL）**

```json
{"id":"14b","category":"TABLE","action":"GET_DDL","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"users"}}
```

响应（data 为完整 DDL 字符串）：
```json
{"id":"14b","success":true,"error":null,"data":"CREATE TABLE `users` (\n  `id` INT NOT NULL,\n  `name` VARCHAR(255),\n  PRIMARY KEY (`id`)\n)"}
```

---

### DATA — 表数据 CRUD

**分页查询（LOB 字段自动截断为 `[LOB Data]`）**

```json
{"id":"15","category":"DATA","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"users","page":1,"pageSize":20}}
```

响应：
```json
{"id":"15","success":true,"error":null,"data":{"total":120,"page":1,"pageSize":50,"rows":[{"id":"1","name":"Alice","avatar":"[LOB Data]"},{"id":"2","name":"Bob","avatar":"[LOB Data]"}]}}
```

**带过滤与排序的分页查询（`where` + `orderBy`）**

直接传原始 SQL 片段，`where` 和 `orderBy` 均为可选字符串。引擎按数据库方言自动校验安全性。

```json
{"id":"15c","category":"DATA","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"users","page":1,"pageSize":20,"where":"age > 18 AND name LIKE '%Alice%'","orderBy":"created_at DESC"}}
```

响应结构与基础分页一致，`total` 为满足过滤条件的总行数。

安全规则（方言级校验，自动拒绝以下内容）：
- 通用：分号 `;`、注释 `--` `/*`、引号外出现 `INSERT/UPDATE/DELETE/DROP/UNION/EXEC/CREATE/ALTER/GRANT/REVOKE/TRUNCATE`
- MySQL：ORDER BY 允许反引号标识符（`` `col` ``）
- PostgreSQL：额外禁止 `COPY`/`DO`，ORDER BY 允许双引号标识符（`"col"`）

合法示例：
```json
{"where": "status = 'active' AND age >= 18"}
{"where": "name IS NOT NULL"}
{"where": "id IN (1, 2, 3)"}
{"orderBy": "name ASC, created_at DESC"}
```

**流式全量查询（`pageSize: 0`，JDBC 游标模式防 OOM）**

通过 JDBC 游标（`TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY` + `fetchSize=100`）逐行读取，PostgreSQL 端自动临时关闭 `autoCommit` 以启用服务端游标，读取完毕后恢复。

请求：
```json
{"id":"15b","category":"DATA","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"users","pageSize":0}}
```

响应（多行，逐行从 stdout 读取，data 结构与分页一致）：
```
{"id":"15b","success":true,"stream":true,"end":false,"data":{"total":1000,"page":0,"pageSize":1,"rows":[{"id":"1","name":"Alice"}]}}
{"id":"15b","success":true,"stream":true,"end":false,"data":{"total":1000,"page":0,"pageSize":1,"rows":[{"id":"2","name":"Bob"}]}}
...
{"id":"15b","success":true,"stream":true,"end":true,"data":null}
```

收到 `end: true` 时停止读取，表示本次请求数据全部传输完毕。

**插入一行**

```json
{"id":"16","category":"DATA","action":"CREATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"users","values":{"name":"Charlie","email":"charlie@example.com"}}}
```

响应：
```json
{"id":"16","success":true,"error":null,"data":{"affectedRows":1}}
```

**更新一行**

```json
{"id":"17","category":"DATA","action":"UPDATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"users","changes":{"name":"Alex","email":"alex@example.com"},"where":{"id":"1"}}}
```

响应：
```json
{"id":"17","success":true,"error":null,"data":{"affectedRows":1}}
```

**删除一行**

```json
{"id":"18","category":"DATA","action":"DELETE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"users","where":{"id":"1"}}}
```

响应：
```json
{"id":"18","success":true,"error":null,"data":{"affectedRows":1}}
```

---

### SQL — 原生 SQL 引擎

**查询（流式返回结果集，data 结构与分页一致）**

```json
{"id":"19","category":"SQL","action":"EXECUTE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"sql":"SELECT id, name FROM users WHERE id > 10 LIMIT 5"}}
```

响应（多行，`total: -1` 表示无法预知总行数）：
```
{"id":"19","success":true,"stream":true,"end":false,"data":{"total":-1,"page":0,"pageSize":1,"rows":[{"id":"11","name":"Dave"}]}}
{"id":"19","success":true,"stream":true,"end":false,"data":{"total":-1,"page":0,"pageSize":1,"rows":[{"id":"12","name":"Eve"}]}}
...
{"id":"19","success":true,"stream":true,"end":true,"data":null}
```

**PostgreSQL 带 schema 上下文的查询**

payload 含 `schema` 字段时，引擎在执行 SQL 前自动设置 `search_path`，确保无前缀表名能正确解析：

```json
{"id":"19b","category":"SQL","action":"EXECUTE","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"my_app_db"},"payload":{"sql":"SELECT id, name FROM users WHERE id > 10 LIMIT 5","schema":"public"}}
```

MySQL 无需指定 `schema`，忽略该字段。

**更新/DDL（返回受影响行数）**

```json
{"id":"20","category":"SQL","action":"EXECUTE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"sql":"UPDATE users SET name = 'Frank' WHERE id = 3"}}
```

响应：
```json
{"id":"20","success":true,"error":null,"data":{"affectedRows":1}}
```

---

### DATA — 造数引擎 (GENERATE)

基于嵌入式 Lua 脚本的批量造数功能，支持多表按序生成（自动处理外键依赖）。Lua 脚本由调用方提供，每个脚本独立执行。

**核心机制**：
- `insert()` 调用时逐条写库（单条 INSERT + 立即返回自增 ID），不在内存积累数据
- 每条插入后实时流式回报进度（Go 端可即时展示当前行数）
- 表按 `tables` 数组顺序执行，先创建的表先入库（满足外键约束）
- `lastId()` 返回当前表最近一条插入的自增 ID（用于外键引用）
- Lua 沙箱禁用 `os`/`io`/`debug`/`package`/`require` 等危险模块

**Lua 引擎版本**：通过 `payload.luaVersion` 选择，默认 `"luajit"`，可选 `"5.1"` / `"5.2"` / `"5.3"` / `"5.4"` / `"5.5"`。

#### 内置函数详解

**数据写入**

| 函数 | 说明 |
|---|---|
| `insert(tableName, rowTable)` | 向指定表插入一行。`tableName` 为表名字符串，`rowTable` 为 Lua table（键=列名，值=列值）。每次调用立即执行 INSERT 并返回自增 ID。列值支持 `string`/`number`/`boolean`/`nil`/`LocalDate`/`LocalDateTime`/`LocalTime`，`number` 自动区分整数（Long）和浮点数（Double）；日期/时间对象按 JDBC `DATE`/`TIMESTAMP`/`TIME` 类型绑定 |
| `lastId()` | 获取当前表最近一次 `insert()` 生成的自增主键值（BIGINT）。无自增列时返回 `nil`。常用于子表引用父表 ID |

**随机数据生成**

| 函数 | 参数 | 返回值 | 说明 |
|---|---|---|---|
| `random_int(min, max)` | 两个整数 | 整数 | 闭区间 `[min, max]` 随机整数 |
| `random_float(min, max)` | 两个浮点数 | 浮点数 | 左闭右开区间 `[min, max)` 随机浮点数 |
| `random_string(length)` | 正整数 | 字符串 | 指定长度的随机字母数字串（`a-zA-Z0-9`） |
| `random_date(start, end)` | 两个日期字符串 `YYYY-MM-DD` | `LocalDate` 对象 | 区间内随机日期；插入时自动按 JDBC `DATE` 类型绑定（避免 PG `date is of type date but expression is of type character varying` 报错） |
| `random_datetime(start, end)` | 两个日期字符串 `YYYY-MM-DD` | `LocalDateTime` 对象 | 区间内随机时间戳（秒级精度），插入时自动按 `TIMESTAMP` 类型绑定 |
| `random_time()` | 无 | `LocalTime` 对象 | 随机 `HH:mm:ss`，插入时按 `TIME` 类型绑定 |
| `random_email()` | 无 | 字符串 | 格式 `user_<随机数字>@example.com` |
| `random_phone()` | 无 | 字符串 | 11 位手机号（`138/139/150/...` 开头） |
| `random_name()` | 无 | 字符串 | 随机姓名（内置中英文姓名池，如 `张三`/`Alice`） |
| `random_enum(...)` | 可变参数 | 同参数类型 | 从传入的参数中随机选取一个 |
| `random_uuid()` | 无 | 字符串 | 标准 UUID 格式（`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`） |

**循环次数由脚本内部控制**，不再通过 `count` 参数传递。Lua 脚本中自行决定循环次数，如 `for i = 1, 1000 do ... end`。

---

#### 造数脚本示例

**示例 1 — 最简单的单表造数**

向 `users` 表插入 100 条基础数据：

```lua
for i = 1, 100 do
  insert('users', {
    name = 'user_' .. i,
    email = 'user_' .. i .. '@test.com'
  })
end
```

```json
{"tables":[{"script":"for i = 1, 100 do\n  insert('users', {\n    name = 'user_' .. i,\n    email = 'user_' .. i .. '@test.com'\n  })\nend"}]}
```

---

**示例 2 — 使用随机函数生成真实感数据**

```lua
for i = 1, 1000 do
  insert('users', {
    name = random_name(),
    email = random_email(),
    age = random_int(18, 65),
    phone = random_phone(),
    gender = random_enum('男', '女'),
    status = random_enum('active', 'inactive', 'banned'),
    bio = random_string(50),
    created_at = random_date('2023-01-01', '2025-06-01')
  })
end
```

---

**示例 3 — 外键引用（父表 → 子表）**

先造 `categories`，再造 `products`，通过 `lastId()` 获取父表自增 ID：

```json
{"payload":{"tables":[
  {"script":"for i = 1, 10 do\n  insert('categories', {\n    name = '分类_' .. i,\n    sort_order = i\n  })\nend"},
  {"script":"for i = 1, 100 do\n  insert('products', {\n    category_id = random_int(1, 10),\n    name = '商品_' .. random_string(6),\n    price = random_int(100, 99999) / 100.0,\n    stock = random_int(0, 500)\n  })\nend"}
]}}
```

如果子表需要精确引用父表最后一行的 ID：

```lua
-- 父表脚本：造 10 个分类
for i = 1, 10 do
  insert('categories', { name = '分类_' .. i })
end

-- 子表脚本：用 lastId() 获取父表最后一个自增 ID
local lastCatId = lastId()
for i = 1, 100 do
  insert('products', {
    category_id = random_int(lastCatId - 9, lastCatId),
    name = '商品_' .. random_string(6),
    price = random_int(10, 99999) / 100.0
  })
end
```

---

**示例 4 — 同一脚本写多张表**

一个脚本内可以多次调用 `insert()` 写不同表，适合一对一关系：

```lua
for i = 1, 500 do
  insert('users', {
    username = 'user_' .. i,
    email = random_email(),
    password_hash = random_string(32)
  })

  local uid = lastId()

  insert('user_profiles', {
    user_id = uid,
    nickname = random_name(),
    avatar = 'https://avatar.example.com/' .. random_string(8) .. '.png',
    bio = random_string(100),
    birthday = random_date('1970-01-01', '2005-12-31')
  })
end
```

---

**示例 5 — 复杂业务场景（电商订单）**

多表外键链：`users` → `orders` → `order_items`，使用 Lua 变量和表暂存中间数据：

```json
{"payload":{"luaVersion":"5.4","tables":[
  {"script":"for i = 1, 50 do\n  insert('users', {\n    username = 'buyer_' .. i,\n    email = random_email(),\n    phone = random_phone(),\n    balance = random_int(0, 1000000) / 100.0\n  })\nend"},
  {"script":"for i = 1, 200 do\n  local userId = random_int(1, 50)\n  insert('orders', {\n    user_id = userId,\n    order_no = 'ORD-' .. random_string(12),\n    total_amount = random_int(100, 500000) / 100.0,\n    status = random_enum('pending', 'paid', 'shipped', 'completed', 'cancelled'),\n    created_at = random_date('2024-01-01', '2025-06-01')\n  })\nend"},
  {"script":"for i = 1, 500 do\n  insert('order_items', {\n    order_id = random_int(1, 200),\n    product_id = random_int(1, 100),\n    quantity = random_int(1, 10),\n    unit_price = random_int(100, 99999) / 100.0\n  })\nend"}
]}}
```

---

**示例 6 — 使用 Lua 语言特性生成复杂数据**

利用 Lua 的 `table`、`string`、`math` 库和控制流：

```lua
local statuses = {'pending', 'paid', 'shipped', 'completed', 'cancelled'}
local weights = {10, 30, 25, 30, 5}  -- 权重分布

-- 加权随机选择
local function weighted_enum(values, weights)
  local total = 0
  for _, w in ipairs(weights) do total = total + w end
  local r = random_int(1, total)
  local acc = 0
  for i, w in ipairs(weights) do
    acc = acc + w
    if r <= acc then return values[i] end
  end
  return values[#values]
end

for i = 1, count do
  local amount = random_int(100, 999999) / 100.0

  -- 大额订单更可能是 completed
  local status
  if amount > 5000 then
    status = weighted_enum(statuses, {2, 20, 30, 45, 3})
  else
    status = weighted_enum(statuses, weights)
  end

  insert('orders', {
    user_id = random_int(1, 50),
    amount = amount,
    discount = math.floor(amount * random_int(0, 30) / 100 * 100) / 100,
    status = status,
    remark = '订单 #' .. i .. ' - ' .. random_name() .. ' 的订单',
    created_at = random_date('2024-01-01', '2025-06-01')
  })
end
```

---

**示例 7 — 日期/时间类型列（PG `date` / `timestamp` 列）**

`random_date` 返回 `LocalDate`、`random_datetime` 返回 `LocalDateTime`、`random_time` 返回 `LocalTime`，引擎会按 JDBC `DATE` / `TIMESTAMP` / `TIME` 类型绑定到 `?` 占位符，避免 PG 报 `column "..." is of type date but expression is of type character varying`：

```lua
for i = 1, 100 do
  insert('biz_user', {
    username    = 'user_' .. i,
    birthday    = random_date('1970-01-01', '2005-12-31'),            -- -> setDate
    last_login_at = random_datetime('2024-01-01', '2026-06-30'),       -- -> setTimestamp
    wake_up_at  = random_time(),                                       -- -> setTime
  })
end
```

如果需要在脚本里拼接日期字符串传给非时间戳列（如 `varchar` 备注），可继续使用 `..` 拼接 —— `LocalDate.toString()` 在拼接时自动转为 ISO 字符串：

```lua
remark = "用户出生于 " .. random_date('1990-01-01', '2000-12-31')
```

> 推荐直接传 `LocalDate` / `LocalDateTime` / `LocalTime` 对象给时间相关列，避免依赖 `..` 拼接的副作用（不同时区/精度/小数秒）。

---

**请求协议**

```json
{
  "id": "30",
  "category": "DATA",
  "action": "GENERATE",
  "connection": {"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},
  "payload": {
    "luaVersion": "5.4",
    "tables": [
      {"count": 100, "script": "..."},
      {"count": 500, "script": "..."}
    ]
  }
}
```

**响应**（流式，每插入一行回报进度，包含实际插入的数据）：

```json
{"id":"30","success":true,"stream":true,"end":false,"data":{"table":"users","inserted":1,"scriptInserted":1,"scriptIndex":1,"totalScripts":2,"sql":"INSERT INTO `users` (`name`, `email`) VALUES (?, ?)","data":{"name":"user_1","email":"user_123456@example.com"}}}
{"id":"30","success":true,"stream":true,"end":false,"data":{"table":"users","inserted":2,"scriptInserted":2,"scriptIndex":1,"totalScripts":2,"sql":"INSERT INTO `users` (`name`, `email`) VALUES (?, ?)","data":{"name":"user_2","email":"user_234567@example.com"}}}
...
{"id":"30","success":true,"stream":true,"end":false,"data":{"table":"orders","inserted":101,"scriptInserted":1,"scriptIndex":2,"totalScripts":2,"sql":"INSERT INTO `orders` (`user_id`, `amount`) VALUES (?, ?)","data":{"user_id":50,"amount":299.99}}}
...
{"id":"30","success":true,"stream":true,"end":true,"data":null}
```

---

### SYSTEM — 系统信息

**获取 JVM 运行时信息（无需数据库连接，`connection` 字段仍需传递但会被忽略）**

```json
{"id":"21","category":"SYSTEM","action":"INFO","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{}}
```

响应（`memory` 字段单位为字节）：
```json
{"id":"21","success":true,"error":null,"data":{"jvmVersion":"21.0.2","jvmVendor":"Oracle Corporation","jvmName":"OpenJDK 64-Bit Server VM","osName":"Windows 11","osArch":"amd64","osVersion":"10.0","availableProcessors":16,"memory":{"max":4294967296,"total":268435456,"used":134217728,"free":134217728},"uptime":120000,"pid":12345}}
```

字段说明：
- `jvmVersion` / `jvmVendor` / `jvmName` — JVM 版本信息
- `osName` / `osArch` / `osVersion` — 操作系统信息
- `availableProcessors` — 可用 CPU 核心数
- `memory.max` — JVM 最大可用内存
- `memory.total` — JVM 当前已分配内存
- `memory.used` — 已使用内存
- `memory.free` — 已分配中的空闲内存
- `uptime` — JVM 启动至今的毫秒数
- `pid` — 进程 ID

---

### FUNCTION — 函数与存储过程管理 (PostgreSQL)

> ⚠️ **注意**：MySQL 当前为占位实现，调用时会抛出 `UnsupportedOperationException`。

PostgreSQL 函数、存储过程和触发器管理模块，支持创建、查询、调用、调试等功能。

**列出所有函数/存储过程/触发器**

```json
{"id":"fn1","category":"FUNCTION","action":"LIST","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"schema":"public"}}
```

响应：
```json
{"id":"fn1","success":true,"error":null,"data":[{"name":"get_user_by_id","routine_type":"FUNCTION","return_type":"SETOF users","language":"plpgsql","security_definer":"SECURITY INVOKER","volatility":"STABLE","arg_count":"1","arg_names":"user_id","schema":"public","description":"根据ID获取用户信息","trigger_table":""},{"name":"sync_users_trigger","routine_type":"TRIGGER","return_type":"STATEMENT AFTER DELETE","language":"plpgsql","security_definer":"SECURITY INVOKER","volatility":"VOLATILE","arg_count":"0","arg_names":"","schema":"public","description":"同步删除用户","trigger_table":"users"}]}
```

**获取函数/存储过程/触发器详细信息（后端自动解析 routineType）**

```json
// 函数/存储过程
{"id":"fn1b","category":"FUNCTION","action":"INFO","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"name":"func_sync_t2_to_t1","schema":"public"}}
```

响应（函数）：
```json
{"id":"fn1b","success":true,"error":null,"data":{"name":"func_sync_t2_to_t1","routine_type":"FUNCTION","schema":"public","language":"plpgsql","return_type":"TRIGGER","volatility":"VOLATILE","security_definer":"SECURITY INVOKER","arg_count":"0","arg_names":"","description":"同步t2到t1","trigger_table":""}}
```

```json
// 触发器
{"id":"fn1c","category":"FUNCTION","action":"INFO","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"name":"trg_t2_after_insert","schema":"public"}}
```

响应（触发器）：
```json
{"id":"fn1c","success":true,"error":null,"data":{"name":"trg_t2_after_insert","routine_type":"TRIGGER","schema":"public","language":"plpgsql","return_type":"ROW BEFORE INSERT","volatility":"VOLATILE","security_definer":"SECURITY INVOKER","arg_count":"0","arg_names":"","description":"","trigger_table":"t2"}}
```

**获取函数/存储过程/触发器 DDL（后端自动解析类型）**

```json
// 函数/存储过程
{"id":"fn2","category":"FUNCTION","action":"GET_DDL","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"name":"get_user_by_id","schema":"public"}}
```

响应：
```json
{"id":"fn2","success":true,"error":null,"data":"CREATE OR REPLACE FUNCTION public.get_user_by_id(user_id integer)\n RETURNS SETOF users\n LANGUAGE plpgsql\n STABLE\nAS $function$\nBEGIN\n  RETURN QUERY SELECT * FROM users WHERE id = user_id;\nEND\n$function$"}
```

```json
// 触发器
{"id":"fn2b","category":"FUNCTION","action":"GET_DDL","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"name":"sync_users_trigger","schema":"public"}}
```

响应：
```json
{"id":"fn2b","success":true,"error":null,"data":"CREATE OR REPLACE TRIGGER sync_users_trigger\n  STATEMENT AFTER DELETE\n  ON users\n  FOR EACH ROW\n  EXECUTE FUNCTION public.func_sync_users();"}
```

**创建函数/存储过程**

直接传递完整 DDL，由调用方控制语法：

```json
{"id":"fn4","category":"FUNCTION","action":"CREATE","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"ddl":"CREATE OR REPLACE FUNCTION calculate_total(price DECIMAL, tax_rate DECIMAL DEFAULT 0.1)\nRETURNS DECIMAL\nLANGUAGE plpgsql\nAS $$\nBEGIN\n  RETURN price * (1 + tax_rate);\nEND;\n$$"}}
```

创建触发器示例：
```json
{"id":"fn4b","category":"FUNCTION","action":"CREATE","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"ddl":"CREATE OR REPLACE FUNCTION func_sync_t2_to_t1()\nRETURNS TRIGGER AS $$\nBEGIN\n  INSERT INTO t1 (id, name) VALUES (NEW.id, NEW.name);\n  RETURN NEW;\nEND;\n$$ LANGUAGE plpgsql"}}
```

响应：
```json
{"id":"fn4","success":true,"error":null,"data":{"success":true,"message":"函数/存储过程创建成功"}}
```

调用函数
```json
{"id":"fn5","category":"FUNCTION","action":"CALL","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"name":"calculate_total","routineType":"FUNCTION","schema":"public","args":["100.00","0.15"]}}
```

响应（函数返回结果）：
```json
{"id":"fn5","success":true,"error":null,"data":{"result":115.0,"row_count":1}}
```
调用存储过程

```json
{"id":"fn6","category":"FUNCTION","action":"CALL","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"name":"create_order","routineType":"PROCEDURE","schema":"public","args":["1","100","5"]}}
```

响应：
```json
{"id":"fn6","success":true,"error":null,"data":{"update_count":1}}
```

调试函数（EXPLAIN、执行计划、依赖分析

```json
{"id":"fn7","category":"FUNCTION","action":"DEBUG","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"name":"get_user_by_id","schema":"public"}}
```

响应：
```json
{"id":"fn7","success":true,"error":null,"data":[{"type":"EXPLAIN","output":"[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Relation Name\":\"users\",\"Filter\":\"(id = $1)\"}}]"},{"type":"INFO","output":"函数名: get_user_by_id\nSchema: public\n语言: plpgsql\n返回类型: SETOF users\n稳定性: STABLE\n安全性: SECURITY INVOKER\n参数: user_id integer"},{"type":"DEPENDENCIES","output":"TABLE: users"}]}
```

验证 DDL 语法（不创建，用于编辑时的语法检查）

```json
{"id":"fn8","category":"FUNCTION","action":"UPDATE","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"ddl":"CREATE OR REPLACE FUNCTION test_func(x INTEGER)\nRETURNS INTEGER AS $$\nBEGIN\n  RETURN x * 2;\nEND;\n$$ LANGUAGE plpgsql"}}
```

响应：
```json
{"id":"fn8","success":true,"error":null,"data":{"valid":true,"message":"DDL 语法验证通过"}}
```

**删除函数/存储过程

```json
{"id":"fn9","category":"FUNCTION","action":"DELETE","connection":{"driver":"Postgresql","host":"localhost","port":5432,"user":"postgres","password":"pass","database":"test_db"},"payload":{"name":"old_function","routineType":"FUNCTION","schema":"public","ifExists":true,"cascade":false}}
```

响应：
```json
{"id":"fn9","success":true,"error":null,"data":{"deleted":"old_function","routineType":"FUNCTION","schema":"public"}}
```

---

### PostgreSQL Schema 支持

所有 TABLE / DATA / SQL 操作均支持在 `payload` 中携带 `schema` 字段，指定 PostgreSQL 的 search_path：

```json
// 任何操作都可以加 schema 参数
{"payload": {"schema": "public", ...}}
```

- 传入 `schema` 后，引擎自动执行 `SET search_path TO <schema>`，确保无前缀表名正确解析到目标 schema
- 不传时使用 PostgreSQL 默认 search_path（通常含 `public`）
- MySQL 忽略 `schema` 参数

---

### EXPORT — 数据导出

基于自定义 SQL 的 5 种格式数据导出，**独立子进程运行**，全链路 JDBC 游标流式逐行处理，内存占用与数据总量无关，支持超大数据量稳定导出。

> **子进程隔离**：导出任务运行在独立的 JVM 子进程中（通过 `ExportProcessManager` 管理），即使导出千万级数据也不会导致主进程 OOM。主进程关闭时子进程自动停止。

**支持格式**：CSV、JSON Lines（.jsonl）、SQL INSERT、Excel（.xlsx）、Parquet

**请求**：
```json
{"id":"exp1","category":"EXPORT","action":"EXPORT","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"sql":"SELECT * FROM users","outputDir":"D:/exports","fileName":"users_2024","format":"CSV","tableName":"users","fetchSize":1000}}
```

字段说明：
- `sql`（必填）— 自定义 SELECT SQL
- `outputDir`（必填）— 输出目录（不存在自动创建）
- `fileName`（必填）— 文件名前缀（不含扩展名）
- `format`（必填）— `CSV` / `JSON_LINES` / `SQL_INSERT` / `EXCEL` / `PARQUET`
- `tableName` — SQL_INSERT 必填，用于生成 INSERT 语句前缀
- `fetchSize` — JDBC 游标拉取批次大小，默认 1000

**流式进度响应**（每行一条 JSON）：
```
{"id":"exp1","success":true,"stream":true,"end":false,"data":{"exportedRows":0,"columnCount":5,"completed":false}}
{"id":"exp1","success":true,"stream":true,"end":false,"data":{"exportedRows":1000,"columnCount":5,"completed":false}}
{"id":"exp1","success":true,"stream":true,"end":false,"data":{"exportedRows":2000,"columnCount":5,"completed":false}}
...
{"id":"exp1","success":true,"stream":true,"end":true,"data":{"exportedRows":13308,"columnCount":5,"completed":true,"filePath":"C:\\Users\\langb\\Desktop\\users_2024.csv"}}
```

- 进度每 1000 行发送一次
- 收到 `end: true` 时表示导出完成，`filePath` 为导出文件路径

**格式特性**：
- **CSV**：UTF-8 BOM 头保证 Excel 打开中文不乱码，自动处理字段转义
- **JSON Lines**：每行一个独立 JSON 对象，零新增依赖
- **SQL INSERT**：逐行生成 INSERT 语句，自动处理单引号转义
- **Excel**：POI SXSSF 流式 API，100 万行/Sheet 自动分页，1000 行内存窗口
- **Parquet**：动态 Schema，列式存储压缩

**数据库适配**：
- **MySQL**：自动设置 `fetchSize = Integer.MIN_VALUE` 启用流式游标
- **PostgreSQL**：自动关闭 `autoCommit` 启用服务端游标，导出完成后自动恢复

**停止导出**：

导出任务运行在独立子进程中，支持手动停止。在新的 EXPORT 请求中指定 `stopExportId` 即可取消对应的导出任务：

```json
{"id":"exp-stop","category":"EXPORT","action":"EXPORT","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"stopExportId":"exp1"}}
```

停止成功后，子进程返回错误响应：
```json
{"id":"exp1","success":false,"error":"Export cancelled by user","data":null}
```

---

### 错误响应示例

连接失败、SQL 语法错误等异常均返回统一错误格式：

```json
{"id":"99","success":false,"error":"Communications link failure: Unable to connect to host","data":null}
```

---

### 退出程序

发送 `CMD_EXIT` 帧或关闭 stdin 流（EOF），进程将清理所有连接池后正常退出。

## 测试

项目包含 **73 个测试，全部通过（0 失败，0 错误）**：

```bash
./gradlew engine:test
```

测试报告：`engine/build/reports/tests/test/index.html`

| 测试套件 | 测试数 | 范围 |
|---|---|---|
| `proto/FramingTest` | 15 | 帧协议 round-trip / BE 编码 / EOF / 大小限制 / 截断检测 |
| `proto/ProtoConvertersTest` | 29 | JsonElement ↔ PayloadValue 全路径（含 `numeric string stays STRING` 回归：字符串 `"42"` 不被误判为 NUMBER） |
| `WireFormatTest` | 24 | Request / Response / ExportCommand protobuf round-trip + `toHashKey` 6 字段独立性回归 |
| `WireProtocolSmokeTest` | 5 | 真实子进程 stdin/stdout 端到端（SYSTEM INFO / 截断 header / 损坏 protobuf / 嵌套 payload / 流式响应） |

## 架构特性

- **Protobuf Wire 协议**：所有 stdin/stdout 数据均为**长度前缀 protobuf 帧**（4-byte BE uint32 + N-byte payload），无 JSON 行；payload 内部允许任意字节（含 `\n` / `\0` / UTF-8 多字节）不破坏协议
- **PayloadValue 类型系统**：`Request.payload` 和 `Response.data` 均为 `PayloadValue`（仿 `google.protobuf.Value`），业务层继续用 `JsonObject`，边界由 `ProtoConverters` 双向转换；字符串严格识别（`"42"` 不被误判为 NUMBER）
- **方言插件化**：方言以独立 JAR 通过 SPI 动态加载，新增数据库无需改主引擎
- **异步非阻塞**：基于 Kotlin 协程实现高并发请求处理
- **输出串行化**：通过 `Channel<ByteArray>` 确保所有响应帧按顺序写出 stdout，不会交错
- **损坏输入容错**：主循环检测到截断 header / 损坏 payload 时发送错误帧并退出（流不可恢复）
- **无状态设计**：每次请求携带完整连接信息
- **连接池复用**：基于 SHA-256 Hash（包含 password）缓存 HikariCP 实例
- **自动资源回收**：10 分钟空闲自动释放连接池
- **安全防护**：强制使用 PreparedStatement 防止 SQL 注入
- **JDBC 游标流式**：大结果集通过服务端游标逐行拉取，避免客户端内存溢出
- **日志隔离**：所有日志输出到滚动文件 (`~/.config/idb/logs/idb-engine.log`)，不污染 stdout protobuf 流
- **嵌入式造数引擎**：LuaJIT 脚本驱动，支持多表按序造数、外键引用、沙箱隔离、流式进度回报；日期/时间列直接传 `LocalDate`/`LocalDateTime`/`LocalTime`，按 JDBC `DATE`/`TIMESTAMP`/`TIME` 类型绑定
- **函数与存储过程管理**：PostgreSQL 完整实现（创建/查询/调用/调试/删除/详情自动解析类型，含触发器支持），MySQL 占位
- **数据导出**：5 种格式（CSV / JSON Lines / SQL INSERT / Excel / Parquet），**独立子进程运行**，JDBC 游标流式逐行处理，支持超大数据量防 OOM
- **完整测试**：73 个单元测试 + 端到端冒烟测试（详见「测试」章节）

## 技术栈

- Kotlin 2.4.0
- JDK 25
- kotlinx-coroutines 1.11.0
- kotlinx-serialization 1.11.0（JSON — 业务层）
- **kotlinx-serialization-protobuf 1.11.0（wire 层）**
- HikariCP 7.0.2
- MySQL Connector/J 9.7.0
- PostgreSQL JDBC 42.7.11
- SLF4J 2.0.18 + Logback 1.5.13
- LuaJIT 4.1.0 (luajava — 嵌入式脚本引擎，造数功能)
- Apache POI 5.5.1（poi-ooxml — Excel 流式导出）
- Apache Parquet 1.17.1（parquet-hadoop — 列式存储导出）
- Apache Hadoop 3.5.0（hadoop-common — Parquet 文件系统抽象）