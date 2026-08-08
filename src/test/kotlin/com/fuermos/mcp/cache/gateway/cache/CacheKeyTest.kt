package com.fuermos.mcp.cache.gateway.cache

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for CacheKey naming convention + reverse extraction.
 *
 * Coverage:
 *   - forRequestId format + prefix consistency
 *   - forParams format + null field handling
 *   - extractRequestId round-trip
 *   - extractParams round-trip + null field decoding
 *   - Wildcard placeholder handling
 */
class CacheKeyTest {

    @Test
    fun `forRequestId produces prefixed key`() {
        val key = CacheKey.forRequestId("abc-123")
        assertEquals("mcp:req:abc-123", key)
    }

    @Test
    fun `forRequestId with UUID v7 id`() {
        val uuid = "0190a3b4-7c89-7abc-9def-1234567890ab"
        val key = CacheKey.forRequestId(uuid)
        assertEquals("mcp:req:$uuid", key)
    }

    @Test
    fun `forParams with all fields`() {
        val key = CacheKey.forParams(
            serverId = "wrongnotebook",
            method = "tools/call",
            toolName = "get_notebook",
            toolVersion = "1.0.0",
            paramsHash = "abcdef0123456789"
        )
        assertEquals(
            "mcp:params:wrongnotebook:tools/call:get_notebook:1.0.0:abcdef0123456789",
            key
        )
    }

    @Test
    fun `forParams with null toolName uses wildcard`() {
        val key = CacheKey.forParams(
            serverId = "svr",
            method = "tools/list",
            toolName = null,
            toolVersion = null,
            paramsHash = "xyz"
        )
        assertEquals("mcp:params:svr:tools/list:_:_:xyz", key)
    }

    @Test
    fun `extractRequestId round-trips`() {
        val requestId = "my-id-42"
        val key = CacheKey.forRequestId(requestId)
        assertEquals(requestId, CacheKey.extractRequestId(key))
    }

    @Test
    fun `extractRequestId returns null for non-request key`() {
        assertNull(CacheKey.extractRequestId("mcp:params:foo"))
        assertNull(CacheKey.extractRequestId("random:key"))
    }

    @Test
    fun `extractRequestId returns null for empty body`() {
        // Defensive: "mcp:req:" (no id) should not return ""
        assertNull(CacheKey.extractRequestId("mcp:req:"))
    }

    @Test
    fun `extractParams round-trips with all fields`() {
        val parts = CacheKey.ParamsKeyParts(
            serverId = "wrongnotebook",
            method = "tools/call",
            toolName = "get_notebook",
            toolVersion = "1.0.0",
            paramsHash = "abc123"
        )
        val key = CacheKey.forParams(
            parts.serverId, parts.method, parts.toolName, parts.toolVersion, parts.paramsHash
        )
        val extracted = CacheKey.extractParams(key)
        assertNotNull(extracted)
        val e1 = extracted
        assertEquals(parts, e1!!)
    }

    @Test
    fun `extractParams decodes wildcard as null`() {
        val key = "mcp:params:svr:tools/list:_:_:xyz"
        val extracted = CacheKey.extractParams(key)
        assertNotNull(extracted)
        val e1 = extracted
        assertNull(e1!!.toolName, "wildcard should decode to null")
        assertNull(extracted.toolVersion, "wildcard should decode to null")
        assertEquals("svr", extracted.serverId)
        assertEquals("xyz", extracted.paramsHash)
    }

    @Test
    fun `extractParams returns null for non-params key`() {
        assertNull(CacheKey.extractParams("mcp:req:abc"))
        assertNull(CacheKey.extractParams("garbage"))
    }

    @Test
    fun `extractParams returns null for malformed body`() {
        assertNull(CacheKey.extractParams("mcp:params:only-three:fields"))
        assertNull(CacheKey.extractParams("mcp:params:a:b:c:d:e:f"))  // 6 parts
    }

    @Test
    fun `keys are distinct for different request_ids`() {
        val k1 = CacheKey.forRequestId("id-1")
        val k2 = CacheKey.forRequestId("id-2")
        assertTrue(k1 != k2)
    }

    @Test
    fun `params keys are distinct for different tools`() {
        val k1 = CacheKey.forParams("svr", "tools/call", "toolA", "1.0.0", "hash")
        val k2 = CacheKey.forParams("svr", "tools/call", "toolB", "1.0.0", "hash")
        assertTrue(k1 != k2, "different toolName should produce different key")
    }

    @Test
    fun `params keys are distinct for different versions`() {
        val k1 = CacheKey.forParams("svr", "tools/call", "toolA", "1.0.0", "hash")
        val k2 = CacheKey.forParams("svr", "tools/call", "toolA", "2.0.0", "hash")
        assertTrue(k1 != k2, "tool version is part of key (auto-invalidate on upgrade)")
    }
}
