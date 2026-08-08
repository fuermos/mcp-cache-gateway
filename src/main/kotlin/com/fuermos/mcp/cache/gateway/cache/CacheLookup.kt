package com.fuermos.mcp.cache.gateway.cache

import com.fuermos.mcp.cache.gateway.persistence.CacheRepository
import com.fuermos.mcp.cache.gateway.persistence.RedisClient
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Two-tier cache lookup — Redis (primary) + PostgreSQL (fallback).
 *
 * Day 2.2 design:
 *   - Step 1: lookup by request_id (Redis, then DB if Redis misses)
 *   - Step 2: lookup by params_hash (Redis, then DB if Redis misses)
 *   - DB hits are NOT auto-promoted to Redis (Day 2.2 keeps it simple;
 *     promotion logic lands in Day 3 cache lookup pipeline)
 *
 * Lookup order (design.md §4.1):
 *   - Step 1 (request_id) — fast path for retries / idempotency
 *   - Step 2 (params_hash) — semantic match across different ids
 *
 * Pattern references:
 *   - design.md §3.4 (Tier 2 fallback policy)
 *   - design.md §4.1 (lookup order)
 *   - design.md §4.2 (hit criteria) + §4.3 (miss criteria)
 */
class CacheLookup(
    private val redis: RedisClient,
    private val dbRepo: CacheRepository? = null,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {

    private val log = LoggerFactory.getLogger(CacheLookup::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Step 1: lookup by exact request_id (idempotency by design).
     *
     * Tries Redis first, falls back to DB if Redis miss.
     */
    fun lookupByRequestId(requestId: String): CacheEntry? {
        val redisHit = lookupRedisKey(CacheKey.forRequestId(requestId))
        if (redisHit != null) return redisHit
        // Tier 2 fallback
        return dbRepo?.findByRequestId(requestId, nowProvider())
    }

    /**
     * Step 2: lookup by params hash (semantic match across different ids).
     */
    fun lookupByParams(
        serverId: String,
        method: String,
        toolName: String?,
        toolVersion: String?,
        paramsHash: String
    ): CacheEntry? {
        val key = CacheKey.forParams(serverId, method, toolName, toolVersion, paramsHash)
        val redisHit = lookupRedisKey(key)
        if (redisHit != null) return redisHit
        // Tier 2 fallback
        return dbRepo?.findByParamsHash(serverId, method, toolName, toolVersion, paramsHash, nowProvider())
    }

    /**
     * Internal Redis-only lookup. Returns null on miss / expired / deserialization failure.
     */
    private fun lookupRedisKey(key: String): CacheEntry? {
        val raw = try {
            redis.sync { it.get(key) }
        } catch (e: Exception) {
            log.warn("Redis GET failed (key={}): {}", key.take(80), e.message)
            return null
        } ?: return null

        val entry = try {
            json.decodeFromString(CacheEntry.serializer(), raw)
        } catch (e: Exception) {
            log.warn("Redis cache deserialization failed (key={}): {}", key.take(80), e.message)
            return null
        }

        val now = nowProvider()
        return when {
            entry.invalidated -> null
            entry.isExpired(now) -> {
                log.debug("Redis miss (expired): key={}", key.take(80))
                null
            }
            else -> {
                log.debug("Redis hit: key={} tier={}", key.take(80), entry.cacheTier)
                entry
            }
        }
    }
}
