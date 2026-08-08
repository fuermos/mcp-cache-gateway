package com.fuermos.mcp.cache.gateway.persistence

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Synchronous Redis client wrapper around Lettuce.
 *
 * Day 2.1 design:
 *   - Sync API (not reactive/coroutine) — gateway is single-threaded stdio,
 *     so async is overkill for Day 2; Day 2.2 may add reactive if needed
 *   - One shared connection (Lettuce threadsafe) — pool of connections would
 *     be wasted for our low-concurrency stdio use case
 *   - JSON-serialized CacheEntry stored as String value
 *
 * Lifecycle:
 *   - connect() opens the connection (idempotent — returns existing if connected)
 *   - disconnect() closes (idempotent)
 *   - isConnected() returns true if connection is open
 *
 * Pattern reference:
 *   - design.md §3.4 (Tier 1 = Redis hot cache)
 *   - design.md §6.2 (LRU eviction when memory pressure — Redis configured
 *     externally via maxmemory-policy allkeys-lru)
 */
class RedisClient(
    private val uri: String,
    private val connectTimeoutMs: Long = 1_000
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(RedisClient::class.java)

    private var lettuceClient: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    /**
     * Open connection (idempotent).
     * @throws IllegalStateException if connection fails
     */
    fun connect() {
        if (connection?.isOpen == true) return
        log.info("connecting to Redis at {}", sanitizeUri(uri))
        val redisUri = RedisURI.create(uri)
        redisUri.timeout = Duration.ofMillis(connectTimeoutMs)
        val client = RedisClient.create(redisUri)
        try {
            val conn = client.connect()
            this.lettuceClient = client
            this.connection = conn
            log.info("Redis connected ✓ (timeout={}ms)", connectTimeoutMs)
        } catch (e: Exception) {
            client.shutdown()
            throw IllegalStateException("Redis connect failed: ${e.message}", e)
        }
    }

    /**
     * Run a block with sync commands. Throws if not connected.
     */
    fun <T> sync(block: (RedisCommands<String, String>) -> T): T {
        val conn = connection ?: error("RedisClient not connected — call connect() first")
        return block(conn.sync())
    }

    fun isConnected(): Boolean = connection?.isOpen == true

    /**
     * Close connection + shutdown Lettuce client. Idempotent.
     */
    fun disconnect() {
        runCatching { connection?.close() }
        connection = null
        runCatching { lettuceClient?.shutdown() }
        lettuceClient = null
    }

    override fun close() = disconnect()

    /**
     * Strip credentials from URI for safe logging.
     * Example: redis://user:pass@host:port → redis://[redacted]@host:port
     */
    private fun sanitizeUri(uri: String): String {
        val atIdx = uri.indexOf('@')
        if (atIdx < 0) return uri
        val schemeEnd = uri.indexOf("://") + 3
        return uri.substring(0, schemeEnd) + "***@" + uri.substring(atIdx + 1)
    }

    companion object {
        /**
         * Default URI for local development.
         */
        const val DEFAULT_URI = "redis://127.0.0.1:6379"
    }
}
