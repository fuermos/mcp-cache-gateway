package com.fuermos.mcp.cache.gateway.transport

import com.fasterxml.uuid.Generators
import java.util.UUID

/**
 * Request ID generation — UUID v7 (time-ordered).
 *
 * Rationale (see design.md §3.2 + §3.6):
 *   - UUID v7 embeds Unix timestamp in high 48 bits → time-sortable
 *   - 128 bits total → collision probability ≈ 0 (for our throughput)
 *   - No coordination needed (unlike auto-increment or sequence)
 *   - Lexicographic sort matches insertion order → great for DB index locality
 *   - 跨 session persist → client 重启后用同 id 仍能命中缓存
 *
 * Library: com.fasterxml.uuid:java-uuid-generator 5.0.0
 *   (already in build.gradle.kts)
 *
 * Note on `id` field type:
 *   - JSON-RPC 2.0 allows id to be string | number | null
 *   - We pin to String for cross-language compatibility (Java/JS/Go all handle
 *     string ids uniformly without int overflow concerns)
 */
object RequestIdFactory {
    /**
     * Generate a new UUID v7 as String (e.g. "0190a3b4-7c89-7abc-9def-1234567890ab").
     */
    fun generate(): RequestId =
        Generators.timeBasedEpochGenerator().generate().toString()

    /**
     * Validate a String looks like a UUID v7.
     *
     * Used by transport layer when receiving client-supplied ids — reject
     * malformed ids early before they hit the cache layer.
     *
     * Returns true if parseable as UUID AND version bits == 7.
     */
    fun isValidUuidV7(id: String): Boolean {
        return try {
            val uuid = UUID.fromString(id)
            uuid.version() == 7
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
