package com.fuermos.mcp.cache.gateway.transport

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for RequestIdFactory (UUID v7 generation + validation).
 *
 * Coverage:
 *   - generate() returns String in UUID format
 *   - version bits == 7
 *   - consecutive generates are different (no collision)
 *   - isValidUuidV7 accepts valid v7 ids, rejects v4/non-UUID strings
 */
class RequestIdTest {

    @Test
    fun `generate returns UUID string in standard format`() {
        val id = RequestIdFactory.generate()
        // UUID format: 8-4-4-4-12 hex chars with hyphens
        assertTrue(
            id.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\$")),
            "expected UUID format, got: $id"
        )
    }

    @Test
    fun `generate returns UUID v7 (version bits == 7)`() {
        val id = RequestIdFactory.generate()
        assertTrue(RequestIdFactory.isValidUuidV7(id), "id should be v7: $id")
        // Third group starts with '7' for v7 UUIDs
        assertEquals('7', id[14], "third group first char should be '7' for v7")
    }

    @Test
    fun `consecutive generates produce distinct ids`() {
        val ids = (1..100).map { RequestIdFactory.generate() }.toSet()
        assertEquals(100, ids.size, "expected 100 unique ids, got ${ids.size}")
    }

    @Test
    fun `isValidUuidV7 accepts valid v7 id`() {
        val id = RequestIdFactory.generate()
        assertTrue(RequestIdFactory.isValidUuidV7(id))
    }

    @Test
    fun `isValidUuidV7 rejects v4 UUID (version bits != 7)`() {
        // v4 UUID: 4xxx in third group
        val v4Uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertFalse(RequestIdFactory.isValidUuidV7(v4Uuid), "v4 should be rejected")
    }

    @Test
    fun `isValidUuidV7 rejects malformed strings`() {
        assertFalse(RequestIdFactory.isValidUuidV7("not-a-uuid"))
        assertFalse(RequestIdFactory.isValidUuidV7(""))
        assertFalse(RequestIdFactory.isValidUuidV7("12345"))
        assertFalse(RequestIdFactory.isValidUuidV7("550e8400-e29b-41d4-a716"))  // truncated
    }

    @Test
    fun `generated ids are sortable by creation time (v7 property)`() {
        // v7 has timestamp in high bits → lexicographic order matches insertion
        val first = RequestIdFactory.generate()
        Thread.sleep(10)  // ensure timestamp difference
        val second = RequestIdFactory.generate()
        // String comparison should match time order
        assertTrue(first < second, "first=$first should be < second=$second (v7 time-ordering)")
        assertNotEquals(first, second)
    }
}
