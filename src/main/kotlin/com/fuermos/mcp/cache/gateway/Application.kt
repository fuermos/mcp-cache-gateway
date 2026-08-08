package com.fuermos.mcp.cache.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * mcp-cache-gateway entry point.
 *
 * Day 2.2: now uses @SpringBootApplication to enable:
 *   - Flyway auto-migration (runs V1__initial_schema.sql on startup)
 *   - HikariCP DataSource from spring.datasource.* config
 *   - Spring component scan (auto-discovers @Component, @Service, @Repository)
 *   - Spring Boot Actuator (/actuator/health, /actuator/prometheus)
 *
 * Sidecar MCP server that adds:
 * - Two-tier cache (Redis Tier 1 + PostgreSQL Tier 2)
 * - request_id first-class idempotency
 * - Per-tool TTL configuration
 * - Stale-while-revalidate
 * - Negative caching
 * - Lazy server loading
 *
 * See docs/design.md for full specification.
 * See docs/phase1-spec.md for Phase 1 plan.
 */
@SpringBootApplication
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args) {
        setBannerMode(org.springframework.boot.Banner.Mode.OFF)
    }
}
