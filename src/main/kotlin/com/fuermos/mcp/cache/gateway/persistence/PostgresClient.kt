package com.fuermos.mcp.cache.gateway.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * PostgreSQL client wrapper around HikariCP connection pool.
 *
 * Day 2.2 design:
 *   - HikariCP pool (max 10, min 2 — per application.yml)
 *   - Spring Boot auto-config also creates a DataSource bean (from
 *     `spring.datasource.*` in application.yml); this class wraps the SAME
 *     pool to expose lifecycle methods + health check
 *   - JDBC operations (NOT JPA / Spring Data) — CacheRepository uses
 *     JdbcTemplate directly for low-overhead cache CRUD
 *
 * Lifecycle:
 *   - HikariDataSource created lazily on first use
 *   - close() shuts down pool (idempotent)
 *
 * Pattern references:
 *   - design.md §3.4 (Tier 2 PostgreSQL cold cache)
 *   - design.md §6.1 (TTL expiry via expires_at index)
 */
class PostgresClient(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    private val maxPoolSize: Int = 10,
    private val minIdle: Int = 2,
    private val connectionTimeoutMs: Long = 5_000,
    private val poolName: String = "mcp-cache-pg-pool"
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(PostgresClient::class.java)

    @Volatile
    private var dataSource: HikariDataSource? = null

    /**
     * Get (or create) the DataSource. Idempotent.
     *
     * Note: Spring Boot auto-config creates its own DataSource bean from
     * `spring.datasource.*`. If you inject this class as a bean, prefer to
     * inject the Spring DataSource directly into CacheRepository instead.
     * This class is for non-Spring contexts (tests, CLI).
     */
    fun dataSource(): DataSource {
        val existing = dataSource
        if (existing != null && !existing.isClosed) return existing

        synchronized(this) {
            val again = dataSource
            if (again != null && !again.isClosed) return again

            log.info("connecting to PostgreSQL: {} (pool={}-{}, timeout={}ms)",
                sanitizeJdbcUrl(jdbcUrl), minIdle, maxPoolSize, connectionTimeoutMs)

            val cfg = HikariConfig().apply {
                this.jdbcUrl = this@PostgresClient.jdbcUrl
                this.username = this@PostgresClient.username
                this.password = this@PostgresClient.password
                this.maximumPoolSize = this@PostgresClient.maxPoolSize
                this.minimumIdle = this@PostgresClient.minIdle
                this.connectionTimeout = connectionTimeoutMs
                this.poolName = this@PostgresClient.poolName
                this.driverClassName = "org.postgresql.Driver"
            }
            val ds = HikariDataSource(cfg)
            dataSource = ds
            return ds
        }
    }

    /**
     * Health check — execute "SELECT 1" on a pool connection.
     */
    fun ping(): Boolean = runCatching {
        dataSource().connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT 1").use { rs ->
                    rs.next() && rs.getInt(1) == 1
                }
            }
        }
    }.getOrDefault(false)

    /**
     * Strip password from JDBC URL for safe logging.
     * "jdbc:postgresql://user:pass@host:port/db" → "jdbc:postgresql://host:port/db"
     */
    private fun sanitizeJdbcUrl(url: String): String {
        // Postgres URL format: jdbc:postgresql://[user[:password]@]host[:port][/db][?params]
        val atIdx = url.indexOf('@')
        if (atIdx < 0) return url
        val schemeEnd = url.indexOf("://") + 3
        return url.substring(0, schemeEnd) + url.substring(atIdx + 1)
    }

    override fun close() {
        runCatching { dataSource?.close() }
        dataSource = null
    }

    companion object {
        /**
         * Default JDBC URL for local development.
         * Note: password NOT included — passed separately via env.
         */
        const val DEFAULT_URL = "jdbc:postgresql://127.0.0.1:5432/mcp_cache"
        const val DEFAULT_USERNAME = "mcp_cache"
    }
}
