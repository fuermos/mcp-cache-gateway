package com.fuermos.mcp.cache.gateway.config

import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * McpBackendWatcher — listens to PostgreSQL NOTIFY events and triggers reload.
 *
 * Phase 2.3 design (per spec §4 Phase 2 2.3):
 *   - LISTEN to channel 'mcp_backend_changed' (fired by V2 triggers)
 *   - On notification: call reload() on BackendsRegistry + ToolConfigResolver
 *   - Single dedicated connection (don't share with HikariCP pool)
 *   - Lifecycle: start()/stop() for graceful shutdown
 *
 * Pattern references:
 *   - 借鉴 PostgreSQL LISTEN/NOTIFY (https://www.postgresql.org/docs/current/sql-notify.html)
 *   - 借鉴 Java PGConnection.getNotifications(timeout) polling loop
 *   - 借鉴 examples/servers.yaml file fallback (deprecated, DB-driven only)
 *
 * Hot reload flow:
 *   1. Admin runs `UPDATE mcp_backend SET enabled = FALSE WHERE name = 'wrongnotebook';`
 *   2. V2 trigger fires `pg_notify('mcp_backend_changed', json)`
 *   3. Watcher polls connection, receives notification
 *   4. Watcher calls registry.reload() + resolver.reloadFromDatabase()
 *   5. New request uses updated config (no gateway restart needed)
 */
class McpBackendWatcher(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    private val channel: String = DEFAULT_CHANNEL,
    private val pollIntervalMs: Long = 1_000,
    private val onNotification: () -> Unit = {}
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(McpBackendWatcher::class.java)

    private val running = AtomicBoolean(false)
    private val connection: Connection = DriverManager.getConnection(jdbcUrl, username, password)
    private var pollingThread: Thread? = null
    private val notificationsReceived = AtomicLong(0)
    private val lastNotificationAt = AtomicLong(0)

    init {
        // Register LISTEN on the dedicated connection
        connection.createStatement().use { stmt ->
            stmt.execute("LISTEN $channel")
        }
        connection.autoCommit = true
    }

    /**
     * Start the watcher (background polling thread).
     *
     * Idempotent — second call is a no-op.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("McpBackendWatcher already running")
            return
        }
        log.info("starting McpBackendWatcher (channel='{}', poll={}ms)", channel, pollIntervalMs)
        val thread = Thread({
            while (running.get()) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    log.warn("polling error: {}", e.message)
                }
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            log.info("McpBackendWatcher polling thread exited")
        }, "mcp-backend-watcher").apply {
            isDaemon = true
            start()
        }
        pollingThread = thread
    }

    /**
     * Poll for notifications once. Returns count of notifications received.
     */
    fun pollOnce(): Int {
        val pgConn = connection.unwrap(PGConnection::class.java)
        val notifications = pgConn.getNotifications(100) ?: return 0
        var count = 0
        for (notif in notifications) {
            count++
            notificationsReceived.incrementAndGet()
            lastNotificationAt.set(System.currentTimeMillis())
            val payload = notif.parameter ?: "{}"
            log.info("received NOTIFY on channel='{}', payload={}", notif.name, payload)
            try {
                onNotification()
            } catch (e: Exception) {
                log.error("onNotification callback failed: {}", e.message)
            }
        }
        return count
    }

    /**
     * Stop the watcher.
     */
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        log.info("stopping McpBackendWatcher")
        pollingThread?.interrupt()
        pollingThread = null
        try {
            connection.createStatement().use { stmt ->
                stmt.execute("UNLISTEN $channel")
            }
        } catch (e: Exception) {
            log.warn("UNLISTEN failed: {}", e.message)
        }
        try {
            connection.close()
        } catch (e: Exception) {
            log.warn("connection close failed: {}", e.message)
        }
    }

    /**
     * Snapshot of watcher state (for observability).
     */
    fun snapshot(): WatcherStats = WatcherStats(
        running = running.get(),
        notificationsReceived = notificationsReceived.get(),
        lastNotificationAt = lastNotificationAt.get(),
        channel = channel
    )

    data class WatcherStats(
        val running: Boolean,
        val notificationsReceived: Long,
        val lastNotificationAt: Long,
        val channel: String
    )

    companion object {
        /**
         * Default NOTIFY channel — must match V2__add_mcp_backend_tables.sql trigger function.
         */
        const val DEFAULT_CHANNEL = "mcp_backend_changed"
    }
}
