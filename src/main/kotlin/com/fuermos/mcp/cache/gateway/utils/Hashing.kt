package com.fuermos.mcp.cache.gateway.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

/**
 * Hashing helpers — sha256 for cache keys + JSON normalization for params.
 *
 * Pattern references:
 *   - design.md §3.6 (Parameter Matching / Idempotency)
 *   - design.md §4.1 (lookup order: request_id → params_hash)
 *
 * Normalization rules (design.md §3.6):
 *   1. JSON canonicalize (sorted keys, no whitespace)
 *   2. Remove non-result fields: `trace_id`, `request_id`, `_meta`
 *   3. UTC timestamps to ISO 8601 string
 *
 * Day 2.1 implementation: implements (1) sorted-keys canonicalization + (2)
 * drop-list. (3) timestamp normalization is left to Day 2.2 (needs schema
 * awareness — what fields are timestamps? per-tool config).
 */
object Hashing {

    private val canonicalJson = Json {
        encodeDefaults = true
        // We want strict canonical output, not pretty
        prettyPrint = false
    }

    /**
     * Fields stripped from params before hashing — they don't affect the
     * result, only audit/metadata.
     */
    private val DROP_FIELDS = setOf("trace_id", "request_id", "_meta", "_internal")

    /**
     * Canonicalize a JsonElement:
     *   - sort all object keys alphabetically
     *   - drop DROP_FIELDS at top level
     *   - leave structure unchanged otherwise
     */
    fun canonicalize(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> {
                val sorted = buildJsonObject {
                    element.keys.sorted().forEach { key ->
                        if (key !in DROP_FIELDS) {
                            put(key, canonicalize(element[key] ?: JsonNull))
                        }
                    }
                }
                sorted
            }
            is JsonArray -> JsonArray(element.map { canonicalize(it) })
            is JsonPrimitive, JsonNull -> element
        }
    }

    /**
     * Compute sha256 of a JsonElement after canonicalization.
     *
     * Output: 64-char hex string.
     */
    fun sha256(element: JsonElement): String {
        val canonical = canonicalize(element)
        val bytes = canonicalJson.encodeToString(JsonElement.serializer(), canonical)
            .toByteArray(Charsets.UTF_8)
        return sha256Bytes(bytes)
    }

    /**
     * Compute sha256 of raw bytes.
     */
    fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Convenience: sha256 of UTF-8 string.
     */
    fun sha256String(s: String): String = sha256Bytes(s.toByteArray(Charsets.UTF_8))
}
