# mcp-cache-gateway Performance

## Design Targets

Per spec §1.1 success criteria:

| Target | Value |
|--------|-------|
| Cache hit rate (steady state) | ≥ 80% |
| Cache hit latency (p99) | < 5ms |
| Cache miss latency (p99) | < 200ms |
| Token savings | ≥ 70% (cached reads avoid MCP roundtrip) |
| Cache throughput | ≥ 1000 req/sec |

## Architecture Impact

### Latency breakdown

| Path | Components | Expected p99 |
|------|-----------|--------------|
| Cache HIT (Redis) | JSON-RPC parse + Redis GET + JSON serialize | < 5ms |
| Cache HIT (PG fallback) | JSON-RPC parse + Redis GET + PG SELECT + JSON serialize | < 30ms |
| Cache MISS + bridge | JSON-RPC parse + Redis GET + bridge HttpClient + Redis SETEX + JSON serialize | < 200ms |
| Cache MISS + subprocess | JSON-RPC parse + Redis GET + subprocess RPC + Redis SETEX + JSON serialize | < 300ms |

### Throughput

| Operation | Resource | Limit |
|-----------|----------|-------|
| Redis SETEX | 1 connection (Lettuce thread-safe) | ~50K ops/sec |
| Redis GET | 1 connection | ~50K ops/sec |
| PG UPSERT | HikariCP pool (max 10) | ~5K rows/sec |
| PG SELECT | HikariCP pool (max 10) | ~10K rows/sec |

Throughput bottleneck: Redis single-connection → can scale via Lettuce Cluster.

## Benchmark Script

Day 7 left as TODO (deferred — see [Benchmark Notebook](benchmark.ipynb) when available).

```python
# benchmark.py — quick benchmark using stdio JSON-RPC
import json, subprocess, time
proc = subprocess.Popen(
    ['./gradlew', 'bootRun', '--offline'],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE
)

def request(method, params=None):
    req = {'jsonrpc': '2.0', 'id': 'bench-' + str(time.time_ns()), 'method': method, 'params': params or {}}
    start = time.time_ns()
    proc.stdin.write((json.dumps(req) + '\n').encode())
    proc.stdin.flush()
    line = proc.stdout.readline()
    end = time.time_ns()
    return end - start, json.loads(line)

# Warm up cache
for _ in range(10):
    request('tools/call', {'name': 'wrongnotebook.list_notebooks', 'arguments': {}})

# Measure hit latency
hit_latencies = []
for _ in range(100):
    ns, _ = request('tools/call', {'name': 'wrongnotebook.list_notebooks', 'arguments': {}})
    hit_latencies.append(ns / 1_000_000)  # ms

import statistics
print(f"hit_rate=100%, p50={statistics.median(hit_latencies):.2f}ms, "
      f"p99={statistics.quantiles(hit_latencies, n=100)[98]:.2f}ms")
```

## Tuning Knobs

### application.yml

```yaml
gateway:
  cache:
    default-ttl-ms: 86400000        # 1 day
    default-swr-grace-ms: 3600000   # 1 hour
    negative-cache-5xx-ttl-ms: 300000  # 5 min
    negative-cache-timeout-ttl-ms: 60000  # 1 min
  lazy-server:
    idle-timeout-ms: 60000          # 60s
    spawn-timeout-ms: 5000          # 5s
```

### tools.yaml per-tool

```yaml
tools:
  - name: wrongnotebook.get_notebook
    ttlMs: 300000              # 5 min (notebooks rarely change)
    swrGraceMs: 60000          # 1 min SWR grace
  - name: wrongnotebook.list_notebooks
    ttlMs: 60000               # 1 min (list changes frequently)
  - name: wrongnotebook.add_question
    ttlMs: 0                   # 0 = do not cache (write)
```

### PostgreSQL

```sql
-- Index for cache lookup by request_id (primary)
-- Already exists via PRIMARY KEY

-- Index for cache lookup by params_hash
CREATE INDEX IF NOT EXISTS idx_params_hash ON mcp_request_state (params_hash);

-- Index for SWR refresh sweep
CREATE INDEX IF NOT EXISTS idx_expires_at_active ON mcp_request_state (expires_at)
  WHERE NOT invalidated;
```

### Redis

```conf
# /etc/redis/redis.conf
maxmemory 1gb
maxmemory-policy allkeys-lru
```

## Cache Hit Rate Strategy

To achieve ≥ 80% hit rate:

1. **Tune TTL per tool**: Tools with infrequent changes can have longer TTL (e.g., `get_notebook` = 5min)
2. **Increase SWR grace**: For semi-volatile data, set `swrGraceMs` to 50% of TTL (e.g., `ttlMs=10min, swrGraceMs=5min`)
3. **Negative cache 5xx**: 5min TTL prevents retry storm on backend failures
4. **Notification-driven invalidation**: Wire `notifications/tools/list_changed` → automatic cache clear

## Memory Footprint

| Component | Heap usage |
|-----------|-----------|
| Spring Boot core | ~100 MB |
| Redis Lettuce client | ~30 MB |
| HikariCP pool (10 conns) | ~50 MB |
| Kotlin coroutines runtime | ~20 MB |
| Total JVM | ~200 MB baseline |

Cache entry size (Redis): ~1-5 KB per entry (depending on tool result size).
100K entries → 100-500 MB Redis memory.

## Reference

- [architecture.md](architecture.md) — System architecture
- [deployment.md](deployment.md) — Deployment + tuning
- [../README.md](../README.md) — Quick start
