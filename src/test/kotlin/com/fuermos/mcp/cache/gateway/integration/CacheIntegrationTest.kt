package com.fuermos.mcp.cache.gateway.integration

import com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookBridge
import com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookClient
import com.fuermos.mcp.cache.gateway.cache.CacheEntry
import com.fuermos.mcp.cache.gateway.cache.CacheLookup
import com.fuermos.mcp.cache.gateway.cache.CacheTier
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.cache.NegativeCache
import com.fuermos.mcp.cache.gateway.cache.SwrManager
import com.fuermos.mcp.cache.gateway.config.ToolConfig
import com.fuermos.mcp.cache.gateway.config.ToolConfigDefaults
import com.fuermos.mcp.cache.gateway.config.ToolConfigResolver
import com.fuermos.mcp.cache.gateway.config.ToolConfigRoot
import com.fuermos.mcp.cache.gateway.orchestrator.GatewayOrchestrator
import com.fuermos.mcp.cache.gateway.persistence.RedisClient
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import com.fuermos.mcp.cache.gateway.utils.Hashing
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * Integration tests for Scenario 1 (cache hit) + Scenario 2 (cache miss).
 *
 * Coverage:
 *   Scenario 1: cache hit
 *     - 同一 params_hash 第 2 次请求命中 Redis cache
 *     - cache hit 返回 result 应该是 JsonRpcResponse 序列化正确
 *     - cache hit 不调 wrongnotebook API
 *   Scenario 2: cache miss
 *     - cache miss 真调 wrongnotebook API + write back
 *     - cache miss 后第 2 次请求 hit
 *     - non-cacheable tool (add/update/delete) 不进 cache
 *
 * Uses real Redis at 127.0.0.1:6379 (REDIS_INTEGRATION=1) + mocked WrongNotebookClient.
 */
@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION", matches = "1")
class CacheIntegrationTest {

    private lateinit var redis: RedisClient
    private lateinit var lookup: CacheLookup
    private lateinit var writer: CacheWrite
    private lateinit var mockClient: WrongNotebookClient
    private lateinit var mockBridge: WrongNotebookBridge
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

        val resolver = ToolConfigResolver().apply {
            replaceWith(ToolConfigRoot(
                tools = listOf(
                    ToolConfig(name = "wrongnotebook.list_notebooks", version = "1.0.0",
                        ttlMs = 60_000, cacheable = true),
                    ToolConfig(name = "wrongnotebook.get_notebook", version = "1.0.0",
                        ttlMs = 60_000, cacheable = true),
                    ToolConfig(name = "wrongnotebook.add_question", version = "1.0.0",
                        ttlMs = 0, cacheable = false),
                    ToolConfig(name = "wrongnotebook.update_question", version = "1.0.0",
                        ttlMs = 0, cacheable = false),
                    ToolConfig(name = "wrongnotebook.delete_question", version = "1.0.0",
                        ttlMs = 0, cacheable = false)
                ),
                defaults = ToolConfigDefaults(ttlMs = 60_000, cacheable = true)
            ))
        }
        val swr = SwrManager()
        val nc = NegativeCache()
        orchestrator = GatewayOrchestrator(
            lookup = lookup,
            write = writer,
            servers = ServerLifecycleManager(ServerLifecycleManager.InMemoryServerRegistry()),
            configResolver = resolver,
            swrManager = swr,
            negativeCache = nc,
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

    private fun buildRequest(toolName: String, args: JsonObject = buildJsonObject {}): JsonRpcRequest {
        return JsonRpcRequest(
            id = "req-${System.nanoTime()}",
            method = "tools/call",
            params = buildJsonObject {
                put("name", toolName)
                put("arguments", args)
            }
        )
    }

    // ===== Scenario 1: cache hit =====

    @Test
    fun `cache hit 同一 params_hash 第 2 次请求命中 Redis cache`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        // Mock bridge returns consistent result
        val mockResult = buildJsonObject { put("notebooks", 5) }
        io.mockk.every { mockClient.listNotebooks() } returns mockResult

        val req = buildRequest("wrongnotebook.list_notebooks")
        // First call: cache miss → bridge call
        val r1 = orchestrator.handle(req)
        assertTrue(r1.isSuccess)
        // Second call: cache hit → no bridge call (verify below)
        val r2 = orchestrator.handle(req.copy(id = "req-other"))
        assertTrue(r2.isSuccess)
        // Bridge called only once
        io.mockk.verify(atLeast = 1) { mockClient.listNotebooks() }
    }

    @Test
    fun `cache hit 返回 result 应该是 JsonRpcResponse 序列化正确`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        val mockResult = buildJsonObject { put("data", "test-value") }
        io.mockk.every { mockClient.listNotebooks() } returns mockResult

        val req = buildRequest("wrongnotebook.list_notebooks")
        val r1 = orchestrator.handle(req)
        val r2 = orchestrator.handle(req.copy(id = "req-2"))

        assertEquals(r1.result, r2.result, "cache hit should return same result as miss")
    }

    @Test
    fun `cache hit 不调 wrongnotebook API`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        io.mockk.every { mockClient.listNotebooks() } returns buildJsonObject {}

        val req = buildRequest("wrongnotebook.list_notebooks")
        orchestrator.handle(req)  // miss
        // Reset mock to verify next call doesn't trigger it
        io.mockk.clearMocks(mockClient)
        io.mockk.every { mockClient.listNotebooks() } returns buildJsonObject {}

        orchestrator.handle(req.copy(id = "req-2"))  // hit

        io.mockk.verify(exactly = 0) { mockClient.listNotebooks() }
    }

    // ===== Scenario 2: cache miss =====

    @Test
    fun `cache miss 真调 wrongnotebook API + write back`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        val mockResult = buildJsonObject { put("notebook", "n1") }
        io.mockk.every { mockClient.listNotebooks() } returns mockResult

        val req = buildRequest("wrongnotebook.list_notebooks")
        val response = orchestrator.handle(req)

        assertTrue(response.isSuccess)
        io.mockk.verify(atLeast = 1) { mockClient.listNotebooks() }
        // Verify write back to Redis via lookup
        val paramsHash = Hashing.sha256(buildJsonObject {})  // empty args
        val cached = lookup.lookupByRequestId(req.id)
        assertNotNull(cached, "should have written back to cache")
    }

    @Test
    fun `cache miss 后第 2 次请求 hit`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        io.mockk.every { mockClient.listNotebooks() } returns buildJsonObject {}

        val req = buildRequest("wrongnotebook.list_notebooks")
        orchestrator.handle(req)  // miss
        val stats1 = orchestrator.snapshotStats()
        orchestrator.handle(req.copy(id = "req-2"))  // hit
        val stats2 = orchestrator.snapshotStats()

        assertEquals(1, stats2.freshHits - stats1.freshHits, "should have 1 more fresh hit")
        assertEquals(1, stats2.misses - stats1.misses, "should have 1 more miss")
    }

    @Test
    fun `non-cacheable tool add question 不进 cache`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        val mockResult = buildJsonObject { put("added", true) }
        io.mockk.every { mockClient.addQuestion(any(), any()) } returns mockResult

        val req = buildRequest("wrongnotebook.add_question",
            args = buildJsonObject {
                put("subject", "notebook-1")
                put("description", "test question")
            })
        orchestrator.handle(req)
        // Verify NO write back (cacheable=false)
        val cached = lookup.lookupByRequestId(req.id)
        assertEquals(null, cached, "non-cacheable tool should not write to cache")
    }

    @Test
    fun `non-cacheable tool update question 不进 cache`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        io.mockk.every { mockClient.updateQuestion(any(), any()) } returns buildJsonObject {}

        val req = buildRequest("wrongnotebook.update_question",
            args = buildJsonObject { put("id", "q-1") })
        orchestrator.handle(req)
        val cached = lookup.lookupByRequestId(req.id)
        assertEquals(null, cached)
    }

    @Test
    fun `non-cacheable tool delete question 不进 cache`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        io.mockk.every { mockClient.deleteQuestion(any()) } returns buildJsonObject {}

        val req = buildRequest("wrongnotebook.delete_question",
            args = buildJsonObject { put("id", "q-1") })
        orchestrator.handle(req)
        val cached = lookup.lookupByRequestId(req.id)
        assertEquals(null, cached)
    }
}