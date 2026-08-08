package com.fuermos.mcp.cache.gateway.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Cache entry — represents a single cached JSON-RPC call result.
 *
 * Storage lifecycle:
 *   1. Cache miss → tool executes → CacheWrite builds CacheEntry
 *   2. CacheEntry serialized → Redis SETEX (TTL = freshUntilMs - now)
 *   3. Async DB INSERT INTO mcp_request_state (TTL via expires_at column)
 *   4. Lookup path: Redis → if miss, DB → if hit, optionally promote to Redis
 *
 * Stale-while-revalidate (SWR, Day 4):
 *   - Within [freshUntilMs, staleUntilMs] window → return stale + async refresh
 *   - staleUntilMs == null → no SWR (single-shot TTL only)
 *   - Per-tool config decides SWR window (see tools.yaml swrGraceMs)
 *
 * Design references:
 *   - design.md §3.3 (PostgreSQL schema columns)
 *   - design.md §3.4 (two-tier promotion logic)
 *   - design.md §3.5 (per-tool TTL)
 */
@Serializable
data class CacheEntry(
    val requestId: String,
    val serverId: String,
    val method: String,
    val toolName: String? = null,
    val toolVersion: String? = null,
    val paramsHash: String,
    val paramsJson: JsonElement,
    val resultJson: JsonElement? = null,
    val resultSize: Int = 0,
    val cacheTier: CacheTier = CacheTier.REDIS,
    val ttlMs: Int,
    val createdAtMs: Long,
    val freshUntilMs: Long,
    val staleUntilMs: Long? = null,
    val hitCount: Int = 0,
    val invalidated: Boolean = false,
    val metadata: JsonElement? = null
) {

    fun isExpired(now: Long): Boolean = now >= freshUntilMs

    fun isInSwrWindow(now: Long): Boolean =
        staleUntilMs != null && now >= freshUntilMs && now < staleUntilMs

    fun isInFreshWindow(now: Long): Boolean =
        now >= createdAtMs && now < freshUntilMs

    fun remainingFreshMs(now: Long): Long =
        (freshUntilMs - now).coerceAtLeast(0)

    companion object {
        /**
         * Compute freshUntil / staleUntil timestamps.
         *
         * @param nowMs current epoch millis
         * @param ttlMs per-tool TTL (ms) — fresh window length
         * @param swrGraceMs SWR grace period (null = no SWR)
         */
        fun computeWindows(nowMs: Long, ttlMs: Int, swrGraceMs: Long?): Pair<Long, Long?> {
            val freshUntil = nowMs + ttlMs
            val staleUntil = swrGraceMs?.let { freshUntil + it }
            return freshUntil to staleUntil
        }
    }
}

/**
 * Which tier(s) an entry currently lives in.
 *
 * Used for metrics + diagnostic logging (e.g. "cache hit, db-promoted" log entry).
 * Day 4 SWR may add SWR_REDIS tier for entries being async-refreshed.
 */
@Serializable
enum class CacheTier {
    REDIS,    // Tier 1 only (hot, ≤ 1 day)
    DB,       // Tier 2 only (cold, > 1 day or Redis evicted)
    BOTH;     // Both tiers (post-write sync state)

    fun includesRedis(): Boolean = this == REDIS || this == BOTH
    fun includesDb(): Boolean = this == DB || this == BOTH
}
