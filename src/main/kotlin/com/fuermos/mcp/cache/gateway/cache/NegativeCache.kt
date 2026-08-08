package com.fuermos.mcp.cache.gateway.cache

import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Negative Cache — short-TTL storage for error responses.
 *
 * Day 4 design (per spec §4 Day 4 afternoon + design.md §5 negative cache policy):
 *   - 5xx errors / timeouts → short TTL (default 300_000 ms = 5 min, configurable per-tool)
 *   - 4xx errors → NOT cached (client errors should always retry freshly)
 *   - jsonrpc parse errors → short TTL (60s)
 *   - Marks entries with metadata.source = "NEGATIVE_CACHE" + error code
 *
 * Why cache 5xx:
 *   - Prevents retry storm — repeated calls within TTL return cached error
 *   - Lets backend recover without being hammered
 *   - Trade-off: client sees stale error for up to TTL ms
 *
 * Why NOT cache 4xx:
 *   - 4xx = client bug (bad params, etc.) — caching doesn't help
 *   - Different clients may have different param shapes
 *   - 4xx should always surface to client for debugging
 *
 * Pattern references:
 *   - design.md §5 (request lifecycle — negative cache on error)
 *   - design.md §3.4 (Tier 1 Redis write for negative entries)
 */
class NegativeCache(
    val error5xxTtlMs: Int = 300_000,       // 5 min default
    val timeoutTtlMs: Int = 60_000,         // 1 min default
    val parseErrorTtlMs: Int = 60_000       // 1 min default
) {

    /**
     * Decide if a response should be cached as negative.
     *
     * @return TtlMs if cacheable (5xx/timeout), null if not cacheable (success or 4xx)
     */
    fun shouldCache(response: JsonRpcResponse): Int? {
        if (response.isSuccess) return null
        val error = response.error ?: return null
        return when {
            // 5xx: -32000 to -32099 (server errors) + -32603 (internal error from timeout)
            error.code in -32099..-32000 -> error5xxTtlMs
            error.code == -32603 -> timeoutTtlMs  // timeout
            error.code == -32700 -> parseErrorTtlMs  // parse error
            // 4xx (-32600 invalid request, -32601 method not found, -32602 invalid params)
            // → not cached (client errors)
            else -> null
        }
    }

    /**
     * Build a negative cache entry from an error response.
     */
    fun buildNegativeEntry(
        request: com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest,
        toolCfg: com.fuermos.mcp.cache.gateway.config.ToolConfig,
        serverId: String,
        toolName: String?,
        toolVersion: String?,
        paramsHash: String,
        paramsJson: JsonElement,
        response: JsonRpcResponse,
        nowProvider: () -> Long = { System.currentTimeMillis() }
    ): CacheEntry {
        val now = nowProvider()
        val ttlMs = shouldCache(response) ?: error("response not negative-cacheable")
        val (freshUntil, _) = CacheEntry.computeWindows(now, ttlMs, null)
        val errorCode = response.error?.code ?: 0
        val errorMessage = response.error?.message ?: "unknown error"

        return CacheEntry(
            requestId = request.id,
            serverId = serverId,
            method = request.method,
            toolName = toolName,
            toolVersion = toolVersion,
            paramsHash = paramsHash,
            paramsJson = paramsJson,
            resultJson = null,  // no result on error
            resultSize = 0,
            cacheTier = CacheTier.REDIS,
            ttlMs = ttlMs,
            createdAtMs = now,
            freshUntilMs = freshUntil,
            staleUntilMs = null,  // no SWR on errors (return error fast or miss fast)
            invalidated = false,
            metadata = buildJsonObject {
                put("source", "NEGATIVE_CACHE")
                put("error_code", errorCode)
                put("error_message", errorMessage)
            }
        )
    }

    companion object {
        /**
         * Standard JSON-RPC error code ranges:
         *   -32700..-32000: reserved
         *   -32000..-32099: server-defined (5xx equivalent)
         *   -32603:         internal error (often timeout)
         *   -32602:         invalid params (4xx equivalent)
         *   -32601:         method not found (4xx equivalent)
         *   -32600:         invalid request (4xx equivalent)
         */
        const val SERVER_ERROR_MIN = -32099
        const val SERVER_ERROR_MAX = -32000
        const val INTERNAL_ERROR = -32603
        const val PARSE_ERROR = -32700
    }
}