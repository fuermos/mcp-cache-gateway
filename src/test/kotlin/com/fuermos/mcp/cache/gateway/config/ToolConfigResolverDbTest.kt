package com.fuermos.mcp.cache.gateway.config

import com.fuermos.mcp.cache.gateway.persistence.PostgresClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Integration tests for ToolConfigResolver DB loader.
 *
 * Uses real PostgreSQL (PG_INTEGRATION=1) with the mcp_tool_config table.
 */
@EnabledIfEnvironmentVariable(named = "PG_INTEGRATION", matches = "1")
class ToolConfigResolverDbTest {

    private lateinit var pg: PostgresClient
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        val password = System.getenv("POSTGRES_PASSWORD")
            ?: try {
                val envFile = java.io.File("/home/fuermos/.openclaw/state/mcp-cache-gateway-pg.env")
                if (envFile.exists()) envFile.readText().trim() else ""
            } catch (_: Exception) { "" }
        pg = PostgresClient(
            jdbcUrl = PostgresClient.DEFAULT_URL,
            username = PostgresClient.DEFAULT_USERNAME,
            password = password
        )
        pg.dataSource()  // trigger connect
        if (!pg.ping()) {
            println("SKIP: PG ping failed")
            return
        }
        jdbc = JdbcTemplate(pg.dataSource())

        // Seed defaults row + 5 wrongnotebook tools (idempotent)
        jdbc.update("""
            INSERT INTO mcp_tool_config (tool_name, ttl_ms, cacheable, time_sensitive, swr_grace_ms, max_param_size)
            VALUES ('__defaults__', 86400000, TRUE, FALSE, NULL, 10240)
            ON CONFLICT (tool_name) DO UPDATE SET ttl_ms = EXCLUDED.ttl_ms, cacheable = EXCLUDED.cacheable
        """.trimIndent())
        jdbc.update("""
            INSERT INTO mcp_tool_config (tool_name, tool_version, ttl_ms, cacheable)
            VALUES ('wrongnotebook.list_notebooks', '1.0.0', 60000, TRUE)
            ON CONFLICT (tool_name) DO NOTHING
        """.trimIndent())
        jdbc.update("""
            INSERT INTO mcp_tool_config (tool_name, tool_version, ttl_ms, cacheable)
            VALUES ('wrongnotebook.add_question', '1.0.0', 0, FALSE)
            ON CONFLICT (tool_name) DO NOTHING
        """.trimIndent())
    }

    @AfterEach
    fun tearDown() {
        pg.close()
    }

    @Test
    fun `loadFromDatabase returns all rows`() {
        if (!pg.ping()) return
        val root = ToolConfigResolver.loadFromDatabase(jdbc)
        assertTrue(root.tools.size >= 2, "should load at least 2 tools (from seed)")
        assertEquals(86_400_000, root.defaults.ttlMs, "defaults loaded from __defaults__ row (1 day)")
    }

    @Test
    fun `loadFromDatabase distinguishes tools from defaults`() {
        if (!pg.ping()) return
        val root = ToolConfigResolver.loadFromDatabase(jdbc)
        val listTool = root.tools.firstOrNull { it.name == "wrongnotebook.list_notebooks" }
        assertNotNull(listTool)
        val lt = listTool!!
        assertEquals("1.0.0", lt.version)
        assertEquals(60_000, lt.ttlMs)
        assertTrue(lt.cacheable)
    }

    @Test
    fun `loadFromDatabase cacheable=false for write tools`() {
        if (!pg.ping()) return
        val root = ToolConfigResolver.loadFromDatabase(jdbc)
        val writeTool = root.tools.firstOrNull { it.name == "wrongnotebook.add_question" }
        assertNotNull(writeTool)
        val w = writeTool!!
        assertEquals(0, w.ttlMs, "write tools should have ttlMs=0")
        assertFalse(w.cacheable, "write tools should have cacheable=false")
    }

    @Test
    fun `fromDatabase factory creates working resolver`() {
        if (!pg.ping()) return
        val resolver = ToolConfigResolver.fromDatabase(jdbc)
        val cfg = resolver.resolveEffectiveConfig("wrongnotebook.list_notebooks")
        assertEquals("wrongnotebook.list_notebooks", cfg.name)
        assertEquals(60_000, cfg.ttlMs)
    }

    @Test
    fun `resolveEffectiveConfig returns defaults for unknown tool`() {
        if (!pg.ping()) return
        val resolver = ToolConfigResolver.fromDatabase(jdbc)
        val cfg = resolver.resolveEffectiveConfig("unknown.tool")
        assertEquals("unknown.tool", cfg.name)
        // Defaults come from __defaults__ row
        assertEquals(86_400_000, cfg.ttlMs, "unknown tools use default TTL")
        assertTrue(cfg.cacheable)
    }

    @Test
    fun `reloadFromDatabase picks up new entries`() {
        if (!pg.ping()) return
        val resolver = ToolConfigResolver.fromDatabase(jdbc)
        // Initial: 2+ tools from seed
        val before = resolver.allTools().size
        // Insert new tool
        jdbc.update("""
            INSERT INTO mcp_tool_config (tool_name, ttl_ms, cacheable)
            VALUES ('test.dynamic_tool', 30000, TRUE)
            ON CONFLICT (tool_name) DO NOTHING
        """.trimIndent())
        // Reload
        resolver.reloadFromDatabase(jdbc)
        val after = resolver.allTools().size
        assertTrue(after > before, "should have more tools after reload")
        assertNotNull(resolver.allTools().firstOrNull { it.name == "test.dynamic_tool" })
    }
}