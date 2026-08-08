package com.fuermos.mcp.cache.gateway.server

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for ServerPool — thread-safe handle registry.
 *
 * Uses a fake Process to avoid spawning real subprocesses in tests.
 * Coverage:
 *   - register / get / remove
 *   - putIfAbsent race semantics
 *   - contains / size / ids / snapshot
 *   - idleHandles() returns stale handles only
 */
class ServerPoolTest {

    private lateinit var pool: ServerPool
    private val fakeProcess: Process
        get() = object : Process() {
            private val out = ByteArrayOutputStream()
            override fun getOutputStream() = out
            override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
            override fun waitFor() = 0
            override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
            override fun exitValue() = 0
            override fun destroy() {}
            override fun destroyForcibly(): Process = this
            override fun isAlive() = true
            override fun pid(): Long = 12345L
        }

    @BeforeEach fun setUp() { pool = ServerPool() }
    @AfterEach fun tearDown() {}

    private fun fakeHandle(serverId: String, lastUsedMs: Long? = null): ServerHandle {
        val handle = ServerHandle(serverId, "echo", listOf("hi"), fakeProcess)
        if (lastUsedMs != null) {
            handle.lastUsedAtMs.set(lastUsedMs)
        }
        return handle
    }

    @Test
    fun `register adds handle and get returns it`() {
        val h = fakeHandle("s1")
        assertTrue(pool.register(h))
        assertEquals(h, pool.get("s1"))
        assertTrue(pool.contains("s1"))
    }

    @Test
    fun `register returns false if id already taken`() {
        val h1 = fakeHandle("s1")
        val h2 = fakeHandle("s1")
        assertTrue(pool.register(h1))
        assertFalse(pool.register(h2), "duplicate register should fail")
        // Original still there
        assertEquals(h1, pool.get("s1"))
    }

    @Test
    fun `get returns null for unknown id`() {
        assertNull(pool.get("missing"))
    }

    @Test
    fun `remove returns handle and clears registry`() {
        val h = fakeHandle("s1")
        pool.register(h)
        assertEquals(h, pool.remove("s1"))
        assertNull(pool.get("s1"))
        assertFalse(pool.contains("s1"))
        assertEquals(0, pool.size())
    }

    @Test
    fun `remove returns null for unknown id`() {
        assertNull(pool.remove("missing"))
    }

    @Test
    fun `size and ids and snapshot reflect current state`() {
        assertEquals(0, pool.size())
        assertTrue(pool.ids().isEmpty())

        val h1 = fakeHandle("s1")
        val h2 = fakeHandle("s2")
        pool.register(h1)
        pool.register(h2)
        assertEquals(2, pool.size())
        assertEquals(setOf("s1", "s2"), pool.ids())

        val snap = pool.snapshot()
        assertEquals(2, snap.size)
        assertEquals(h1, snap["s1"])
        assertEquals(h2, snap["s2"])
    }

    @Test
    fun `idleHandles returns handles older than threshold`() {
        val now = System.currentTimeMillis()
        val h1 = fakeHandle("old", lastUsedMs = now - 100_000)   // 100s old
        val h2 = fakeHandle("recent", lastUsedMs = now - 1_000)   // 1s old
        val h3 = fakeHandle("new", lastUsedMs = now)              // 0s old
        pool.register(h1); pool.register(h2); pool.register(h3)

        val idle = pool.idleHandles(idleThresholdMs = 60_000)
        assertEquals(1, idle.size)
        assertEquals("old", idle[0].serverId)
    }

    @Test
    fun `idleHandles returns empty when nothing is stale`() {
        val h = fakeHandle("recent", lastUsedMs = System.currentTimeMillis())
        pool.register(h)
        assertTrue(pool.idleHandles(idleThresholdMs = 60_000).isEmpty())
    }

    @Test
    fun `idleHandles returns empty when pool is empty`() {
        assertTrue(pool.idleHandles(60_000).isEmpty())
    }
}
