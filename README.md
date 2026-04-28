# 短链微服务 short-link-service

纯内存、线程安全、无外部依赖的短链微服务。基于 **Java 8 + Spring Boot 2.7.x**，仅依赖 JDK 标准库与 Guava，无需 Redis / MySQL / 配置中心，开箱即用。

---

## 目录

- [功能特性](#功能特性)
- [项目架构](#项目架构)
- [核心设计](#核心设计)
- [接口文档](#接口文档)
- [启动方式](#启动方式)
- [测试验证](#测试验证)

---

## 功能特性

| 特性 | 说明 |
|------|------|
| **普通短链** | 将任意长 URL 映射为 7 位 Base62 短码 |
| **盲盒短链** | 多目标 URL 随机跳转，适用于 A/B 测试、活动落地页 |
| **次数控制** | 盲盒短链可设最大解析次数，耗尽后自动断链 |
| **断链管理** | 支持手动标记断链 & 批量 HTTP HEAD 探活自动检测 |
| **组合查询** | 按短码 / 渠道 / 断链状态多维过滤 |
| **线程安全** | 全程无锁（CAS + AtomicXxx），支持高并发 |
| **零外部依赖** | 无 Redis / MySQL，重启后数据清空（纯演示 / 轻量场景） |

---

## 项目架构

### 目录结构

```
short-link-service/
├── Dockerfile                          多阶段构建镜像
├── docker-start.sh                     Docker 管理脚本
├── start.sh                            Linux 原生启动脚本
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/shortlink/
    │   │   ├── ShortLinkApplication.java          启动类
    │   │   ├── controller/
    │   │   │   └── ShortLinkController.java        REST 控制器（6 个接口）
    │   │   ├── service/
    │   │   │   └── ShortLinkService.java           核心业务逻辑
    │   │   ├── model/
    │   │   │   └── ShortLinkRecord.java            短链实体（含无锁并发控制）
    │   │   ├── dto/
    │   │   │   ├── ApiResponse.java                统一响应包装
    │   │   │   ├── GenerateReq.java                普通短链请求
    │   │   │   ├── BlindBoxReq.java                盲盒短链请求
    │   │   │   ├── ResolveResp.java                解析响应
    │   │   │   └── QueryReq.java                   组合查询请求
    │   │   └── exception/
    │   │       ├── BusinessException.java          业务异常
    │   │       └── GlobalExceptionHandler.java     全局异常拦截（@RestControllerAdvice）
    │   └── resources/
    │       └── application.yml
    └── test/
        └── java/com/shortlink/service/
            └── ShortLinkServiceTest.java           8 个单元测试用例
```

### 分层职责

```
HTTP 请求
    │
    ▼
┌─────────────────────────────────┐
│  ShortLinkController            │  参数接收、@Valid 校验、响应封装
└──────────────┬──────────────────┘
               │
               ▼
┌─────────────────────────────────┐
│  ShortLinkService               │  业务规则、短码生成、并发控制
│                                 │
│  store: ConcurrentHashMap       │  纯内存存储（Key=短码, Value=Record）
│  COUNTER: AtomicLong            │  全局单调递增短码序列号
└──────────────┬──────────────────┘
               │
               ▼
┌─────────────────────────────────┐
│  ShortLinkRecord                │  实体 + 并发方法                    │
│  ├── AtomicLong  resolveCount   │  解析计数（无锁）
│  ├── AtomicInteger remaining    │  剩余次数（CAS 自旋）
│  └── AtomicBoolean isBroken     │  断链标志（CAS 单次写）
└─────────────────────────────────┘
```

---

## 核心设计

### 1. 短码生成（Base62，7 位）

```
全局 AtomicLong 自增  →  toBase62(seq)  →  左补 '0' 至 7 位
```

- 字符集 `0-9a-zA-Z`（62 个字符），7 位理论容量 **62⁷ ≈ 35 亿**条
- `AtomicLong.getAndIncrement()` 保证多线程下序列号唯一，无锁无竞争
- 冲突时最多重试 3 次（理论不可达，防御性设计）

### 2. 盲盒次数控制（无锁 CAS）

```
resolveUrl() 内部 CAS 自旋：
  1. 读取 remainingCount（原子读）
  2. remaining <= 0  →  确保断链标记 → 返回 null
  3. CAS(current, current-1)
     成功：拿到本次解析机会；current-1==0 时负责标记断链
     失败：自旋重试（平均 1~2 次）
```

彻底消除「检查-减一-标记」三步之间的 TOCTOU 竞态，100 线程并发抢 50 次，精确无超发（测试用例 `testConcurrentMaxCount` 验证）。

### 3. 统一异常处理

```
BusinessException（自定义，含 code 字段）
    │
    ▼
GlobalExceptionHandler（@RestControllerAdvice）
    ├── BusinessException          → code + message 直接返回
    ├── MethodArgumentNotValidException → 提取所有字段错误拼接返回
    └── Exception                  → 500 兜底，屏蔽内部细节
```

### 4. 响应结构

所有接口统一返回：

```json
{
  "code": 200,
  "msg":  "success",
  "data": { }
}
```

业务错误时 `code` 为非 200 值（400 参数错误 / 404 不存在 / 410 已断链 / 500 系统错误）。

---

## 接口文档

### 基础信息

- **Base URL**：`http://localhost:8080`
- **Content-Type**：`application/json`

---

### 1. 生成普通短链

`POST /api/short-link/normal`

**请求体：**

```json
{
  "longUrl": "https://www.example.com/very/long/path?foo=bar",
  "channel": "wechat"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `longUrl` | string | ✅ | 目标长链，必须以 `http://` 或 `https://` 开头 |
| `channel` | string | ❌ | 渠道标识，默认 `"default"` |

**成功响应：**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "shortCode": "0000001",
    "urls": ["https://www.example.com/very/long/path?foo=bar"],
    "channel": "wechat",
    "createdAt": "2026-04-29T00:00:00",
    "resolveCount": 0,
    "maxResolveCount": 0,
    "remainingCount": -1,
    "blindBox": false,
    "broken": false,
    "brokenReason": null
  }
}
```

---

### 2. 生成盲盒短链

`POST /api/short-link/blindbox`

**请求体：**

```json
{
  "longUrls": [
    "https://landing-a.example.com",
    "https://landing-b.example.com",
    "https://landing-c.example.com"
  ],
  "maxCount": 100,
  "channel": "app"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `longUrls` | array | ✅ | 候选 URL 列表，**至少 2 个** |
| `maxCount` | int | ❌ | 最大解析次数，`0` 或不填表示不限 |
| `channel` | string | ❌ | 渠道标识，默认 `"default"` |

**成功响应** 同上，`blindBox: true`，`maxResolveCount: 100`，`remainingCount: 100`。

---

### 3. 解析短码

`GET /api/short-link/resolve/{code}`

**路径参数：**

| 参数 | 说明 |
|------|------|
| `code` | 7 位 Base62 短码 |

**成功响应：**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "targetUrl": "https://landing-b.example.com",
    "shortCode": "0000001",
    "resolveCount": 1,
    "blindBox": true
  }
}
```

**错误响应：**

| code | 场景 |
|------|------|
| 404 | 短码不存在 |
| 410 | 短链已断链（含次数耗尽） |

---

### 4. 手动标记断链

`PUT /api/short-link/{code}/broken`

**请求参数：**

| 参数 | 位置 | 必填 | 说明 |
|------|------|------|------|
| `code` | path | ✅ | 短码 |
| `reason` | query | ❌ | 断链原因描述 |

**示例：**

```bash
curl -X PUT "http://localhost:8080/api/short-link/0000001/broken?reason=活动已结束"
```

**成功响应：**

```json
{ "code": 200, "msg": "success", "data": null }
```

---

### 5. 批量探活检测

`POST /api/short-link/detect-broken`

对所有**未断链**的短链发起 HTTP HEAD 请求（超时 3s），响应码 ≥ 400 或网络异常时自动标记断链。

**示例：**

```bash
curl -X POST http://localhost:8080/api/short-link/detect-broken
```

**成功响应：**

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "shortCode": "0000001",
      "targetUrl": "https://example.com",
      "detectResult": "OK(status=200)",
      "newlyBroken": false
    },
    {
      "shortCode": "0000002",
      "targetUrl": "https://dead-link.example.com",
      "detectResult": "BROKEN(status=404)",
      "newlyBroken": true
    }
  ]
}
```

---

### 6. 组合查询

`GET /api/short-link/query`

所有参数可选，AND 语义组合过滤；全部不传则返回全量记录。

**查询参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `shortCode` | string | 精确匹配短码 |
| `channel` | string | 精确匹配渠道 |
| `broken` | boolean | `true` 仅返回已断链，`false` 仅返回未断链 |

**示例：**

```bash
# 查询 wechat 渠道下未断链的记录
curl "http://localhost:8080/api/short-link/query?channel=wechat&broken=false"

# 查询所有已断链记录
curl "http://localhost:8080/api/short-link/query?broken=true"

# 精确查询某条记录
curl "http://localhost:8080/api/short-link/query?shortCode=0000001"
```

---

## 启动方式

### 环境要求

| 依赖 | 版本要求 |
|------|---------|
| JDK | 8（`1.8.0_191+`） |
| Maven | 3.x |
| Docker | 20.x+（Docker 方式启动时需要） |

---

### 方式一：Linux 原生启动（`start.sh`）

适合直接在 Linux 服务器上运行，无需 Docker。

```bash
# 授权（只需一次）
chmod +x start.sh

# 构建并前台运行（看到日志，Ctrl+C 停止）
./start.sh

# 构建并后台守护运行
./start.sh --daemon

# 跳过构建，直接后台启动（JAR 已存在时）
./start.sh --skip-build --daemon

# 自定义端口
SERVER_PORT=9090 ./start.sh --daemon
```

**后台模式运维命令：**

```bash
tail -f logs/app.log                         # 实时日志
kill -0 $(cat logs/app.pid) && echo running  # 检查进程
kill $(cat logs/app.pid)                     # 停止服务
```

---

### 方式二：Docker 启动（`docker-start.sh`）

推荐用于生产部署或隔离测试，无需本地安装 Java / Maven。

```bash
# 授权（只需一次）
chmod +x docker-start.sh

# 一键构建镜像 + 启动容器（首次约 2~5 分钟）
./docker-start.sh start

# 其他常用命令
./docker-start.sh logs       # 实时跟踪日志
./docker-start.sh status     # 查看状态与资源占用
./docker-start.sh stop       # 停止并删除容器
./docker-start.sh restart    # 重新构建镜像 + 重启
./docker-start.sh clean      # 停止容器 + 删除镜像
```

**自定义配置（通过环境变量）：**

```bash
HOST_PORT=9090 ./docker-start.sh start             # 映射到宿主机 9090 端口
MEM_LIMIT=1g CPU_LIMIT=2 ./docker-start.sh restart # 调整资源限制
IMAGE_TAG=v1.1 ./docker-start.sh build             # 打版本标签
LOG_DIR=/var/log/shortlink ./docker-start.sh start # 自定义日志目录
```

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `HOST_PORT` | `8080` | 宿主机映射端口 |
| `MEM_LIMIT` | `512m` | 容器内存上限 |
| `CPU_LIMIT` | `1.0` | 容器 CPU 核数上限 |
| `IMAGE_TAG` | `latest` | 镜像标签 |
| `LOG_DIR` | `./logs` | 宿主机日志挂载目录 |

---

### 方式三：本地开发直接运行

```bash
# 编译并运行测试
mvn clean compile test -B

# 跳过测试直接启动
mvn spring-boot:run

# 或打包后运行
mvn clean package -DskipTests -B
java -jar target/short-link-service-1.0.0-SNAPSHOT.jar
```

---

### 启动成功验证

服务启动后，执行以下命令验证：

```bash
# 全量查询（应返回空列表）
curl http://localhost:8080/api/short-link/query

# 创建一条普通短链
curl -X POST http://localhost:8080/api/short-link/normal \
  -H "Content-Type: application/json" \
  -d '{"longUrl":"https://www.example.com","channel":"test"}'

# 解析刚创建的短码（将 0000000 替换为返回的实际 shortCode）
curl http://localhost:8080/api/short-link/resolve/0000000
```

---

## 测试验证

项目包含 8 个 JUnit 5 测试用例，覆盖核心并发与业务场景：

| 测试用例 | 验证内容 |
|----------|---------|
| `testConcurrentResolve` | 50 线程 × 20 次 = 1000 次并发解析，无异常，`resolveCount` 精确 |
| `testBlindBoxDistribution` | 100 次解析，4 个候选 URL 命中分布均匀 |
| `testMaxCountExhaustion` | 次数耗尽后自动断链，后续解析抛 410 异常 |
| `testConcurrentMaxCount` | 100 线程抢 50 次配额，成功=50，失败=50，零超发（CAS 验证） |
| `testManualMarkBroken` | 手动断链后解析被拦截，重复标记不覆盖首次 reason |
| `testQuery` | 渠道 / 断链状态 / 短码三维组合过滤准确性 |
| `testUrlValidation` | 空 URL、非 http/https URL、盲盒少于 2 个 URL 均抛 400 |
| `testResolveNotFound` | 不存在的短码返回 404 |

```bash
mvn clean test -B
```
