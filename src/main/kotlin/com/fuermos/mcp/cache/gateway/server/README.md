# Server Module

Lazy spawning + lifecycle management of MCP server subprocesses.

## Files

### `ServerHandle.kt`
Wrapper around a Java `Process` + state + counters:
- State machine: `SPAWNING → ACTIVE → IDLE → CLOSED | DEAD`
- Atomic counters: `totalCalls`, `errorCalls`, `restartCount`
- Liveness flag + `lastUsedAtMs` (for idle cleanup)
- `touch()` on acquire() to update lastUsedAtMs
- `execute(request)` — JSON-RPC roundtrip via stdin/stdout (Day 3.1 added)
- `markDead()` + `close()` lifecycle

### `ServerPool.kt`
ConcurrentHashMap registry of `serverId → ServerHandle`:
- `register(handle)` — atomic put
- `get(id)` — handle lookup
- `idleHandles(thresholdMs)` — handles idle > threshold, for cleanup

### `ServerLifecycleManager.kt`
Lazy spawn + idle cleanup:
- `acquire(serverId)` — return existing or spawn new
- `release(serverId)` — mark idle
- `killServer(serverId)` — force-stop
- `cleanupTick()` — reap idle handles (runs on ScheduledExecutorService)
- `shutdown()` — close all + stop executor

`ServerRegistry` interface + `InMemoryServerRegistry` impl (Day 1.2).

## State Machine

```
[none] → acquire() → [SPAWNING] → handshake → [ACTIVE]
                                                ↓
                                              release() → [IDLE]
                                                ↓
                                          cleanupTick() → [CLOSED]

On crash: [ACTIVE|IDLE] → [DEAD] → [CLOSED]
```

## Phase 1 Note

Phase 1 wrongnotebook is **in-process** (not subprocess). The Day 5 bridge (`bridge/wrongnotebook/`) calls the HTTP API directly via `WrongNotebookClient`. The ServerLifecycleManager is fully implemented (Day 1.2) but not actively used for Phase 1 — reserved for Phase 2+ subprocess bridges (wigolo, exameow, pdf-router).

See `examples/servers.yaml` for the Phase 2+ server registry template.

## Usage

```kotlin
val registry = ServerLifecycleManager.InMemoryServerRegistry()
registry.register(ServerLifecycleManager.ServerConfig(
    serverId = "wrongnotebook",
    cmd = "java",
    args = listOf("-jar", "/path/to/wrongnotebook-bridge.jar"),
    env = mapOf("WRONGNOTEBOOK_URL" to "http://localhost:3032")
))
val manager = ServerLifecycleManager(registry, idleTimeoutMs = 60_000)

val handle = manager.acquire("wrongnotebook")
val response = handle.execute(jsonRpcRequest)  // stdin/stdout roundtrip
```

## Pattern References

- `tubi-mcp/wigolo-bridge.js` — original lazy spawn pattern
- `cache/CacheLookup.kt` — uses subprocess handles
- `bridge/wrongnotebook/` — Phase 1 alternative (in-process)
