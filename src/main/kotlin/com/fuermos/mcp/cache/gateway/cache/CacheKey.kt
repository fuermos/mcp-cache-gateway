package com.fuermos.mcp.cache.gateway.cache

/**
 * Cache key strategies — Redis key naming convention.
 *
 * Pattern references:
 *   - design.md §3.6 (cache key algorithm)
 *   - design.md §4 (lookup order: request_id → params_hash fallback)
 *
 * Two key types:
 *   1. Request-id key (exact match):   mcp:req:{request_id}
 *      — used for retry/idempotency (same id always returns same result)
 *   2. Params hash key (semantic):     mcp:params:{server_id}:{method}:{tool}:{version}:{params_hash}
 *      — used for parameter matching across different request ids
 *
 * The "params_hash" component itself is sha256 of canonicalized params
 * (see utils/Hashing.kt).
 */
object CacheKey {

    private const val PREFIX_REQ = "mcp:req:"
    private const val PREFIX_PARAMS = "mcp:params:"
    private const val WILDCARD = "_"  // placeholder for null fields

    /**
     * Key for exact request_id lookup (idempotency by design).
     */
    fun forRequestId(requestId: String): String = "$PREFIX_REQ$requestId"

    /**
     * Key for parameter-matching lookup (semantic match across different ids).
     *
     * Null fields are replaced with `_` so the key shape stays consistent.
     */
    fun forParams(
        serverId: String,
        method: String,
        toolName: String?,
        toolVersion: String?,
        paramsHash: String
    ): String {
        val toolPart = toolName ?: WILDCARD
        val versionPart = toolVersion ?: WILDCARD
        return "$PREFIX_PARAMS$serverId:$method:$toolPart:$versionPart:$paramsHash"
    }

    /**
     * Extract requestId from a key (for reverse-lookup / debug).
     * Returns null if the key is not a request-id key.
     */
    fun extractRequestId(key: String): String? =
        key.removePrefix(PREFIX_REQ).takeIf { key.startsWith(PREFIX_REQ) && it.isNotEmpty() }

    /**
     * Extract params components from a key. Returns null if not a params key.
     */
    fun extractParams(key: String): ParamsKeyParts? {
        if (!key.startsWith(PREFIX_PARAMS)) return null
        val body = key.removePrefix(PREFIX_PARAMS)
        val parts = body.split(":")
        if (parts.size != 5) return null
        return ParamsKeyParts(
            serverId = parts[0],
            method = parts[1],
            toolName = parts[2].takeIf { it != WILDCARD },
            toolVersion = parts[3].takeIf { it != WILDCARD },
            paramsHash = parts[4]
        )
    }

    data class ParamsKeyParts(
        val serverId: String,
        val method: String,
        val toolName: String?,
        val toolVersion: String?,
        val paramsHash: String
    )
}
