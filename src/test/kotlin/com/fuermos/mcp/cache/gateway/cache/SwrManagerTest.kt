package com.fuermos.mcp.cache.gateway.cache

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for SwrManager — window classification + single-flight refresh.
 */
class SwrManagerTest {

    private fun entry(nowMs: Long, ttlMs: Int, swrGraceMs: Long?): CacheEntry {
        val (fresh, stale) = CacheEntry.computeWindows(nowMs, ttlMs, swrGraceMs)
        return CacheEntry(
            requestId = "req-1",
            serverId = "test",
            method = "tools/call",
            toolName = "test_tool",
            toolVersion = "1.0.0",
            paramsHash = "abc",
            paramsJson = buildJsonObject { put("city", "sf") },
            resultJson = buildJsonObject { put("temp", 72) },
            cacheTier = CacheTier.REDIS,
            ttlMs = ttlMs,
            createdAtMs = nowMs,
            freshUntilMs = fresh,
            staleUntilMs = stale
        )
    }

    @Test
    fun `classify FRESH during fresh window`() {
        val mgr = SwrManager()
        val entry = entry(nowMs = 1_000_000L, ttlMs = 60_000, swrGraceMs = 30_000)
        assertEquals(SwrManager.Window.FRESH, mgr.classify(entry, nowMs = 1_030_000L))
    }

    @Test
    fun `classify STALE during SWR window`() {
        val mgr = SwrManager()
        val entry = entry(nowMs = 1_000_000L, ttlMs = 60_000, swrGraceMs = 30_000)
        assertEquals(SwrManager.Window.STALE, mgr.classify(entry, nowMs = 1_070_000L))
    }

    @Test
    fun `classify EXPIRED after stale window`() {
        val mgr = SwrManager()
        val entry = entry(nowMs = 1_000_000L, ttlMs = 60_000, swrGraceMs = 30_000)
        assertEquals(SwrManager.Window.EXPIRED, mgr.classify(entry, nowMs = 1_100_000L))
    }

    @Test
    fun `classify EXPIRED immediately when no SWR`() {
        val mgr = SwrManager()
        val entry = entry(nowMs = 1_000_000L, ttlMs = 60_000, swrGraceMs = null)
        // Past freshUntil → EXPIRED (no stale window to fall through)
        assertEquals(SwrManager.Window.EXPIRED, mgr.classify(entry, nowMs = 1_070_000L))
    }

    @Test
    fun `single-flight tryAcquireRefresh allows first caller`() {
        val mgr = SwrManager()
        assertTrue(mgr.tryAcquireRefresh("hash1"))
        assertFalse(mgr.tryAcquireRefresh("hash1"), "second acquire should fail")
    }

    @Test
    fun `single-flight releaseRefresh allows new caller`() {
        val mgr = SwrManager()
        assertTrue(mgr.tryAcquireRefresh("hash1"))
        mgr.releaseRefresh("hash1")
        assertTrue(mgr.tryAcquireRefresh("hash1"), "after release, new acquire should succeed")
    }

    @Test
    fun `isRefreshInFlight reflects current state`() {
        val mgr = SwrManager()
        assertFalse(mgr.isRefreshInFlight("hash1"))
        mgr.tryAcquireRefresh("hash1")
        assertTrue(mgr.isRefreshInFlight("hash1"))
        mgr.releaseRefresh("hash1")
        assertFalse(mgr.isRefreshInFlight("hash1"))
    }

    @Test
    fun `stale hits counter increments`() {
        val mgr = SwrManager()
        mgr.recordStaleHit()
        mgr.recordStaleHit()
        mgr.recordStaleHit()
        assertEquals(3, mgr.snapshot().staleHits)
    }

    @Test
    fun `total refreshes counter increments on acquire`() {
        val mgr = SwrManager()
        mgr.tryAcquireRefresh("a")
        mgr.tryAcquireRefresh("b")
        assertEquals(2, mgr.snapshot().totalRefreshes)
    }

    @Test
    fun `in-flight count reflects active refreshes`() {
        val mgr = SwrManager()
        assertEquals(0, mgr.snapshot().inFlightCount)
        mgr.tryAcquireRefresh("a")
        mgr.tryAcquireRefresh("b")
        assertEquals(2, mgr.snapshot().inFlightCount)
        mgr.releaseRefresh("a")
        assertEquals(1, mgr.snapshot().inFlightCount)
    }
}