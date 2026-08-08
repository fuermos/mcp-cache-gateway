package com.fuermos.mcp.cache.gateway.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for Hashing — sha256 + JSON canonicalization.
 *
 * Coverage:
 *   - sha256Bytes determinism
 *   - sha256String empty / unicode
 *   - canonicalize: sorted keys, drop fields, preserve structure
 *   - canonicalize: nested objects + arrays
 *   - sha256(canonical) same input → same output
 *   - canonicalize drops trace_id / request_id / _meta / _internal
 */
class HashingTest {

    @Test
    fun `sha256Bytes is deterministic`() {
        val a = Hashing.sha256Bytes("hello".toByteArray())
        val b = Hashing.sha256Bytes("hello".toByteArray())
        assertEquals(a, b)
        assertEquals(64, a.length, "sha256 hex is 64 chars")
    }

    @Test
    fun `sha256 of known empty string`() {
        // sha256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hashing.sha256Bytes(ByteArray(0))
        )
    }

    @Test
    fun `sha256 of hello`() {
        // sha256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Hashing.sha256String("hello")
        )
    }

    @Test
    fun `canonicalize sorts keys alphabetically`() {
        val obj = buildJsonObject {
            put("z", 1)
            put("a", 2)
            put("m", 3)
        }
        val canonical = Hashing.canonicalize(obj) as JsonObject
        assertEquals(listOf("a", "m", "z"), canonical.keys.toList())
    }

    @Test
    fun `canonicalize drops trace_id and request_id and _meta`() {
        val obj = buildJsonObject {
            put("city", "sf")
            put("trace_id", "should-be-dropped")
            put("request_id", "also-dropped")
            put("_meta", "internal")
        }
        val canonical = Hashing.canonicalize(obj) as JsonObject
        assertEquals(setOf("city"), canonical.keys)
    }

    @Test
    fun `canonicalize preserves non-drop fields`() {
        val obj = buildJsonObject {
            put("city", "sf")
            put("unit", "F")
            put("trace_id", "drop-me")
        }
        val canonical = Hashing.canonicalize(obj) as JsonObject
        assertTrue("city" in canonical)
        assertTrue("unit" in canonical)
        assertTrue("trace_id" !in canonical)
    }

    @Test
    fun `canonicalize recurses into nested objects`() {
        val nested = buildJsonObject {
            put("inner", buildJsonObject {
                put("z", 1)
                put("a", 2)
            })
        }
        val canonical = Hashing.canonicalize(nested) as JsonObject
        val innerCanonical = canonical["inner"] as JsonObject
        assertEquals(listOf("a", "z"), innerCanonical.keys.toList())
    }

    @Test
    fun `canonicalize preserves array order`() {
        val obj = buildJsonObject {
            put("items", buildJsonArray {
                add(JsonPrimitive("c"))
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            })
        }
        val canonical = Hashing.canonicalize(obj) as JsonObject
        val items = canonical["items"] as JsonArray
        assertEquals(3, items.size)
        assertEquals("c", items[0].toString().trim('"'))
        assertEquals("a", items[1].toString().trim('"'))
        assertEquals("b", items[2].toString().trim('"'))
    }

    @Test
    fun `sha256 of canonicalized json is order-independent`() {
        // Two objects with same fields in different order should hash same
        val a = buildJsonObject {
            put("city", "sf")
            put("unit", "F")
        }
        val b = buildJsonObject {
            put("unit", "F")
            put("city", "sf")
        }
        assertEquals(Hashing.sha256(a), Hashing.sha256(b))
    }

    @Test
    fun `sha256 of canonicalized json differs when fields differ`() {
        val a = buildJsonObject {
            put("city", "sf")
            put("unit", "F")
        }
        val b = buildJsonObject {
            put("city", "nyc")
            put("unit", "F")
        }
        assertNotEquals(Hashing.sha256(a), Hashing.sha256(b))
    }

    @Test
    fun `sha256 ignores trace_id for hash stability`() {
        // Same logical params, different trace_id → same hash (semantic match)
        val a = buildJsonObject {
            put("city", "sf")
            put("trace_id", "trace-A")
        }
        val b = buildJsonObject {
            put("city", "sf")
            put("trace_id", "trace-B")
        }
        assertEquals(Hashing.sha256(a), Hashing.sha256(b))
    }

    @Test
    fun `unicode in canonicalize preserved`() {
        val obj = buildJsonObject {
            put("city", "北京")
        }
        val canonical = Hashing.canonicalize(obj) as JsonObject
        assertEquals("北京", (canonical["city"] as JsonPrimitive).content)
    }
}
