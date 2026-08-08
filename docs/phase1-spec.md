# mcp-cache-gateway Phase 1 Spec（7 天 · Track A · 主人 2026-08-08 11:18 拍板）

> **作者**：skill-master（智多星）
> **接收**：shrek（agent:shrek）
> **验收**：pikachu（agent:pikachu，独立 verify）
> **协调**：skill-master
> **关联**：
> - 设计 doc：`docs/design.md`（v1.0 草案，11 sections + 5 Mermaid flowcharts）
> - 主人授权：fuermos 2026-08-08 11:18 "A"
> - 战略上下文：mcp-cache-gateway = tubi-mcp 下一代（取代 + 增量）

---

## 0. TL;DR

| 维度 | 内容 |
|------|------|
| **目标** | Spring Boot 3 + Kotlin MCP cache gateway，sidecar 模式起步 + wrongnotebook 部分迁移 |
| **工作量** | 7 天 |
| **里程碑** | Day 7 能独立处理 wrongnotebook 5 个最常用 tools（替换 tubi-mcp ~30%）|
| **技术栈** | Kotlin 1.9 + Spring Boot 3.3 + Spring WebFlux + Spring Data Redis/JDBC + zt-exec + MCP Kotlin SDK |
| **测试** | JUnit 5 + MockK + Testcontainers（Redis + PostgreSQL）>80% 行覆盖 |
| **文档** | 4 个 doc + 3 个 ADR + per-module README |
| **派工** | shrek（实施）+ pikachu（独立 verify）+ skill-master（协调） |

---

## 1. 目标与非目标

### 1.1 目标（Phase 1 必须 ship）
1. ✅ Spring Boot + Kotlin 项目骨架（gradle KTS + 依赖完整）
2. ✅ 两层缓存（Redis Tier 1 + PostgreSQL Tier 2）
3. ✅ request_id first-class + params_hash fallback
4. ✅ Per-tool TTL 配置
5. ✅ SWR（Stale-while-revalidate）
6. ✅ Negative caching
7. ✅ Lazy server loading
8. ✅ notifications/list_changed 失效处理
9. ✅ **wrongnotebook bridge（5 个最常用 tools）** ← 第一个迁移
10. ✅ JUnit 测试 + 集成测试 + e2e smoke
11. ✅ 文档（architecture / deployment / performance + 3 ADR + per-module README）

### 1.2 非目标（Phase 1 不做）
- ❌ wigolo bridge 迁移（Phase 2）
- ❌ exameow bridge 迁移（Phase 3）
- ❌ pdf-router 迁移（Phase 4）
- ❌ Adaptive TTL tuning（Phase 6 opt-in）
- ❌ MCP Streamable HTTP（先 stdio，HTTP Phase 2）
- ❌ Multi-user / 权限隔离（tubi-mcp 也是 single-user，无需改）

---

## 2. 架构图（高层）

```
┌──────────────────┐
│  LLM Agent       │
└────────┬─────────┘
         │ JSON-RPC (stdio)
         ▼
┌─────────────────────────────────────────┐
│  mcp-cache-gateway (Spring Boot 3)       │
│  ┌────────────────────────────────┐    │
│  │ Transport Layer                │    │
│  │  - stdio JSON-RPC              │    │
│  │  - lazy spawn (借鉴 tubi-mcp   │    │
│  │    wigolo-bridge 模式)         │    │
│  └──────────┬─────────────────────┘    │
│             ▼                            │
│  ┌────────────────────────────────┐    │
│  │ Cache Pipeline                  │    │
│  │  - request_id lookup            │    │
│  │  - params_hash fallback         │    │
│  │  - SWR + negative cache         │    │
│  │  - per-tool TTL                 │    │
│  └──────────┬─────────────────────┘    │
│             │                            │
│  ┌──────────┴──────────┐                │
│  │ Tier 1: Redis     │ Tier 2: PostgreSQL│
│  │ (ioredis / Lettuce)│ (pg / JDBC)      │
│  └────────┬──────────┘                │
│           │                            │
│  ┌────────▼─────────────────────┐    │
│  │ Bridge: wrongnotebook         │    │
│  │  - 5 tools (subset of 15)     │    │
│  │  - 借鉴 tubi-mcp               │    │
│  │    wrongnotebook-mcp-bridge.js │    │
│  │  - NextAuth auto-login         │    │
│  └────────┬─────────────────────┘    │
└───────────┼──────────────────────────┘
            ▼
   ┌────────────────────┐
   │ wrong-notebook     │
   │ (Next.js + Prisma) │
   │ localhost:3032│
   └────────────────────┘
```

---

## 3. Module 结构（详细到文件）

```
mcp-cache-gateway/
├── build.gradle.kts                  # Gradle KTS 配置
├── settings.gradle.kts
├── gradle.properties
├── gradlew + gradlew.bat + wrapper/
├── src/
│   ├── main/
│   │   ├── kotlin/com/fuermos/mcp/cache/gateway/
│   │   │   ├── Application.kt                    # @SpringBootApplication entry
│   │   │   ├── config/
│   │   │   │   ├── GatewayConfig.kt              # 主配置（端口/Redis/PG/tool config 路径）
│   │   │   │   ├── ToolConfig.kt                 # 解析 tools.yaml（per-tool TTL + metadata）
│   │   │   │   └── ServerConfig.kt               # server registry（lazy spawn 目标）
│   │   │   ├── transport/
│   │   │   │   ├── StdioTransport.kt             # stdio JSON-RPC 解析（借鉴 mcp-cache-proxy.js）
│   │   │   │   ├── JsonRpcEnvelope.kt            # request/response/notification 数据类
│   │   │   │   └── RequestId.kt                  # UUID v7 生成
│   │   │   ├── server/
│   │   │   │   ├── ServerLifecycleManager.kt    # lazy spawn + cleanup（借鉴 wigolo-bridge）
│   │   │   │   └── ServerPool.kt            # server_id → proc 映射
│   │   │   ├── cache/
│   │   │   │   ├── CacheEntry.kt                 # 数据类（含 staleUntil 字段）
│   │   │   │   ├── CacheKey.kt                   # 缓存键算法（request_id 优先 + params_hash）
│   │   │   │   ├── CacheLookup.kt                # Step 1/2 查找逻辑
│   │   │   │   ├── CacheWrite.kt                 # Redis sync + DB async
│   │   │   │   ├── SwrManager.kt                 # SWR 窗口 + async refresh
│   │   │   │   └── NegativeCache.kt              # 错误结果短 TTL
│   │   │   ├── persistence/
│   │   │   │   ├── RedisClient.kt                # Lettuce 包装
│   │   │   │   ├── PostgresClient.kt             # HikariCP + jdbcTemplate
│   │   │   │   ├── migrations/                   # Flyway migrations
│   │   │   │   │   └── V1__initial_schema.sql
│   │   │   │   └── CacheRepository.kt            # PG CRUD
│   │   │   ├── observability/
│   │   │   │   ├── Metrics.kt                    # Micrometer 包装（Prometheus）
│   │   │   │   └── Logging.kt                    # structured logging
│   │   │   ├── bridge/
│   │   │   │   └── wrongnotebook/
│   │   │   │       ├── WrongNotebookBridge.kt    # 主入口（nextauth + token persistence）
│   │   │   │       ├── WrongNotebookAuth.kt      # NextAuth auto-login
│   │   │   │       ├── WrongNotebookClient.kt    # HTTP client（WebClient）
│   │   │   │       └── tools/
│   │   │   │           ├── GetNotebookTool.kt    # 5 tools 第一批
│   │   │   │           ├── ListNotebooksTool.kt
│   │   │   │           ├── AddQuestionTool.kt
│   │   │   │           ├── UpdateQuestionTool.kt
│   │   │   │           └── DeleteQuestionTool.kt
│   │   │   ├── gateway/
│   │   │   │   ├── GatewayOrchestrator.kt        # 整体编排
│   │   │   │   └── McpMethodRouter.kt            # tools/list + tools/call 路由
│   │   │   └── utils/
│   │   │       ├── Json.kt                        # kotlinx.serialization helpers
│   │   │       └── Hashing.kt                     # sha256(normalize(params))
│   │   └── resources/
│   │       ├── application.yml                    # Spring Boot 配置
│   │       └── logback-spring.xml
│   └── test/
│       └── kotlin/com/fuermos/mcp/cache/gateway/
│           ├── cache/
│           │   ├── CacheLookupTest.kt             # 单元测试
│           │   ├── CacheKeyTest.kt
│           │   └── SwrManagerTest.kt
│           ├── transport/
│           │   └── JsonRpcEnvelopeTest.kt
│           ├── persistence/
│           │   ├── RedisClientTest.kt              # Testcontainers Redis
│           │   └── PostgresClientTest.kt           # Testcontainers PostgreSQL
│           ├── integration/
│           │   ├── CachePipelineIntegrationTest.kt
│           │   ├── BridgeIntegrationTest.kt
│           │   └── SwrIntegrationTest.kt
│           └── e2e/
│               └── WrongNotebookSmokeTest.kt       # 真实 wrong-notebook API
├── docs/
│   ├── design.md                                   # v1.0 草案（已存）
│   ├── architecture.md                             # ★ shrek Day 7 输出
│   ├── deployment.md                               # ★ shrek Day 7 输出
│   ├── performance.md                              # ★ shrek Day 7 输出
│   ├── wrongnotebook-mcp-tools.md                  # ★ 移植的 5 tools spec
│   └── adr/
│       ├── 0001-stack-ts-to-kotlin.md              # ★ skill-master 已写
│       ├── 0002-gradual-migration.md               # ★ skill-master 已写
│       └── 0003-cache-strategy.md                  # ★ shrek Day 7 写（cache 实施决策）
├── examples/
│   ├── openclaw-config.json                        # OpenClaw MCP 集成示例
│   └── tools.yaml                                  # per-tool TTL 配置示例
├── README.md
├── LICENSE
└── .gitignore
```

**LOC 估算**：
- 主代码：~2500-3000 LOC
- 测试：~1500-2000 LOC（>80% 覆盖）
- 配置 + 文档：~500 LOC
- **总计**：~4500-5500 LOC

---

## 4. Day-by-Day 详细任务

#### **Day 1 — Setup + Lazy Spawn（8h）**

**Morning (4h)**：项目骨架
- [ ] `gradle init --type kotlin-application` （或手写 build.gradle.kts）
- [ ] 配置 `build.gradle.kts`：Spring Boot 3.3 + Kotlin 1.9 + 所有依赖（见 §5）
- [ ] 配置 `settings.gradle.kts` + `gradle.properties`
- [ ] 创建 `Application.kt`（@SpringBootApplication）
- [ ] 配置 `application.yml`（server port + Redis/PG URLs + tool config path）
- [ ] `.gitignore` 已存在，verify Gradle 输出目录被忽略
- [ ] **commit**: `chore: Spring Boot 3 + Kotlin skeleton`

**Afternoon (4h)**：Transport + Lazy Spawn
- [ ] `transport/JsonRpcEnvelope.kt`：JSON-RPC 2.0 request/response/notification 数据类
- [ ] `transport/RequestId.kt`：UUID v7 生成（用 `com.fasterxml.uuid:java-uuid-generator`）
- [ ] `transport/StdioTransport.kt`：stdio JSON-RPC 解析（line-delimited JSON）+ 序列化
- [ ] `server/ServerLifecycleManager.kt`：lazy spawn + cleanup tick
- [ ] `server/ServerPool.kt`：server_id → ChildProcess 映射
- [ ] **测试**：单测（StdioTransport 解析、ServerLifecycleManager spawn/kill）
- [ ] **commit**: `feat: transport + lazy server loading`

**借鉴**：
- `tubi-mcp/wigolo-bridge.js`（subprocess spawn 模式 + backpressure queue）
- `mcp-cache-proxy.js`（stdio JSON-RPC 解析）

---

#### **Day 2 — Two-Tier Cache（8h）**

**Morning (4h)**：Redis Tier 1
- [ ] `persistence/RedisClient.kt`：Lettuce 包装
- [ ] `cache/CacheEntry.kt`：数据类（request_id, params_hash, result, freshUntil, staleUntil, ttlMs, hitCount）
- [ ] `cache/CacheKey.kt`：键算法（request_id 优先 → params_hash fallback）
- [ ] `cache/CacheLookup.kt`：Step 1 request_id 查 Redis
- [ ] `cache/CacheWrite.kt`：Redis SETEX 同步写
- [ ] **测试**：单测（CacheKey 一致性、CacheLookup hit/miss）
- [ ] **commit**: `feat: Redis Tier 1 cache`

**Afternoon (4h)**：PostgreSQL Tier 2
- [ ] `persistence/PostgresClient.kt`：HikariCP + jdbcTemplate
- [ ] `persistence/migrations/V1__initial_schema.sql`：mcp_request_state + mcp_tool_config 表（参考设计 doc §3.3）
- [ ] 配置 Flyway migration 自动执行
- [ ] `persistence/CacheRepository.kt`：PG CRUD（insert / query by request_id / query by params_hash）
- [ ] `cache/CacheWrite.kt`：DB 异步写（用 `@Async` 或 Kotlin coroutine）
- [ ] **测试**：Testcontainers PostgreSQL（real DB）
- [ ] **commit**: `feat: PostgreSQL Tier 2 cache`

**借鉴**：
- `tubi-mcp/mcp-cache-proxy.js`（shrek 自写的 cache 实现模式）
- 设计 doc §3.3 schema（直接对应）

---

#### **Day 3 — Lookup Pipeline + TTL（8h）**

**Morning (4h)**：完整 Lookup Pipeline
- [ ] `cache/CacheLookup.kt`：Step 1 (request_id) + Step 2 (params_hash) + tool_version 一致性检查
- [ ] `cache/CacheWrite.kt`：合并 Redis + DB 写入逻辑
- [ ] `gateway/GatewayOrchestrator.kt`：orchestrate lookup → execute → write back
- [ ] **测试**：集成测试（mock Redis + mock DB，验证 lookup 流程）
- [ ] **commit**: `feat: complete cache lookup pipeline`

**Afternoon (4h)**：Per-tool TTL Config
- [ ] `config/ToolConfig.kt`：解析 `tools.yaml`（kotlinx.serialization）
- [ ] `cache/CacheWrite.kt`：读取 toolConfig.ttlMs 应用到 freshUntil
- [ ] `examples/tools.yaml`：示例配置（get_weather 5min / solve_math 1day 等）
- [ ] `gateway/McpMethodRouter.kt`：tools/list 返回 tool metadata（含 ttlMs/timeSensitive）
- [ ] **测试**：单测（ToolConfig 解析）+ 集成测试（不同 tool 不同 TTL）
- [ ] **commit**: `feat: per-tool TTL configuration`

---

#### **Day 4 — SWR + Negative Cache + Invalidation（8h）**

**Morning (4h)**：SWR
- [ ] `cache/SwrManager.kt`：fresh window / stale window / expired window 状态判断
- [ ] `cache/CacheLookup.kt`：在 stale window 时返回 stale + schedule async refresh
- [ ] `cache/CacheWrite.kt`：写入时设置 `staleUntil = freshUntil + swrGraceMs`
- [ ] **测试**：单元测试（窗口判断）+ 集成测试（stale window 行为）
- [ ] **commit**: `feat: stale-while-revalidate`

**Afternoon (4h)**：Negative Cache + Invalidation
- [ ] `cache/NegativeCache.kt`：5xx / timeout 短 TTL（默认 300s/60s）
- [ ] `cache/CacheLookup.kt`：检测 4xx 不缓存、5xx 走 negative cache
- [ ] `gateway/McpMethodRouter.kt`：监听 `notifications/list_changed` → invalidate 缓存
- [ ] `gateway/McpMethodRouter.kt`：tool version 变化 → 自动 invalidate（key 含 version）
- [ ] **测试**：单测 + 集成测试
- [ ] **commit**: `feat: negative cache + invalidation`

---

#### **Day 5 — Wrongnotebook Bridge（8h）**

**Morning (4h)**：Bridge Skeleton + Auth
- [ ] `bridge/wrongnotebook/WrongNotebookClient.kt`：WebClient + base URL（默认 `http://localhost:3032`）
- [ ] `bridge/wrongnotebook/WrongNotebookAuth.kt`：NextAuth auto-login + CSRF + cookie 处理
- [ ] `bridge/wrongnotebook/WrongNotebookBridge.kt`：初始化 + 持久化 token 到 `~/.openclaw/state/wrongnotebook-credentials.json`
- [ ] **测试**：单元测试（auth flow mock）
- [ ] **commit**: `feat: wrongnotebook bridge skeleton + auth`

**Afternoon (4h)**：5 Tools 实现
- [ ] `bridge/wrongnotebook/tools/GetNotebookTool.kt`
- [ ] `bridge/wrongnotebook/tools/ListNotebooksTool.kt`
- [ ] `bridge/wrongnotebook/tools/AddQuestionTool.kt`
- [ ] `bridge/wrongnotebook/tools/UpdateQuestionTool.kt`
- [ ] `bridge/wrongnotebook/tools/DeleteQuestionTool.kt`
- [ ] `gateway/McpMethodRouter.kt`：注册 5 tools 到 tools/list 响应
- [ ] **测试**：单元测试（每个 tool）+ 集成测试（5 tools 端到端）
- [ ] **commit**: `feat: wrongnotebook 5 tools`

**借鉴**：
- `tubi-mcp/wrongnotebook-mcp-bridge.js`（15 tools 全实现，本期只取 5 个最常用）
- `tubi-mcp/wrong-notebook-bridge.js`（NextAuth + token persistence）
- `tubi-mcp/docs/wrongnotebook-mcp-tools.md`（15 tools spec）

**Phase 2 范围**：剩余 10 tools 移到 Phase 2

---

#### **Day 6 — 测试覆盖（8h）**

**Morning (4h)**：单元 + 集成测试补全
- [ ] 单元测试覆盖率从 70% 提到 >80%
- [ ] 集成测试增加 edge cases：MRTR bypass / timeSensitive / Redis 挂 fallback DB / DB 挂 return miss
- [ ] Testcontainers 配置（Redis 7 + PostgreSQL 15）
- [ ] **commit**: `test: improve coverage to >80%`

**Afternoon (4h)**：e2e Smoke Test
- [ ] `e2e/WrongNotebookSmokeTest.kt`：真实 wrong-notebook API 集成测试
- [ ] 性能 benchmark：
  - Hit rate（空 cache → 100 calls，测量 hit rate）
  - Latency p50/p95/p99（cache hit vs miss）
  - Token savings（粗估，real MCP call vs cached）
- [ ] **commit**: `test: e2e smoke + performance bench`

---

#### **Day 7 — 文档 + 收尾（8h）**

**Morning (4h)**：文档
- [ ] `docs/architecture.md`：系统架构 + Mermaid diagram（借鉴设计 doc §3.1）
- [ ] `docs/deployment.md`：部署步骤 + config 示例 + systemd unit 模板
- [ ] `docs/performance.md`：benchmark 结果 + 调优建议
- [ ] `docs/wrongnotebook-mcp-tools.md`：5 tools spec（移植 tubi-mcp docs + Kotlin adaptation）
- [ ] `docs/adr/0003-cache-strategy.md`：cache 实施决策（为什么两 tier / SWR 启用规则）
- [ ] per-module README：`src/cache/README.md` / `src/transport/README.md` / 等
- [ ] **commit**: `docs: Phase 1 documentation`

**Afternoon (4h)**：README + Final Polish
- [ ] 更新 `README.md`：添加 "Replace tubi-mcp" 章节 + Build & Run（Gradle 命令）+ 部署步骤
- [ ] 更新 `docs/design.md`：附录 B "Phase 1 implementation notes"
- [ ] 更新 `examples/openclaw-config.json`：OpenClaw MCP config 集成示例
- [ ] 验证 `./gradlew build` + `./gradlew test` 全过
- [ ] 验证 `./gradlew run` 能启动 gateway
- [ ] **commit**: `docs: README + design update`
- [ ] **push** 到 origin main

---

## 5. 关键技术细节

### 5.1 build.gradle.kts（核心依赖）

```kotlin
plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.4"
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    
    // Database
    implementation("org.flywaydb:flyway-core")
    implementation("org.postgresql:postgresql")
    
    // JSON-RPC + HTTP
    implementation("com.fasterxml.uuid:java-uuid-generator:5.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    
    // Process management
    implementation("org.zeroturnaround:zt-exec:1.12")
    
    // MCP SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.5.0")
    
    // Metrics
    implementation("io.micrometer:micrometer-registry-prometheus")
    
    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:redis")
}
```

### 5.2 PostgreSQL Schema（V1__initial_schema.sql）

```sql
CREATE TABLE mcp_request_state (
    request_id      TEXT PRIMARY KEY,
    server_id       TEXT NOT NULL,
    method          TEXT NOT NULL,
    tool_name       TEXT,
    tool_version    TEXT,
    params_hash     TEXT NOT NULL,
    params_json     JSONB NOT NULL,
    result_json     JSONB,
    result_size     INTEGER,
    cache_tier      TEXT,
    ttl_ms          INTEGER NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    invalidated     BOOLEAN DEFAULT FALSE,
    hit_count       INTEGER DEFAULT 0,
    metadata        JSONB
);

CREATE INDEX idx_params_hash ON mcp_request_state (params_hash);
CREATE INDEX idx_expires_at_active ON mcp_request_state (expires_at) 
    WHERE NOT invalidated;
CREATE INDEX idx_tool_name ON mcp_request_state (tool_name);

CREATE TABLE mcp_tool_config (
    tool_name       TEXT PRIMARY KEY,
    tool_version    TEXT,
    ttl_ms          INTEGER NOT NULL DEFAULT 86400000,
    time_sensitive  BOOLEAN DEFAULT FALSE,
    cacheable       BOOLEAN DEFAULT TRUE,
    swr_grace_ms    INTEGER,
    max_param_size  INTEGER,
    notes           TEXT
);
```

### 5.3 Wrongnotebook 5 tools 优先级

| Tool | tubi-mcp 实现 | 优先级 | 原因 |
|------|--------------|--------|------|
| `get_notebook` | `1ba5979` 有 | P0 | 高频读 |
| `list_notebooks` | `1ba5979` 有 | P0 | 列操作 |
| `add_question` | `62c541f` 有 | P0 | 主写操作 |
| `update_question` | `62c541f` 有 | P1 | 中频写 |
| `delete_question` | `1ba5979` 有 | P1 | 低频写 |

**Phase 2 范围**（10 tools）：
- `get_question` / `search_questions` / `practice_record` / `update_mastery` / `create_notebook` / `delete_notebook` / `list_subjects` / `get_stats` / `export_notebook` / `import_questions`

---

## 6. 测试要求（主人：充分测试）

### 6.1 覆盖率目标

| 类型 | 目标 | 工具 |
|------|------|------|
| **Unit tests** | >80% 行覆盖 | JUnit 5 + MockK |
| **Integration** | 5 场景覆盖 | Spring Boot Test + Testcontainers |
| **e2e** | 真实 wrong-notebook API | WebClient + real network |
| **Performance** | hit rate / p50/p95/p99 | Micrometer + JMH (optional) |

### 6.2 必测场景（11 个）

1. Cache miss → 真实 tool 执行 → 写入两 tier
2. Cache hit（request_id 命中）→ 立即返回
3. Cache hit（params_hash 命中）→ 立即返回
4. Cache hit（SWR window）→ 返回 stale + 后台 refresh
5. Cache hit（expired）→ 真 miss
6. Negative cache（5xx）→ 短 TTL 缓存
7. Tool version 变化 → key 变 → cache miss
8. timeSensitive tool + SWR → 永不 stale（直接 miss）
9. Redis 挂 → fallback PostgreSQL
10. PostgreSQL 挂 → return miss（不 crash）
11. Lazy server loading → first call spawn, idle > 60s kill

### 6.3 Wrongnotebook Smoke Test 用例

- 5 tools 全部跑通
- 真实 wrong-notebook API（`http://localhost:3032`）
- Token persistence 验证
- Cache 命中验证（第二次 call 走 cache）

---

## 7. 文档要求（主人：详细文档）

| 文档 | 内容 | 估时 |
|------|------|------|
| `docs/architecture.md` | 系统架构 + Mermaid + 模块依赖图 | 1h |
| `docs/deployment.md` | 部署步骤 + config + systemd unit | 1h |
| `docs/performance.md` | benchmark 结果 + 调优 | 1h |
| `docs/wrongnotebook-mcp-tools.md` | 5 tools spec（输入/输出/错误）| 1h |
| `docs/adr/0003-cache-strategy.md` | cache 实施决策记录 | 0.5h |
| `src/cache/README.md` | cache 模块设计 + 使用 | 0.5h |
| `src/transport/README.md` | transport 层说明 | 0.5h |
| `src/server/README.md` | server lifecycle 说明 | 0.5h |
| `src/bridge/wrongnotebook/README.md` | bridge 集成说明 | 0.5h |
| `README.md` 更新 | Replace tubi-mcp + Build & Run | 1h |
| **总计** | | **8h** |

---

## 8. 验收标准（shrek ship → pikachu verify）

### 8.1 shrek ship 时
- [ ] `./gradlew build` 全过（无 error）
- [ ] `./gradlew test` 全过（>80% 覆盖）
- [ ] `./gradlew run` 启动成功，logs 看到 "Gateway ready on port 3850"
- [ ] 真实 wrong-notebook 5 tools smoke test 全过
- [ ] Performance benchmark 报告
- [ ] 所有文档完成
- [ ] Git commit + push 到 main

### 8.2 pikachu verify
- [ ] 独立 clone repo + 跑 `./gradlew build`
- [ ] 跑 `./gradlew test` → 100% pass
- [ ] 独立跑 e2e smoke → 验证 5 tools 工作
- [ ] 性能 benchmark → 命中 hit rate >40%, p99 <200ms
- [ ] 检查无 hardcoded credentials
- [ ] 报告：6/6 verify pass 或 X/6 + 详情

### 8.3 skill-master 协调
- 每天进度 ping 派大星
- Day 3/5/7 关键节点 verify
- 派大星 22:00 cron 进度汇报

---

## 9. 派工协议

### 9.1 shrek 工作目录
- 主目录：`~/dev/mcp-cache-gateway/`
- **不要**碰 `~/dev/tubi-mcp/`（保留作 reference）

### 9.2 commit message 格式
```
<type>(<scope>): <subject>

[optional body]

[optional footer]
```
类型：feat / fix / docs / test / chore / refactor / perf
scope：transport / cache / server / bridge / persistence / observability / config

### 9.3 handoff 时机
- **Day 3 晚**：初步 cache pipeline → pikachu smoke verify
- **Day 5 晚**：wrongnotebook 5 tools → pikachu integration verify
- **Day 7 晚**：全 phase ship → pikachu full verify + skill-master ack

### 9.4 我（skill-master）的协调
- 派大星 22:00 cron 汇报进度
- Day 3/5/7 关键节点 ping 主人
- 任何 blocker 立即 escalate

---

## 10. 风险 + 缓解

| 风险 | 缓解 |
|------|------|
| shrek 不熟 MCP Kotlin SDK | 提前 check docs，Day 1 上午做 spike |
| 真实 wrong-notebook API 不稳定 | Testcontainers + mock server fallback |
| Kotlin coroutine 学习曲线 | 用 StructuredConcurrency（structured scope），避免 leak |
| 7 天可能不够 | Day 5/6 已留余地；如不够可降 Phase 1 到 4 tools |
| 主人改变需求 | Phase 1 ship 后再 iter，避免 scope creep |

---

## 11. 立即动手清单（shrek Day 1 第一件事）

1. [ ] 读本 spec 全部
2. [ ] 读 `docs/design.md` 全部
3. [ ] 读 `tubi-mcp/mcp-cache-proxy.js` + `tubi-mcp/wigolo-bridge.js` 借鉴模式
4. [ ] 读 `tubi-mcp/wrongnotebook-mcp-bridge.js` 借鉴 bridge 模式
5. [ ] `./gradlew init` 或手写 `build.gradle.kts`
6. [ ] 写 `Application.kt` 跑通空启动
7. [ ] Day 1 commit: `chore: Spring Boot 3 + Kotlin skeleton`

---

## 12. 关联 + Reference

- 设计 doc：`docs/design.md`（v1.0 草案）
- ADR-0001（TS → Kotlin）：已写（在 docs/adr/）
- ADR-0002（gradual migration）：已写（在 docs/adr/）
- tubi-mcp 参考：`<TUBI_MCP_HOME>/`
- 派大星 22:00 cron：自动 ping（无需手动）

---

**派工时间**：2026-08-08 11:18 CST
**开始时间**：shrek 收到后立即
**汇报节奏**：每天 22:00 CST（派大星 cron 自动）
**完成 deadline**：2026-08-15 23:59 CST

---

**作者**：skill-master 🎯
**接收**：shrek（请确认收到 + 开始 Day 1）
**抄送**：pikachu（Day 3/5/7 verify）+ 派大星（22:00 cron 进度）