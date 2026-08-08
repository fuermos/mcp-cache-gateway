package com.fuermos.mcp.cache.gateway.orchestrator

import com.fuermos.mcp.cache.gateway.cache.CacheLookup
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.config.BackendConfig
import com.fuermos.mcp.cache.gateway.config.BackendsRegistry
import com.fuermos.mcp.cache.gateway.persistence.RedisClient
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager
import com.fuermos.mcp.cache.gateway.transport.JsonRpcError
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * Unit tests for GeneralProxy — focused on routing logic + cache pipeline.
 *
 * Uses preloaded BackendsRegistry (no DB) + real Redis for cache verification.
 *
 * Coverage:
 *   - routeCall returns error for missing tool name (-32602)
 *   - routeCall returns error for invalid format (-32602)
 *   - routeCall returns error for unknown backend (-32601)
 *   - routeCall parses 'backend.tool' format correctly
 *   - snapshot returns correct stats
 */
@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION", matches = "1")
class GeneralProxyTest {

    private lateinit var redis: RedisClient
    private lateinit var lookup: CacheLookup
    private lateinit var write: CacheWrite
    private lateinit var serverManager: ServerLifecycleManager

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
        write = CacheWrite(redis, dbRepo = null, nowProvider = nowProvider)

        serverManager = ServerLifecycleManager(
            serverRegistry = ServerLifecycleManager.InMemoryServerRegistry(),
            idleTimeoutMs = 60_000,
            spawnTimeoutMs = 5_000
        )
    }

    @AfterEach
    fun tearDown() {
        if (redis.isConnected()) redis.disconnect()
    }

    private fun fakeBackend(name: String): BackendConfig = BackendConfig(
        name = name,
        displayName = "Fake $name",
        enabled = true,
        cmd = "/bin/sh",
        args = listOf("-c", "cat"),
        cwd = null,
        spawnTimeoutMs = 1000,
        idleTimeoutMs = 60_000,
        maxRestarts = 1,
        eager = false,
        protocol = "stdio",
        env = emptyMap(),
        version = 1
    )

    private fun makeRegistry(backends: List<BackendConfig>): BackendsRegistry {
        return BackendsRegistry(preloadedBackends = backends)
    }

    @Test
    fun `routeCall returns error for missing tool name`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        val registry = makeRegistry(listOf(fakeBackend("fake")))
        val proxy = GeneralProxy(registry, lookup, write, serverManager)

        val request = JsonRpcRequest(
            id = "test-1",
            method = "tools/call",
            params = buildJsonObject { put("foo", "bar") }
        )
        val response = proxy.routeCall(request)
        assertTrue(response.isError)
        assertEquals(-32602, response.error?.code)
    }

    @Test
    fun `routeCall returns error for invalid format`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        val registry = makeRegistry(listOf(fakeBackend("registered")))
        val proxy = GeneralProxy(registry, lookup, write, serverManager)

        val request = JsonRpcRequest(
            id = "test-1",
            method = "tools/call",
            params = buildJsonObject {
                put("name", "no_dot_in_name")
                put("arguments", JsonObject(emptyMap()))
            }
        )
        val response = proxy.routeCall(request)
        assertTrue(response.isError)
        assertEquals(-32602, response.error?.code)
    }

    @Test
    fun `routeCall returns error for unknown backend`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        val registry = makeRegistry(listOf(fakeBackend("registered")))
        val proxy = GeneralProxy(registry, lookup, write, serverManager)

        val request = JsonRpcRequest(
            id = "test-1",
            method = "tools/call",
            params = buildJsonObject {
                put("name", "unknown.tool")
                put("arguments", JsonObject(emptyMap()))
            }
        )
        val response = proxy.routeCall(request)
        assertTrue(response.isError)
        assertEquals(-32601, response.error?.code)
    }

    @Test
    fun `routeCall format is parsed correctly`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        val registry = makeRegistry(listOf(fakeBackend("fake")))
        val proxy = GeneralProxy(registry, lookup, write, serverManager)

        // Use cached request_id so cache HIT path triggers (avoid subprocess spawn)
        // First call: cache miss → would try to spawn. Skip via cached entry.
        // Instead, just verify routeCall correctly looks up backend in cache list.
        // We'll set up a cached entry directly.
        val cachedBackend = fakeBackend("fake")
        val cachedEntry = com.fuermos.mcp.cache.gateway.cache.CacheEntry(
            requestId = "cache-hit-test",
            serverId = "fake",
            method = "tools/call",
            toolName = "fake.some_tool",
            toolVersion = null,
            paramsHash = "ignored",
            paramsJson = JsonObject(emptyMap()),
            resultJson = buildJsonObject { put("result", "from_cache") },
            resultSize = 0,
            cacheTier = com.fuermos.mcp.cache.gateway.cache.CacheTier.REDIS,
            ttlMs = 60_000,
            createdAtMs = System.currentTimeMillis(),
            freshUntilMs = System.currentTimeMillis() + 60_000,
            staleUntilMs = null
        )
        write.write(cachedEntry)

        // Now request with cached id — should HIT, return cached result
        val request = JsonRpcRequest(
            id = "cache-hit-test",
            method = "tools/call",
            params = buildJsonObject {
                put("name", "fake.some_tool")
                put("arguments", JsonObject(emptyMap()))
            }
        )
        val response = proxy.routeCall(request)
        assertTrue(response.isSuccess)
        val result = response.result as JsonObject
        assertEquals("from_cache", (result.get("result") as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `snapshot returns correct stats`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        val registry = makeRegistry(listOf(fakeBackend("snap-test")))
        val proxy = GeneralProxy(registry, lookup, write, serverManager)

        val s1 = proxy.snapshot()
        assertEquals(0, s1.aggregateCalls)
        assertEquals(0, s1.routeCalls)
        assertEquals(0, s1.toolListCacheSize)
    }

    @Test
    fun `snapshot reflects aggregateTools call count`() = runBlocking {
        if (!redis.isConnected()) return@runBlocking
        val registry = makeRegistry(listOf(fakeBackend("agg")))
        val proxy = GeneralProxy(registry, lookup, write, serverManager)

        // aggregateTools will try to spawn subprocess — fails gracefully
        proxy.aggregateTools()
        val s = proxy.snapshot()
        assertEquals(1, s.aggregateCalls, "aggregateTools should increment aggregateCalls")
    }
}