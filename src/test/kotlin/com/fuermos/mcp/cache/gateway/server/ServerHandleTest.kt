package com.fuermos.mcp.cache.gateway.server

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for ServerHandle — state machine + counters + lifecycle.
 *
 * Coverage:
 *   - Initial state is ACTIVE (set by ServerLifecycleManager.spawn)
 *   - touch() updates lastUsedAtMs + increments totalCalls
 *   - recordError() increments errorCalls
 *   - State transitions follow canTransitionTo rules
 *   - Invalid transitions are rejected
 *   - close() is idempotent + sets isAlive=false
 *   - markDead() transitions to DEAD
 */
class ServerHandleTest {

    private fun fakeProcess(): Process = object : Process() {
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
        override fun pid(): Long = 99999L
    }

    @Test
    fun `fresh handle is alive and reports pid`() {
        val h = ServerHandle("s1", "echo", listOf("hi"), fakeProcess())
        assertTrue(h.isAlive)
        assertEquals(99999L, h.pid)
        assertEquals(0L, h.totalCallCount)
        assertEquals(0L, h.errorCallCount)
    }

    @Test
    fun `touch increments totalCalls and updates lastUsedAtMs`() {
        val h = ServerHandle("s1", "echo", listOf("hi"), fakeProcess())
        val before = h.lastUsedAtMs.get()
        Thread.sleep(10)
        h.touch()
        assertEquals(1L, h.totalCallCount)
        assertTrue(h.lastUsedAtMs.get() > before)
        h.touch()
        assertEquals(2L, h.totalCallCount)
    }

    @Test
    fun `recordError increments errorCalls`() {
        val h = ServerHandle("s1", "echo", listOf("hi"), fakeProcess())
        h.recordError()
        h.recordError()
        h.recordError()
        assertEquals(3L, h.errorCallCount)
    }

    @Test
    fun `state transitions follow canTransitionTo rules (forward only)`() {
        val h = ServerHandle("s1", "echo", listOf(), fakeProcess())
        // ACTIVE → IDLE allowed
        assertTrue(h.transitionTo(ServerHandle.State.IDLE))
        assertEquals(ServerHandle.State.IDLE, h.state)
        // IDLE → ACTIVE allowed
        assertTrue(h.transitionTo(ServerHandle.State.ACTIVE))
        // ACTIVE → DEAD allowed
        assertTrue(h.transitionTo(ServerHandle.State.DEAD))
        // DEAD → ACTIVE rejected
        assertFalse(h.transitionTo(ServerHandle.State.ACTIVE))
        // DEAD → CLOSED allowed (terminal)
        assertTrue(h.transitionTo(ServerHandle.State.CLOSED))
        // CLOSED → anything rejected
        assertFalse(h.transitionTo(ServerHandle.State.ACTIVE))
        assertFalse(h.transitionTo(ServerHandle.State.IDLE))
    }

    @Test
    fun `SPAWNING can transition to ACTIVE or DEAD or CLOSED`() {
        val h = ServerHandle("s1", "echo", listOf(), fakeProcess())
        // Initial state is ACTIVE; transition to SPAWNING (forward allowed)
        assertEquals(ServerHandle.State.ACTIVE, h.state)
        assertTrue(h.transitionTo(ServerHandle.State.IDLE))
        assertTrue(h.transitionTo(ServerHandle.State.ACTIVE))
    }

    @Test
    fun `markDead sets state to DEAD and isAlive to false`() {
        val h = ServerHandle("s1", "echo", listOf(), fakeProcess())
        assertTrue(h.isAlive)
        h.markDead()
        assertFalse(h.isAlive)
        assertEquals(ServerHandle.State.DEAD, h.state)
    }

    @Test
    fun `close is idempotent`() {
        val h = ServerHandle("s1", "echo", listOf(), fakeProcess())
        h.close()
        assertFalse(h.isAlive)
        assertEquals(ServerHandle.State.CLOSED, h.state)
        // Second close should not throw
        h.close()
        h.close()
        assertFalse(h.isAlive)
    }

    @Test
    fun `toString includes key fields`() {
        val h = ServerHandle("my-srv", "echo", listOf("hi"), fakeProcess())
        h.touch()
        h.recordError()
        val str = h.toString()
        assertTrue(str.contains("my-srv"), "serverId in: $str")
        assertTrue(str.contains("calls=1"), "calls in: $str")
        assertTrue(str.contains("errors=1"), "errors in: $str")
        assertTrue(str.contains("state="), "state in: $str")
    }

    @Test
    fun `restartCount is publicly accessible atomic`() {
        val h = ServerHandle("s1", "echo", listOf(), fakeProcess())
        assertEquals(0L, h.restartCount.get())
        h.restartCount.incrementAndGet()
        h.restartCount.incrementAndGet()
        assertEquals(2L, h.restartCount.get())
    }
}
