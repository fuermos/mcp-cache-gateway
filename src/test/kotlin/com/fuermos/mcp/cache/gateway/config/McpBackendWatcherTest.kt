package com.fuermos.mcp.cache.gateway.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for McpBackendWatcher (LISTEN/NOTIFY).
 *
 * Coverage:
 *   - start/stop lifecycle
 *   - pollOnce receives notification after UPDATE
 *   - callback fires on notification
 *   - snapshot tracks counts
 *   - close() is idempotent
 */
@EnabledIfEnvironmentVariable(named = "PG_INTEGRATION", matches = "1")
class McpBackendWatcherTest {

    private val jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/mcp_cache"
    private val username = "mcp_cache"
    private val password = System.getenv("POSTGRES_PASSWORD")
        ?: java.io.File("/home/fuermos/.openclaw/state/mcp-cache-gateway-pg.env")
            .readText().trim()
    private lateinit var watcher: McpBackendWatcher
    private val callbackCount = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        callbackCount.set(0)
        watcher = McpBackendWatcher(
            jdbcUrl = jdbcUrl,
            username = username,
            password = password,
            channel = "mcp_backend_changed",
            pollIntervalMs = 200,
            onNotification = { callbackCount.incrementAndGet() }
        )
    }

    @AfterEach
    fun tearDown() {
        if (::watcher.isInitialized) {
            watcher.close()
        }
    }

    @Test
    fun `start and close lifecycle`() {
        watcher.start()
        val stats = watcher.snapshot()
        assertTrue(stats.running)
        assertEquals("mcp_backend_changed", stats.channel)
        watcher.close()
        val after = watcher.snapshot()
        assertEquals(false, after.running, "should not be running after close")
    }

    @Test
    fun `pollOnce receives notification after UPDATE`() {
        // Trigger NOTIFY by UPDATE
        java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE mcp_backend SET enabled = enabled WHERE name = 'wrongnotebook'")
            }
        }
        // Poll — should receive notification
        val count = watcher.pollOnce()
        assertTrue(count >= 1, "should receive at least 1 notification, got $count")
        val stats = watcher.snapshot()
        assertTrue(stats.notificationsReceived >= 1)
        assertTrue(stats.lastNotificationAt > 0)
    }

    @Test
    fun `callback fires on notification`() {
        // Trigger NOTIFY
        java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE mcp_backend SET enabled = enabled WHERE name = 'wrongnotebook'")
            }
        }
        watcher.pollOnce()
        assertEquals(1, callbackCount.get(), "callback should fire once per notification")
    }

    @Test
    fun `close is idempotent`() {
        watcher.close()
        // Second close should not throw
        watcher.close()
    }

    @Test
    fun `multiple UPDATEs produce multiple notifications`() {
        java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
            conn.createStatement().use { stmt ->
                repeat(3) {
                    stmt.execute("UPDATE mcp_backend SET enabled = enabled WHERE name = 'wrongnotebook'")
                }
            }
        }
        // First poll gets all (drained)
        val count = watcher.pollOnce()
        assertTrue(count >= 3, "should receive at least 3 notifications, got $count")
        assertEquals(count, callbackCount.get())
    }

    @Test
    fun `snapshot starts with zero notifications`() {
        val stats = watcher.snapshot()
        assertEquals(0, stats.notificationsReceived)
        assertEquals(0, stats.lastNotificationAt)
    }
}