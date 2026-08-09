package com.fuermos.mcp.cache.gateway.config

import com.fuermos.mcp.cache.gateway.cache.CacheLookup
import com.fuermos.mcp.cache.gateway.cache.CacheWrite
import com.fuermos.mcp.cache.gateway.http.McpHttpController
import com.fuermos.mcp.cache.gateway.http.StreamableHttpHandler
import com.fuermos.mcp.cache.gateway.orchestrator.GeneralProxy
import com.fuermos.mcp.cache.gateway.persistence.CacheRepository
import com.fuermos.mcp.cache.gateway.persistence.PostgresClient
import com.fuermos.mcp.cache.gateway.persistence.RedisClient
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager.ServerConfig
import com.fuermos.mcp.cache.gateway.server.ServerLifecycleManager.ServerRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File

/**
 * GatewayHttpConfig — Spring @Configuration wiring all beans needed for Phase 3 HTTP layer.
 *
 * Phase 3 (per skill-master 8/9 15:12 CST directive + Step 3+4):
 *   - Wires PostgresClient, RedisClient, CacheRepository (data layer)
 *   - Wires CacheLookup, CacheWrite (cache layer)
 *   - Wires ToolConfigResolver (per-tool TTL config)
 *   - Wires ServerLifecycleManager with BackendsRegistryServerRegistry (subprocess spawn)
 *   - Wires BackendsRegistry (DB-driven backend registry)
 *   - Wires GeneralProxy (orchestrator)
 *   - Wires StreamableHttpHandler + McpHttpController (HTTP layer)
 *
 * Pattern references:
 *   - design.md §3.1 (Spring Boot manages backend lifecycle)
 *   - MCP 2025-03-26 spec §transports (Streamable HTTP)
 *   - Day 2.5 / Day 2.6 GeneralProxy orchestrator (no changes — wire-in only)
 */
@Configuration
class GatewayHttpConfig {

    private val log = LoggerFactory.getLogger(GatewayHttpConfig::class.java)

    // ==================== Persistence Layer ====================

    @Bean
    fun postgresClient(
        @Value("\${spring.datasource.url}") jdbcUrl: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String
    ): PostgresClient {
        log.info("Creating PostgresClient (url={}, user={})", jdbcUrl, username)
        return PostgresClient(jdbcUrl = jdbcUrl, username = username, password = password)
    }

    @Bean
    fun redisClient(
        @Value("\${spring.data.redis.host:127.0.0.1}") host: String,
        @Value("\${spring.data.redis.port:6379}") port: Int
    ): RedisClient {
        val uri = "redis://$host:$port"
        log.info("Creating RedisClient (uri={})", uri)
        return RedisClient(uri = uri)
    }

    @Bean
    fun cacheRepository(jdbcTemplate: JdbcTemplate): CacheRepository {
        log.info("Creating CacheRepository")
        return CacheRepository(jdbcTemplate)
    }

    // ==================== Cache Layer ====================

    @Bean
    fun cacheLookup(redisClient: RedisClient, cacheRepository: CacheRepository): CacheLookup {
        log.info("Creating CacheLookup")
        return CacheLookup(redis = redisClient, dbRepo = cacheRepository)
    }

    @Bean
    fun cacheWrite(redisClient: RedisClient, cacheRepository: CacheRepository): CacheWrite {
        log.info("Creating CacheWrite")
        return CacheWrite(redis = redisClient, dbRepo = cacheRepository)
    }

    // ==================== Tool Config ====================

    @Bean
    fun toolConfigResolver(
        @Value("\${gateway.tool-config-path}") toolConfigPath: String
    ): ToolConfigResolver {
        log.info("Loading tool config from: {}", toolConfigPath)
        val configFile = File(toolConfigPath)
        if (!configFile.exists()) {
            log.warn("Tool config file not found at {} — using empty config", toolConfigPath)
            return ToolConfigResolver(ToolConfigRoot(tools = emptyList()))
        }
        val root = ToolConfigLoader().loadFromFile(toolConfigPath)
        log.info("Loaded {} tool config entries", root.tools.size)
        return ToolConfigResolver(root)
    }

    // ==================== Server Lifecycle ====================

    /**
     * ServerRegistry that delegates to BackendsRegistry (DB-driven).
     * Phase 3 design: single source of truth — backend config comes from DB,
     * ServerRegistry just adapts to the ServerLifecycleManager interface.
     */
    @Bean
    fun serverRegistry(backendsRegistry: BackendsRegistry): ServerRegistry {
        return object : ServerRegistry {
            override fun get(serverId: String): ServerConfig? {
                val backend = backendsRegistry.cachedBackends().firstOrNull { it.name == serverId }
                    ?: return null
                return ServerConfig(
                    serverId = backend.name,
                    cmd = backend.cmd,
                    args = backend.args,
                    cwd = backend.cwd,
                    env = backend.env.takeIf { it.isNotEmpty() }
                )
            }
            override fun ids(): Set<String> =
                backendsRegistry.cachedBackends().map { it.name }.toSet()
        }
    }

    @Bean
    fun serverLifecycleManager(
        serverRegistry: ServerRegistry,
        @Value("\${gateway.lazy-server.idle-timeout-ms:60000}") idleTimeoutMs: Long,
        @Value("\${gateway.lazy-server.spawn-timeout-ms:5000}") spawnTimeoutMs: Long
    ): ServerLifecycleManager {
        log.info("Creating ServerLifecycleManager (idle={}ms, spawn={}ms)", idleTimeoutMs, spawnTimeoutMs)
        return ServerLifecycleManager(
            serverRegistry = serverRegistry,
            idleTimeoutMs = idleTimeoutMs,
            spawnTimeoutMs = spawnTimeoutMs
        )
    }

    // ==================== Backend Registry (DB-driven) ====================

    /**
     * BackendsRegistry loads from DB on first call. We call loadBackends() eagerly
     * at startup to populate cache (and let any DB connection issues surface).
     */
    @Bean
    fun backendsRegistry(
        jdbcTemplate: JdbcTemplate
    ): BackendsRegistry {
        log.info("Creating BackendsRegistry (DB-driven)")
        val registry = BackendsRegistry(primaryJdbc = jdbcTemplate)
        // Eager load — surface DB errors at startup, not on first request.
        runCatching { registry.loadBackends() }
            .onSuccess { log.info("Pre-loaded {} backend(s) from DB", it.size) }
            .onFailure { log.warn("Initial backend load failed (will retry on demand): {}", it.message) }
        return registry
    }

    // ==================== Orchestrator (HTTP layer dependency) ====================

    @Bean
    fun generalProxy(
        backendsRegistry: BackendsRegistry,
        cacheLookup: CacheLookup,
        cacheWrite: CacheWrite,
        serverLifecycleManager: ServerLifecycleManager
    ): GeneralProxy {
        log.info("Creating GeneralProxy")
        return GeneralProxy(
            backendsRegistry = backendsRegistry,
            lookup = cacheLookup,
            write = cacheWrite,
            serverManager = serverLifecycleManager
        )
    }

    // ==================== HTTP Layer ====================

    @Bean
    fun streamableHttpHandler(generalProxy: GeneralProxy): StreamableHttpHandler {
        log.info("Creating StreamableHttpHandler")
        return StreamableHttpHandler(generalProxy = generalProxy)
    }
}