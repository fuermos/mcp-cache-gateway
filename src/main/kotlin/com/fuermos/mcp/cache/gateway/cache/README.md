# Cache Module

Two-tier cache (Redis Tier 1 + PostgreSQL Tier 2) with SWR + negative cache + invalidation.

## Files

### `CacheEntry.kt`
Data class representing one cached JSON-RPC call result. Includes:
- `requestId` (UUID v7 string)
- `serverId`, `method`, `toolName?`, `toolVersion?`
- `paramsHash` (sha256 of canonical JSON params)
- `paramsJson`, `resultJson?`, `resultSize`
- `cacheTier` (REDIS | DB | BOTH)
- `ttlMs`, `createdAtMs`, `freshUntilMs`, `staleUntilMs?`
- `hitCount`, `invalidated`, `metadata?`

Window methods:
- `isExpired(now)` → true if past fresh window
- `isInSwrWindow(now)` → true if in stale window (requires `staleUntilMs != null`)
- `isInFreshWindow(now)` → true if in fresh window
- `remainingFreshMs(now)` → TTL delta

`computeWindows(now, ttlMs, swrGraceMs)` static helper.

### `CacheKey.kt`
Naming strategy:
- `forRequestId(id)` → `mcp:req:{id}`
- `forParams(server, method, tool, version, hash)` → `mcp:params:{server}:{method}:{tool}:{version}:{hash}`
- Null fields → `_` wildcard
- `extractRequestId(key)` / `extractParams(key)` for reverse lookup

### `CacheLookup.kt`
Tier 1 (Redis) + Tier 2 (PG) lookup with 2-step resolution:
1. `lookupByRequestId(id)` → exact match
2. `lookupByParams(server, method, tool, version, hash)` → semantic match

Returns null on miss / expired / deserialization failure.

### `CacheWrite.kt`
Two-tier write:
- `write(entry)` → sync Redis SETEX + async PG UPSERT
- `invalidateByRequestId(id)` → Redis DEL + async PG invalidate
- `invalidateByMethod(serverId, method, tool?, version?)` → Redis SCAN + DEL
- `invalidateByTool(toolName)` → Redis SCAN + DEL
- `invalidateByToolVersion(toolName, oldVersion)` → byMethod with old version

### `SwrManager.kt`
Stale-While-Revalidate orchestration:
- `classify(entry, now)` → FRESH | STALE | EXPIRED
- `tryAcquireRefresh(paramsHash)` → single-flight permit
- `releaseRefresh(paramsHash)` → release
- `recordStaleHit()` → metric counter

### `NegativeCache.kt`
Error response policy:
- `shouldCache(response)` → TTL for cacheable errors (5xx → 5min, -32603 timeout → 1min, -32700 parse → 1min), null for 4xx
- `buildNegativeEntry(...)` → CacheEntry with `metadata.source = NEGATIVE_CACHE`

## Pattern References

- `utils/Hashing.kt` — sha256 + canonical JSON for params_hash
- `persistence/RedisClient.kt` — Lettuce sync wrapper
- `persistence/CacheRepository.kt` — PG JdbcTemplate CRUD
- `config/ToolConfigResolver.kt` — per-tool TTL config

## Usage

```kotlin
val resolver = ToolConfigResolver.fromYamlPath("./examples/tools.yaml")
val lookup = CacheLookup(redisClient, dbRepo = null)
val write = CacheWrite(redisClient, dbRepo = null)

// In orchestrator:
val paramsHash = Hashing.sha256(requestParams)
val cached = lookup.lookupByRequestId(request.id)
if (cached != null && !cached.isExpired(now)) {
    return cached.toResponse()  // FRESH HIT
}
// ... MISS → forward → write.write(entry) ...
```
