# 📖 Wails-Kotlin 数据库管理端后端架构设计文档 (V1.0 完整版)

## 1. 架构总览 (Architecture Overview)

本项目采用 **Wails (Go) + Kotlin (JVM)** 混合架构，旨在开发一款高安全性、免安装、跨平台的桌面端数据库管理工具。
Kotlin 后端被设计为一个**无头 (Headless)**、**无状态 (Stateless)** 的底层”数据库算力引擎”。它不暴露任何网络端口（如 HTTP/WebSocket），完全依附于 Wails 主进程的生命周期，通过标准输入输出流（StdIn/StdOut）接收指令并返回结果。

**系统拓扑流：**
`[前端 Webview]` ↔ `[Wails Go 主进程]` ↔ `(StdIn/StdOut JSON 管道)` ↔ `[Kotlin 独立子进程]` ↔ `[MySQL / PostgreSQL]`

## 2. 技术栈选型 (Technology Stack)

- **核心语言**：Kotlin 2.3.21 / JDK 21
- **数据库驱动**：原生 JDBC (MySQL Connector/J 9.1.0, PostgreSQL JDBC Driver 42.7.4)
- **连接池管理**：HikariCP 6.2.1 (业界最高性能、资源占用低的连接池)
- **数据序列化**：`kotlinx.serialization` 1.7.3 (无反射、轻量化、原生支持 Kotlin 协程与数据类)
- **日志框架**：SLF4J + Logback (所有日志输出到 stderr)
- **构建与分发**：Gradle + ShadowJar (构建为单一 FatJar，后续可通过 GraalVM Native Image 编译为无 JRE 依赖的二进制文件)

## 3. 核心机制设计 (Core Mechanisms)

### 3.1 管道通信协议 (Pipeline I/O Protocol)

- **交互介质**：标准输入 (`System.in`) 与 标准输出 (`System.out`)。
- **数据格式**：单行压缩 JSON 字符串（Minified JSON）。
- **边界标识**：使用换行符 `\n` 作为单次请求和响应的结束符。
- **日志隔离**：Kotlin 内部的任何常规日志（如 `logger.info` 或异常堆栈）**必须**重定向到标准错误流 (`System.err`) 或本地日志文件，绝对禁止混入 `System.out`，以免破坏返回给 Go 进程的 JSON 结构。
- **长驻运行**：使用 `BufferedReader.readLine()` 阻塞式读取，支持长期驻留运行，直到收到 `CMD_EXIT` 或 stdin 关闭（EOF）。

### 3.2 绝对无状态设计 (Stateless Design)

Kotlin 进程不维护”当前选中的数据库”等业务状态。**每一次**请求都必须在其 JSON 载荷中携带完整的数据库连接凭证（IP、端口、账号、密码、库名）。

### 3.3 动态连接池管理器 (Dynamic Pool Manager)

为了解决无状态带来的频繁 TCP 握手开销，Kotlin 内部实现基于 Hash Key 的智能缓存连接池。

1. **连接复用**：根据传入的凭证生成 SHA-256 Hash，若缓存中已有对应的 HikariCP 实例且活跃，则直接复用。
2. **资源自动回收**：针对桌面端场景极致调优，`idleTimeout` 设为 10 分钟。若某个库 10 分钟无操作，该连接池将自动缩容直至完全销毁，释放本地内存资源。
3. **极限并发**：最大连接数 (`maximumPoolSize`) 限制为 5，足以应对单机用户的并发查询。
4. **连接超时**：`connectionTimeout` 设为 5 秒，快速失败避免界面卡死。

## 4. 数据交互契约 (JSON Protocol Spec)

### 4.1 统一请求体 (Request Envelope)

```json
{
  “id”: “req-uuid-1234”,
  “category”: “SCHEMA | USER | TABLE | DATA | SQL”,
  “action”: “LIST | CREATE | UPDATE | DELETE | EXECUTE”,
  “connection”: {
    “driver”: “mysql | postgresql”,
    “host”: “127.0.0.1”,
    “port”: 3306,
    “user”: “root”,
    “password”: “secret_password”,
    “database”: “target_db”
  },
  “payload”: { 
    // 具体的业务参数，详见功能模块
  }
}
```

### 4.2 统一响应体 (Response Envelope)

```json
{
  “id”: “req-uuid-1234”,
  “success”: true,
  “error”: null,
  “data”: {
    // 根据 action 返回对应的结果 (如 List<Map> 或受影响行数)
  }
}
```

## 5. 功能模块详细设计 (Feature Modules)

为兼容 MySQL 与 PostgreSQL，底层业务统一使用 `java.sql.DatabaseMetaData` 及标准 SQL 方言适配器模式。

### 5.1 架构管理 (Schema Management)

处理不同库的物理层级差异。MySQL 将其视为 `Database`，PG 将其视为 `Database -> Schema`。

- **LIST**: 获取可用架构。MySQL 走 `SHOW DATABASES`，PG 走 `information_schema.schemata`。
- **CREATE / DELETE**: 映射为 `CREATE/DROP DATABASE` 或 `CREATE/DROP SCHEMA`。

### 5.2 用户权限 (User & Privilege)

- **LIST**: 查询系统用户表（PG: `pg_user`, MySQL: `mysql.user`）。
- **UPDATE (授权/回收)**:
    - Payload: `{“user”: “dev”, “schema”: “public”, “privileges”: [“SELECT”, “INSERT”], “isGrant”: true}`
    - Kotlin 转换为标准 `GRANT ... ON ... TO ...` 或 `REVOKE ...` 执行。

### 5.3 表结构元数据 (Table Metadata)

- **LIST (查看表列表)**: 强制使用 JDBC `connection.metaData.getTables`，拒绝拼接系统表查询，保证极高的版本兼容性。
- **COLUMN_LIST (查看列与主键)**: 使用 `metaData.getColumns` 和 `metaData.getPrimaryKeys`，将各数据库专属类型（如 PG 的 `varchar` 与 MySQL 的 `varchar`）统一映射为前端友好的通用类型表示。
- **CREATE / UPDATE / DELETE**: 提供建表、增删改字段的 DDL 组装器。

### 5.4 表数据运维 (Table Data CRUD)

为了防止大数据量拖垮本地内存，强制实施分页和参数化查询。

- **LIST**:
    - 必须传入 `page` 和 `pageSize`。
    - 无论 MySQL 还是 PG，均拼装 `LIMIT ? OFFSET ?`。
    - **大字段截断策略**：若检测到 `BLOB`, `LONGTEXT`, `BYTEA`，默认返回 `[LOB Data]` 占位符，需前端额外发请求按需获取，防止 JSON 爆满。
- **CREATE / UPDATE / DELETE**:
    - 强制使用 `PreparedStatement`。
    - Payload 示例：`{“tableName”: “users”, “changes”: {“name”: “Alex”}, “where”: {“id”: 1}}`
    - 严格按类型绑定参数（如 `stmt.setString`, `stmt.setInt`），**从物理层绝缘 SQL 注入**。

### 5.5 原生 SQL 引擎 (Arbitrary SQL Engine)

- **EXECUTE**: 接收前端传来的纯 SQL 字符串。
- 内部调用 `statement.execute()`，并判断返回值：
    - 若为 `true` (结果集): 提取 `ResultSetMetaData`，遍历组装 `List<Map<String, String>>`（列名 -> 值）返回。
    - 若为 `false` (更新操作): 获取 `updateCount`，返回 `{ “affectedRows”: N }`。

## 6. 安全与健壮性保障 (Security & Reliability)

1. **防进程孤儿 (Graceful Shutdown)**：
   - Kotlin 主循环使用 `BufferedReader.readLine()` 阻塞式读取标准输入流。
   - 当读到 `null` (EOF/输入流关闭) 或特定指令 `”CMD_EXIT”` 时，调用 `PoolManager.closeAll()` 清理所有连接池，并调用 `exitProcess(0)` 正常退出。
   - 添加 JVM Shutdown Hook，确保即使进程被强制终止也能清理资源。
   - Wails 的 Go 进程在关闭时负责切断管道。

2. **连接超时管控 (Timeout Protection)**：
   在 HikariCP 中配置 `connectionTimeout = 5000` (5秒)。当用户输入了错误的 IP 或密码时，能在 5 秒内快速失败并把错误 JSON 返回给前端，避免界面长时间卡死。

3. **全局异常捕获 (Global Exception Handler)**：
   所有的 JDBC `SQLException`（如语法错误、主键冲突、权限不足）都会被 Kotlin 拦截，并提取 `e.message` 包装入 Response 的 `error` 字段。**绝对禁止**应用因未捕获异常而崩溃退出。

## 7. 工程目录结构 (Directory Structure)

```
src/main/kotlin/
├── Main.kt                    // 入口点，维护 BufferedReader 阻塞式读取循环
├── dispatcher/
│   └── RequestDispatcher.kt   // 解析 JSON，分发请求路由
├── pool/
│   └── PoolManager.kt         // HikariCP 动态管理与 SHA-256 缓存
├── handlers/                  // 业务处理层
│   ├── SchemaHandler.kt       
│   ├── TableHandler.kt        
│   ├── DataHandler.kt         
│   ├── UserHandler.kt         
│   └── SqlEngineHandler.kt    
├── models/                    // 数据契约
│   ├── Request.kt             
│   └── Response.kt            
└── resources/
    └── logback.xml            // 日志配置（输出到 stderr）
```

## 8. 构建与部署 (Build & Deploy)

### 8.1 构建 FatJar

```bash
./gradlew shadowJar
```

产物位于 `build/libs/idb-engine.jar`，包含所有依赖，可直接运行。

### 8.2 运行

```bash
java -jar build/libs/idb-engine.jar
```

### 8.3 与 Wails 集成

Go 进程通过 `exec.Command` 启动 Kotlin 子进程，并通过 stdin/stdout 管道通信：

```go
cmd := exec.Command(“java”, “-jar”, “idb-engine.jar”)
stdin, _ := cmd.StdinPipe()
stdout, _ := cmd.StdoutPipe()
cmd.Start()

// 发送请求
stdin.Write([]byte(jsonRequest + “\n”))

// 读取响应
scanner := bufio.NewScanner(stdout)
scanner.Scan()
response := scanner.Text()
```

## 9. 实现状态 (Implementation Status)

✅ 已完成：
- 核心架构与通信协议
- 连接池管理（HikariCP + SHA-256 缓存）
- 五大业务模块（Schema/Table/Data/User/SQL）
- 长驻运行机制（BufferedReader + Shutdown Hook）
- 日志隔离（stderr）
- 构建配置（Gradle + ShadowJar）

⏳ 待扩展：
- GraalVM Native Image 编译
- 更多数据库方言支持（Oracle, SQL Server）
- 性能监控与指标上报
