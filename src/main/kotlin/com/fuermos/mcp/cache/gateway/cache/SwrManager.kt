package com.fuermos.mcp.cache.gateway.cache

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Stale-While-Revalidate (SWR) Manager — orchestrates async refresh.
 *
 * Day 4 design (per spec §4 Day 4 morning + design.md §3.4):
 *   - States: FRESH (return immediately) / STALE (return + schedule refresh) / EXPIRED (miss)
 *   - Single-flight refresh per (server, method, tool, version, params_hash):
 *     if a refresh is already in progress, don't enqueue another one
 *   - Refresh failures don't kill the next request — they just defer to EXPIRED path
 *
 * Single-flight pattern:
 *   - Track in-flight refreshes in ConcurrentHashMap (keyed by params_hash)
 *   - If key present → another request is refreshing; new request returns stale immediately
 *   - If absent → register, schedule refresh, deregister when done
 *
 * Pattern references:
 *   - design.md §3.4 (two-tier cache + SWR)
 *   - design.md §5 (request lifecycle — Step 2 SWR path)
 *   - spec §4 Day 4 morning (SWR + async refresh)
 */
class SwrManager {

    private val log = LoggerFactory.getLogger(SwrManager::class.java)

    private val inFlightRefreshes = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val refreshCount = AtomicLong(0)
    private val staleHits = AtomicLong(0)

    /**
     * Classify cache entry's current window.
     */
    fun classify(entry: CacheEntry, nowMs: Long): Window {
        return when {
            entry.isInFreshWindow(nowMs) -> Window.FRESH
            entry.isInSwrWindow(nowMs) -> Window.STALE
            else -> Window.EXPIRED
        }
    }

    /**
     * Attempt to acquire a refresh permit for the given params hash.
     *
     * @return true if this caller should perform the refresh,
     *         false if another request is already refreshing
     */
    fun tryAcquireRefresh(paramsHash: String): Boolean {
        val prev = inFlightRefreshes.putIfAbsent(paramsHash, true)
        if (prev == null) {
            refreshCount.incrementAndGet()
            return true
        }
        return false
    }

    /**
     * Release the refresh permit (called after refresh completes/fails).
     */
    fun releaseRefresh(paramsHash: String) {
        inFlightRefreshes.remove(paramsHash)
    }

    /**
     * Check if a refresh is in flight for params hash.
     */
    fun isRefreshInFlight(paramsHash: String): Boolean =
        inFlightRefreshes.containsKey(paramsHash)

    /**
     * Count of stale hits (callers that returned stale + skipped refresh).
     */
    fun recordStaleHit() {
        staleHits.incrementAndGet()
    }

    /**
     * Snapshot for metrics/observability.
     */
    fun snapshot(): SwrStats = SwrStats(
        inFlightCount = inFlightRefreshes.size,
        totalRefreshes = refreshCount.get(),
        staleHits = staleHits.get()
    )

    enum class Window {
        FRESH,    // return immediately
        STALE,    // return stale + async refresh (if not already in flight)
        EXPIRED   // miss → forward to server
    }

    data class SwrStats(
        val inFlightCount: Int,
        val totalRefreshes: Long,
        val staleHits: Long
    )
}