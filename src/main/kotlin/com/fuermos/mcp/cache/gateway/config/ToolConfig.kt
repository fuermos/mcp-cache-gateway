package com.fuermos.mcp.cache.gateway.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml

/**
 * Per-tool TTL configuration (loaded from examples/tools.yaml).
 *
 * Day 3 design:
 *   - Parses YAML at gateway startup (or on `notifications/config_changed`)
 *   - Defaults applied to tools missing fields
 *   - `resolveEffectiveConfig(toolName)` merges per-tool + defaults
 *   - Frozen after load (immutable for thread safety)
 *
 * Pattern reference:
 *   - design.md §3.5 (per-tool TTL config — tool metadata fields)
 *   - spec §4 Day 3 afternoon (per-tool TTL config)
 *
 * YAML schema (from examples/tools.yaml):
 * ```yaml
 * tools:
 *   - name: get_weather
 *     version: "1.0.0"
 *     ttlMs: 300000
 *     cacheable: true
 *     timeSensitive: false
 *     swrGraceMs: null
 *     maxParamSize: 10240
 * defaults:
 *   ttlMs: 86400000
 *   cacheable: true
 *   timeSensitive: false
 *   swrGraceMs: null
 *   maxParamSize: 10240
 * ```
 */
@Serializable
data class ToolConfig(
    val name: String,
    val version: String? = null,
    val ttlMs: Int = 86_400_000,
    val cacheable: Boolean = true,
    val timeSensitive: Boolean = false,
    val swrGraceMs: Long? = null,
    val maxParamSize: Int? = 10_240,
    val notes: String? = null
)

@Serializable
data class ToolConfigDefaults(
    val ttlMs: Int = 86_400_000,        // 1 day
    val cacheable: Boolean = true,
    val timeSensitive: Boolean = false,
    val swrGraceMs: Long? = null,        // null = auto = ttlMs * 0.5 (Day 4 SWR)
    val maxParamSize: Int = 10_240
)

@Serializable
data class ToolConfigRoot(
    val tools: List<ToolConfig> = emptyList(),
    val defaults: ToolConfigDefaults = ToolConfigDefaults()
)

/**
 * Loader + accessor for ToolConfig.
 *
 * Loads YAML from file or string. Provides `resolveEffectiveConfig()` that
 * merges per-tool config with defaults (Day 3 design: per-tool wins).
 */
class ToolConfigLoader {

    private val log = LoggerFactory.getLogger(ToolConfigLoader::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    /**
     * Load from a YAML string.
     *
     * SnakeYAML parses to a generic Map; we then transform to typed data class.
     * (Could use kotlinx.serialization YAML plugin but adds a dep — SnakeYAML
     *  is already in build.gradle.kts via spring-boot-starter-webflux.)
     */
    fun loadFromString(yamlContent: String): ToolConfigRoot {
        val parsed = try {
            Yaml().load<Any?>(yamlContent)
        } catch (e: Exception) {
            return ToolConfigRoot()
        }
        @Suppress("UNCHECKED_CAST")
        val raw = parsed as? Map<String, Any?>
            ?: return ToolConfigRoot()
        return fromMap(raw)
    }

    /**
     * Load from a file path.
     */
    fun loadFromFile(path: String): ToolConfigRoot {
        val content = java.io.File(path).readText(Charsets.UTF_8)
        return loadFromString(content)
    }

    /**
     * Resolve effective config for a tool name.
     *
     * Behavior:
     *   - Tool present in YAML → use its fields, fall back to defaults for missing
     *   - Tool NOT present → return a synthetic config from defaults
     *
     * Returns ToolConfig with all fields populated (never null).
     */
    fun resolveEffectiveConfig(root: ToolConfigRoot, toolName: String): ToolConfig {
        val tool = root.tools.firstOrNull { it.name == toolName }
        if (tool != null) {
            return tool.copy(
                ttlMs = tool.ttlMs,  // Already non-null with default
                cacheable = tool.cacheable,
                timeSensitive = tool.timeSensitive,
                swrGraceMs = tool.swrGraceMs ?: root.defaults.swrGraceMs,
                maxParamSize = tool.maxParamSize ?: root.defaults.maxParamSize
            )
        }
        // Unknown tool — return defaults-only config (cacheable=true by default)
        return ToolConfig(
            name = toolName,
            ttlMs = root.defaults.ttlMs,
            cacheable = root.defaults.cacheable,
            timeSensitive = root.defaults.timeSensitive,
            swrGraceMs = root.defaults.swrGraceMs,
            maxParamSize = root.defaults.maxParamSize
        )
    }

    /**
     * Map raw SnakeYAML output (Map<String, Any?>) → typed ToolConfigRoot.
     *
     * SnakeYAML returns:
     *   - numbers as Int / Long / Double
     *   - booleans as Boolean
     *   - strings as String
     *   - nested maps as Map<String, Any?>
     *   - lists as ArrayList<Any?>
     */
    private fun fromMap(raw: Map<String, Any?>): ToolConfigRoot {
        val defaultsMap = raw["defaults"] as? Map<String, Any?> ?: emptyMap()
        val defaults = ToolConfigDefaults(
            ttlMs = (defaultsMap["ttlMs"] as? Int) ?: 86_400_000,
            cacheable = (defaultsMap["cacheable"] as? Boolean) ?: true,
            timeSensitive = (defaultsMap["timeSensitive"] as? Boolean) ?: false,
            swrGraceMs = (defaultsMap["swrGraceMs"] as? Long) ?: (defaultsMap["swrGraceMs"] as? Int)?.toLong(),
            maxParamSize = (defaultsMap["maxParamSize"] as? Int) ?: 10_240
        )

        @Suppress("UNCHECKED_CAST")
        val toolList = (raw["tools"] as? List<Map<String, Any?>>) ?: emptyList()
        val tools = toolList.map { toolMap ->
            ToolConfig(
                name = toolMap["name"] as? String ?: error("tool entry missing 'name'"),
                version = toolMap["version"] as? String,
                ttlMs = (toolMap["ttlMs"] as? Int) ?: defaults.ttlMs,
                cacheable = (toolMap["cacheable"] as? Boolean) ?: defaults.cacheable,
                timeSensitive = (toolMap["timeSensitive"] as? Boolean) ?: defaults.timeSensitive,
                swrGraceMs = (toolMap["swrGraceMs"] as? Long)
                ?: (toolMap["swrGraceMs"] as? Int)?.toLong()
                ?: defaults.swrGraceMs,
                maxParamSize = (toolMap["maxParamSize"] as? Int) ?: defaults.maxParamSize,
                notes = toolMap["notes"] as? String
            )
        }
        return ToolConfigRoot(tools = tools, defaults = defaults)
    }
}
