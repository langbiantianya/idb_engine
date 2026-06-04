# IDB Engine - Database Management Backend

基于 Kotlin + JDBC 的无头数据库管理引擎，通过 stdin/stdout 与 Wails 前端通信。

## 构建

```bash
./gradlew jar
```

构建产物结构：
```
build/libs/
├── idb-engine.jar      ← 主程序瘦包
├── libs/               ← 运行时依赖（Kotlin、HikariCP、日志等）
└── drivers/            ← JDBC 驱动（内置 MySQL/PostgreSQL，可追加）
```

## 运行

```bash
cd build/libs && java -jar idb-engine.jar
```

程序启动后会监听标准输入，等待 JSON 请求。

## 通信协议

### 请求格式

```json
{
  "id": "req-uuid-1234",
  "category": "SCHEMA|USER|TABLE|DATA|SQL|SYSTEM",
  "action": "LIST|CREATE|UPDATE|DELETE|EXECUTE|GET_DDL|INFO",
  "connection": {
    "driver": "mysql|postgresql",
    "host": "127.0.0.1",
    "port": 3306,
    "user": "root",
    "password": "password",
    "database": "test_db"
  },
  "payload": {}
}
```

### 响应格式

```json
{
  "id": "req-uuid-1234",
  "success": true,
  "error": null,
  "stream": false,
  "end": false,
  "data": {}
}
```

- `stream` / `end`：流式响应专用字段，普通响应中为 `false`（可忽略）。详见下方「流式全量查询」。

## 功能示例

> 所有请求均为单行压缩 JSON，以 `\n` 结尾发送到 stdin；响应从 stdout 读取一行。

### SCHEMA — 架构管理

**列出所有数据库/Schema**

```json
{"id":"1","category":"SCHEMA","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{}}
```

响应：
```json
{"id":"1","success":true,"error":null,"data":["information_schema","mysql","test_db"]}
```

**创建数据库**

```json
{"id":"2","category":"SCHEMA","action":"CREATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{"name":"new_db"}}
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

---

### TABLE — 表结构元数据

**列出所有表**

```json
{"id":"8","category":"TABLE","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{}}
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

```json
{"id":"10","category":"TABLE","action":"CREATE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"products","columns":[{"name":"id","type":"INT","nullable":false,"isPrimaryKey":true},{"name":"name","type":"VARCHAR","size":255,"nullable":false},{"name":"price","type":"DECIMAL","nullable":true,"defaultValue":"0.00"}]}}
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

**更新/DDL（返回受影响行数）**

```json
{"id":"20","category":"SQL","action":"EXECUTE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"sql":"UPDATE users SET name = 'Frank' WHERE id = 3"}}
```

响应：
```json
{"id":"20","success":true,"error":null,"data":{"affectedRows":1}}
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

### 错误响应示例

连接失败、SQL 语法错误等异常均返回统一错误格式：

```json
{"id":"99","success":false,"error":"Communications link failure: Unable to connect to host","data":null}
```

---

### 退出程序

发送 `CMD_EXIT` 或关闭 stdin 流（EOF），进程将清理所有连接池后正常退出。

## 架构特性

- **异步非阻塞**：基于 Kotlin 协程实现高并发请求处理
- **输出串行化**：通过 Channel 确保标准输出不会交错混乱
- **无状态设计**：每次请求携带完整连接信息
- **连接池复用**：基于 SHA-256 Hash 缓存 HikariCP 实例
- **自动资源回收**：10 分钟空闲自动释放连接池
- **安全防护**：强制使用 PreparedStatement 防止 SQL 注入
- **JDBC 游标流式**：大结果集通过服务端游标逐行拉取，避免客户端内存溢出
- **日志隔离**：所有日志输出到滚动文件 (`~/.config/idb/logs/idb-engine.log`)，不污染 stdout JSON 流

## 技术栈

- Kotlin 2.3.21
- JDK 21
- kotlinx-coroutines 1.11.0
- HikariCP 7.0.2
- MySQL Connector/J 9.7.0
- PostgreSQL JDBC 42.7.11
- kotlinx.serialization 1.11.0
- SLF4J 2.0.18 + Logback 1.5.13