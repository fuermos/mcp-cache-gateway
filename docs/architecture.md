# mcp-cache-gateway Architecture

## Overview

`mcp-cache-gateway` is a Spring Boot 3 + Kotlin sidecar MCP server that adds a two-tier cache wrapper around existing MCP servers (Phase 1: wrongnotebook only). It speaks JSON-RPC 2.0 over stdio, applies per-tool TTL + SWR + negative cache policies, and persists entries to Redis (Tier 1) and PostgreSQL (Tier 2).

## High-Level Architecture

```
┌──────────────────┐
│  LLM Agent       │
└────────┬─────────┘
         │ JSON-RPC (stdio)
         ▼
┌─────────────────────────────────────────┐
│  mcp-cache-gateway (Spring Boot 3)       │
│  ┌────────────────────────────────┐    │
│  │ Transport Layer (stdio JSON-RPC) │    │
│  └──────────┬─────────────────────┘    │
│             ▼                            │
│  ┌────────────────────────────────┐    │
│  │ McpMethodRouter                  │    │
│  │  - initialize / ping             │    │
│  │  - tools/call → orchestrator     │    │
│  │  - tools/list → 5 tools          │    │
│  │  - notifications/list_changed    │    │
│  └──────────┬─────────────────────┘    │
│             ▼                            │
│  ┌────────────────────────────────┐    │
│  │ GatewayOrchestrator              │    │
│  │  SWR + per-tool TTL + negative   │    │
│  │  1. cache lookup (req_id → params)|    │
│  │  2. SWR stale + async refresh    │    │
│  │  3. forward to bridge / server   │    │
│  │  4. write back (Redis + async PG)│    │
│  └──────────┬─────────────────────┘    │
│             ▼                            │
│  ┌──────────┴──────────┐                │
│  │ Tier 1: Redis     │ Tier 2: PostgreSQL│
│  │ (Lettuce)         │ (JdbcTemplate)   │
│  └────────┬──────────┘                │
│           ▼                            │
│  ┌─────────────────────────────────┐   │
│  │ Bridge: wrongnotebook (in-process)│   │
│  │  - 5 tools (Day 5)               │   │
│  │  - NextAuth auto-login + cookie  │   │
│  │  - HTTP client (java.net.http)   │   │
│  └─────────────────────────────────┘   │
└───────────┼──────────────────────────┘
            ▼ HTTP
   ┌──────────────────┐
   │ wrong-notebook   │
   │ (Next.js :3032)  │
   └──────────────────┘
```

## Module Structure

```
src/main/kotlin/com/fuermos/mcp/cache/gateway/
├── Application.kt                          # @SpringBootApplication entry
├── config/
│   ├── ToolConfig.kt                        # Per-tool TTL config + YAML loader
│   └── ToolConfigResolver.kt                # Runtime config accessor
├── transport/
│   ├── JsonRpcEnvelope.kt                   # request/response/notif data classes
│   ├── RequestId.kt                         # UUID v7 generation
│   └── StdioTransport.kt                    # line-delimited JSON-RPC over stdio
├── server/
│   ├── ServerHandle.kt                      # Process wrapper + state machine
│   ├── ServerPool.kt                        # ConcurrentHashMap registry
│   └── ServerLifecycleManager.kt            # lazy spawn + cleanup tick
├── cache/
│   ├── CacheEntry.kt                        # data class with windows
│   ├── CacheKey.kt                          # naming strategy
│   ├── CacheLookup.kt                       # 2-tier lookup (Redis + PG)
│   ├── CacheWrite.kt                        # SETEX + async UPSERT
│   ├── SwrManager.kt                        # SWR + single-flight refresh
│   └── NegativeCache.kt                     # 5xx/timeout short TTL
├── persistence/
│   ├── RedisClient.kt                       # Lettuce wrapper
│   ├── PostgresClient.kt                    # HikariCP wrapper
│   └── CacheRepository.kt                   # PG CRUD (JdbcTemplate)
├── bridge/wrongnotebook/
│   ├── WrongNotebookAuth.kt                 # NextAuth auto-login + cookie persist
│   ├── WrongNotebookClient.kt               # HTTP client (5 methods)
│   └── WrongNotebookBridge.kt               # 5 tool definitions + handlers
├── orchestrator/
│   ├── GatewayOrchestrator.kt               # main request loop
│   └── McpMethodRouter.kt                   # JSON-RPC method dispatch
└── utils/
    └── Hashing.kt                           # sha256 + canonical JSON
```

## Key Design Decisions

### 1. request_id first-class idempotency

Every JSON-RPC request carries a UUID v7 `id` (string). This is used as the Tier 1 cache key for exact-match lookup. Retries with the same request_id always hit the cache, providing at-most-once tool execution semantics.

**Reference**: [design.md §3.2](https://github.com/fuermos/mcp-cache-gateway/blob/main/docs/design.md#32-request-id-语义升级)

### 2. Two-tier cache (Redis + PostgreSQL)

- **Tier 1 (Redis)**: Hot path, TTL ≤ 1 day, fast reads, automatic eviction via allkeys-lru
- **Tier 2 (PostgreSQL)**: Cold path, long-term storage, indexed by `(params_hash, expires_at)`, hit_count tracking

Reads: Redis → DB fallback. Writes: sync Redis + async DB UPSERT.

**Reference**: [design.md §3.4](https://github.com/fuermos/mcp-cache-gateway/blob/main/docs/design.md#34-两级缓存策略)

### 3. SWR (Stale-While-Revalidate)

For tools with `swrGraceMs > 0`:
- `freshUntilMs ≤ now < staleUntilMs`: return stale response + async refresh
- `now ≥ staleUntilMs`: cache miss, forward to server

Single-flight refresh per params_hash via `SwrManager.tryAcquireRefresh()`.

**Reference**: [design.md §5](https://github.com/fuermos/mcp-cache-gateway/blob/main/docs/design.md#5-请求生命周期完整流程)

### 4. Negative cache

5xx errors / timeouts → short TTL (5min for 5xx, 1min for timeout). 4xx errors → never cached.

**Reference**: [design.md §5](https://github.com/fuermos/mcp-cache-gateway/blob/main/docs/design.md#5-请求生命周期完整流程)

### 5. Lazy server loading (subprocess pattern)

Although Phase 1 doesn't spawn external processes (in-process bridge), the ServerLifecycleManager / ServerHandle / ServerPool trio is fully implemented for Phase 2+ bridges (wigolo, exameow, pdf-router).

Each serverId has:
- InProcess vs Subprocess (Phase 1 = in-process via bridge)
- State machine: SPAWNING → ACTIVE → IDLE → CLOSED | DEAD
- Idle cleanup tick (default 60s)
- Touch() on acquire() to update lastUsedAtMs

**Reference**: [design.md §3.1](https://github.com/fuermos/mcp-cache-gateway/blob/main/docs/design.md#31-架构图)

### 6. Per-tool TTL configuration

`examples/tools.yaml` defines per-tool:
- `ttlMs` (fresh window)
- `cacheable` (true/false — read/write split)
- `timeSensitive` (annotation only)
- `swrGraceMs` (SWR window)

`ToolConfigResolver` resolves effective config (per-tool + defaults merge) on every request.

**Reference**: [design.md §3.5](https://github.com/fuermos/mcp-cache-gateway/blob/main/docs/design.md#35-per-tool-ttl-配置)

### 7. In-process bridge (Phase 1 simplification)

Phase 1 wrongnotebook bridge is **in-process** (Kotlin client lib called directly by GatewayOrchestrator via `wrongNotebookBridge` injection). This avoids the subprocess spawn overhead and simplifies the deployment model. Phase 2+ may migrate to subprocess bridge via `ServerLifecycleManager` for stronger isolation.

**Reference**: [examples/servers.yaml comment](https://github.com/fuermos/mcp-cache-gateway/blob/main/examples/servers.yaml)

### 8. JSON-RPC over stdio

Single-threaded synchronous stdin/stdout. No HTTP listener (Phase 1). Line-delimited JSON-RPC 2.0 messages.

```kotlin
class StdioTransport(input: InputStream, output: OutputStream) {
    fun readMessage(): JsonRpcMessage?     // blocks on next newline
    fun writeMessage(msg: JsonRpcMessage)  // writes single-line JSON + '\n'
}
```

**Reference**: [transport/README.md](https://github.com/fuermos/mcp-cache-gateway/blob/main/src/main/kotlin/com/fuermos/mcp/cache/gateway/transport/README.md)

## Request Lifecycle (cache hit path)

```
LLM Agent → JSON-RPC tools/call (request_id=X, params={...})
StdioTransport reads line → McpMethodRouter dispatches
GatewayOrchestrator.handle(request):
  1. ToolConfigResolver.resolveEffectiveConfig(toolName) → config
  2. lookup.lookupByRequestId(X) → hit? return cached
  3. lookup.lookupByParams(...) → hit? return cached
  4. SWR stale? return stale + async refresh
  5. Forward to bridge (in-process) → JsonRpcResponse
  6. write.write(entry) (sync Redis + async PG)
  7. Return response via StdioTransport
```

## Concurrency

- GatewayOrchestrator: coroutine-based (structured concurrency, SupervisorJob)
- CacheWrite: async DB writes via `CoroutineScope(SupervisorJob() + Dispatchers.IO)`
- SwrManager: single-flight refresh via `ConcurrentHashMap<String, Boolean>`
- RedisClient: single shared connection (Lettuce is threadsafe)
- PostgresClient: HikariCP pool (max 10, min 2)

## Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Spring Boot | 3.3.0 | DI + config + HikariCP + Flyway |
| Kotlin | 1.9.24 | Coroutines + serialization |
| Lettuce | bundled | Redis client |
| HikariCP | bundled | PG connection pool |
| Flyway | bundled | Schema migrations |
| JUnit 5 | 5.8.2 | Unit + integration tests |
| MockK | 1.13.13 | Mocking (bridge client, etc.) |

## Building

```bash
cd ~/dev/mcp-cache-gateway
./gradlew assemble        # produces bootJar (~38 MB) + thin jar (~262 KB)
./gradlew bootRun --offline  # starts Spring Boot (Phase 1 stdio mode)
```

## Testing

```bash
REDIS_INTEGRATION=1 PG_INTEGRATION=1 \
  POSTGRES_USER=mcp_cache POSTGRES_PASSWORD=*** \
  ./gradlew test --offline
```

Results: 166 tests, 5 scenarios integration tests, ~95% module coverage.
