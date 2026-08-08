package com.fuermos.mcp.cache.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate

/**
 * ToolConfigResolver — runtime accessor for per-tool TTL config.
 *
 * Day 3.1 design (智多星 orchestrator anchor):
 *   - Loads ToolConfigRoot from YAML at startup (one-shot)
 *   - Provides `resolveEffectiveConfig(toolName)` for GatewayOrchestrator hot path
 *   - Frozen (immutable) after load → thread-safe
 *   - Hot-reload via [replaceWith] (Day 4+, e.g. notifications/config_changed)
 *
 * Pattern reference:
 *   - design.md §3.5 (per-tool TTL config — tool metadata fields)
 *   - spec §4 Day 3 afternoon (per-tool TTL config wiring)
 */
class ToolConfigResolver(
    private var root: ToolConfigRoot,
    private val loader: ToolConfigLoader = ToolConfigLoader()
) {

    private val log = LoggerFactory.getLogger(ToolConfigResolver::class.java)

    companion object {
        /** Build a resolver from a YAML file path. */
        fun fromYamlPath(yamlPath: String, loader: ToolConfigLoader = ToolConfigLoader()): ToolConfigResolver =
            ToolConfigResolver(loader.loadFromFile(yamlPath), loader)

        /** Build a resolver from a YAML content string. */
        fun fromYamlContent(yamlContent: String, loader: ToolConfigLoader = ToolConfigLoader()): ToolConfigResolver =
            ToolConfigResolver(loader.loadFromString(yamlContent), loader)

        /** Build a default empty resolver (no tools, all defaults). */
        fun empty(loader: ToolConfigLoader = ToolConfigLoader()): ToolConfigResolver =
            ToolConfigResolver(ToolConfigRoot(), loader)

        /**
         * Build a resolver from mcp_tool_config table (DB-driven, Phase 2.2).
         *
         * Reads tool configs + defaults from DB. Used by Phase 2 to eliminate
         * the examples/tools.yaml file (per 主人 17:46 拍板: 完全 DB-driven).
         *
         * Expects table schema (from V1__initial_schema.sql):
         *   mcp_tool_config (
         *     tool_name TEXT PRIMARY KEY,
         *     tool_version TEXT,
         *     ttl_ms INTEGER DEFAULT 86400000,
         *     time_sensitive BOOLEAN DEFAULT FALSE,
         *     cacheable BOOLEAN DEFAULT TRUE,
         *     swr_grace_ms INTEGER,
         *     max_param_size INTEGER,
         *     notes TEXT,
         *     updated_at TIMESTAMPTZ DEFAULT NOW()
         *   )
         */
        fun fromDatabase(jdbc: JdbcTemplate): ToolConfigResolver {
            val root = loadFromDatabase(jdbc)
            return ToolConfigResolver(root)
        }

        /**
         * Load ToolConfigRoot directly from DB (used by fromDatabase + reload).
         *
         * Defaults come from a special row where tool_name='__defaults__' (or
         * fall back to ToolConfigDefaults() if not present).
         */
        fun loadFromDatabase(jdbc: JdbcTemplate): ToolConfigRoot {
            val rows = jdbc.queryForList(QUERY_TOOLS)
            val tools = mutableListOf<ToolConfig>()
            var defaults = ToolConfigDefaults()

            for (row in rows) {
                val toolName = row["tool_name"] as String
                if (toolName == "__defaults__") {
                    defaults = ToolConfigDefaults(
                        ttlMs = (row["ttl_ms"] as? Number)?.toInt() ?: defaults.ttlMs,
                        cacheable = row["cacheable"] as? Boolean ?: defaults.cacheable,
                        timeSensitive = row["time_sensitive"] as? Boolean ?: defaults.timeSensitive,
                        swrGraceMs = (row["swr_grace_ms"] as? Number)?.toLong(),
                        maxParamSize = (row["max_param_size"] as? Number)?.toInt() ?: defaults.maxParamSize
                    )
                } else {
                    tools.add(
                        ToolConfig(
                            name = toolName,
                            version = row["tool_version"] as? String,
                            ttlMs = (row["ttl_ms"] as? Number)?.toInt() ?: defaults.ttlMs,
                            cacheable = row["cacheable"] as? Boolean ?: defaults.cacheable,
                            timeSensitive = row["time_sensitive"] as? Boolean ?: defaults.timeSensitive,
                            swrGraceMs = (row["swr_grace_ms"] as? Number)?.toLong() ?: defaults.swrGraceMs,
                            maxParamSize = (row["max_param_size"] as? Number)?.toInt() ?: defaults.maxParamSize,
                            notes = row["notes"] as? String
                        )
                    )
                }
            }
            return ToolConfigRoot(tools = tools, defaults = defaults)
        }

        private const val QUERY_TOOLS = """
            SELECT tool_name, tool_version, ttl_ms, time_sensitive, cacheable,
                   swr_grace_ms, max_param_size, notes
            FROM mcp_tool_config
            ORDER BY tool_name
        """
    }

    /** Default constructor — empty config, default loader. */
    constructor() : this(ToolConfigRoot(), ToolConfigLoader())

    /**
     * Reload from DB (called by McpBackendWatcher on NOTIFY).
     */
    fun reloadFromDatabase(jdbc: JdbcTemplate) {
        val newRoot = loadFromDatabase(jdbc)
        replaceWith(newRoot)
    }

    /**
     * Resolve effective config for a tool name (per-tool + defaults merge).
     *
     * @return ToolConfig with all fields populated (never null)
     */
    fun resolveEffectiveConfig(toolName: String): ToolConfig {
        return loader.resolveEffectiveConfig(root, toolName)
    }

    /**
     * Snapshot of all loaded tools (read-only).
     */
    fun allTools(): List<ToolConfig> = root.tools

    /**
     * Replace config (hot-reload). Thread-safe via @Volatile.
     *
     * For Day 3.1: mainly for tests. Day 4+ may wire this to
     * `notifications/config_changed` MCP handler.
     */
    fun replaceWith(newRoot: ToolConfigRoot) {
        log.info("ToolConfigResolver reloaded: tools={}, defaults.ttlMs={}",
            newRoot.tools.size, newRoot.defaults.ttlMs)
        root = newRoot
    }

    fun root(): ToolConfigRoot = root
}