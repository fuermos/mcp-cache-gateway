package com.fuermos.mcp.cache.gateway.orchestrator

import com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookBridge
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.transport.JsonRpcError
import com.fuermos.mcp.cache.gateway.transport.JsonRpcNotification
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * McpMethodRouter — JSON-RPC method dispatcher for MCP server methods.
 *
 * Day 3.1 design (智多星 approval):
 *   - Routes MCP JSON-RPC requests to:
 *     - initialize → handled locally (returns server capabilities)
 *     - ping → handled locally
 *     - tools/list → forwards to first healthy server (Day 3: skip — handled by GatewayOrchestrator if toolName)
 *     - tools/call → forwards to GatewayOrchestrator (cache lookup → execute → write back)
 *     - notifications/<star> → fire-and-forget (Day 4 stub for cancel + progress)
 *   - Returns JsonRpcResponse with proper JSON-RPC 2.0 error format
 *
 * Pattern references:
 *   - design.md §6 (notification handling)
 *   - spec §4 Day 3 (orchestrator + per-tool TTL routing)
 */
class McpMethodRouter(
    private val orchestrator: GatewayOrchestrator,
    private val cacheWrite: CacheWrite? = null,
    private val wrongNotebookBridge: WrongNotebookBridge? = null,
    private val serverInfo: ServerInfo = defaultServerInfo()
) {

    private val log = LoggerFactory.getLogger(McpMethodRouter::class.java)

    /**
     * Dispatch a JSON-RPC request to the appropriate handler.
     *
     * Synchronous wrapper — uses runBlocking for the suspend orchestrator.
     * Stdio transport is single-threaded so blocking is acceptable.
     */
    fun dispatch(request: JsonRpcRequest): JsonRpcResponse {
        return runBlocking {
            try {
                when (request.method) {
                    "initialize" -> handleInitialize(request)
                    "ping" -> handlePing(request)
                    "tools/list" -> handleToolsList(request)
                    "tools/call" -> {
                        // Day 5: check if this is a wrongnotebook bridge tool
                        val wnResp = handleWrongNotebookToolCall(request)
                        if (wnResp != null) wnResp else orchestrator.handle(request)
                    }
                    else -> JsonRpcResponse.failure(
                        request.id,
                        JsonRpcError(
                            code = JsonRpcResponse.ERR_METHOD_NOT_FOUND,
                            message = "Method not found: ${request.method}"
                        )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // coroutine cancellation must propagate
            } catch (e: Exception) {
                log.error("dispatch error for method={}, id={}: {}", request.method, request.id, e.message, e)
                JsonRpcResponse.failure(
                    request.id,
                    JsonRpcError(
                        code = JsonRpcResponse.ERR_INTERNAL_ERROR,
                        message = "Internal error: ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * Handle a notification (no response expected).
     *
     * Day 3.1: minimal — log + ignore. Day 4+ will wire:
     *   - notifications/cancelled → cancel pending request
     *   - notifications/progress → update progress tracking
     *   - notifications/initialized → mark ready
     */
    fun handleNotification(notification: JsonRpcNotification) {
        log.debug("notification: method={}", notification.method)
        when (notification.method) {
            "notifications/cancelled" -> {
                // Day 4 stub — cancel handling is Day 5+ (need in-flight request registry)
                log.debug("cancel received: {}", notification.params)
            }
            "notifications/progress" -> log.debug("progress: {}", notification.params)
            "notifications/initialized" -> log.debug("client initialized")
            "notifications/tools/list_changed" -> handleToolsListChanged(notification.params)
            "notifications/tools/invalidate" -> handleToolsInvalidate(notification.params)
            "notifications/config_changed" -> {
                // Day 5+ — re-load tools.yaml + ToolConfigResolver.replaceWith()
                log.debug("config_changed received (Day 5+ feature, ignored for now)")
            }
            else -> log.debug("ignoring unknown notification: {}", notification.method)
        }
    }

    /**
     * Handle notifications/tools/list_changed — invalidate all cache entries.
     *
     * Per MCP spec: tool list changed means old tool metadata may be stale.
     * Safest action: invalidate ALL cached entries (we don't know which tools changed).
     */
    private fun handleToolsListChanged(params: JsonElement?) {
        if (cacheWrite == null) {
            log.warn("notifications/tools/list_changed received but cacheWrite not wired; ignoring")
            return
        }
        val p = params as? JsonObject
        val serverId = (p?.get("serverId") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "default"
        // Day 4: invalidate ALL entries for this server (broader invalidation).
        // Day 5+ will narrow to specific tools based on params.tools[].
        val count = cacheWrite.invalidateByMethod(serverId = serverId, method = "*")
        log.info("notifications/tools/list_changed: invalidated {} entries for server={}", count, serverId)
    }

    /**
     * Handle notifications/tools/invalidate — invalidate specific tool entries.
     *
     * Per design.md §6.1: server sends this notification when a tool's
     * result becomes invalid (e.g., notebook was edited externally).
     */
    private fun handleToolsInvalidate(params: JsonElement?) {
        if (cacheWrite == null) {
            log.warn("notifications/tools/invalidate received but cacheWrite not wired; ignoring")
            return
        }
        val p = params as? JsonObject
        val toolName = (p?.get("toolName") as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (toolName == null) {
            log.warn("notifications/tools/invalidate missing 'toolName' param: {}", p)
            return
        }
        val count = cacheWrite.invalidateByTool(toolName)
        log.info("notifications/tools/invalidate: invalidated {} entries for tool={}", count, toolName)
    }

    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val result = buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("serverInfo", buildJsonObject {
                put("name", serverInfo.name)
                put("version", serverInfo.version)
            })
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {
                    put("listChanged", false)  // Day 3: static tools/list
                })
                put("resources", buildJsonObject {
                    put("subscribe", false)
                    put("listChanged", false)
                })
                put("prompts", buildJsonObject {
                    put("listChanged", false)
                })
                put("logging", buildJsonObject {})
            })
            put("instructions", "mcp-cache-gateway — two-tier (Redis+PG) cache wrapper")
        }
        return JsonRpcResponse.success(request.id, result)
    }

    private fun handlePing(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse.success(request.id, buildJsonObject {})
    }

    /**
     * tools/list — Day 3.1 stub returns empty list.
     *
     * Day 4+ will aggregate tools from all registered servers (lazy-spawn
     * to query tools/list, then cache tool metadata in tool config).
     */
    private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val toolList = if (wrongNotebookBridge != null) {
            // Day 5: aggregate tools from wrongnotebook bridge
            wrongNotebookBridge.listTools().map { tool ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                }
            }
        } else {
            emptyList()
        }
        val result = buildJsonObject {
            put("tools", JsonArray(toolList))
        }
        return JsonRpcResponse.success(request.id, result)
    }

    /**
     * Override tools/call routing for wrongnotebook bridge tools.
     *
     * Day 5 design: instead of routing through GatewayOrchestrator (which uses
     * ServerHandle to spawn a subprocess), we directly invoke the bridge for
     * wrongnotebook.* tools. The orchestrator still wraps with cache logic.
     */
    private suspend fun handleWrongNotebookToolCall(request: JsonRpcRequest): JsonRpcResponse? {
        val bridge = wrongNotebookBridge ?: return null
        val params = request.params as? JsonObject ?: return null
        val toolName = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: return null
        if (!toolName.startsWith("wrongnotebook.")) return null
        val arguments = (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        val toolDef = bridge.toolDefinition(toolName) ?: return JsonRpcResponse.failure(
            request.id,
            JsonRpcError(
                code = JsonRpcResponse.ERR_METHOD_NOT_FOUND,
                message = "Unknown wrongnotebook tool: $toolName"
            )
        )
        // For now: route through orchestrator (cache lookup → if miss, call bridge)
        return orchestrator.handleWrongNotebookCall(request, toolName, arguments, toolDef.cacheable)
    }

    data class ServerInfo(
        val name: String,
        val version: String
    )

    companion object {
        fun defaultServerInfo() = ServerInfo(
            name = "mcp-cache-gateway",
            version = "0.3.0"  // Day 3.1 release
        )
    }
}