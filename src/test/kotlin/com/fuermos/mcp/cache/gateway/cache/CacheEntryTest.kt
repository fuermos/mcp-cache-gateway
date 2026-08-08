package com.fuermos.mcp.cache.gateway.cache

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for CacheEntry — windows computation + state checks.
 */
class CacheEntryTest {

    private fun sampleEntry(
        createdAtMs: Long = 1_000_000L,
        ttlMs: Int = 60_000,
        swrGraceMs: Long? = null,
        invalidated: Boolean = false
    ): CacheEntry {
        val (freshUntil, staleUntil) = CacheEntry.computeWindows(createdAtMs, ttlMs, swrGraceMs)
        return CacheEntry(
            requestId = "req-1",
            serverId = "test-server",
            method = "tools/call",
            toolName = "get_weather",
            toolVersion = "1.0.0",
            paramsHash = "abc",
            paramsJson = buildJsonObject { put("city", "sf") },
            resultJson = buildJsonObject { put("temp", 72) },
            cacheTier = CacheTier.REDIS,
            ttlMs = ttlMs,
            createdAtMs = createdAtMs,
            freshUntilMs = freshUntil,
            staleUntilMs = staleUntil,
            invalidated = invalidated
        )
    }

    @Test
    fun `computeWindows with no SWR returns null staleUntil`() {
        val (fresh, stale) = CacheEntry.computeWindows(nowMs = 0L, ttlMs = 1000, swrGraceMs = null)
        assertEquals(1000L, fresh)
        assertNull(stale)
    }

    @Test
    fun `computeWindows with SWR grace adds it to freshUntil`() {
        val (fresh, stale) = CacheEntry.computeWindows(nowMs = 1000L, ttlMs = 500, swrGraceMs = 300)
        assertEquals(1500L, fresh)
        assertEquals(1800L, stale)
    }

    @Test
    fun `isExpired returns false within fresh window`() {
        val e = sampleEntry(createdAtMs = 1_000_000L, ttlMs = 60_000)
        assertFalse(e.isExpired(now = 1_030_000L), "should not be expired at fresh window midpoint")
    }

    @Test
    fun `isExpired returns true at or after freshUntil`() {
        val e = sampleEntry(createdAtMs = 1_000_000L, ttlMs = 60_000)
        assertTrue(e.isExpired(now = 1_060_000L), "expired exactly at freshUntil")
        assertTrue(e.isExpired(now = 1_100_000L), "expired after freshUntil")
    }

    @Test
    fun `isInSwrWindow returns false when no SWR`() {
        val e = sampleEntry(createdAtMs = 1_000_000L, ttlMs = 60_000, swrGraceMs = null)
        assertFalse(e.isInSwrWindow(now = 1_030_000L))
        assertFalse(e.isInSwrWindow(now = 1_090_000L))
    }

    @Test
    fun `isInSwrWindow returns true between freshUntil and staleUntil`() {
        val e = sampleEntry(createdAtMs = 1_000_000L, ttlMs = 60_000, swrGraceMs = 30_000)
        assertFalse(e.isInSwrWindow(now = 1_030_000L), "still in fresh window")
        assertTrue(e.isInSwrWindow(now = 1_070_000L), "in SWR window (after freshUntil)")
        assertFalse(e.isInSwrWindow(now = 1_100_000L), "past staleUntil")
    }

    @Test
    fun `isInFreshWindow true during fresh window`() {
        val e = sampleEntry(createdAtMs = 1_000_000L, ttlMs = 60_000)
        assertTrue(e.isInFreshWindow(now = 1_000_000L))
        assertTrue(e.isInFreshWindow(now = 1_059_999L))
    }

    @Test
    fun `remainingFreshMs returns delta to freshUntil`() {
        val e = sampleEntry(createdAtMs = 1_000_000L, ttlMs = 60_000)
        assertEquals(60_000L, e.remainingFreshMs(now = 1_000_000L))
        assertEquals(30_000L, e.remainingFreshMs(now = 1_030_000L))
        assertEquals(0L, e.remainingFreshMs(now = 1_060_000L), "coerced to 0")
        assertEquals(0L, e.remainingFreshMs(now = 1_100_000L), "coerced to 0 even past expiry")
    }

    @Test
    fun `CacheTier includesRedis and includesDb flags`() {
        assertTrue(CacheTier.REDIS.includesRedis())
        assertFalse(CacheTier.REDIS.includesDb())
        assertTrue(CacheTier.DB.includesDb())
        assertFalse(CacheTier.DB.includesRedis())
        assertTrue(CacheTier.BOTH.includesRedis())
        assertTrue(CacheTier.BOTH.includesDb())
    }
}
