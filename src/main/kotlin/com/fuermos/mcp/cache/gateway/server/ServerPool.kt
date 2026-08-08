package com.fuermos.mcp.cache.gateway.server

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Pool of spawned MCP server processes, keyed by serverId.
 *
 * Pattern reference (借鉴 tubi-mcp/wigolo-bridge.js):
 *   - ServerPool is the registry: server_id → ServerHandle
 *   - ServerHandle wraps the underlying Process + state
 *   - Lifecycle states: Idle → Spawning → Active → Idle → Cleanup
 *
 * Thread safety:
 *   - Uses ConcurrentHashMap for the underlying registry
 *   - All public methods are thread-safe
 *   - State transitions inside ServerHandle are protected by synchronized
 *
 * Day 1.2 design choice:
 *   - Synchronous spawn (blocking) — first call waits for handshake
 *   - Cleanup is driven by ServerLifecycleManager's tick (not self-cleanup)
 *   - One process per serverId (no per-tool pool yet — that's Phase 2)
 */
class ServerPool {

    private val registry = ConcurrentHashMap<String, ServerHandle>()

    /**
     * Atomically register a newly-spawned handle. Returns false if another
     * concurrent caller already registered one with the same id (then the
     * caller should use the existing one and stop its own).
     */
    fun register(handle: ServerHandle): Boolean {
        val existing = registry.putIfAbsent(handle.serverId, handle)
        return existing == null
    }

    /**
     * Get current handle for serverId (or null if not spawned).
     */
    fun get(serverId: String): ServerHandle? = registry[serverId]

    /**
     * Check if serverId is currently registered.
     */
    fun contains(serverId: String): Boolean = registry.containsKey(serverId)

    /**
     * List all registered server IDs (snapshot).
     */
    fun ids(): Set<String> = registry.keys.toSet()

    /**
     * Snapshot of all handles (immutable view).
     */
    fun snapshot(): Map<String, ServerHandle> = registry.toMap()

    /**
     * Remove handle from registry (called when process exits or idle-cleanup
     * reaps the entry). Returns the removed handle or null if not present.
     */
    fun remove(serverId: String): ServerHandle? = registry.remove(serverId)

    /**
     * Current registry size.
     */
    fun size(): Int = registry.size

    /**
     * Find handles whose last-used timestamp is older than `idleThresholdMs`.
     * Used by cleanup tick in ServerLifecycleManager.
     */
    fun idleHandles(idleThresholdMs: Long): List<ServerHandle> {
        val now = Instant.now().toEpochMilli()
        return registry.values.filter { handle ->
            val lastUsed = handle.lastUsedAtMs.get()
            now - lastUsed >= idleThresholdMs
        }
    }
}
