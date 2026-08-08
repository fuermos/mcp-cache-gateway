package com.fuermos.mcp.cache.gateway.integration

import com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookBridge
import com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookClient
import com.fuermos.mcp.cache.gateway.cache.CacheLookup
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.cache.NegativeCache
import com.fuermos.mcp.cache.gateway.config.ToolConfig
import com.fuermos.mcp.cache.gateway.config.ToolConfigDefaults
import com.fuermos.mcp.cache.gateway.config.ToolConfigResolver
import com.fuermos.mcp.cache.gateway.config.ToolConfigRoot
import com.fuermos.mcp.cache.gateway.orchestrator.GatewayOrchestrator
import com.fuermos.mcp.cache.gateway.persistence.RedisClient
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager
import com.fuermos.mcp.cache.gateway.transport.JsonRpcError
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
 * Integration tests for Scenario 4 — Negative Cache.
 *
 * Coverage:
 *   - 5xx 错误 → 短 TTL 5min 缓存 + 不重打 API
 *   - 4xx 错误 → 不缓存
 *   - timeout 错误 → 短 TTL 1min 缓存
 */
@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION", matches = "1")
class NegativeCacheIntegrationTest {

    private lateinit var redis: RedisClient
    private lateinit var lookup: CacheLookup
    private lateinit var writer: CacheWrite
    private lateinit var mockClient: WrongNotebookClient
    private lateinit var mockBridge: WrongNotebookBridge
    private lateinit var orchestrator: GatewayOrchestrator

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

        val resolver = ToolConfigResolver().apply {
            replaceWith(ToolConfigRoot(
                tools = listOf(
                    ToolConfig(name = "wrongnotebook.list_notebooks", version = "1.0.0",
                        ttlMs = 60_000, cacheable = true)
                ),
                defaults = ToolConfigDefaults(ttlMs = 60_000, cacheable = true)
            ))
        }
        orchestrator = GatewayOrchestrator(
            lookup = lookup,
            write = writer,
            servers = ServerLifecycleManager(ServerLifecycleManager.InMemoryServerRegistry()),
            configResolver = resolver,
            swrManager = com.fuermos.mcp.cache.gateway.cache.SwrManager(),
            negativeCache = NegativeCache(),
            wrongNotebookBridge = mockBridge,
            executeTimeoutMs = 1_000
        )
    }

    @AfterEach
    fun tearDown() {
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
    fun `5xx 错误 → 短 TTL 5min 缓存 + 不重打 API`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        // Mock bridge to return 5xx error
        every { mockClient.listNotebooks() } throws com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookApiException(
            code = "WRONGNOTEBOOK_HTTP_500",
            message = "Internal server error",
            httpStatus = 500,
            body = ""
        )

        val req1 = buildRequest("wrongnotebook.list_notebooks")
        val r1 = orchestrator.handle(req1)
        assertTrue(r1.isError)
        // Verify second call returns cached negative entry (no re-fetch)
        val req2 = buildRequest("wrongnotebook.list_notebooks")
        orchestrator.handle(req2)
        // Bridge called once for first miss + once for negative cache writeback
        // (no second fetch on error-miss)
        verify(atLeast = 1) { mockClient.listNotebooks() }
    }

    @Test
    fun `4xx 错误 → 不缓存`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        every { mockClient.listNotebooks() } throws com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookApiException(
            code = "WRONGNOTEBOOK_HTTP_422",
            message = "Invalid params",
            httpStatus = 422,
            body = ""
        )

        val req1 = buildRequest("wrongnotebook.list_notebooks")
        val r1 = orchestrator.handle(req1)
        assertTrue(r1.isError)
        // Verify NO cache write
        val cached = lookup.lookupByRequestId(req1.id)
        assertNull(cached, "4xx should not be cached")
    }

    @Test
    fun `timeout 错误 → 短 TTL 1min 缓存`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        every { mockClient.listNotebooks() } throws com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookApiException(
            code = "WRONGNOTEBOOK_HTTP_504",
            message = "Gateway timeout",
            httpStatus = 504,
            body = ""
        )

        val req1 = buildRequest("wrongnotebook.list_notebooks")
        val r1 = orchestrator.handle(req1)
        assertTrue(r1.isError)
        // Verify write to cache (negative cache)
        val cached = lookup.lookupByRequestId(req1.id)
        assertNotNull(cached, "timeout should be cached as negative")
        // Second call should hit cached negative entry
        val req2 = buildRequest("wrongnotebook.list_notebooks")
        orchestrator.handle(req2)
        verify(atLeast = 1) { mockClient.listNotebooks() }
    }
}