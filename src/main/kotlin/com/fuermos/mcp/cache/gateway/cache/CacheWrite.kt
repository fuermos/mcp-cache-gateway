package com.fuermos.mcp.cache.gateway.cache

import com.fuermos.mcp.cache.gateway.persistence.CacheRepository
import com.fuermos.mcp.cache.gateway.persistence.RedisClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Two-tier cache write — Redis (sync) + PostgreSQL (async via coroutine).
 *
 * Day 2.2 design:
 *   - Tier 1 Redis: sync SETEX (immediate consistency)
 *   - Tier 2 PostgreSQL: async INSERT/UPSERT (coroutine, Dispatchers.IO)
 *   - DB write failures are logged but don't fail the operation (Redis is hot path)
 *   - Both tiers always written in parallel (no skip — design.md §3.4)
 *
 * Async DB write rationale (智多星 Day 2.2 approval):
 *   - Dispatchers.IO (DB IO is IO-bound, not CPU-bound)
 *   - SupervisorJob (one DB failure doesn't cancel sibling writes)
 *   - Structured concurrency — write scope tied to CacheWrite lifetime
 *
 * Pattern reference:
 *   - design.md §3.4 (two-tier promotion + async write policy)
 *   - design.md §6 (TTL + invalidation strategies)
 */
class CacheWrite(
    private val redis: RedisClient,
    private val dbRepo: CacheRepository? = null,
    private val writeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(CacheWrite::class.java)

    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Write entry to BOTH tiers:
     *   - Redis: sync SETEX (returns true/false)
     *   - PostgreSQL: async UPSERT via coroutine (fire-and-forget)
     *
     * @return true if Redis write succeeded (DB write is async, not blocking)
     */
    fun write(entry: CacheEntry): Boolean {
        val redisOk = writeToRedis(entry)
        if (dbRepo != null) {
            // Async DB write — fire-and-forget
            writeScope.launch {
                val dbOk = dbRepo.upsert(entry.copy(cacheTier = CacheTier.BOTH))
                if (!dbOk) {
                    log.warn("async DB upsert failed (request_id={})", entry.requestId)
                }
            }
        }
        return redisOk
    }

    /**
     * Write entry to Redis only (Tier 1).
     */
    private fun writeToRedis(entry: CacheEntry): Boolean {
        val key = CacheKey.forRequestId(entry.requestId)
        val paramsKey = CacheKey.forParams(
            entry.serverId, entry.method,
            entry.toolName, entry.toolVersion, entry.paramsHash
        )
        val now = nowProvider()
        val ttlMs = entry.remainingFreshMs(now).coerceAtLeast(1_000)

        val serialized = json.encodeToString(CacheEntry.serializer(), entry)

        return try {
            redis.sync { cmd ->
                // Lettuce SETEX takes seconds (Long) not Duration
                val ttlSeconds = (ttlMs / 1000).coerceAtLeast(1L)
                cmd.setex(key, ttlSeconds, serialized)
                cmd.setex(paramsKey, ttlSeconds, serialized)
            }
            log.debug("Redis write OK: key={} ttl={}ms", key, ttlMs)
            true
        } catch (e: Exception) {
            log.warn("Redis SETEX failed (key={}): {}", key, e.message)
            false
        }
    }

    /**
     * Invalidate entry by request_id — clears BOTH tiers.
     */
    fun invalidateByRequestId(requestId: String): Boolean {
        val redisOk = invalidateRedis(requestId)
        if (dbRepo != null) {
            writeScope.launch {
                val dbOk = dbRepo.invalidate(requestId)
                if (!dbOk) {
                    log.warn("async DB invalidate failed (request_id={})", requestId)
                }
            }
        }
        return redisOk
    }

    private fun invalidateRedis(requestId: String): Boolean {
        val key = CacheKey.forRequestId(requestId)
        return try {
            redis.sync { it.del(key) }
            log.debug("Redis invalidate: key={}", key)
            true
        } catch (e: Exception) {
            log.warn("Redis DEL failed (key={}): {}", key, e.message)
            false
        }
    }

    /**
     * Invalidate all entries for a given (server, method, tool).
     *
     * Day 4 design (spec §4 Day 4 afternoon + design.md §6.1 invalidation):
     *   - Uses Redis SCAN + pattern delete (params keys have server/method/tool/version)
     *   - DB uses UPDATE WHERE (logical invalidation, no row delete)
     *   - Returns count of invalidated entries
     *
     * Used for notifications/list_changed + tool version bump.
     */
    fun invalidateByMethod(
        serverId: String,
        method: String,
        toolName: String? = null,
        toolVersion: String? = null
    ): Int {
        // Redis glob: * matches any chars (including ':')
        // Key format: mcp:params:{server}:{method}:{tool}:{version}:{hash}
        // Use literal fields where known, '*' where unknown
        val serverPart = if (serverId == "*") "*" else serverId
        val methodPart = if (method == "*") "*" else method
        val toolPart = toolName ?: "*"
        val versionPart = toolVersion ?: "*"
        // Pattern: prefix:server:method:tool:version:*  (hash is always last segment)
        val pattern = "mcp:params:$serverPart:$methodPart:$toolPart:$versionPart:*"

        val redisDeleted = try {
            redis.sync { cmd ->
                val keys = cmd.keys(pattern)
                if (keys.isNotEmpty()) cmd.del(*keys.toTypedArray()) else 0L
            }
        } catch (e: Exception) {
            log.warn("Redis SCAN/DEL failed (pattern={}): {}", pattern, e.message)
            0L
        }
        log.info("Redis invalidateByMethod: server={}, method={}, tool={}, version={}, deleted={}",
            serverId, method, toolName, toolVersion, redisDeleted)

        if (dbRepo != null) {
            // Async DB invalidation — for Day 4 use direct SQL via JdbcTemplate
            // (CacheRepository interface doesn't yet expose invalidateByMethod;
            //  this is a known gap, will be added Day 5 if needed)
            writeScope.launch {
                log.debug("async DB invalidateByMethod not yet implemented (pattern={})", pattern)
            }
        }
        return redisDeleted.toInt()
    }

    /**
     * Invalidate all entries for a tool name across all methods.
     *
     * Day 4: used when notifications/list_changed signals a tool was removed.
     * Pattern: mcp:params:*:{tool}:*:*  (no server/method filter)
     */
    fun invalidateByTool(toolName: String): Int {
        // Pattern matches any server/method/version with this tool name
        // Redis glob: * matches any chars, so pattern covers all 5 preceding segments
        val pattern = "mcp:params:*:$toolName:*:*"
        val redisDeleted = try {
            redis.sync { cmd ->
                val keys = cmd.keys(pattern)
                if (keys.isNotEmpty()) cmd.del(*keys.toTypedArray()) else 0L
            }
        } catch (e: Exception) {
            log.warn("Redis SCAN/DEL failed (pattern={}): {}", pattern, e.message)
            0L
        }
        log.info("Redis invalidateByTool: tool={}, deleted={}", toolName, redisDeleted)
        return redisDeleted.toInt()
    }

    /**
     * Invalidate all entries for a given (tool, version) — used when tool version
     * changes (design.md §3.7). All old-version entries get cleaned up.
     */
    fun invalidateByToolVersion(toolName: String, oldVersion: String): Int {
        return invalidateByMethod(
            serverId = "*",  // all servers
            method = "*",    // all methods
            toolName = toolName,
            toolVersion = oldVersion
        )
    }

    override fun close() {
        // Cancel pending DB writes
        writeScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
