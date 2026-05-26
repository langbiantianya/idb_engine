# IDB Engine - Database Management Backend

基于 Kotlin + JDBC 的无头数据库管理引擎，通过 stdin/stdout 与 Wails 前端通信。

## 构建

```bash
# 构建普通 JAR
./gradlew build

# 构建 FatJar (包含所有依赖)
./gradlew shadowJar
```

构建产物位于 `build/libs/idb-engine.jar`

## 运行

```bash
java -jar build/libs/idb-engine.jar
```

程序启动后会监听标准输入，等待 JSON 请求。

## 通信协议

### 请求格式

```json
{
  "id": "req-uuid-1234",
  "category": "SCHEMA|USER|TABLE|DATA|SQL",
  "action": "LIST|CREATE|UPDATE|DELETE|EXECUTE",
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
  "data": {}
}
```

## 功能示例

### 1. 列出所有数据库/Schema

```json
{"id":"1","category":"SCHEMA","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"mysql"},"payload":{}}
```

### 2. 列出所有表

```json
{"id":"2","category":"TABLE","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{}}
```

### 3. 查询表数据（分页）

```json
{"id":"3","category":"DATA","action":"LIST","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"tableName":"users","page":1,"pageSize":20}}
```

### 4. 执行原生 SQL

```json
{"id":"4","category":"SQL","action":"EXECUTE","connection":{"driver":"mysql","host":"localhost","port":3306,"user":"root","password":"pass","database":"test_db"},"payload":{"sql":"SELECT * FROM users LIMIT 10"}}
```

### 5. 退出程序

发送 `CMD_EXIT` 或关闭 stdin 流。

## 架构特性

- **无状态设计**：每次请求携带完整连接信息
- **连接池复用**：基于 SHA-256 Hash 缓存 HikariCP 实例
- **自动资源回收**：10 分钟空闲自动释放连接池
- **安全防护**：强制使用 PreparedStatement 防止 SQL 注入
- **日志隔离**：所有日志输出到 stderr，不污染 stdout JSON 流

## 技术栈

- Kotlin 2.3.21
- JDK 21
- HikariCP 6.2.1
- MySQL Connector/J 9.1.0
- PostgreSQL JDBC 42.7.4
- kotlinx.serialization 1.7.3