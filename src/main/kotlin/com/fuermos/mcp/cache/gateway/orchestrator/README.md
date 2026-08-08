# Orchestrator Module

Main request loop + JSON-RPC method dispatch.

## Files

### `GatewayOrchestrator.kt`
Main request handler (suspend):
1. Extract tool name from `tools/call` params
2. If `wrongnotebook.*` → route to in-process bridge
3. If non-cacheable (cacheable=false or ttlMs=0) → forward directly
4. **cache lookup**: request_id → params_hash fallback
5. **FRESH HIT**: return cached
6. **SWR stale**: return stale + async refresh (single-flight)
7. **MISS**: forward to server/bridge
8. **write back**: sync Redis + async PG (and negative cache on 5xx)

`handleWrongNotebookCall()` — in-process bridge path (Day 5)

Stats counters: `freshHits`, `staleHits`, `misses`, `writes`, `negativeWrites`, `swrRefreshes`, `errors`, `timeouts`

### `McpMethodRouter.kt`
JSON-RPC method dispatch:
- `initialize` → serverInfo + capabilities
- `ping` → empty object
- `tools/list` → 5 wrongnotebook tool definitions
- `tools/call` → wrongnotebook bridge OR generic orchestrator
- `notifications/initialized` → log
- `notifications/cancelled` → stub (Day 5+)
- `notifications/progress` → log
- `notifications/tools/list_changed` → invalidate cache
- `notifications/tools/invalidate` → invalidate by tool name
- `notifications/config_changed` → stub (Day 5+)

## Request Flow

```
McpMethodRouter.dispatch(request)
  ├─ initialize / ping → local handler
  ├─ tools/list → wrongnotebook bridge.listTools()
  ├─ tools/call
  │   ├─ wrongnotebook.X → GatewayOrchestrator.handleWrongNotebookCall()
  │   └─ other → GatewayOrchestrator.handle()
  └─ notifications/* → handleNotification()
```

## Cache Decision Tree

```
              ┌───────────────┐
              │ Request       │
              └───────┬───────┘
                      ▼
           cacheable=false / ttlMs=0?
              ├─ YES → forward directly (no cache)
              └─ NO  ↓
                      ▼
         lookupByRequestId(X) → hit?
              ├─ YES → return cached (FRESH HIT)
              └─ NO  ↓
                      ▼
         lookupByParams(...) → hit?
              ├─ YES → return cached (FRESH HIT)
              └─ NO  ↓
                      ▼
            in SWR window?
              ├─ YES → return stale + async refresh
              └─ NO  ↓
                      ▼
         forward to bridge/server
              ├─ success → write back (Redis + PG)
              └─ 5xx/timeout → negative cache (5min/1min)
              └─ 4xx → no cache (return error directly)
```

## Usage

```kotlin
val orchestrator = GatewayOrchestrator(
    lookup = cacheLookup,
    write = cacheWrite,
    servers = serverLifecycleManager,
    configResolver = toolConfigResolver,
    swrManager = swrManager,
    negativeCache = negativeCache,
    wrongNotebookBridge = bridge,
    executeTimeoutMs = 30_000
)
val router = McpMethodRouter(orchestrator, cacheWrite = cacheWrite, wrongNotebookBridge = bridge)

// In stdio loop:
val request = transport.readMessage()
val response = router.dispatch(request)
transport.writeMessage(response)
```

## Pattern References

- `cache/` — CacheEntry, CacheLookup, CacheWrite, SwrManager, NegativeCache
- `bridge/wrongnotebook/` — Phase 1 in-process bridge
- `transport/` — JSON-RPC transport
- `config/ToolConfigResolver.kt` — per-tool TTL config
