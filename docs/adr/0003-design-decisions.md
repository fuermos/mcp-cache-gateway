# ADR-0003: Phase 1 Design Decisions

## Status

Accepted (2026-08-09 for Phase 1 ship). Will be reviewed at Phase 2 boundary.

## Context

`mcp-cache-gateway` is a Spring Boot 3 + Kotlin sidecar MCP server that wraps existing MCP servers (Phase 1: wrongnotebook) with a two-tier cache (Redis + PostgreSQL). It speaks JSON-RPC 2.0 over stdio.

This ADR captures the key design decisions made during Phase 1 implementation (2026-08-08 to 2026-08-09).

## Decision 1: request_id first-class idempotency

**Decision**: Every JSON-RPC request carries a UUID v7 `id` (string). This is used as the Tier 1 cache key for exact-match lookup.

**Alternative considered**: Hash-based idempotency (only by params_hash). Pros: simpler. Cons: client retries with new request_id wouldn't hit cache.

**Rationale**: UX-wise, clients retry with the same request_id to detect "did my call succeed?". With request_id as primary key, retries always hit cache. Phase 1 spec §1.1 explicitly requires this.

**Trade-off**: Slightly larger cache footprint (more keys), but client UX is much better.

## Decision 2: Two-tier cache (Redis + PostgreSQL)

**Decision**: Redis as hot path (TTL ≤ 1 day), PostgreSQL as cold path (long-term).

**Alternative considered**: Redis-only. Pros: simpler. Cons: cold data loss on Redis restart, no audit trail.

**Rationale**: Phase 1 spec §3.4 requires both. Postgres provides:
- Long-term retention (audit + cross-session replay)
- Crash safety (Redis can be evicted under memory pressure)
- hit_count tracking for cache analytics

**Trade-off**: 2x write latency (Redis sync + PG async), but read latency benefits from Redis fast path.

## Decision 3: SWR (Stale-While-Revalidate) with single-flight

**Decision**: For tools with `swrGraceMs > 0`, return stale data + async refresh when in stale window. Single-flight via `ConcurrentHashMap<String, Boolean>`.

**Alternative considered**: Blocking refresh (always wait for fresh data). Pros: simpler. Cons: increased latency on stale path.

**Rationale**: Phase 1 spec §1.1 requires < 5ms hit latency. SWR returns stale response immediately (no network wait), refresh in background. Single-flight prevents refresh storm when N concurrent requests hit stale window.

**Trade-off**: Brief window where N-1 clients see stale data, but only 1 actually refreshes.

## Decision 4: Negative cache on 5xx only

**Decision**: 5xx errors → short TTL (5min default). 4xx errors → never cached.

**Alternative considered**: Cache all errors. Pros: simpler. Cons: amplifies client errors (e.g., bad params).

**Rationale**: 5xx = backend issue (transient). Caching prevents retry storm. 4xx = client issue (persistent). Caching hides the bug.

**Trade-off**: Adds complexity to error handling, but prevents cascading failures.

## Decision 5: Per-tool TTL via YAML config

**Decision**: `examples/tools.yaml` defines per-tool TTL, cacheable, swrGraceMs. Loaded at startup.

**Alternative considered**: Single global TTL. Pros: simpler. Cons: write ops cached, reads evicted too aggressively.

**Rationale**: Different tools have different volatility. Per-tool config lets ops tune without code changes.

**Trade-off**: Config file to maintain. Mitigated by defaults (every tool gets a sensible default).

## Decision 6: In-process bridge for Phase 1

**Decision**: Phase 1 wrongnotebook bridge is in-process Kotlin client lib (not subprocess). GatewayOrchestrator routes wrongnotebook.* tools directly via `wrongNotebookBridge` injection.

**Alternative considered**: Subprocess bridge (per spec §5 original). Pros: stronger isolation. Cons: 1-layer IPC overhead, more complex deployment.

**Rationale**: Phase 1 spec §3 (examples/servers.yaml) explicitly noted "Phase 1 中 wrongnotebook 是 mcp-cache-gateway 内部实现, 不需要 spawn". For 5 tools, in-process is simpler and faster.

**Trade-off**: No isolation between gateway and wrongnotebook HTTP client (JVM crash affects both). Mitigated by:
- HttpClient timeouts (30s default)
- try/catch around HTTP errors
- Negative cache on 5xx

**Migration plan**: Phase 2+ may migrate to subprocess bridge via `ServerLifecycleManager` for wigolo + exameow (heavier bridges).

## Decision 7: JSON-RPC over stdio (Phase 1)

**Decision**: Single-threaded synchronous stdin/stdout. Line-delimited JSON-RPC 2.0 messages.

**Alternative considered**: HTTP transport (e.g., Streamable HTTP). Pros: multi-client. Cons: more complex deployment.

**Rationale**: Phase 1 spec §1.2 explicitly states stdio only. Single-client-per-gateway is OK for LLM Agent integration.

**Trade-off**: Only 1 client can connect per gateway instance. Mitigated by running multiple instances (no shared state, each has own Redis/PG connections).

## Decision 8: Kotlin 1.9.24 (not 2.1)

**Decision**: Kotlin 1.9.24 compiler. Avoided Kotlin 2.1 due to dependency `kotlin-sdk:0.5.0` being compiled with 2.1 (Kotlin version mismatch).

**Alternative considered**: Upgrade Kotlin to 2.1. Pros: matches SDK. Cons: K2 compiler breaking changes, more refactoring.

**Rationale**: Removing the unused MCP SDK dep was simpler than upgrading Kotlin across the codebase. We don't actually use the SDK's API (JSON-RPC is fully self-managed).

**Trade-off**: Stuck on Kotlin 1.9 LTS features. Phase 2+ can decide whether to upgrade.

## Decision 9: JUnit 5.8.2 (not 5.10)

**Decision**: JUnit 5.8.2 (cached in offline cache). Force-upgraded from spring-boot-managed 5.10.2 via `resolutionStrategy.eachDependency`.

**Rationale**: Network 170 B/s blocked Maven Central downloads. 5.8.2 was the only version available in `~/.gradle/caches`.

**Trade-off**: Newer JUnit 5 features (display name generators, parameterized tests improvements) not available. Acceptable for Phase 1.

## Decision 10: Spring Boot Actuator + Prometheus disabled

**Decision**: Removed `spring-boot-starter-actuator` + `micrometer-registry-prometheus` deps.

**Rationale**: Prometheus libs not in offline cache. Phase 1 health check via `ps` + `redis-cli PING` + `psql` is sufficient.

**Trade-off**: No metrics endpoint. Phase 2+ may re-enable with HTTP transport.

## Decision 11: Testcontainers not used

**Decision**: Use real local Redis + PG (127.0.0.1:6379 + 127.0.0.1:5432) instead of Testcontainers.

**Rationale**: Docker image not cached + 170 B/s network blocked. Local services already running.

**Trade-off**: Tests depend on local services. CI environment must provide Redis + PG.

## Decision 12: MockK for client mocking

**Decision**: Use MockK 1.13.13 for mocking WrongNotebookClient in integration tests.

**Rationale**: Kotlin-native mocking (better than Mockito for suspending functions, sealed classes).

**Trade-off**: MockK has learning curve. Acceptable for test code.

## Decision 13: KDoc convention for nested comments

**Decision**: Avoid `/*` sequences inside KDoc (Kotlin lexer treats as nested comment start).

**Examples**:
- ❌ `wrongnotebook.<star>` reads as `wrongnotebook./<star>` → comment start
- ✅ `wrongnotebook/{star}` or escape `wrongnotebook.<colon><star>`
- ❌ `http://user:<pw>@host` reads as `//***` → comment start
- ✅ `http://host:[redacted]

## Conclusion

These 13 decisions shape Phase 1 architecture. Phase 2+ may revisit:
- Decision 6 (in-process → subprocess bridge)
- Decision 8 (Kotlin 1.9 → 2.1)
- Decision 9 (JUnit 5.8 → 5.10)
- Decision 10 (no metrics → Prometheus)

The remaining decisions are stable.
