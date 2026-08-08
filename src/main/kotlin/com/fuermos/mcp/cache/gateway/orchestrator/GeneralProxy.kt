package com.fuermos.mcp.cache.gateway.orchestrator

import com.fuermos.mcp.cache.gateway.cache.CacheLookup
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.config.BackendConfig
import com.fuermos.mcp.cache.gateway.config.BackendsRegistry
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
        val refreshedAtNanos: Long
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
                val name = (toolJson["name"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val description = (toolJson["description"] as? JsonPrimitive)?.contentOrNull ?: ""
                AggregateTool(
                    name = "${backend.name}.$name",
                    backend = backend.name,
                    description = description
                )
            }
            toolListCache[backend.name] = ToolListCacheEntry(tools, now)
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
        if (parts.size != 2) {
            return JsonRpcResponse.failure(
                request.id,
                JsonRpcError(
                    code = JsonRpcResponse.ERR_INVALID_PARAMS,
                    message = "tool name must be in 'backend.tool' format: $toolName"
                )
            )
        }
        val backendName = parts[0]
        val actualToolName = parts[1]

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
            val forwardedRequest = JsonRpcRequest(
                id = request.id,
                method = "tools/call",
                params = JsonObject(mapOf(
                    "name" to JsonPrimitive(actualToolName),
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