# mcp-cache-gateway

A Spring Boot 3 + Kotlin sidecar MCP server that wraps existing MCP servers with a two-tier cache (Redis + PostgreSQL) + SWR + negative cache + per-tool TTL.

**Phase 1** (complete ✅): wrongnotebook bridge — 5 most-common tools (read/list/add/update/delete).

**Phase 2+** (planned): wigolo, exameow, pdf-router bridges.

## Quick Start

```bash
# 1. Clone + build
cd ~/dev/mcp-cache-gateway
./gradlew assemble

# 2. Set up environment
cat > ~/.openclaw/state/mcp-cache-gateway.env <<EOF
export POSTGRES_USER=mcp_cache
export POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
export POSTGRES_URL=jdbc:postgresql://localhost:5432/mcp_cache
export WRONGNOTEBOOK_URL=http://localhost:3032
export WRONGNOTEBOOK_USER=your_nextauth_user
export WRONGNOTEBOOK_PASSWORD=${WRONGNOTEBOOK_PASSWORD}
EOF
chmod 600 ~/.openclaw/state/mcp-cache-gateway.env

# 3. Start PostgreSQL + Redis (Flyway migrates automatically)
sudo systemctl start postgresql redis-server
PGPASSWORD="${POSTGRES_PASSWORD}" psql -h 127.0.0.1 -U mcp_cache -d mcp_cache \
  -f src/main/resources/db/migration/V1__initial_schema.sql

# 4. Run
source ~/.openclaw/state/mcp-cache-gateway.env
./gradlew bootRun --offline
```

## Configuration

| Environment Variable | Default | Purpose |
|---------------------|---------|---------|
| `POSTGRES_USER` | `mcp_cache` | PostgreSQL user |
| `POSTGRES_PASSWORD` | (required) | PostgreSQL password |
| `POSTGRES_URL` | `jdbc:postgresql://localhost:5432/mcp_cache` | JDBC URL |
| `REDIS_HOST` | `127.0.0.1` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `WRONGNOTEBOOK_URL` | `http://localhost:3032` | wrong-notebook backend |
| `WRONGNOTEBOOK_USER` | (required) | NextAuth username |
| `WRONGNOTEBOOK_PASSWORD` | (required) | NextAuth password |

See `application.yml` for full configuration.

## Architecture

```
LLM Agent
  ↓ JSON-RPC (stdio)
mcp-cache-gateway
  ├─ Transport Layer (JSON-RPC 2.0 line-delimited)
  ├─ McpMethodRouter (method dispatch)
  ├─ GatewayOrchestrator (SWR + per-tool TTL + negative cache)
  ├─ CacheWrite (Redis SETEX + PG async UPSERT)
  ├─ CacheLookup (Redis GET + PG fallback)
  ├─ SwrManager (single-flight refresh)
  ├─ NegativeCache (5xx/timeout short TTL)
  └─ WrongNotebookBridge (Phase 1 in-process bridge)
      ↓ HTTP
   wrong-notebook (Next.js :3032)
```

→ [docs/architecture.md](docs/architecture.md)

## 5 Tools (Phase 1)

| Tool | Cacheable | TTL | Idempotent |
|------|-----------|-----|------------|
| `wrongnotebook.list_notebooks` | ✅ | 60s | yes |
| `wrongnotebook.get_notebook` | ✅ | 60s | yes |
| `wrongnotebook.add_question` | ❌ | 0 (write) | no |
| `wrongnotebook.update_question` | ❌ | 0 (write) | no |
| `wrongnotebook.delete_question` | ❌ | 0 (destructive) | yes |

## Performance Targets

| Target | Value |
|--------|-------|
| Cache hit rate | ≥ 80% |
| Cache hit p99 | < 5ms |
| Cache miss p99 | < 200ms |
| Token savings | ≥ 70% |
| Throughput | ≥ 1000 req/sec |

→ [docs/performance.md](docs/performance.md)

## Testing

```bash
# Unit + integration tests (166 tests, 100% passing)
REDIS_INTEGRATION=1 PG_INTEGRATION=1 \
  POSTGRES_USER=mcp_cache POSTGRES_PASSWORD=${POSTGRES_PASSWORD} \
  ./gradlew test --offline
```

5 integration scenarios:
- Cache hit (Scenario 1)
- Cache miss + write back (Scenario 2)
- SWR (Scenario 3)
- Negative cache (Scenario 4)
- Invalidation (Scenario 5)

→ [docs/adr/0003-design-decisions.md](docs/adr/0003-design-decisions.md)

## Deployment

→ [docs/deployment.md](docs/deployment.md)

## Roadmap

### Phase 1 — wrongnotebook (✅ complete)
- 5 most-common tools
- Two-tier cache (Redis + PG)
- SWR + per-tool TTL + negative cache
- In-process bridge (no subprocess overhead)

### Phase 2 — wigolo + exameow (planned)
- Subprocess bridge (via ServerLifecycleManager)
- 10 more wrongnotebook tools
- Adaptive TTL tuning
- Multi-user scope

### Phase 3 — pdf-router (planned)
- HTTP transport (Streamable HTTP)
- TLS for external APIs
- Metrics + Prometheus

### Phase 4 — replace tubi-mcp
- OpenClaw MCP config update
- Migrate shrek + other agents
- Deprecate tubi-mcp

## License

Private (fuermos personal project).

## References

- [docs/architecture.md](docs/architecture.md) — System architecture
- [docs/deployment.md](docs/deployment.md) — Deployment guide
- [docs/performance.md](docs/performance.md) — Performance + tuning
- [docs/adr/0003-design-decisions.md](docs/adr/0003-design-decisions.md) — Design decisions
- [docs/phase1-spec.md](docs/phase1-spec.md) — Phase 1 spec
- [docs/design.md](docs/design.md) — Original design doc
- [examples/tools.yaml](examples/tools.yaml) — Per-tool TTL config
- [examples/servers.yaml](examples/servers.yaml) — Server registry (Phase 2+)
