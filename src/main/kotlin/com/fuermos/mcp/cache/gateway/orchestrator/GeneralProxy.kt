package com.fuermos.mcp.cache.gateway.orchestrator

import com.fuermos.mcp.cache.gateway.cache.CacheLookup
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.config.BackendConfig
import com.fuermos.mcp.cache.gateway.config.BackendsRegistry
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import com.fuermos.mcp.cache.gateway.utils.Hashing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * GeneralProxy — Y 架构 orchestrator (Phase 2.4).
 *
 * Aggregates tools from all enabled backends + routes tools/call by
 * `backend.name + tool.name`. Caches results in two-tier cache.
 *
 * Phase 2.4 design (per spec §4 Phase 2 2.4):
 *   - tools/list: aggregate all enabled backends' tools
 *   - tools/call: route by `backend.name + tool.name`
 *   - cache lookup pipeline (Redis → PG fallback)
 *   - subprocess spawn via ServerLifecycleManager (Day 1.2)
 *
 * Pattern references:
 *   - 借鉴 design.md §3.1 (架构 — Spring Boot manages backend lifecycle)
 *   - 借鉴 examples/servers.yaml (server registry, deprecated file)
 *   - 借鉴 mcp_backend_env (DB-driven env vars + secret_ref)
 *   - 借鉴 McpMethodRouter (JSON-RPC method dispatch)
 */
class GeneralProxy(
    private val backendsRegistry: BackendsRegistry,
    private val lookup: CacheLookup,
    private val write: CacheWrite,
    private val serverManager: ServerLifecycleManager
) {

    private val log = LoggerFactory.getLogger(GeneralProxy::class.java)

    /**
     * Aggregate tools from all enabled backends.
     *
     * Each backend contributes its tools to the aggregate list. Tools are
     * tagged with `backend.name` so tools/call can route correctly.
     */
    fun aggregateTools(): List<AggregateTool> {
        val backends = backendsRegistry.cachedBackends()
        val tools = mutableListOf<AggregateTool>()
        for (backend in backends) {
            // Phase 2 simplification: bridge contributes 5 tools (per wrongnotebook)
            // In production, this would query each backend's tools/list (via spawn)
            // For Phase 2 in-process bridge pattern, we hardcode wrongnotebook 5 tools
            val backendTools = knownToolsFor(backend)
            tools.addAll(backendTools)
        }
        return tools
    }

    /**
     * Route a tools/call request to the appropriate backend.
     *
     * @return JsonRpcResponse from the backend (or cached entry on hit)
     */
    suspend fun routeCall(request: JsonRpcRequest): JsonRpcResponse {
        val toolName = extractToolName(request)
            ?: return JsonRpcResponse.failure(request.id, JsonRpcError(
                code = JsonRpcResponse.ERR_INVALID_PARAMS,
                message = "tools/call request missing 'name' parameter"
            ))

        // Parse "backend.tool" format (e.g. "wrongnotebook.list_notebooks")
        val parts = toolName.split(".", limit = 2)
        if (parts.size != 2) {
            return JsonRpcResponse.failure(request.id, JsonRpcError(
                code = JsonRpcResponse.ERR_INVALID_PARAMS,
                message = "tool name must be in 'backend.tool' format: $toolName"
            ))
        }
        val backendName = parts[0]
        val actualToolName = parts[1]

        val backend = backendsRegistry.cachedBackends().firstOrNull { it.name == backendName }
            ?: return JsonRpcResponse.failure(request.id, JsonRpcError(
                code = JsonRpcResponse.ERR_METHOD_NOT_FOUND,
                message = "Unknown backend: $backendName (enabled: ${backendsRegistry.cachedBackends().map { it.name }})"
            ))

        // Cache lookup (request_id → params_hash)
        val paramsHash = computeParamsHash(request)
        val cached = lookup.lookupByRequestId(request.id)
        if (cached != null && !cached.isExpired(System.currentTimeMillis())) {
            log.debug("cache HIT (request_id={})", request.id)
            return JsonRpcResponse.success(request.id, cached.resultJson ?: kotlinx.serialization.json.JsonNull)
        }

        // Cache miss → forward to backend (subprocess via ServerLifecycleManager)
        val handle = serverManager.acquire(backendName)
        val result = handle.execute(request)
        // Write back (sync Redis + async PG) if successful
        if (result.isSuccess) {
            write.writeCacheEntry(request.id, backendName, request.method, toolName, paramsHash, request.params ?: JsonObject(emptyMap()), result.result)
        }
        return result
    }

    /**
     * Known tools for a backend (Phase 2.4 simplification).
     *
     * Real implementation would spawn backend subprocess + call tools/list.
     * For wrongnotebook in-process bridge, we hardcode the 5 Phase 1 tools.
     */
    private fun knownToolsFor(backend: BackendConfig): List<AggregateTool> {
        // TODO Day 2.5: when bridging to subprocess, call backend.tools/list
        // For now, return Phase 1 wrongnotebook 5 tools if backend.name == 'wrongnotebook'
        return when (backend.name) {
            "wrongnotebook" -> listOf(
                AggregateTool(name = "wrongnotebook.list_notebooks", backend = backend.name, description = "List all notebooks (R, cacheable=true, TTL=60s)"),
                AggregateTool(name = "wrongnotebook.get_notebook", backend = backend.name, description = "Fetch a single notebook by id (R, cacheable=true, TTL=60s)"),
                AggregateTool(name = "wrongnotebook.add_question", backend = backend.name, description = "Add question to notebook (W, cacheable=false)"),
                AggregateTool(name = "wrongnotebook.update_question", backend = backend.name, description = "Update existing question (W, cacheable=false)"),
                AggregateTool(name = "wrongnotebook.delete_question", backend = backend.name, description = "Delete question by id (W, cacheable=false)")
            )
            else -> emptyList()  // No known tools for unbridged backends
        }
    }

    /**
     * Extract tool name from tools/call request params.
     */
    private fun extractToolName(request: JsonRpcRequest): String? {
        val params = request.params as? JsonObject ?: return null
        val nameEl = params["name"] as? JsonPrimitive ?: return null
        return nameEl.contentOrNull
    }

    /**
     * Compute stable params hash (same as GatewayOrchestrator).
     */
    private fun computeParamsHash(request: JsonRpcRequest): String {
        val params = request.params ?: JsonObject(emptyMap())
        return Hashing.sha256(params)
    }
}

/**
 * AggregateTool — tool + its backend (for tools/list aggregation).
 */
data class AggregateTool(
    val name: String,
    val backend: String,
    val description: String
)

/**
 * Alias for JsonRpcError (avoid import cycle).
 */
typealias JsonRpcError = com.fuermos.mcp.cache.gateway.transport.JsonRpcError

// Import for buildJsonObject used in routeCall (avoid IDE red)
private val unused = buildJsonObject { put("k", "v") }
