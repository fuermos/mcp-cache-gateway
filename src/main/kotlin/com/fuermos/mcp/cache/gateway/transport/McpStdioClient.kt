package com.fuermos.mcp.cache.gateway.transport

import com.fuermos.mcp.cache.gateway.config.BackendConfig
import com.fuermos.mcp.cache.gateway.server.ServerHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * McpStdioClient — JSON-RPC 2.0 client over a ServerHandle's subprocess stdio.
 *
 * Phase 2.5.1 design (per spec §4 Phase 2 2.5.1):
 *   - Lightweight JSON-RPC client (send/recv only)
 *   - Wraps an existing ServerHandle (spawn lifecycle managed by ServerLifecycleManager)
 *   - sendRequest(method, params): JsonElement
 *   - sendNotification(method, params): no response
 *
 * Pattern references:
 *   - 借鉴 Phase 1 wrongnotebook-mcp-bridge.js (JSON-RPC over stdio pattern)
 *   - 借鉴 Day 1.1 StdioTransport (line-delimited NDJSON — but we OWN write side here)
 *   - 借鉴 Day 1.2 ServerHandle (subprocess spawn + execute — we reuse, not reimplement)
 *
 * Phase 2.5 simplification: McpStdioClient wraps execute() for type conversion
 * (method+params → JsonRpcRequest, response.result → JsonElement).
 */
class McpStdioClient(
    private val backend: BackendConfig,
    private val handle: ServerHandle
) {

    private val log = LoggerFactory.getLogger(McpStdioClient::class.java)

    // encodeDefaults = true so `jsonrpc: "2.0"` is always serialized into requests.
    // Without this, kotlinx.serialization drops default-valued fields (JsonRpcRequest.jsonrpc
    // defaults to "2.0"), and MCP peers like tubi-mcp/wrongnotebook-mcp-bridge reject the
    // request with "Invalid Request: jsonrpc must be \"2.0\"".
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Send a JSON-RPC request and wait for response result.
     *
     * @return parsed response result (JsonElement)
     * @throws RuntimeException on error
     */
    suspend fun sendRequest(method: String, params: JsonElement? = null): JsonElement {
        val id = UUID.randomUUID().toString()
        val request = JsonRpcRequest(id = id, method = method, params = params)
        val response = handle.execute(request)
        if (response.isError) {
            throw RuntimeException(
                "backend '${backend.name}' returned error for $method: " +
                    (response.error?.message ?: "unknown")
            )
        }
        return response.result ?: kotlinx.serialization.json.JsonNull
    }

    /**
     * Send a JSON-RPC notification (no response expected).
     *
     * Wraps in JsonRpcRequest with id=null marker — actually uses Notification type.
     */
    suspend fun sendNotification(method: String, params: JsonElement? = null) {
        // Reuse sendRequest with a notification-style wrapper
        // Actually JSON-RPC notifications have no id — ServerHandle.execute expects id,
        // so we encode as a request but caller treats as fire-and-forget.
        // For Phase 2.5, we'll skip notification support — all MCP methods we need are requests.
        log.warn("sendNotification not yet implemented (method={})", method)
    }

    /**
     * Convenience: list tools from backend.
     *
     * @return List of JsonObject with 'name' + 'description' + 'inputSchema'
     */
    suspend fun listTools(): List<JsonObject> {
        val result = sendRequest("tools/list", null)
        return when (result) {
            is JsonObject -> {
                val tools = result["tools"] as? JsonArray ?: return emptyList()
                tools.mapNotNull { it as? JsonObject }
            }
            else -> emptyList()
        }
    }

    companion object {
        /**
         * Wrap a ServerHandle as McpStdioClient for type-safe JSON-RPC.
         */
        fun wrap(backend: BackendConfig, handle: ServerHandle): McpStdioClient =
            McpStdioClient(backend, handle)
    }
}