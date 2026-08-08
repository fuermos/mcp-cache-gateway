package com.fuermos.mcp.cache.gateway.server

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue


/**
 * Unit tests for ServerLifecycleManager — lazy spawn + cleanup tick + shutdown.
 *
 * Uses `cat` (POSIX) as a long-lived test subprocess (no MCP protocol).
 * Tests use short idleTimeoutMs / cleanupIntervalMs to keep tests fast.
 *
 * Coverage:
 *   - acquire() spawns on first call, returns existing on second
 *   - acquire() fails on unknown serverId
 *   - acquire() refuses after shutdown
 *   - release() marks IDLE
 *   - cleanup tick reaps idle handles
 *   - killServer() closes handle immediately
 *   - shutdown() closes all + idempotent
 */
class ServerLifecycleManagerTest {

    private lateinit var registry: ServerLifecycleManager.InMemoryServerRegistry
    private lateinit var mgr: ServerLifecycleManager

    @BeforeEach
    fun setUp() {
        registry = ServerLifecycleManager.InMemoryServerRegistry(mapOf(
            "cat-server" to ServerLifecycleManager.ServerConfig(
                serverId = "cat-server",
                cmd = "cat",
                args = listOf()
            ),
            "sleep-server" to ServerLifecycleManager.ServerConfig(
                serverId = "sleep-server",
                cmd = "sleep",
                args = listOf("60")  // sleep 60s, will be killed in cleanup
            )
        ))
    }

    @AfterEach
    fun tearDown() {
        if (::mgr.isInitialized) mgr.shutdown()
    }

    private fun makeMgr(
        idleTimeoutMs: Long = 100,
        spawnTimeoutMs: Long = 1_000,
        cleanupIntervalMs: Long = 50
    ): ServerLifecycleManager = ServerLifecycleManager(
        serverRegistry = registry,
        idleTimeoutMs = idleTimeoutMs,
        spawnTimeoutMs = spawnTimeoutMs,
        cleanupIntervalMs = cleanupIntervalMs
    )

    @Test
    fun `acquire spawns new process on first call`() {
        mgr = makeMgr()
        val handle = mgr.acquire("cat-server")
        assertNotNull(handle)
        assertEquals("cat-server", handle.serverId)
        assertTrue(handle.isAlive, "spawned process should be alive")
        assertEquals(1, handle.totalCallCount)
    }

    @Test
    fun `acquire returns existing handle on second call (no duplicate spawn)`() {
        mgr = makeMgr()
        val h1 = mgr.acquire("cat-server")
        val h2 = mgr.acquire("cat-server")
        assertEquals(h1, h2, "should return same handle, not spawn new")
        assertEquals(2, h2.totalCallCount, "touch() called on second acquire")
    }

    @Test
    fun `acquire fails for unknown serverId`() {
        mgr = makeMgr()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            mgr.acquire("no-such-server")
        }
        assertTrue(ex.message!!.contains("not registered"), "error: ${ex.message}")
    }

    @Test
    fun `acquire refuses after shutdown`() {
        mgr = makeMgr()
        mgr.acquire("cat-server")
        mgr.shutdown()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            mgr.acquire("cat-server")
        }
        assertTrue(ex.message!!.contains("shutting down"), "error: ${ex.message}")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `cleanup tick reaps idle handles after idleTimeoutMs`() {
        mgr = makeMgr(idleTimeoutMs = 100, cleanupIntervalMs = 50)
        val handle = mgr.acquire("sleep-server")
        assertTrue(handle.isAlive)
        mgr.release("sleep-server")  // mark IDLE, lastUsed stays at acquire time

        // Wait for cleanup tick to fire
        Thread.sleep(400)
        assertFalse(handle.isAlive, "process should be killed by cleanup tick")
        assertEquals(0, mgr.snapshot().size)
    }

    @Test
    fun `recent acquire prevents cleanup reap`() {
        mgr = makeMgr(idleTimeoutMs = 500, cleanupIntervalMs = 50)
        val h1 = mgr.acquire("sleep-server")
        Thread.sleep(100)
        // Second acquire within idle window — touch() updates lastUsed
        val h2 = mgr.acquire("sleep-server")
        assertEquals(h1, h2)
        Thread.sleep(200)  // not enough to exceed 500ms threshold
        assertTrue(h1.isAlive, "should not be reaped (recent touch)")
    }

    @Test
    fun `killServer closes handle and removes from pool`() {
        mgr = makeMgr()
        val handle = mgr.acquire("cat-server")
        assertTrue(handle.isAlive)
        mgr.killServer("cat-server")
        assertFalse(handle.isAlive)
        assertEquals(0, mgr.snapshot().size)
    }

    @Test
    fun `shutdown is idempotent`() {
        mgr = makeMgr()
        mgr.acquire("cat-server")
        mgr.shutdown()
        // Second call should not throw
        mgr.shutdown()
        assertTrue(true, "second shutdown did not throw")
    }

    @Test
    fun `shutdown closes all handles`() {
        mgr = makeMgr()
        val h1 = mgr.acquire("cat-server")
        val h2 = mgr.acquire("sleep-server")
        mgr.shutdown()
        assertFalse(h1.isAlive)
        assertFalse(h2.isAlive)
        assertEquals(0, mgr.snapshot().size)
    }

    @Test
    fun `snapshot returns currently-spawned handles`() {
        mgr = makeMgr()
        assertTrue(mgr.snapshot().isEmpty())
        mgr.acquire("cat-server")
        mgr.acquire("sleep-server")
        val snap = mgr.snapshot()
        assertEquals(setOf("cat-server", "sleep-server"), snap.keys)
    }

    @Test
    fun `ServerRegistry InMemory implementation supports register and unregister`() {
        val reg = ServerLifecycleManager.InMemoryServerRegistry()
        assertTrue(reg.ids().isEmpty())
        reg.register(ServerLifecycleManager.ServerConfig("x", "echo", listOf()))
        assertEquals(setOf("x"), reg.ids())
        reg.unregister("x")
        assertTrue(reg.ids().isEmpty())
    }
}
