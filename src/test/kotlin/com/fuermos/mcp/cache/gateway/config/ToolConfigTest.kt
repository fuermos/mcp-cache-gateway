package com.fuermos.mcp.cache.gateway.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for ToolConfigLoader + per-tool TTL resolution.
 *
 * Coverage:
 *   - loadFromString minimal (no tools)
 *   - loadFromString full schema (5 tools + defaults)
 *   - resolveEffectiveConfig with per-tool entry
 *   - resolveEffectiveConfig falls back to defaults for unknown tool
 *   - Default values applied to missing per-tool fields
 *   - Per-tool overrides win over defaults
 */
class ToolConfigTest {

    private val loader = ToolConfigLoader()

    @Test
    fun `loadFromString empty yields empty root with defaults`() {
        val root = loader.loadFromString("")
        assertTrue(root.tools.isEmpty())
        // Default values match ToolConfigDefaults defaults
        assertEquals(86_400_000, root.defaults.ttlMs)
        assertTrue(root.defaults.cacheable)
    }

    @Test
    fun `loadFromString full wrongnotebook config (matches examples tools yaml)`() {
        val yaml = """
            tools:
              - name: get_notebook
                version: "1.0.0"
                ttlMs: 3600000
                cacheable: true
                timeSensitive: false
              - name: add_question
                ttlMs: 0
                cacheable: false
            defaults:
              ttlMs: 86400000
              cacheable: true
              timeSensitive: false
              maxParamSize: 10240
        """.trimIndent()
        val root = loader.loadFromString(yaml)
        assertEquals(2, root.tools.size)
        assertEquals("get_notebook", root.tools[0].name)
        assertEquals("1.0.0", root.tools[0].version)
        assertEquals(3_600_000, root.tools[0].ttlMs)
        assertEquals("add_question", root.tools[1].name)
        assertEquals(0, root.tools[1].ttlMs)
        assertFalse(root.tools[1].cacheable)
        assertEquals(10_240, root.defaults.maxParamSize)
    }

    @Test
    fun `resolveEffectiveConfig returns tool-specific config when present`() {
        val yaml = """
            tools:
              - name: get_notebook
                ttlMs: 3600000
                cacheable: true
            defaults:
              ttlMs: 86400000
        """.trimIndent()
        val root = loader.loadFromString(yaml)
        val cfg = loader.resolveEffectiveConfig(root, "get_notebook")
        assertEquals("get_notebook", cfg.name)
        assertEquals(3_600_000, cfg.ttlMs, "per-tool ttlMs should win")
    }

    @Test
    fun `resolveEffectiveConfig falls back to defaults for unknown tool`() {
        val yaml = """
            tools:
              - name: get_notebook
                ttlMs: 3600000
            defaults:
              ttlMs: 86400000
              cacheable: true
              timeSensitive: false
              maxParamSize: 10240
        """.trimIndent()
        val root = loader.loadFromString(yaml)
        val cfg = loader.resolveEffectiveConfig(root, "unknown_tool")
        assertEquals("unknown_tool", cfg.name)
        assertEquals(86_400_000, cfg.ttlMs, "unknown tool should use default TTL")
        assertTrue(cfg.cacheable)
        assertFalse(cfg.timeSensitive)
        assertEquals(10_240, cfg.maxParamSize)
    }

    @Test
    fun `per-tool fields fall back to defaults when missing`() {
        val yaml = """
            tools:
              - name: minimal_tool
            defaults:
              ttlMs: 5000
              cacheable: false
              timeSensitive: true
              maxParamSize: 2048
        """.trimIndent()
        val root = loader.loadFromString(yaml)
        val cfg = loader.resolveEffectiveConfig(root, "minimal_tool")
        assertEquals(5000, cfg.ttlMs)
        assertFalse(cfg.cacheable)
        assertTrue(cfg.timeSensitive)
        assertEquals(2048, cfg.maxParamSize)
    }

    @Test
    fun `loadFromString handles malformed YAML gracefully`() {
        // SnakeYAML is lenient — should not throw on weird inputs
        val root = loader.loadFromString("not-a-yaml-at-all")
        assertTrue(root.tools.isEmpty())
    }

    @Test
    fun `loadFromString null tools section yields empty list`() {
        val yaml = """
            defaults:
              ttlMs: 60000
        """.trimIndent()
        val root = loader.loadFromString(yaml)
        assertTrue(root.tools.isEmpty())
        assertEquals(60_000, root.defaults.ttlMs)
    }

    @Test
    fun `ttlMs zero means no cache (write operations)`() {
        val yaml = """
            tools:
              - name: add_question
                ttlMs: 0
                cacheable: false
        """.trimIndent()
        val root = loader.loadFromString(yaml)
        val cfg = loader.resolveEffectiveConfig(root, "add_question")
        assertEquals(0, cfg.ttlMs, "ttlMs=0 means don't cache")
        assertFalse(cfg.cacheable, "explicit cacheable=false")
    }

    @Test
    fun `timeSensitive annotation preserved`() {
        val yaml = """
            tools:
              - name: get_weather
                ttlMs: 300000
                timeSensitive: true
              - name: solve_math
                ttlMs: 86400000
                timeSensitive: false
        """.trimIndent()
        val root = loader.loadFromString(yaml)
        assertTrue(loader.resolveEffectiveConfig(root, "get_weather").timeSensitive)
        assertFalse(loader.resolveEffectiveConfig(root, "solve_math").timeSensitive)
    }

    @Test
    fun `swrGraceMs propagates from defaults when tool omits`() {
        val yaml = """
            tools:
              - name: list_notebooks
                ttlMs: 1800000
                # swrGraceMs omitted → use default
            defaults:
              swrGraceMs: 60000
        """.trimIndent()
        val root = loader.loadFromString(yaml)
        val cfg = loader.resolveEffectiveConfig(root, "list_notebooks")
        assertEquals(60_000L, cfg.swrGraceMs, "should inherit swrGraceMs from defaults")
    }
}
