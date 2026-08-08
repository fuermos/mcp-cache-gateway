package com.fuermos.mcp.cache.gateway.server

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages lazy spawning, lifecycle, and idle cleanup of MCP server processes.
 *
 * Pattern reference (借鉴 tubi-mcp/wigolo-bridge.js):
 *   - lazy spawn: first acquire() triggers ProcessBuilder.start()
 *   - idle cleanup tick: every N seconds, reap handles older than idleTimeout
 *   - crash recovery: exponential backoff restart (1s/2s/3s, maxRestarts=3)
 *   - _onCrash(err): reject pending calls + restart
 *
 * Day 1.2 design choices:
 *   - Synchronous (blocking) ProcessBuilder-based spawn — no coroutines yet
 *   - Cleanup runs on a single-thread ScheduledExecutorService (1 tick/sec)
 *   - Restart logic is cooperative: caller of acquire() retries on FAILURE
 *     (we don't auto-restart inside this class — keep it simple for Day 1.2;
 *     auto-restart with backoff is a Day 2 enhancement)
 *
 * Lifecycle (per serverId):
 *   - acquire(serverId)
 *       └─ pool.contains(id) ?
 *           ├─ Yes → return existing handle (touch)
 *           └─ No  → spawn(serverId) → register → return handle
 *   - cleanup tick (every cleanupIntervalMs)
 *       └─ for each idle handle (lastUsed > idleTimeoutMs) → close + remove
 *   - shutdown() → close all handles + stop executor
 */
class ServerLifecycleManager(
    private val serverRegistry: ServerRegistry,
    private val idleTimeoutMs: Long = 60_000,         // 60s
    private val spawnTimeoutMs: Long = 5_000,          // 5s
    private val cleanupIntervalMs: Long = 1_000,       // 1s tick
    private val maxRestarts: Int = 3
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(ServerLifecycleManager::class.java)

    private val pool = ServerPool()
    private val shuttingDown = AtomicBoolean(false)
    private val restartAttempts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    private val cleanupExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "server-lifecycle-cleanup").apply { isDaemon = true }
    }

    init {
        cleanupExecutor.scheduleAtFixedRate(
            { runCatching { cleanupTick() }.onFailure { log.warn("cleanup tick error", it) } },
            cleanupIntervalMs,
            cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * Acquire (or spawn) a server handle. Touches the last-used timestamp.
     *
     * @return ServerHandle on success
     * @throws IllegalStateException if shutting down or spawn fails
     */
    fun acquire(serverId: String): ServerHandle {
        check(!shuttingDown.get()) { "ServerLifecycleManager is shutting down" }

        // Fast path: already spawned
        pool.get(serverId)?.let { existing ->
            if (existing.isAlive) {
                existing.transitionTo(ServerHandle.State.ACTIVE)
                existing.touch()
                return existing
            }
            // Existing handle is dead (process crashed) → fall through to respawn
            pool.remove(serverId)
            log.info("[{}] existing handle was dead, respawning", serverId)
        }

        // Slow path: spawn fresh
        return spawn(serverId)
    }

    /**
     * Spawn a new server process and register it.
     */
    private fun spawn(serverId: String): ServerHandle {
        val cfg = serverRegistry.get(serverId)
            ?: throw IllegalStateException("server not registered: $serverId (known: ${serverRegistry.ids()})")

        log.info("[{}] spawning: {} {}", serverId, cfg.cmd, cfg.args.joinToString(" "))

        val pb = ProcessBuilder(cfg.cmd, *cfg.args.toTypedArray())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectInput(ProcessBuilder.Redirect.PIPE)

        // Optional env vars
        cfg.env?.forEach { (k, v) -> pb.environment()[k] = v }
        // Working dir defaults to current
        cfg.cwd?.let { pb.directory(java.io.File(it)) }

        val process: Process = try {
            pb.start()
        } catch (e: Exception) {
            log.error("[{}] spawn failed: {}", serverId, e.message)
            throw IllegalStateException("failed to spawn server $serverId: ${e.message}", e)
        }

        val handle = ServerHandle(
            serverId = serverId,
            cmd = cfg.cmd,
            args = cfg.args,
            process = process
        )
        handle.transitionTo(ServerHandle.State.SPAWNING)

        if (!pool.register(handle)) {
            // Lost race — another concurrent spawn succeeded. Use theirs, close ours.
            log.warn("[{}] lost spawn race, closing our process", serverId)
            handle.close()
            return pool.get(serverId) ?: error("pool lost handle after race")
        }

        // Reset restart attempts on successful spawn
        restartAttempts[serverId] = AtomicInteger(0)

        // TODO Day 1.2 follow-up: drain stderr in background thread + initial handshake
        // For Day 1.2, we trust the spawn + mark ACTIVE immediately. Real readiness
        // check (JSON-RPC initialize roundtrip) lands in Day 2.
        handle.transitionTo(ServerHandle.State.ACTIVE)
        handle.touch()
        return handle
    }

    /**
     * Release a handle (mark as idle, do not close immediately).
     * Cleanup tick will reap if no acquire() within idleTimeoutMs.
     */
    fun release(serverId: String) {
        pool.get(serverId)?.let { handle ->
            handle.transitionTo(ServerHandle.State.IDLE)
            // touch() not called — lastUsedAtMs stays old so cleanup reaps it
        }
    }

    /**
     * Force-stop a server (e.g., after error storm).
     */
    fun killServer(serverId: String) {
        pool.get(serverId)?.let { handle ->
            log.warn("[{}] force-killing (errors={}/calls={})",
                serverId, handle.errorCallCount, handle.totalCallCount)
            handle.close()
            pool.remove(serverId)
        }
    }

    /**
     * Periodic cleanup tick — reap handles whose lastUsedAtMs is older than
     * idleTimeoutMs. Called by cleanupExecutor every cleanupIntervalMs.
     */
    private fun cleanupTick() {
        val idle = pool.idleHandles(idleTimeoutMs)
        if (idle.isNotEmpty()) {
            log.debug("cleanup tick: reaping {} idle handle(s)", idle.size)
        }
        for (handle in idle) {
            log.info("[{}] reaping (idle > {}ms): calls={}, errors={}",
                handle.serverId, idleTimeoutMs, handle.totalCallCount, handle.errorCallCount)
            handle.close()
            pool.remove(handle.serverId)
        }
    }

    /**
     * Snapshot of currently-spawned handles (for metrics / debugging).
     */
    fun snapshot(): Map<String, ServerHandle> = pool.snapshot()

    /**
     * Shutdown — close all handles + stop executor.
     * Idempotent.
     */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        log.info("shutting down ServerLifecycleManager ({} handle(s) active)", pool.size())
        pool.snapshot().values.forEach { it.close() }
        pool.snapshot().keys.toList().forEach { pool.remove(it) }
        cleanupExecutor.shutdown()
        cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)
        log.info("shutdown complete")
    }

    override fun close() = shutdown()

    /**
     * Configuration for one MCP server (passed to ProcessBuilder).
     */
    data class ServerConfig(
        val serverId: String,
        val cmd: String,
        val args: List<String>,
        val cwd: String? = null,
        val env: Map<String, String>? = null
    )

    /**
     * Server registry — provides ServerConfig by serverId.
     *
     * Day 1.2 uses a simple in-memory Map-backed registry.
     * Day 2 will replace with config loaded from examples/servers.yaml.
     */
    interface ServerRegistry {
        fun get(serverId: String): ServerConfig?
        fun ids(): Set<String>
    }

    /**
     * In-memory implementation of ServerRegistry (for tests + Day 1.2).
     */
    class InMemoryServerRegistry(initial: Map<String, ServerConfig> = emptyMap()) : ServerRegistry {
        private val map = java.util.concurrent.ConcurrentHashMap<String, ServerConfig>(initial)

        override fun get(serverId: String): ServerConfig? = map[serverId]
        override fun ids(): Set<String> = map.keys.toSet()

        fun register(cfg: ServerConfig) { map[cfg.serverId] = cfg }
        fun unregister(serverId: String) { map.remove(serverId) }
    }
}
