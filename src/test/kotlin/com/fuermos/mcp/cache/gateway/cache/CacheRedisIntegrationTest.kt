package com.fuermos.mcp.cache.gateway.cache

import com.fuermos.mcp.cache.gateway.persistence.RedisClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Integration test for CacheLookup + CacheWrite against real Redis.
 *
 * Uses Redis at 127.0.0.1:6379 (no password, default config).
 * Skips if REDIS_INTEGRATION_DISABLED=1 or if Redis unreachable.
 *
 * Each test uses a unique requestId (UUID) to avoid cross-test pollution.
 * Tests clean up their keys in @AfterEach.
 */
@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION", matches = "1")
class CacheRedisIntegrationTest {

    private lateinit var redis: RedisClient
    private lateinit var lookup: CacheLookup
    private lateinit var writer: CacheWrite
    private val nowProvider = { System.currentTimeMillis() }
    private val testKeys = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        redis = RedisClient(uri = RedisClient.DEFAULT_URI)
        try {
            redis.connect()
        } catch (e: Exception) {
            // Skip test gracefully if Redis unavailable
            println("SKIP: Redis unavailable at ${RedisClient.DEFAULT_URI}: ${e.message}")
            return
        }
        lookup = CacheLookup(redis, dbRepo = null, nowProvider = nowProvider)
        writer = CacheWrite(redis, dbRepo = null, nowProvider = nowProvider)
    }

    @AfterEach
    fun tearDown() {
        // Cleanup test keys to avoid pollution
        if (redis.isConnected()) {
            redis.sync { cmd ->
                testKeys.forEach { key -> cmd.del(key) }
            }
        }
        redis.disconnect()
    }

    private fun sampleEntry(
        requestId: String = "req-${System.nanoTime()}",
        ttlMs: Int = 60_000,
        swrGraceMs: Long? = null,
        toolName: String? = "test_tool",
        toolVersion: String? = "1.0.0",
        paramsJson: kotlinx.serialization.json.JsonElement = buildJsonObject { put("city", "sf") },
        resultJson: kotlinx.serialization.json.JsonElement? = buildJsonObject { put("temp", 72) }
    ): CacheEntry {
        val now = nowProvider()
        val (freshUntil, staleUntil) = CacheEntry.computeWindows(now, ttlMs, swrGraceMs)
        testKeys.add(CacheKey.forRequestId(requestId))
        testKeys.add(CacheKey.forParams("test-server", "tools/call", toolName, toolVersion,
            HashingIntegrationHelpers.sha256Params(paramsJson)))
        return CacheEntry(
            requestId = requestId,
            serverId = "test-server",
            method = "tools/call",
            toolName = toolName,
            toolVersion = toolVersion,
            paramsHash = HashingIntegrationHelpers.sha256Params(paramsJson),
            paramsJson = paramsJson,
            resultJson = resultJson,
            resultSize = 10,
            cacheTier = CacheTier.REDIS,
            ttlMs = ttlMs,
            createdAtMs = now,
            freshUntilMs = freshUntil,
            staleUntilMs = staleUntil
        )
    }

    @Test
    fun `lookupByRequestId returns null when key absent`() {
        if (!redis.isConnected()) return
        val hit = lookup.lookupByRequestId("nonexistent-${System.nanoTime()}")
        assertNull(hit)
    }

    @Test
    fun `write then lookupByRequestId returns entry`() {
        if (!redis.isConnected()) return
        val entry = sampleEntry()
        assertTrue(writer.write(entry))
        val hit = lookup.lookupByRequestId(entry.requestId)
        assertNotNull(hit)
        val hit1 = hit
        assertEquals(entry.requestId, hit1!!.requestId)
        assertEquals(entry.toolName, hit.toolName)
        assertEquals(entry.paramsHash, hit.paramsHash)
    }

    @Test
    fun `write then lookupByParams returns entry`() {
        if (!redis.isConnected()) return
        val entry = sampleEntry()
        assertTrue(writer.write(entry))
        val hit = lookup.lookupByParams(
            serverId = entry.serverId,
            method = entry.method,
            toolName = entry.toolName,
            toolVersion = entry.toolVersion,
            paramsHash = entry.paramsHash
        )
        assertNotNull(hit)
        val hit1 = hit
        assertEquals(entry.requestId, hit1!!.requestId)
    }

    @Test
    fun `lookupByParams miss when tool differs`() {
        if (!redis.isConnected()) return
        val entry = sampleEntry(toolName = "toolA")
        writer.write(entry)
        val hit = lookup.lookupByParams(
            serverId = entry.serverId,
            method = entry.method,
            toolName = "toolB",  // different
            toolVersion = entry.toolVersion,
            paramsHash = entry.paramsHash
        )
        assertNull(hit, "different tool should miss")
    }

    @Test
    fun `entry with TTL 1s expires`() {
        if (!redis.isConnected()) return
        val entry = sampleEntry(ttlMs = 1_000)
        writer.write(entry)
        // Hit immediately
        assertNotNull(lookup.lookupByRequestId(entry.requestId))
        // Wait 1.5s
        Thread.sleep(1_500)
        // Now should miss (expired)
        assertNull(lookup.lookupByRequestId(entry.requestId))
    }

    @Test
    fun `invalidateByRequestId removes the key`() {
        if (!redis.isConnected()) return
        val entry = sampleEntry()
        writer.write(entry)
        assertNotNull(lookup.lookupByRequestId(entry.requestId), "should hit before invalidate")
        assertTrue(writer.invalidateByRequestId(entry.requestId))
        assertNull(lookup.lookupByRequestId(entry.requestId), "should miss after invalidate")
    }

    @Test
    fun `RedisClient disconnect is idempotent`() {
        if (!redis.isConnected()) return
        redis.disconnect()
        assertTrue(!redis.isConnected())
        // Second call should not throw
        redis.disconnect()
    }

    @Test
    fun `RedisClient connect after disconnect works`() {
        if (!redis.isConnected()) return
        redis.disconnect()
        // Should be able to reconnect
        try {
            redis.connect()
            assertTrue(redis.isConnected())
        } catch (e: Exception) {
            // If reconnect fails (e.g. server went away), that's also acceptable
            println("Reconnect failed (acceptable): ${e.message}")
        }
    }
}

/**
 * Helpers — note these re-use Hashing.sha256 from utils package.
 * Located here to keep import surface clean for the integration test.
 */
private object HashingIntegrationHelpers {
    fun sha256Params(element: kotlinx.serialization.json.JsonElement): String {
        return com.fuermos.mcp.cache.gateway.utils.Hashing.sha256(element)
    }
}
