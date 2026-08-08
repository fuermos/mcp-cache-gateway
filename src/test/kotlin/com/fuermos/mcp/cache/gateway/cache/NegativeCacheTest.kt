package com.fuermos.mcp.cache.gateway.cache

import com.fuermos.mcp.cache.gateway.transport.JsonRpcError
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for NegativeCache policy.
 */
class NegativeCacheTest {

    @Test
    fun `5xx server errors are cached with 5min TTL`() {
        val nc = NegativeCache()
        val response = JsonRpcResponse.failure(
            id = "req-1",
            error = JsonRpcError(code = -32000, message = "Internal server error")
        )
        val ttl = nc.shouldCache(response)
        assertNotNull(ttl)
        assertEquals(300_000, ttl)
    }

    @Test
    fun `internal error 32603 cached with timeout TTL`() {
        val nc = NegativeCache()
        val response = JsonRpcResponse.failure(
            id = "req-1",
            error = JsonRpcError(code = -32603, message = "timeout")
        )
        assertEquals(60_000, nc.shouldCache(response))
    }

    @Test
    fun `parse error 32700 cached with parse error TTL`() {
        val nc = NegativeCache()
        val response = JsonRpcResponse.failure(
            id = "req-1",
            error = JsonRpcError(code = -32700, message = "Invalid JSON")
        )
        assertEquals(60_000, nc.shouldCache(response))
    }

    @Test
    fun `4xx invalid params not cached`() {
        val nc = NegativeCache()
        val response = JsonRpcResponse.failure(
            id = "req-1",
            error = JsonRpcError(code = -32602, message = "Invalid params")
        )
        assertNull(nc.shouldCache(response))
    }

    @Test
    fun `4xx method not found not cached`() {
        val nc = NegativeCache()
        val response = JsonRpcResponse.failure(
            id = "req-1",
            error = JsonRpcError(code = -32601, message = "Method not found")
        )
        assertNull(nc.shouldCache(response))
    }

    @Test
    fun `4xx invalid request not cached`() {
        val nc = NegativeCache()
        val response = JsonRpcResponse.failure(
            id = "req-1",
            error = JsonRpcError(code = -32600, message = "Invalid request")
        )
        assertNull(nc.shouldCache(response))
    }

    @Test
    fun `success responses are not negative-cached`() {
        val nc = NegativeCache()
        val response = JsonRpcResponse.success(id = "req-1", result = buildJsonObject { put("ok", true) })
        assertNull(nc.shouldCache(response))
    }

    @Test
    fun `buildNegativeEntry metadata marks NEGATIVE_CACHE source`() {
        val nc = NegativeCache()
        val request = JsonRpcRequest(id = "req-1", method = "tools/call", params = buildJsonObject { put("x", 1) })
        val response = JsonRpcResponse.failure(id = "req-1", error = JsonRpcError(code = -32000, message = "boom"))

        val toolCfg = com.fuermos.mcp.cache.gateway.config.ToolConfig(
            name = "test_tool", ttlMs = 60_000, cacheable = true
        )
        val entry = nc.buildNegativeEntry(
            request = request,
            toolCfg = toolCfg,
            serverId = "test-server",
            toolName = "test_tool",
            toolVersion = null,
            paramsHash = "abc",
            paramsJson = request.params!!,
            response = response,
            nowProvider = { 1_000_000L }
        )

        assertEquals(300_000, entry.ttlMs, "5xx should use 5min TTL")
        assertEquals(1_300_000L, entry.freshUntilMs)
        assertNull(entry.staleUntilMs, "no SWR on negative cache")
        assertNotNull(entry.metadata)
        val meta = entry.metadata as kotlinx.serialization.json.JsonObject
        assertEquals("NEGATIVE_CACHE", (meta["source"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(-32000, (meta["error_code"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertTrue(((meta["error_message"] as kotlinx.serialization.json.JsonPrimitive).content).contains("boom"))
    }

    @Test
    fun `buildNegativeEntry throws when response not cacheable`() {
        val nc = NegativeCache()
        val request = JsonRpcRequest(id = "req-1", method = "tools/call")
        val response = JsonRpcResponse.success(id = "req-1", result = buildJsonObject {})
        val toolCfg = com.fuermos.mcp.cache.gateway.config.ToolConfig(
            name = "test_tool", ttlMs = 60_000, cacheable = true
        )
        val ex = runCatching {
            nc.buildNegativeEntry(
                request = request,
                toolCfg = toolCfg,
                serverId = "test",
                toolName = "test_tool",
                toolVersion = null,
                paramsHash = "abc",
                paramsJson = buildJsonObject {},
                response = response
            )
        }.exceptionOrNull()
        assertNotNull(ex)
        val exMsg = ex!!.message
        assertTrue(exMsg!!.contains("not negative-cacheable"))
    }
}