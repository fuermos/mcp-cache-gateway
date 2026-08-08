package com.fuermos.mcp.cache.gateway.server

import com.fuermos.mcp.cache.gateway.transport.JsonRpcError
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Handle to a spawned MCP server process.
 *
 * Wraps the underlying Java Process + state + counters.
 * One instance per server_id (lives in ServerPool until idle-cleanup or
 * process death).
 *
 * Pattern reference (借鉴 tubi-mcp/wigolo-bridge.js WigoloBridge._pending / _restarts):
 *   - atomic counters for totalCalls / errorCalls / restartCount
 *   - @Volatile alive flag + lastDeathAtMs
 *   - startWatchdogIfNeeded() daemon thread for periodic liveness check
 *   - exponential backoff restart (2/4/8/16/32s, max 5 retries)
 *
 * Day 1.2 simplified:
 *   - Synchronous spawn (Day 1.2 doesn't use coroutines yet)
 *   - No watchdog thread yet — that's Day 1.2 follow-up or Day 2 (after Redis)
 *   - Restart logic lives in ServerLifecycleManager, not here
 *
 * Day 3.1 added: execute(JsonRpcRequest) — synchronous JSON-RPC roundtrip
 *   - Writes serialized request to stdin (newline-terminated)
 *   - Reads one line from stdout, parses as JsonRpcResponse
 *   - Single-flight per handle (ReentrantLock — no concurrent execute() calls)
 *   - For higher concurrency, spawn multiple ServerHandle instances per serverId
 */
class ServerHandle(
    val serverId: String,
    val cmd: String,
    val args: List<String>,
    val process: Process
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(ServerHandle::class.java)

    private val json = Json { ignoreUnknownKeys = true }
    private val executeLock = java.util.concurrent.locks.ReentrantLock()

    /** Liveness flag — set to false when process dies or handle is closed. */
    private val aliveFlag = AtomicBoolean(true)

    /** Last time this handle was acquired (for idle cleanup). */
    val lastUsedAtMs: AtomicLong = AtomicLong(Instant.now().toEpochMilli())

    /** Total number of acquire() calls (lifecycle metric). */
    private val totalCalls = AtomicLong(0)

    /** Total number of call errors (lifecycle metric). */
    private val errorCalls = AtomicLong(0)

    /** Number of restart attempts (set by ServerLifecycleManager). */
    val restartCount: AtomicLong = AtomicLong(0)

    /** State of the handle — atomic for safe transitions from multiple threads. */
    private val stateRef = AtomicReference(State.ACTIVE)

    /** stdout pipe (raw bytes — transport layer wraps in BufferedReader). */
    val stdout: InputStream
        get() = process.inputStream

    /** stderr pipe (for logging). */
    val stderr: InputStream
        get() = process.errorStream

    /** stdin pipe (for sending JSON-RPC requests). */
    val stdin: OutputStream
        get() = process.outputStream

    /** Current process PID (null if not yet spawned). */
    val pid: Long?
        get() = runCatching { process.pid() }.getOrNull()

    /** Whether the process is currently alive. */
    val isAlive: Boolean
        get() = aliveFlag.get() && process.isAlive

    /** Current lifecycle state. */
    val state: State
        get() = stateRef.get()

    /** Public counter accessors (used by metrics/logging). */
    val totalCallCount: Long get() = totalCalls.get()
    val errorCallCount: Long get() = errorCalls.get()

    /**
     * Execute a JSON-RPC request synchronously against this server process.
     *
     * Protocol:
     *   - Write JSON-line + "\n" to stdin
     *   - Read one JSON-line from stdout (blocking, on Dispatchers.IO)
     *   - Return parsed JsonRpcResponse
     *
     * Single-flight: ReentrantLock prevents concurrent execute() on same handle.
     * For higher concurrency, spawn multiple ServerHandle instances per serverId.
     *
     * Errors:
     *   - Process dead → -32603 internal error + markDead()
     *   - Read timeout → caller wraps withTimeout (handled by GatewayOrchestrator)
     *   - Parse error → -32700 parse error
     */
    suspend fun execute(request: JsonRpcRequest): JsonRpcResponse = withContext(Dispatchers.IO) {
        if (!isAlive) {
            markDead()
            recordError()
            return@withContext JsonRpcResponse.failure(
                request.id,
                JsonRpcError(
                    code = JsonRpcResponse.ERR_INTERNAL_ERROR,
                    message = "server process is not alive (serverId=$serverId, pid=$pid)"
                )
            )
        }
        executeLock.lock()
        try {
            val serialized = json.encodeToString(JsonRpcRequest.serializer(), request)
            val writer = process.outputStream
            writer.write((serialized + "\n").toByteArray(Charsets.UTF_8))
            writer.flush()

            val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            val line = reader.readLine() ?: run {
                recordError()
                markDead()
                return@withContext JsonRpcResponse.failure(
                    request.id,
                    JsonRpcError(
                        code = JsonRpcResponse.ERR_INTERNAL_ERROR,
                        message = "server closed stdout unexpectedly (serverId=$serverId)"
                    )
                )
            }

            try {
                json.decodeFromString(JsonRpcResponse.serializer(), line)
            } catch (e: Exception) {
                recordError()
                JsonRpcResponse.failure(
                    request.id,
                    JsonRpcError(
                        code = JsonRpcResponse.ERR_INTERNAL_ERROR,
                        message = "server response parse failed: ${e.message}"
                    )
                )
            }
        } finally {
            executeLock.unlock()
        }
    }

    /**
     * Mark this handle as used (called by ServerLifecycleManager.acquire).
     * Updates lastUsedAtMs to prevent premature idle cleanup.
     */
    fun touch() {
        lastUsedAtMs.set(Instant.now().toEpochMilli())
        totalCalls.incrementAndGet()
    }

    /**
     * Mark a call error (for metrics + restart decisions).
     */
    fun recordError() {
        errorCalls.incrementAndGet()
    }

    /**
     * Transition state (only valid forward transitions allowed).
     * Returns true on success, false if invalid transition.
     */
    fun transitionTo(newState: State): Boolean {
        while (true) {
            val current = stateRef.get()
            if (!current.canTransitionTo(newState)) {
                return false
            }
            if (stateRef.compareAndSet(current, newState)) {
                log.debug("[{}] state: {} → {}", serverId, current, newState)
                return true
            }
        }
    }

    /**
     * Mark handle as dead (called when process exits or external kill).
     * Idempotent.
     */
    fun markDead() {
        aliveFlag.set(false)
        transitionTo(State.DEAD)
    }

    /**
     * Force-kill the process (SIGTERM then SIGKILL after grace period).
     */
    override fun close() {
        if (!aliveFlag.compareAndSet(true, false)) {
            return  // already closed
        }
        try {
            if (process.isAlive) {
                process.destroy()  // SIGTERM
                val exited = runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
                if (!exited && process.isAlive) {
                    process.destroyForcibly()  // SIGKILL
                }
            }
        } catch (e: Exception) {
            log.warn("[{}] close() error: {}", serverId, e.message)
        } finally {
            transitionTo(State.CLOSED)
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    enum class State {
        SPAWNING,  // Process started but not yet handshaked
        ACTIVE,    // Handshake complete, ready for tools/call
        IDLE,      // No recent calls; candidate for cleanup
        CLOSED,    // Handle closed (process killed)
        DEAD;      // Process died unexpectedly (crashed / OOM)

        fun canTransitionTo(target: State): Boolean = when (this) {
            SPAWNING -> target == ACTIVE || target == DEAD || target == CLOSED
            ACTIVE   -> target == IDLE || target == DEAD || target == CLOSED
            IDLE     -> target == ACTIVE || target == DEAD || target == CLOSED
            CLOSED   -> false  // terminal
            DEAD     -> target == CLOSED  // can be closed after death
        }
    }

    override fun toString(): String =
        "ServerHandle(serverId=$serverId, pid=$pid, state=$state, " +
        "alive=$isAlive, calls=$totalCallCount, errors=$errorCallCount, " +
        "restarts=${restartCount.get()})"
}