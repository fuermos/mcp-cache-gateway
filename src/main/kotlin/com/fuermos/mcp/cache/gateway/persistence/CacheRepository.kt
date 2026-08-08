package com.fuermos.mcp.cache.gateway.persistence

import com.fuermos.mcp.cache.gateway.cache.CacheEntry
import com.fuermos.mcp.cache.gateway.cache.CacheTier
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * CacheRepository — PostgreSQL CRUD for mcp_request_state table.
 *
 * Implements design.md §3.3 schema + §3.4 Tier 2 (cold cache) write policy
 * + §4.1 Step 2 lookup (params_hash fallback when Redis misses).
 *
 * Day 2.2 design:
 *   - Uses Spring JdbcTemplate (low-overhead, no JPA)
 *   - Async writes use CacheWrite.kt's coroutine (NOT inside this class)
 *   - Read queries return Optional<CacheEntry> (null = miss)
 *   - Hit_count incremented on lookup hit (lightweight UPDATE)
 *
 * Pattern reference:
 *   - design.md §3.3 (V1__initial_schema.sql columns)
 *   - design.md §3.6 (cache key fields → SQL WHERE clause)
 */
class CacheRepository(
    private val jdbcTemplate: JdbcTemplate
) {

    private val log = LoggerFactory.getLogger(CacheRepository::class.java)

    private val rowMapper = RowMapper<CacheEntry> { rs, _ ->
        val paramsJson = (rs.getObject("params_json") as PGobject).value
        val resultJson = rs.getObject("result_json") as? PGobject
        val metadata = rs.getObject("metadata") as? PGobject
        val cacheTierStr = rs.getString("cache_tier")
        CacheEntry(
            requestId = rs.getString("request_id") ?: error("request_id is NULL"),
            serverId = rs.getString("server_id") ?: error("server_id is NULL"),
            method = rs.getString("method") ?: error("method is NULL"),
            toolName = rs.getString("tool_name"),
            toolVersion = rs.getString("tool_version"),
            paramsHash = rs.getString("params_hash") ?: error("params_hash is NULL"),
            paramsJson = kotlinx.serialization.json.Json.parseToJsonElement(paramsJson ?: "{}"),
            resultJson = resultJson?.value?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) },
            resultSize = rs.getInt("result_size"),
            cacheTier = cacheTierStr?.let { runCatching { CacheTier.valueOf(it.uppercase()) }.getOrNull() } ?: CacheTier.DB,
            ttlMs = rs.getInt("ttl_ms"),
            createdAtMs = rs.getTimestamp("created_at").time,
            freshUntilMs = rs.getTimestamp("expires_at").time,
            staleUntilMs = rs.getTimestamp("stale_until")?.time,
            hitCount = rs.getInt("hit_count"),
            invalidated = rs.getBoolean("invalidated"),
            metadata = metadata?.value?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) }
        )
    }

    /**
     * Insert or update a cache entry.
     *
     * Uses UPSERT (ON CONFLICT DO UPDATE) so re-writes replace the entry.
     * Returns true on success, false on DB error.
     */
    fun upsert(entry: CacheEntry): Boolean = runCatching {
        jdbcTemplate.update(
            UPSERT_SQL,
            entry.requestId,
            entry.serverId,
            entry.method,
            entry.toolName,
            entry.toolVersion,
            entry.paramsHash,
            PGobject().apply { type = "jsonb"; value = entry.paramsJson.toString() },
            entry.resultJson?.let { PGobject().apply { type = "jsonb"; value = it.toString() } },
            entry.resultSize,
            entry.cacheTier.name.lowercase(),
            entry.ttlMs,
            Timestamp(Instant.ofEpochMilli(entry.freshUntilMs).toEpochMilli()),
            entry.staleUntilMs?.let { Timestamp(Instant.ofEpochMilli(it).toEpochMilli()) },
            entry.invalidated,
            entry.metadata?.let { PGobject().apply { type = "jsonb"; value = it.toString() } }
        )
        log.debug("PG upsert OK: request_id={}", entry.requestId)
        true
    }.onFailure {
        log.warn("PG upsert failed (request_id={}): {}", entry.requestId, it.message)
    }.getOrDefault(false)

    /**
     * Lookup by exact request_id (Step 1 lookup — same as Redis primary).
     *
     * Returns null on:
     *   - row not present
     *   - invalidated = true
     *   - expires_at <= now
     *
     * Increments hit_count on hit (best-effort).
     */
    fun findByRequestId(requestId: String, nowMs: Long = System.currentTimeMillis()): CacheEntry? {
        val entry = runCatching {
            jdbcTemplate.query(
                FIND_BY_REQUEST_ID_SQL,
                rowMapper,
                requestId,
                Timestamp(Instant.ofEpochMilli(nowMs).toEpochMilli())
            ).firstOrNull()
        }.onFailure {
            log.warn("PG findByRequestId failed: {}", it.message)
        }.getOrNull()
        if (entry != null) {
            incrementHitCount(requestId)
        }
        return entry
    }

    /**
     * Lookup by params hash (Step 2 lookup — same as Redis semantic).
     *
     * Returns the most recent non-invalidated, non-expired entry.
     */
    fun findByParamsHash(
        serverId: String,
        method: String,
        toolName: String?,
        toolVersion: String?,
        paramsHash: String,
        nowMs: Long = System.currentTimeMillis()
    ): CacheEntry? {
        val entry = runCatching {
            jdbcTemplate.query(
                FIND_BY_PARAMS_HASH_SQL,
                rowMapper,
                serverId,
                method,
                toolName,
                toolVersion,
                paramsHash,
                Timestamp(Instant.ofEpochMilli(nowMs).toEpochMilli())
            ).firstOrNull()
        }.onFailure {
            log.warn("PG findByParamsHash failed: {}", it.message)
        }.getOrNull()
        if (entry != null) {
            incrementHitCount(entry.requestId)
        }
        return entry
    }

    /**
     * Invalidate (logical delete — sets invalidated=true).
     *
     * Future lookups filter out invalidated rows.
     */
    fun invalidate(requestId: String): Boolean = runCatching {
        jdbcTemplate.update(
            "UPDATE mcp_request_state SET invalidated = TRUE WHERE request_id = ?",
            requestId
        )
        true
    }.getOrDefault(false)

    /**
     * Increment hit_count (best-effort, no transaction).
     */
    private fun incrementHitCount(requestId: String) {
        runCatching {
            jdbcTemplate.update(
                "UPDATE mcp_request_state SET hit_count = hit_count + 1 WHERE request_id = ?",
                requestId
            )
        }
    }

    /**
     * Delete expired entries (cleanup sweep).
     *
     * Day 2.2: stub. Day 5+ may add periodic cleanup job.
     */
    fun deleteExpired(nowMs: Long = System.currentTimeMillis()): Int {
        return runCatching {
            jdbcTemplate.update(
                "DELETE FROM mcp_request_state WHERE expires_at < ? AND invalidated = TRUE",
                Timestamp(Instant.ofEpochMilli(nowMs).toEpochMilli())
            )
        }.getOrDefault(0)
    }

    /**
     * Count of total entries (for metrics).
     */
    fun count(): Long = runCatching {
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mcp_request_state", Long::class.java) ?: 0L
    }.getOrDefault(0L)

    companion object {
        private const val UPSERT_SQL = """
            INSERT INTO mcp_request_state (
                request_id, server_id, method, tool_name, tool_version,
                params_hash, params_json, result_json, result_size,
                cache_tier, ttl_ms, expires_at, stale_until, invalidated, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (request_id) DO UPDATE SET
                server_id = EXCLUDED.server_id,
                method = EXCLUDED.method,
                tool_name = EXCLUDED.tool_name,
                tool_version = EXCLUDED.tool_version,
                params_hash = EXCLUDED.params_hash,
                params_json = EXCLUDED.params_json,
                result_json = EXCLUDED.result_json,
                result_size = EXCLUDED.result_size,
                cache_tier = EXCLUDED.cache_tier,
                ttl_ms = EXCLUDED.ttl_ms,
                expires_at = EXCLUDED.expires_at,
                stale_until = EXCLUDED.stale_until,
                invalidated = EXCLUDED.invalidated,
                metadata = EXCLUDED.metadata,
                hit_count = 0
        """

        private const val FIND_BY_REQUEST_ID_SQL = """
            SELECT request_id, server_id, method, tool_name, tool_version,
                   params_hash, params_json, result_json, result_size,
                   cache_tier, ttl_ms, expires_at, stale_until, invalidated, hit_count, metadata,
                   created_at
            FROM mcp_request_state
            WHERE request_id = ? AND invalidated = FALSE AND expires_at > ?
            LIMIT 1
        """

        private const val FIND_BY_PARAMS_HASH_SQL = """
            SELECT request_id, server_id, method, tool_name, tool_version,
                   params_hash, params_json, result_json, result_size,
                   cache_tier, ttl_ms, expires_at, stale_until, invalidated, hit_count, metadata,
                   created_at
            FROM mcp_request_state
            WHERE server_id = ?
              AND method = ?
              AND (tool_name = ? OR (? IS NULL AND tool_name IS NULL))
              AND (tool_version = ? OR (? IS NULL AND tool_version IS NULL))
              AND params_hash = ?
              AND invalidated = FALSE
              AND expires_at > ?
            ORDER BY created_at DESC
            LIMIT 1
        """
    }
}
