package com.fuermos.mcp.cache.gateway.http

import com.fuermos.mcp.cache.gateway.orchestrator.AggregateTool
import com.fuermos.mcp.cache.gateway.orchestrator.GeneralProxy
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * StreamableHttpHandler — gateway-side logic for MCP Streamable HTTP transport.
 *
 * Phase 3 (per skill-master 8/9 15:12 CST directive + MCP 2025-03-26 spec):
 *   - Wraps GeneralProxy.aggregateTools() / routeCall() for HTTP exposure
 *   - Encodes JSON-RPC 2.0 responses (kotlinx.serialization Json)
 *   - Decodes JSON-RPC 2.0 requests from HTTP body
 *   - Phase 3 base: returns application/json (immediate response)
 *   - Future SSE / chunked stream: handled here when needed (long-running tools)
 *
 * Pattern references:
 *   - MCP 2025-03-26 spec §transports (HTTP POST + JSON-RPC body, response as application/json OR text/event-stream)
 *   - design.md §1.2 (3 transports: stdio / Streamable HTTP / HTTP+SSE deprecated)
 *   - Day 2.6 GeneralProxy.aggregateTools / routeCall (reused unchanged)
 *
 * Scope (8/9 15:14 skill-master spec step 4):
 *   - POST /mcp/tools/list → handleToolsList() → JSON-RPC response with tool array
 *   - POST /mcp/tools/call → handleToolsCall() → JSON-RPC response from routeCall()
 */
class StreamableHttpHandler(
    private val generalProxy: GeneralProxy,
    private val json: Json = DEFAULT_JSON
) {

    private val log = LoggerFactory.getLogger(StreamableHttpHandler::class.java)

    /**
     * Handle POST /mcp/tools/list — aggregate tools from all enabled backends.
     *
     * Returns JSON-RPC response:
     *   { "jsonrpc": "2.0", "id": "...", "result": { "tools": [ {...}, {...} ] } }
     */
    suspend fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val tools = generalProxy.aggregateTools()
        log.debug("/mcp/tools/list: returning {} tool(s)", tools.size)
        val toolsArray: JsonArray = buildJsonArray {
            tools.forEach { tool -> add(toolToJson(tool)) }
        }
        val result: JsonElement = buildJsonObject {
            put("tools", toolsArray)
        }
        return JsonRpcResponse.success(request.id, result)
    }

    /**
     * Handle POST /mcp/tools/call — route call through GeneralProxy.
     *
     * Returns JSON-RPC response with either:
     *   - { "result": {...} } on success (cache HIT or fresh MISS)
     *   - { "error": { code, message, data? } } on failure
     *
     * GeneralProxy handles cache lookup + spawn + write-back transparently.
     */
    suspend fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        log.debug("/mcp/tools/call: id={}, method={}", request.id, request.method)
        return generalProxy.routeCall(request)
    }

    /**
     * Encode a JsonRpcResponse to JSON string for HTTP body.
     */
    fun encode(response: JsonRpcResponse): String =
        json.encodeToString(JsonRpcResponse.serializer(), response)

    /**
     * Decode an HTTP body string into a JsonRpcRequest.
     * Throws on malformed JSON or missing required fields.
     */
    fun decode(body: String): JsonRpcRequest =
        json.decodeFromString(JsonRpcRequest.serializer(), body)

    /**
     * Convert an AggregateTool to its JSON representation (per MCP spec tools/list).
     */
    private fun toolToJson(tool: AggregateTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        // MCP spec requires inputSchema; provide a permissive default.
        // Per-tool schema will land when per-tool metadata is wired (future).
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("additionalProperties", true)
        })
    }

    companion object {
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}