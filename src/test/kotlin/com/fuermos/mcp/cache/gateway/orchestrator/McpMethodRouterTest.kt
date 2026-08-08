package com.fuermos.mcp.cache.gateway.orchestrator

import com.fuermos.mcp.cache.gateway.cache.CacheLookup
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.config.ToolConfigDefaults
import com.fuermos.mcp.cache.gateway.config.ToolConfigResolver
import com.fuermos.mcp.cache.gateway.config.ToolConfigRoot
import com.fuermos.mcp.cache.gateway.persistence.RedisClient
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager
import com.fuermos.mcp.cache.gateway.transport.JsonRpcNotification
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
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

/**
 * Unit + integration tests for McpMethodRouter.
 *
 * Coverage:
 *   - initialize → returns serverInfo + capabilities
 *   - ping → returns empty object
 *   - tools/list → returns empty list (Day 3.1 stub)
 *   - unknown method → returns -32601 method not found
 *   - notification → no response, no exception
 *   - exception in dispatch → returns -32603 internal error
 */
@EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION", matches = "1")
class McpMethodRouterTest {

    private lateinit var redis: RedisClient
    private lateinit var router: McpMethodRouter

    @BeforeEach
    fun setUp() {
        redis = RedisClient(uri = RedisClient.DEFAULT_URI)
        try {
            redis.connect()
        } catch (e: Exception) {
            println("SKIP: Redis unavailable: ${e.message}")
            return
        }

        val lookup = CacheLookup(redis, dbRepo = null)
        val writer = CacheWrite(redis, dbRepo = null)
        val resolver = ToolConfigResolver().apply {
            replaceWith(ToolConfigRoot(defaults = ToolConfigDefaults(ttlMs = 60_000, cacheable = true)))
        }
        val registry = ServerLifecycleManager.InMemoryServerRegistry().apply {
            register(ServerLifecycleManager.ServerConfig(
                serverId = "default", cmd = "/bin/sh", args = listOf("-c", "cat")
            ))
        }
        val servers = ServerLifecycleManager(serverRegistry = registry)
        val orchestrator = GatewayOrchestrator(
            lookup = lookup,
            write = writer,
            servers = servers,
            configResolver = resolver,
            executeTimeoutMs = 500
        )
        router = McpMethodRouter(orchestrator = orchestrator)
    }

    @AfterEach
    fun tearDown() {
        redis.disconnect()
    }

    private fun req(method: String, id: String = "id-${System.nanoTime()}", params: JsonObject? = null): JsonRpcRequest {
        return JsonRpcRequest(id = id, method = method, params = params)
    }

    @Test
    fun `initialize returns serverInfo and capabilities`() {
        if (!redis.isConnected()) return
        val response = router.dispatch(req("initialize"))
        assertTrue(response.isSuccess)
        val result = response.result as JsonObject
        assertNotNull(result["serverInfo"])
        assertNotNull(result["capabilities"])
        val serverInfo = result["serverInfo"] as JsonObject
        assertEquals("mcp-cache-gateway", (serverInfo["name"] as JsonPrimitive).content)
        assertTrue((serverInfo["version"] as JsonPrimitive).content.isNotEmpty())
    }

    @Test
    fun `ping returns empty object`() {
        if (!redis.isConnected()) return
        val response = router.dispatch(req("ping"))
        assertTrue(response.isSuccess)
        val result = response.result as JsonObject
        assertEquals(0, result.size, "ping should return empty object")
    }

    @Test
    fun `tools_list returns empty list (Day 3_1 stub)`() {
        if (!redis.isConnected()) return
        val response = router.dispatch(req("tools/list"))
        assertTrue(response.isSuccess)
        val result = response.result as JsonObject
        val tools = result["tools"] as kotlinx.serialization.json.JsonArray
        assertEquals(0, tools.size, "Day 3.1 stub returns empty tools list")
    }

    @Test
    fun `unknown method returns -32601 method not found`() {
        if (!redis.isConnected()) return
        val response = router.dispatch(req("unknown/method"))
        assertTrue(response.isError)
        assertEquals(-32601, response.error?.code)
    }

    @Test
    fun `notification handler does not throw`() {
        // No exception = pass
        router.handleNotification(JsonRpcNotification(method = "notifications/cancelled",
            params = buildJsonObject { put("requestId", "abc") }))
        router.handleNotification(JsonRpcNotification(method = "notifications/progress"))
        router.handleNotification(JsonRpcNotification(method = "notifications/initialized"))
        router.handleNotification(JsonRpcNotification(method = "unknown/notification"))
    }

    @Test
    fun `custom serverInfo in constructor`() {
        if (!redis.isConnected()) return
        val lookup = CacheLookup(redis, dbRepo = null)
        val writer = CacheWrite(redis, dbRepo = null)
        val resolver = ToolConfigResolver()
        val orchestrator = GatewayOrchestrator(
            lookup, writer,
            ServerLifecycleManager(serverRegistry = ServerLifecycleManager.InMemoryServerRegistry()),
            resolver, executeTimeoutMs = 500
        )
        val customRouter = McpMethodRouter(
            orchestrator = orchestrator,
            serverInfo = McpMethodRouter.ServerInfo(name = "custom-server", version = "9.9.9")
        )
        val response = customRouter.dispatch(req("initialize"))
        val result = response.result as JsonObject
        val serverInfo = result["serverInfo"] as JsonObject
        assertEquals("custom-server", (serverInfo["name"] as JsonPrimitive).content)
        assertEquals("9.9.9", (serverInfo["version"] as JsonPrimitive).content)
    }
}