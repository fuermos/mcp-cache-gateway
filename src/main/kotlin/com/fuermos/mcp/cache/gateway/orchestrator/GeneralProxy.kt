package com.fuermos.mcp.cache.gateway.orchestrator

import com.fuermos.mcp.cache.gateway.cache.CacheLookup
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.config.BackendConfig
import com.fuermos.mcp.cache.gateway.config.BackendsRegistry
import com.fuermos.mcp.cache.gateway.server.ServerHandle
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager
import com.fuermos.mcp.cache.gateway.transport.JsonRpcError
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import com.fuermos.mcp.cache.gateway.transport.McpStdioClient
import com.fuermos.mcp.cache.gateway.utils.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * GeneralProxy — Y 架构 orchestrator (Phase 2.5.2).
 *
 * Real subprocess integration:
 *   - aggregateTools() spawns each enabled backend + calls tools/list via McpStdioClient
 *   - routeCall() parses 'backend.tool' format, spawns backend, forwards tools/call
 *   - Cache lookup pipeline reused (Day 2.2 CacheLookup)
 *   - ServerLifecycleManager reused (Day 1.2 — subprocess spawn + lifecycle)
 *
 * Design (per 主人 17:31 acceptance + 智多星 spec §4 2.5.2):
 *   - Tool name format: '{backend.name}.{tool.name}' (e.g. 'wrongnotebook.list_notebooks')
 *   - Real subprocess spawn — NO hardcoded tools
 *   - One backend fail doesn't break aggregateTools (graceful degradation)
 *   - Tool listing cache (30s) to avoid re-spawn per request
 */
class GeneralProxy(
    private val backendsRegistry: BackendsRegistry,
    private val lookup: CacheLookup,
    private val write: CacheWrite,
    private val serverManager: ServerLifecycleManager,
    private val toolListCacheTtlMs: Long = 30_000L
) {

    private val log = LoggerFactory.getLogger(GeneralProxy::class.java)

    private val aggregateCalls = AtomicLong(0)
    private val routeCalls = AtomicLong(0)
    private val toolListCache = mutableMapOf<String, ToolListCacheEntry>()
    private val toolListCacheTtlNanos = toolListCacheTtlMs * 1_000_000L

    private data class ToolListCacheEntry(
        val tools: List<AggregateTool>,
        val refreshedAtNanos: Long,
        // True iff this backend already returns tool names prefixed with its own backend id
        // (e.g. tubi-mcp/wrongnotebook-mcp-bridge). When true, routeCall() forwards the full
        // tool name (with prefix) to the subprocess; otherwise it strips the prefix per the
        // Phase 2.5 contract (raw tool name expected by the subprocess).
        val forwardPrefixed: Boolean = false
    )

    /**
     * Aggregate tools from all enabled backends.
     *
     * Spawns each backend subprocess + calls tools/list via McpStdioClient.
     * Aggregates results. Graceful degradation: one backend fail → others still work.
     */
    suspend fun aggregateTools(): List<AggregateTool> = coroutineScope {
        val backends = backendsRegistry.cachedBackends()
        aggregateCalls.incrementAndGet()
        log.info("aggregating tools from {} enabled backend(s)", backends.size)

        backends.map { backend ->
            async(Dispatchers.IO) {
                try {
                    aggregateFromBackend(backend)
                } catch (e: Exception) {
                    log.error("backend '{}' tools/list failed: {}", backend.name, e.message)
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    /**
     * Get tools from a single backend (with 30s cache).
     */
    private suspend fun aggregateFromBackend(backend: BackendConfig): List<AggregateTool> {
        val now = System.nanoTime()
        val cached = toolListCache[backend.name]
        if (cached != null && (now - cached.refreshedAtNanos) < toolListCacheTtlNanos) {
            log.debug("tool list cache HIT for backend '{}'", backend.name)
            return cached.tools
        }

        val handle = serverManager.acquire(backend.name)
        val client = McpStdioClient.wrap(backend, handle)
        try {
            val rawTools = client.listTools()
            val tools = rawTools.mapNotNull { toolJson ->
                val rawName = (toolJson["name"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                // Avoid double-prefixing: some backends (e.g. tubi-mcp/wrongnotebook-mcp-bridge)
                // already return names prefixed with their backend id (e.g. "wrongnotebook.list_notebooks").
                // In that case, use the raw name as-is. Otherwise prepend the backend name.
                val name = if (rawName.startsWith("${backend.name}.")) rawName else "${backend.name}.$rawName"
                val description = (toolJson["description"] as? JsonPrimitive)?.contentOrNull ?: ""
                AggregateTool(
                    name = name,
                    backend = backend.name,
                    description = description
                )
            }
            // Detect "already prefixed" backends (e.g. tubi-mcp/wrongnotebook-mcp-bridge) so that
            // routeCall() forwards the full prefixed tool name to the subprocess. Otherwise
            // (Phase 2.5 echo backends), routeCall strips the backend prefix per the original
            // contract (subprocess receives the raw tool name like "echo_tool").
            //
            // Heuristic: if any tool name from the backend starts with "backendName." AND that
            // prefix wasn't added by us this same call (the original raw name already had it),
            // treat the backend as prefixed.
            val forwardPrefixed = rawTools.any { raw ->
                val rawName = (raw["name"] as? JsonPrimitive)?.contentOrNull ?: return@any false
                rawName.startsWith("${backend.name}.")
            }
            toolListCache[backend.name] = ToolListCacheEntry(tools, now, forwardPrefixed = forwardPrefixed)
            log.info("backend '{}' tools/list: {} tools", backend.name, tools.size)
            return tools
        } finally {
            // McpStdioClient (wrap mode) doesn't own process
        }
    }

    /**
     * Route a tools/call to the appropriate backend.
     */
    suspend fun routeCall(request: JsonRpcRequest): JsonRpcResponse {
        routeCalls.incrementAndGet()

        val toolName = extractToolName(request)
            ?: return JsonRpcResponse.failure(
                request.id,
                JsonRpcError(
                    code = JsonRpcResponse.ERR_INVALID_PARAMS,
                    message = "tools/call missing 'name' parameter"
                )
            )

        val parts = toolName.split(".", limit = 2)
        var backendName: String
        var actualToolName: String
        if (parts.size == 2) {
            backendName = parts[0]
            actualToolName = parts[1]
        } else {
            // Fallback: tool name without 'backend.' prefix (e.g. legacy clients or DB tools that
            // stored raw names like 'wrongnotebook_list_notebooks' instead of 'wrongnotebook.list_notebooks').
            // If exactly one backend is registered, treat the whole toolName as the tool name for that backend.
            val backends = backendsRegistry.cachedBackends()
            backendName = if (backends.size == 1) {
                log.debug("tool name '{}' has no backend prefix; using only registered backend '{}'", toolName, backends[0].name)
                backends[0].name
            } else {
                return JsonRpcResponse.failure(
                    request.id,
                    JsonRpcError(
                        code = JsonRpcResponse.ERR_INVALID_PARAMS,
                        message = "tool name must be in 'backend.tool' format (or no prefix when exactly one backend is registered): $toolName"
                    )
                )
            }
            actualToolName = toolName
        }

        // Cache lookup by request_id (idempotency)
        val cached = lookup.lookupByRequestId(request.id)
        if (cached != null && !cached.isExpired(System.currentTimeMillis())) {
            log.debug("cache HIT (request_id={})", request.id)
            return JsonRpcResponse.success(
                request.id,
                cached.resultJson ?: kotlinx.serialization.json.JsonNull
            )
        }

        // Cache miss → spawn backend + forward
        val backend = backendsRegistry.cachedBackends().firstOrNull { it.name == backendName }
            ?: return JsonRpcResponse.failure(
                request.id,
                JsonRpcError(
                    code = JsonRpcResponse.ERR_METHOD_NOT_FOUND,
                    message = "Unknown backend: $backendName"
                )
            )

        val handle = serverManager.acquire(backendName)
        try {
            val arguments = (request.params as? JsonObject)?.get("arguments") as? JsonObject
                ?: JsonObject(emptyMap())
            // Two forwarding formats are supported:
            //   - Phase 2.5 echo backends: receive the RAW tool name (e.g. "echo_tool")
            //   - tubi-mcp/wrongnotebook-mcp-bridge (and similar): receive the FULL
            //     prefixed name (e.g. "wrongnotebook.list_notebooks") because the backend
            //     itself owns tool name namespacing.
            //
            // We detect the backend's format from the tools/list cache (populated by an
            // earlier aggregateTools() call). Clients MUST call /mcp/tools/list at least
            // once before /mcp/tools/call so the cache is populated; otherwise we fall
            // back to the Phase 2.5 default (raw name) and bridged backends will return
            // "Unknown tool" — clients should retry with the tool name format reported
            // by tools/list.
            val cachedEntry = toolListCache[backendName]
            val baseNameToForward = if (cachedEntry?.forwardPrefixed == true) toolName else actualToolName
            val forwardedRequest = JsonRpcRequest(
                id = request.id,
                method = "tools/call",
                params = JsonObject(mapOf(
                    "name" to JsonPrimitive(baseNameToForward),
                    "arguments" to arguments
                ))
            )
            val response = handle.execute(forwardedRequest)

            if (response.isSuccess) {
                val paramsHash = Hashing.sha256(arguments)
                write.writeCacheEntry(
                    requestId = request.id,
                    serverId = backendName,
                    method = request.method,
                    toolName = toolName,
                    paramsHash = paramsHash,
                    paramsJson = arguments,
                    resultJson = response.result
                )
            }
            return response
        } finally {
            // ServerLifecycleManager handles cleanup on idle timeout
        }
    }

    private fun extractToolName(request: JsonRpcRequest): String? {
        val params = request.params as? JsonObject ?: return null
        val nameEl = params["name"] as? JsonPrimitive ?: return null
        return nameEl.contentOrNull
    }

    /**
     * Snapshot for observability.
     */
    fun snapshot(): ProxyStats = ProxyStats(
        aggregateCalls = aggregateCalls.get(),
        routeCalls = routeCalls.get(),
        toolListCacheSize = toolListCache.size
    )

    data class ProxyStats(
        val aggregateCalls: Long,
        val routeCalls: Long,
        val toolListCacheSize: Int
    )
}

/**
 * AggregateTool — tool + its backend (for tools/list aggregation).
 */
data class AggregateTool(
    val name: String,
    val backend: String,
    val description: String
)