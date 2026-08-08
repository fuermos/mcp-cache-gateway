package com.fuermos.mcp.cache.gateway.cache

import com.fuermos.mcp.cache.gateway.persistence.CacheRepository
import com.fuermos.mcp.cache.gateway.persistence.PostgresClient
import com.fuermos.mcp.cache.gateway.utils.Hashing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Integration test for CacheRepository against real PostgreSQL.
 *
 * Uses PG at 127.0.0.1:5432, db=mcp_cache, user=mcp_cache.
 * Requires password via env: POSTGRES_PASSWORD (or relies on ~/.openclaw/state/mcp-cache-gateway-pg.env).
 * Skips if PG_INTEGRATION_DISABLED=1 or DB unreachable.
 *
 * Each test uses a unique requestId (UUID v7) to avoid cross-test pollution.
 * @AfterEach cleans up test rows.
 */
@EnabledIfEnvironmentVariable(named = "PG_INTEGRATION", matches = "1")
class CachePgIntegrationTest {

    private lateinit var pg: PostgresClient
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var repo: CacheRepository
    private val testRequestIds = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        val password = System.getenv("POSTGRES_PASSWORD")
            ?: try {
                val envFile = java.io.File("<OPENCLAW_STATE_DIR>/mcp-cache-gateway-pg.env")
                if (envFile.exists()) envFile.readText().trim() else ""
            } catch (_: Exception) { "" }

        pg = PostgresClient(
            jdbcUrl = PostgresClient.DEFAULT_URL,
            username = PostgresClient.DEFAULT_USERNAME,
            password = password
        )
        try {
            pg.dataSource()  // trigger connect
        } catch (e: Exception) {
            println("SKIP: PG unavailable: ${e.message}")
            return
        }
        if (!pg.ping()) {
            println("SKIP: PG ping failed")
            return
        }
        jdbcTemplate = JdbcTemplate(pg.dataSource())
        repo = CacheRepository(jdbcTemplate)
    }

    @AfterEach
    fun tearDown() {
        // Cleanup test rows
        if (::jdbcTemplate.isInitialized) {
            testRequestIds.forEach { id ->
                runCatching { jdbcTemplate.update("DELETE FROM mcp_request_state WHERE request_id = ?", id) }
            }
        }
        pg.close()
    }

    private fun sampleEntry(
        requestId: String = "req-${System.nanoTime()}",
        ttlMs: Int = 60_000,
        swrGraceMs: Long? = null,
        toolName: String? = "test_tool",
        toolVersion: String? = "1.0.0",
        params: kotlinx.serialization.json.JsonElement = buildJsonObject { put("city", "sf") },
        result: kotlinx.serialization.json.JsonElement? = buildJsonObject { put("temp", 72) }
    ): CacheEntry {
        val now = System.currentTimeMillis()
        val (freshUntil, staleUntil) = CacheEntry.computeWindows(now, ttlMs, swrGraceMs)
        testRequestIds.add(requestId)
        return CacheEntry(
            requestId = requestId,
            serverId = "test-server",
            method = "tools/call",
            toolName = toolName,
            toolVersion = toolVersion,
            paramsHash = Hashing.sha256(params),
            paramsJson = params,
            resultJson = result,
            resultSize = 10,
            cacheTier = CacheTier.BOTH,
            ttlMs = ttlMs,
            createdAtMs = now,
            freshUntilMs = freshUntil,
            staleUntilMs = staleUntil
        )
    }

    @Test
    fun `upsert inserts new entry`() {
        if (!::repo.isInitialized) return
        val entry = sampleEntry()
        assertTrue(repo.upsert(entry))
        val fetched = repo.findByRequestId(entry.requestId)
        assertNotNull(fetched)
        val f1 = fetched
        assertEquals(entry.requestId, f1!!.requestId)
        assertEquals(entry.toolName, fetched.toolName)
        assertEquals(entry.paramsHash, fetched.paramsHash)
    }

    @Test
    fun `upsert updates existing entry (ON CONFLICT)`() {
        if (!::repo.isInitialized) return
        val entry = sampleEntry()
        repo.upsert(entry)
        // Modify and re-upsert
        val modified = entry.copy(toolVersion = "2.0.0", ttlMs = 120_000)
        repo.upsert(modified)
        val fetched = repo.findByRequestId(entry.requestId)
        assertNotNull(fetched)
        val f1 = fetched
        assertEquals("2.0.0", f1!!.toolVersion, "upsert should replace version")
        assertEquals(120_000, fetched.ttlMs)
    }

    @Test
    fun `findByRequestId returns null when not present`() {
        if (!::repo.isInitialized) return
        val hit = repo.findByRequestId("nonexistent-${System.nanoTime()}")
        assertNull(hit)
    }

    @Test
    fun `findByRequestId returns null when invalidated`() {
        if (!::repo.isInitialized) return
        val entry = sampleEntry()
        repo.upsert(entry)
        assertTrue(repo.invalidate(entry.requestId))
        assertNull(repo.findByRequestId(entry.requestId))
    }

    @Test
    fun `findByRequestId returns null when expired`() {
        if (!::repo.isInitialized) return
        // TTL 1s — wait then query
        val entry = sampleEntry(ttlMs = 1_000)
        repo.upsert(entry)
        Thread.sleep(1_500)
        assertNull(repo.findByRequestId(entry.requestId))
    }

    @Test
    fun `findByParamsHash returns entry when params match`() {
        if (!::repo.isInitialized) return
        val entry = sampleEntry(toolName = "toolA", toolVersion = "1.0.0",
            params = buildJsonObject { put("city", "sf") })
        repo.upsert(entry)
        val hit = repo.findByParamsHash(
            serverId = "test-server",
            method = "tools/call",
            toolName = "toolA",
            toolVersion = "1.0.0",
            paramsHash = Hashing.sha256(entry.paramsJson)
        )
        assertNotNull(hit)
        val h1 = hit
        assertEquals(entry.requestId, h1!!.requestId)
    }

    @Test
    fun `findByParamsHash miss when tool differs`() {
        if (!::repo.isInitialized) return
        val entry = sampleEntry(toolName = "toolA")
        repo.upsert(entry)
        val hit = repo.findByParamsHash(
            serverId = "test-server",
            method = "tools/call",
            toolName = "toolB",
            toolVersion = entry.toolVersion,
            paramsHash = entry.paramsHash
        )
        assertNull(hit)
    }

    @Test
    fun `findByParamsHash handles null toolName and version`() {
        if (!::repo.isInitialized) return
        val entry = sampleEntry(toolName = null, toolVersion = null)
        repo.upsert(entry)
        val hit = repo.findByParamsHash(
            serverId = "test-server",
            method = "tools/call",
            toolName = null,
            toolVersion = null,
            paramsHash = entry.paramsHash
        )
        assertNotNull(hit)
        val h1 = hit
        assertEquals(entry.requestId, h1!!.requestId)
    }

    @Test
    fun `count returns total rows`() {
        if (!::repo.isInitialized) return
        val before = repo.count()
        repo.upsert(sampleEntry())
        repo.upsert(sampleEntry())
        val after = repo.count()
        assertEquals(before + 2, after)
    }

    @Test
    fun `hit_count incremented on lookup`() {
        if (!::repo.isInitialized) return
        val entry = sampleEntry()
        repo.upsert(entry)
        repo.findByRequestId(entry.requestId)
        repo.findByRequestId(entry.requestId)
        repo.findByRequestId(entry.requestId)
        // Fetch with hit_count visible (use raw query — bypass hit_count++ in find)
        val rawHitCount = jdbcTemplate.queryForObject(
            "SELECT hit_count FROM mcp_request_state WHERE request_id = ?",
            Int::class.java, entry.requestId
        )
        assertNotNull(rawHitCount)
        assertTrue(rawHitCount >= 3, "hit_count should be at least 3, got: $rawHitCount")
    }
}
