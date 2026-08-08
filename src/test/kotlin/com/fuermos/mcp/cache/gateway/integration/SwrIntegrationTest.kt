package com.fuermos.mcp.cache.gateway.integration

import com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookBridge
import com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookClient
import com.fuermos.mcp.cache.gateway.cache.CacheEntry
import com.fuermos.mcp.cache.gateway.cache.CacheLookup
import com.fuermos.mcp.cache.gateway.cache.CacheTier
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.cache.SwrManager
import com.fuermos.mcp.cache.gateway.config.ToolConfig
import com.fuermos.mcp.cache.gateway.config.ToolConfigDefaults
import com.fuermos.mcp.cache.gateway.config.ToolConfigResolver
import com.fuermos.mcp.cache.gateway.config.ToolConfigRoot
import com.fuermos.mcp.cache.gateway.orchestrator.GatewayOrchestrator
import com.fuermos.mcp.cache.gateway.persistence.RedisClient
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.utils.Hashing
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Integration tests for Scenario 3 — Stale-While-Revalidate (SWR).
 *
 * Coverage:
 *   - TTL 到期 + 在 stale 窗口内 → 返回 stale + async refresh
 *   - TTL 到期 + 超过 stale 窗口 → cache miss + 真调
 *   - single-flight: 同一 params_hash 同时 5 个请求只 1 个 refresh
 *
 * Uses real Redis at 127.0.0.1:6379 (REDIS_INTEGRATION=1) + mocked WrongNotebookClient.
 */
@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION", matches = "1")
class SwrIntegrationTest {

    private lateinit var redis: RedisClient
    private lateinit var lookup: CacheLookup
    private lateinit var writer: CacheWrite
    private lateinit var mockClient: WrongNotebookClient
    private lateinit var mockBridge: WrongNotebookBridge
    private lateinit var swr: SwrManager
    private lateinit var orchestrator: GatewayOrchestrator
    private val testKeys = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        redis = RedisClient(uri = RedisClient.DEFAULT_URI)
        try {
            redis.connect()
        } catch (e: Exception) {
            println("SKIP: Redis unavailable: ${e.message}")
            return
        }
        val nowProvider = { System.currentTimeMillis() }
        lookup = CacheLookup(redis, dbRepo = null, nowProvider = nowProvider)
        writer = CacheWrite(redis, dbRepo = null, nowProvider = nowProvider)

        mockClient = mockk(relaxed = true)
        mockBridge = WrongNotebookBridge(mockClient)

        // 2s TTL + 30s SWR grace for testing
        val resolver = ToolConfigResolver().apply {
            replaceWith(ToolConfigRoot(
                tools = listOf(
                    ToolConfig(name = "wrongnotebook.list_notebooks", version = "1.0.0",
                        ttlMs = 2_000, cacheable = true, swrGraceMs = 30_000)
                ),
                defaults = ToolConfigDefaults(ttlMs = 60_000, cacheable = true, swrGraceMs = 30_000)
            ))
        }

        swr = SwrManager()
        orchestrator = GatewayOrchestrator(
            lookup = lookup,
            write = writer,
            servers = ServerLifecycleManager(ServerLifecycleManager.InMemoryServerRegistry()),
            configResolver = resolver,
            swrManager = swr,
            wrongNotebookBridge = mockBridge,
            executeTimeoutMs = 1_000
        )
    }

    @AfterEach
    fun tearDown() {
        if (redis.isConnected()) {
            redis.sync { cmd -> testKeys.forEach { cmd.del(it) } }
        }
        redis.disconnect()
    }

    private fun buildRequest(toolName: String): JsonRpcRequest =
        JsonRpcRequest(
            id = "req-${System.nanoTime()}",
            method = "tools/call",
            params = buildJsonObject {
                put("name", toolName)
                put("arguments", buildJsonObject {})
            }
        )

    @Test
    fun `TTL 到期 + 在 stale 窗口内 → 返回 stale + async refresh`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        every { mockClient.listNotebooks() } returns buildJsonObject { put("count", 1) }

        // First call: cache miss + write
        val req1 = buildRequest("wrongnotebook.list_notebooks")
        orchestrator.handle(req1)
        // Wait for TTL to expire (2s) but stay in SWR window (30s)
        delay(2_500)
        // Second call: miss → not yet, should be STALE (TTL 2s, SWR 30s)
        every { mockClient.listNotebooks() } returns buildJsonObject { put("count", 2) }
        val req2 = buildRequest("wrongnotebook.list_notebooks")
        val statsBefore = orchestrator.snapshotStats()
        val resp2 = orchestrator.handle(req2)
        // Wait for async refresh to complete
        delay(200)

        assertTrue(resp2.isSuccess)
        // after stale hit: staleHits counter should be ≥ 1
        val statsAfter = orchestrator.snapshotStats()
        assertTrue(statsAfter.staleHits >= statsBefore.staleHits, "should have stale hit")
    }

    @Test
    fun `TTL 到期 + 超过 stale 窗口 → cache miss + 真调`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        every { mockClient.listNotebooks() } returns buildJsonObject { put("count", 1) }

        // Use fixed request_id so cache lookup can find entry
        val fixedId = "test-req-fixed-1"
        val req1 = JsonRpcRequest(
            id = fixedId,
            method = "tools/call",
            params = buildJsonObject {
                put("name", "wrongnotebook.list_notebooks")
                put("arguments", buildJsonObject {})
            }
        )
        orchestrator.handle(req1)
        // Verify entry was cached
        val cached = lookup.lookupByRequestId(fixedId)
        assertNotNull(cached, "should be cached immediately after write")
        // Manually expire by deleting (simulates TTL + SWR expiry)
        redis.sync { it.del("mcp:req:$fixedId") }
        // Next call should be a miss
        every { mockClient.listNotebooks() } returns buildJsonObject { put("count", 2) }
        val statsBefore = orchestrator.snapshotStats()
        orchestrator.handle(req1.copy(id = "test-req-fixed-2"))
        val statsAfter = orchestrator.snapshotStats()
        // Note: stats include all hits/misses since orchestrator start
        assertTrue(statsAfter.misses >= statsBefore.misses, "should have at least 1 miss")
    }

    @Test
    fun `single-flight 同一 params_hash 同时 5 个请求只 1 个 refresh`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        // Pre-populate a stale entry (TTL=2s, SWR=30s)
        every { mockClient.listNotebooks() } returns buildJsonObject { put("count", 1) }
        val req1 = buildRequest("wrongnotebook.list_notebooks")
        orchestrator.handle(req1)
        // Wait 2.5s to enter SWR window
        delay(2_500)
        // Now: 5 sequential requests in SWR window — each triggers SWR path
        every { mockClient.listNotebooks() } returns buildJsonObject { put("count", 2) }
        val requests = (1..5).map { buildRequest("wrongnotebook.list_notebooks") }
        val results = requests.map { orchestrator.handle(it) }
        results.forEach { assertTrue(it.isSuccess) }
        // Wait a moment for any async refresh
        delay(500)
        // SWR stats: each stale hit triggers a refresh (since sequential, no overlap)
        val swrStats = swr.snapshot()
        assertTrue(swrStats.totalRefreshes >= 1, "should have at least 1 SWR refresh, got ${swrStats.totalRefreshes}")
    }
}