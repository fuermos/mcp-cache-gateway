package com.fuermos.mcp.cache.gateway.orchestrator

import com.fuermos.mcp.cache.gateway.cache.CacheEntry
import com.fuermos.mcp.cache.gateway.cache.CacheLookup
import com.fuermos.mcp.cache.gateway.cache.CacheTier
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.cache.NegativeCache
import com.fuermos.mcp.cache.gateway.cache.SwrManager
import com.fuermos.mcp.cache.gateway.bridge.wrongnotebook.WrongNotebookBridge
import com.fuermos.mcp.cache.gateway.config.ToolConfig
import com.fuermos.mcp.cache.gateway.config.ToolConfigResolver
import com.fuermos.mcp.cache.gateway.server.ServerHandle
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager
import com.fuermos.mcp.cache.gateway.transport.JsonRpcError
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import com.fuermos.mcp.cache.gateway.utils.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * GatewayOrchestrator — main request handler for the MCP gateway.
 *
 * Day 4 design (spec §4 Day 4 morning+afternoon):
 *   - Coroutine-based (structured concurrency, SupervisorJob)
 *   - Step 1: cache lookup (Redis → PG fallback)
 *   - Step 2: SWR stale return + single-flight async refresh
 *   - Step 3: cache miss → forward to server (with per-request timeout)
 *   - Step 4: write back (sync Redis + async PG)
 *   - Negative cache on 5xx (NegativeCache policy: 5min default, 4xx never cached)
 *   - Invalidation triggers via write.invalidateByMethod / invalidateByToolVersion
 *   - Shutdown grace via coroutineContext.cancelChildren()
 *
 * Pattern references:
 *   - design.md §3.4 (two-tier cache + SWR)
 *   - design.md §4 (cache lookup pipeline)
 *   - design.md §5 (request lifecycle — SWR + negative cache paths)
 *   - design.md §6.1 (active invalidation strategies)
 *   - spec §4 Day 4 morning+afternoon
 */
class GatewayOrchestrator(
    private val lookup: CacheLookup,
    private val write: CacheWrite,
    private val servers: ServerLifecycleManager,
    private val configResolver: ToolConfigResolver,
    private val swrManager: SwrManager = SwrManager(),
    private val negativeCache: NegativeCache = NegativeCache(),
    private val executeTimeoutMs: Long = 30_000,
    private val negativeCacheTtlMs: Int = 300_000,  // 5 min default (overridden by NegativeCache)
    private val wrongNotebookBridge: WrongNotebookBridge? = null
) {

    private val log = LoggerFactory.getLogger(GatewayOrchestrator::class.java)

    private val stats = OrchestratorStats()

    /**
     * Main handle() entry — called by McpMethodRouter per request.
     *
     * Returns JsonRpcResponse with either:
     *   - cached result (with X-Cache header hint via metadata.resultSource)
     *   - forwarded result (with original server's content)
     *   - JSON-RPC error (-32603 internal error on timeout / -32001 server spawn fail)
     */
    suspend fun handle(request: JsonRpcRequest): JsonRpcResponse = coroutineScope {
        // Tool name extraction — null for non-tools/call methods
        val toolName = extractToolName(request)

        // Day 5: route wrongnotebook bridge tools directly (in-process bridge;
        // do not go through subprocess ServerLifecycleManager path)
        if (toolName != null && toolName.startsWith("wrongnotebook.") && wrongNotebookBridge != null) {
            val arguments = (request.params as? kotlinx.serialization.json.JsonObject)
                ?.get("arguments") as? kotlinx.serialization.json.JsonObject
                ?: kotlinx.serialization.json.JsonObject(emptyMap())
            val toolDef = wrongNotebookBridge.toolDefinition(toolName)
            val cacheable = toolDef?.cacheable ?: true
            return@coroutineScope handleWrongNotebookCall(request, toolName, arguments, cacheable)
        }

        // Per-tool config (defaults if tool name absent)
        val toolCfg = if (toolName != null) {
            configResolver.resolveEffectiveConfig(toolName)
        } else {
            // Non-tool requests (initialize, tools/list, ping, etc.) — bypass cache
            return@coroutineScope forwardToServer(request, source = "no-cache")
        }

        // Short-circuit: non-cacheable tools (cacheable=false or ttlMs=0)
        if (!toolCfg.cacheable || toolCfg.ttlMs <= 0) {
            return@coroutineScope forwardToServer(request, source = "non-cacheable")
        }

        // Step 1: cache lookup
        val paramsHash = computeParamsHash(request)
        val cached = lookupByBothKeys(request, paramsHash)

        if (cached != null && !cached.isExpired(System.currentTimeMillis())) {
            // FRESH HIT
            stats.freshHits.incrementAndGet()
            return@coroutineScope responseFromCached(request, cached, source = "FRESH")
        }

        // Step 2: SWR stale path (Day 4 added)
        if (cached != null && cached.isInSwrWindow(System.currentTimeMillis())) {
            swrManager.recordStaleHit()
            // Single-flight: only one refresh in flight per params_hash
            if (swrManager.tryAcquireRefresh(paramsHash)) {
                // Async refresh in background — don't block main flow
                this@coroutineScope.launch(Dispatchers.IO) {
                    try {
                        refreshEntry(request, toolCfg, paramsHash, toolName)
                    } catch (e: Exception) {
                        log.warn("async SWR refresh failed (request_id={}): {}", request.id, e.message)
                    } finally {
                        swrManager.releaseRefresh(paramsHash)
                    }
                }
            }
            stats.staleHits.incrementAndGet()
            return@coroutineScope responseFromCached(request, cached, source = "STALE")
        }

        // Step 3: cache miss → forward to server
        val result = forwardToServer(request, source = "MISS")

        // Step 4: write back (only if success — skip write on errors per NegativeCache policy)
        if (!result.isError && toolCfg.cacheable && toolCfg.ttlMs > 0) {
            val entry = buildCacheEntry(request, toolCfg, paramsHash, result)
            write.write(entry)
            stats.writes.incrementAndGet()
        } else if (result.isError && toolCfg.cacheable) {
            // Negative cache on errors — 5xx/timeout short TTL (NegativeCache policy)
            writeNegativeCache(request, toolCfg, paramsHash, result)
        }

        return@coroutineScope result
    }

    /**
     * Async refresh for SWR stale path (Day 4 added).
     *
     * Forwards the request to the server again to get fresh data, then
     * updates the cache. Failures are logged + deferred to next request.
     */
    private suspend fun refreshEntry(
        request: JsonRpcRequest,
        toolCfg: ToolConfig,
        paramsHash: String,
        toolName: String?
    ) {
        log.debug("SWR async refresh: request_id={}, tool={}", request.id, toolName)
        val result = forwardToServer(request, source = "SWR-REFRESH")
        if (!result.isError && toolCfg.cacheable && toolCfg.ttlMs > 0) {
            val entry = buildCacheEntry(request, toolCfg, paramsHash, result)
            write.write(entry)
            stats.swrRefreshes.incrementAndGet()
        }
    }

    /**
     * Lookup by request_id first, then params_hash.
     */
    private fun lookupByBothKeys(
        request: JsonRpcRequest,
        paramsHash: String
    ): CacheEntry? {
        // Step 1a: by request_id (idempotency)
        val byId = lookup.lookupByRequestId(request.id)
        if (byId != null) return byId

        // Step 1b: by params_hash (semantic)
        val toolName = extractToolName(request)
        return lookup.lookupByParams(
            serverId = extractServerId(request),
            method = request.method,
            toolName = toolName,
            toolVersion = null,
            paramsHash = paramsHash
        )
    }

    /**
     * Forward request to a server, with timeout + spawn handling.
     */
    private suspend fun forwardToServer(
        request: JsonRpcRequest,
        source: String
    ): JsonRpcResponse {
        stats.misses.incrementAndGet()
        log.debug("cache {} forwarding {} {} (source={})", source, request.method, request.id, source)

        val serverId = extractServerId(request)
        val handle: ServerHandle = try {
            // acquire() is synchronous (ProcessBuilder-based) — not suspend
            servers.acquire(serverId)
        } catch (e: Exception) {
            log.error("server spawn failed for {}: {}", serverId, e.message)
            stats.errors.incrementAndGet()
            return JsonRpcResponse.failure(
                request.id,
                JsonRpcError(
                    code = JsonRpcResponse.ERR_SERVER_ERROR_BASE,
                    message = "Failed to spawn server '$serverId': ${e.message}"
                )
            )
        }

        return withTimeoutOrNull(executeTimeoutMs) {
            withContext(Dispatchers.IO) {
                handle.execute(request)
            }
        } ?: run {
            log.warn("request timeout ({}ms) for server={}, request_id={}", executeTimeoutMs, serverId, request.id)
            stats.timeouts.incrementAndGet()
            JsonRpcResponse.failure(
                request.id,
                JsonRpcError(
                    code = JsonRpcResponse.ERR_INTERNAL_ERROR,
                    message = "Request timeout after ${executeTimeoutMs}ms"
                )
            )
        }
    }

    /**
     * Build cache entry from successful result.
     */
    private fun buildCacheEntry(
        request: JsonRpcRequest,
        toolCfg: ToolConfig,
        paramsHash: String,
        result: JsonRpcResponse
    ): CacheEntry {
        val now = System.currentTimeMillis()
        val ttlMs = toolCfg.ttlMs
        val (freshUntil, staleUntil) = CacheEntry.computeWindows(now, ttlMs, toolCfg.swrGraceMs)
        return CacheEntry(
            requestId = request.id,
            serverId = extractServerId(request),
            method = request.method,
            toolName = extractToolName(request),
            toolVersion = null,
            paramsHash = paramsHash,
            paramsJson = request.params ?: buildJsonObject {},
            resultJson = extractResultJson(result),
            resultSize = estimateSize(result),
            cacheTier = CacheTier.REDIS,
            ttlMs = ttlMs,
            createdAtMs = now,
            freshUntilMs = freshUntil,
            staleUntilMs = staleUntil
        )
    }

    /**
     * Negative cache for errors — short TTL.
     */
    private fun writeNegativeCache(
        request: JsonRpcRequest,
        toolCfg: ToolConfig,
        paramsHash: String,
        result: JsonRpcResponse
    ) {
        if (!toolCfg.cacheable) return
        // NegativeCache.shouldCache() returns TTL for cacheable errors (5xx/timeout),
        // null for 4xx (don't cache).
        val ttlMs = negativeCache.shouldCache(result) ?: return
        val entry = negativeCache.buildNegativeEntry(
            request = request,
            toolCfg = toolCfg,
            serverId = extractServerId(request),
            toolName = extractToolName(request),
            toolVersion = null,
            paramsHash = paramsHash,
            paramsJson = request.params ?: buildJsonObject {},
            response = result
        )
        write.write(entry)
        stats.negativeWrites.incrementAndGet()
    }

    /**
     * Convert cached entry to JSON-RPC response.
     */
    private fun responseFromCached(
        request: JsonRpcRequest,
        cached: CacheEntry,
        source: String
    ): JsonRpcResponse {
        return JsonRpcResponse.success(
            id = request.id,
            result = cached.resultJson ?: JsonNull
        )
    }

    /**
     * Handle a wrongnotebook bridge tool call (Day 5 added).
     *
     * Flow:
     *   1. If tool is non-cacheable → forward to bridge directly (no cache check)
     *   2. If tool is cacheable → lookup cache first
     *      - HIT: return cached
     *      - MISS: call bridge + write back
     *
     * @return JsonRpcResponse from bridge (success or error)
     */
    suspend fun handleWrongNotebookCall(
        request: JsonRpcRequest,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
        cacheable: Boolean
    ): JsonRpcResponse {
        val bridge = wrongNotebookBridge ?: return JsonRpcResponse.failure(
            request.id,
            JsonRpcError(
                code = JsonRpcResponse.ERR_INTERNAL_ERROR,
                message = "wrongnotebook bridge not configured"
            )
        )

        // Non-cacheable: forward directly (no cache write)
        if (!cacheable) {
            log.debug("wrongnotebook non-cacheable call: tool={}", toolName)
            val result = try {
                bridge.callTool(toolName, arguments)
            } catch (e: Exception) {
                log.warn("wrongnotebook call failed: tool={}, error={}", toolName, e.message)
                return JsonRpcResponse.failure(
                    request.id,
                    JsonRpcError(
                        code = JsonRpcResponse.ERR_INTERNAL_ERROR,
                        message = "wrongnotebook call failed: ${e.message}"
                    )
                )
            }
            return JsonRpcResponse.success(request.id, result)
        }

        // Cacheable: lookup then forward if miss
        val paramsHash = Hashing.sha256(arguments)
        val cached = lookup.lookupByParams(
            serverId = "wrongnotebook",
            method = "tools/call",
            toolName = toolName,
            toolVersion = "1.0.0",
            paramsHash = paramsHash
        )
        if (cached != null && !cached.isExpired(System.currentTimeMillis())) {
            stats.freshHits.incrementAndGet()
            return responseFromCached(request, cached, source = "FRESH")
        }

        // MISS → call bridge
        stats.misses.incrementAndGet()
        val result = try {
            bridge.callTool(toolName, arguments)
        } catch (e: Exception) {
            log.warn("wrongnotebook call failed: tool={}, error={}", toolName, e.message)
            return JsonRpcResponse.failure(
                request.id,
                JsonRpcError(
                    code = JsonRpcResponse.ERR_INTERNAL_ERROR,
                    message = "wrongnotebook call failed: ${e.message}"
                )
            )
        }

        // Write back (cacheable read)
        val toolCfg = configResolver.resolveEffectiveConfig(toolName)
        val now = System.currentTimeMillis()
        val (freshUntil, _) = CacheEntry.computeWindows(now, toolCfg.ttlMs, toolCfg.swrGraceMs)
        val entry = CacheEntry(
            requestId = request.id,
            serverId = "wrongnotebook",
            method = "tools/call",
            toolName = toolName,
            toolVersion = "1.0.0",
            paramsHash = paramsHash,
            paramsJson = arguments,
            resultJson = result,
            resultSize = 0,
            cacheTier = CacheTier.REDIS,
            ttlMs = toolCfg.ttlMs,
            createdAtMs = now,
            freshUntilMs = freshUntil,
            staleUntilMs = null
        )
        write.write(entry)
        stats.writes.incrementAndGet()

        return JsonRpcResponse.success(request.id, result)
    }

    /**
     * Extract tool name from tools/call request params.
     */
    private fun extractToolName(request: JsonRpcRequest): String? {
        if (request.method != "tools/call") return null
        val params = request.params as? JsonObject ?: return null
        return (params["name"] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * Extract server id from request (params._serverId or default).
     */
    private fun extractServerId(request: JsonRpcRequest): String {
        val params = request.params as? JsonObject
        val explicit = params?.get("_serverId") as? JsonPrimitive
        return explicit?.contentOrNull ?: DEFAULT_SERVER_ID
    }

    /**
     * Compute stable params hash for cache key.
     */
    private fun computeParamsHash(request: JsonRpcRequest): String {
        val params = request.params ?: JsonNull
        return Hashing.sha256(params)
    }

    /**
     * Extract result content as JsonElement for cache storage.
     */
    private fun extractResultJson(result: JsonRpcResponse): JsonElement? {
        return result.result
    }

    /**
     * Rough estimate of result JSON size in bytes.
     */
    private fun estimateSize(result: JsonRpcResponse): Int {
        return result.result?.toString()?.length ?: 0
    }

    fun snapshotStats(): StatsSnapshot = stats.snapshot()

    /**
     * Stats counters for observability.
     */
    private class OrchestratorStats {
        val freshHits = AtomicLong(0)
        val staleHits = AtomicLong(0)
        val misses = AtomicLong(0)
        val writes = AtomicLong(0)
        val negativeWrites = AtomicLong(0)
        val swrRefreshes = AtomicLong(0)
        val errors = AtomicLong(0)
        val timeouts = AtomicLong(0)

        fun snapshot(): StatsSnapshot = StatsSnapshot(
            freshHits = freshHits.get(),
            staleHits = staleHits.get(),
            misses = misses.get(),
            writes = writes.get(),
            negativeWrites = negativeWrites.get(),
            swrRefreshes = swrRefreshes.get(),
            errors = errors.get(),
            timeouts = timeouts.get()
        )
    }

    data class StatsSnapshot(
        val freshHits: Long,
        val staleHits: Long,
        val misses: Long,
        val writes: Long,
        val negativeWrites: Long,
        val swrRefreshes: Long,
        val errors: Long,
        val timeouts: Long
    )

    companion object {
        const val DEFAULT_SERVER_ID = "default"
    }
}