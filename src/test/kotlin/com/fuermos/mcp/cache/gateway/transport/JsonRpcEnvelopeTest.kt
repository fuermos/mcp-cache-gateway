package com.fuermos.mcp.cache.gateway.transport

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for JsonRpcEnvelope data classes.
 *
 * Coverage:
 *   - Construction + field defaults
 *   - isSuccess / isError helpers
 *   - Standard error codes are in expected range
 *   - Round-trip serialization (encode → decode preserves identity)
 */
class JsonRpcEnvelopeTest {

    @Test
    fun `JsonRpcRequest has id, method, params, default jsonrpc 2_0`() {
        val req = JsonRpcRequest(
            id = "abc-123",
            method = "tools/call",
            params = buildJsonObject { put("name", "get_weather") }
        )
        assertEquals("abc-123", req.id)
        assertEquals("tools/call", req.method)
        assertNotNull(req.params)
        assertEquals("2.0", req.jsonrpc)
    }

    @Test
    fun `JsonRpcRequest params is optional`() {
        val req = JsonRpcRequest(id = "x", method = "ping")
        assertNull(req.params)
    }

    @Test
    fun `JsonRpcResponse success factory builds correct shape`() {
        val result = buildJsonObject { put("ok", true) }
        val resp = JsonRpcResponse.success(id = "r1", result = result)
        assertTrue(resp.isSuccess)
        assertFalse(resp.isError)
        assertEquals(result, resp.result)
        assertNull(resp.error)
    }

    @Test
    fun `JsonRpcResponse failure factory builds correct shape`() {
        val err = JsonRpcError(
            code = JsonRpcResponse.ERR_METHOD_NOT_FOUND,
            message = "no such method"
        )
        val resp = JsonRpcResponse.failure(id = "r2", error = err)
        assertFalse(resp.isSuccess)
        assertTrue(resp.isError)
        assertEquals(err, resp.error)
        assertNull(resp.result)
    }

    @Test
    fun `JsonRpcNotification has method but no id field`() {
        val notif = JsonRpcNotification(
            method = "notifications/initialized",
            params = buildJsonObject { put("version", "2026-07-28") }
        )
        assertEquals("notifications/initialized", notif.method)
        // Notifications do NOT carry an id by JSON-RPC 2.0 spec
        // (we model id as not present in the data class itself)
    }

    @Test
    fun `standard error codes are in spec-defined ranges`() {
        // Per JSON-RPC 2.0 §5.1
        assertEquals(-32700, JsonRpcResponse.ERR_PARSE_ERROR)
        assertEquals(-32600, JsonRpcResponse.ERR_INVALID_REQUEST)
        assertEquals(-32601, JsonRpcResponse.ERR_METHOD_NOT_FOUND)
        assertEquals(-32602, JsonRpcResponse.ERR_INVALID_PARAMS)
        assertEquals(-32603, JsonRpcResponse.ERR_INTERNAL_ERROR)
        // Server error base
        assertEquals(-32000, JsonRpcResponse.ERR_SERVER_ERROR_BASE)
    }

    @Test
    fun `round-trip serialize deserialize preserves identity (Request)`() {
        val original = JsonRpcRequest(
            id = "0190a3b4-7c89-7abc-9def-1234567890ab",
            method = "resources/read",
            params = buildJsonObject {
                put("uri", "file:///tmp/test.txt")
                put("limit", 100)
            }
        )
        val json = kotlinx.serialization.json.Json
        val encoded = json.encodeToString(JsonRpcRequest.serializer(), original)
        val decoded = json.decodeFromString(JsonRpcRequest.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip serialize deserialize preserves identity (Response success)`() {
        val original = JsonRpcResponse.success(
            id = "abc",
            result = buildJsonObject {
                put("temperature", 72)
                put("unit", "F")
            }
        )
        val json = kotlinx.serialization.json.Json
        val encoded = json.encodeToString(JsonRpcResponse.serializer(), original)
        val decoded = json.decodeFromString(JsonRpcResponse.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip serialize deserialize preserves identity (Notification)`() {
        val original = JsonRpcNotification(
            method = "notifications/tools/list_changed"
        )
        val json = kotlinx.serialization.json.Json
        val encoded = json.encodeToString(JsonRpcNotification.serializer(), original)
        val decoded = json.decodeFromString(JsonRpcNotification.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `JsonRpcError carries optional data field`() {
        val errWithData = JsonRpcError(
            code = -32602,
            message = "Invalid params",
            data = buildJsonObject {
                put("field", "tool_name")
                put("reason", "required but missing")
            }
        )
        assertNotNull(errWithData.data)
        assertTrue(errWithData.data is JsonObject)

        val errNoData = JsonRpcError(code = -32601, message = "Method not found")
        assertNull(errNoData.data)
    }
}
