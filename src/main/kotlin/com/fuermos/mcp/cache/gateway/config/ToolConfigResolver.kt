package com.fuermos.mcp.cache.gateway.config

import org.slf4j.LoggerFactory

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
    }

    /** Default constructor — empty config, default loader. */
    constructor() : this(ToolConfigRoot(), ToolConfigLoader())

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