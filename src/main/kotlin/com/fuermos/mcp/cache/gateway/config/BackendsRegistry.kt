package com.fuermos.mcp.cache.gateway.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Paths

/**
 * BackendConfig — runtime representation of one MCP backend (loaded from DB).
 *
 * Phase 2.1 design (per spec §4 Phase 2 2.1):
 *   - DB-driven (mcp_backend + mcp_backend_env tables)
 *   - DB backup fallback (primary down → try backup; both down → throw)
 *   - Fail fast on startup (主+backup 都 down = gateway 拒绝服务)
 *   - secrets via secret_ref (file:/path/to/file#KEY or literal:VALUE for dev)
 *
 * Pattern reference:
 *   - 借鉴 design.md §3.1 (架构 — Spring Boot manages backend lifecycle)
 *   - 借鉴 examples/servers.yaml (Phase 1 server registry schema, deprecated)
 *   - 借鉴 tubi-mcp/wrong-notebook-bridge.js (env var substitution)
 */
data class BackendConfig(
    val name: String,
    val displayName: String,
    val enabled: Boolean,
    val cmd: String,
    val args: List<String>,
    val cwd: String?,
    val spawnTimeoutMs: Long,
    val idleTimeoutMs: Long,
    val maxRestarts: Int,
    val eager: Boolean,
    val protocol: String,
    val env: Map<String, String>,  // resolved env vars (plaintext + secret_ref resolved)
    val version: Int,
    val notes: String? = null
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse args JSON string (e.g. '["-jar", "app.jar"]') to List<String>.
         */
        fun parseArgs(argsJson: String): List<String> {
            return runCatching {
                val parsed = json.parseToJsonElement(argsJson)
                if (parsed is JsonArray) {
                    parsed.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                } else emptyList()
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * BackendsRegistry — loads backend configs from DB with backup fallback.
 *
 * Phase 2.1 design:
 *   - loadBackends() → list of enabled BackendConfig
 *   - Primary DataSource: main DB (default)
 *   - Backup DataSource (optional): streaming replica OR pg_dump restore
 *   - On loadBackends() call:
 *     1. Try primary (SELECT FROM mcp_backend WHERE enabled = TRUE)
 *     2. If primary fails (Connection refused, etc.) AND backup configured → try backup
 *     3. If both fail → throw BackendsRegistryException (fail fast)
 *
 * Secret resolution:
 *   - env_value: use directly
 *   - secret_ref: parse + load from file or environment
 *     - file:/path/to/file#KEY: read KEY from file (chmod 600)
 *     - literal:VALUE: use directly (dev only, NOT for production)
 *
 * Audit integration (Day 2.3 McpBackendWatcher):
 *   - LISTEN/NOTIFY channel 'mcp_backend_changed' triggers reload
 *   - Watcher calls reload() which re-queries DB
 *
 * Pattern references:
 *   - 借鉴 Spring JdbcTemplate (no JPA — direct SQL)
 *   - 借鉴 secret-ref pattern from Kubernetes/HashiCorp Vault (simplified)
 */
class BackendsRegistry(
    private val primaryJdbc: JdbcTemplate,
    private val backupJdbc: JdbcTemplate? = null,
    private val secretResolver: SecretRefResolver = DefaultSecretRefResolver
) {

    private val log = LoggerFactory.getLogger(BackendsRegistry::class.java)

    /**
     * In-memory cache of loaded backends.
     * Refreshed via reload() (called by McpBackendWatcher on NOTIFY).
     */
    @Volatile
    private var cached: List<BackendConfig> = emptyList()

    /**
     * Test-only constructor — pre-populates cache, skips DB.
     * Use for unit tests that don't need real DB.
     */
    constructor(preloadedBackends: List<BackendConfig>) : this(
        primaryJdbc = JdbcTemplate()
    ) {
        this.cached = preloadedBackends
    }

    /**
     * Load all enabled backends. Tries primary, falls back to backup on failure.
     */
    fun loadBackends(): List<BackendConfig> {
        // Try primary
        val fromPrimary = runCatching { loadFromJdbc(primaryJdbc) }
        if (fromPrimary.isSuccess) {
            val list = fromPrimary.getOrThrow()
            log.info("loaded {} backends from primary DB", list.size)
            cached = list
            return list
        }

        val primaryErr = fromPrimary.exceptionOrNull()
        log.warn("primary DB load failed: {} — trying backup", primaryErr?.message)

        // Try backup
        if (backupJdbc != null) {
            val backup: JdbcTemplate = backupJdbc
            val fromBackup = runCatching { loadFromJdbc(backup) }
            if (fromBackup.isSuccess) {
                val list = fromBackup.getOrThrow()
                log.warn("loaded {} backends from BACKUP DB (primary down)", list.size)
                cached = list
                return list
            }
            val backupErr = fromBackup.exceptionOrNull()
            log.error("backup DB load also failed: {}", backupErr?.message)
            throw BackendsRegistryException(
                "both primary and backup DB unavailable: primary=${primaryErr?.message}, backup=${backupErr?.message}",
                primaryErr
            )
        }

        // No backup — fail fast
        throw BackendsRegistryException("primary DB unavailable and no backup configured", primaryErr)
    }

    /**
     * Reload (called by McpBackendWatcher on NOTIFY).
     * Returns the new count of backends (for metrics).
     */
    fun reload(): Int = loadBackends().size

    /**
     * Get current cached backends (without DB query).
     * Returns empty list if not loaded yet — caller should call loadBackends() first.
     */
    fun cachedBackends(): List<BackendConfig> = cached

    /**
     * Load backends from a specific JdbcTemplate (used by both primary and backup paths).
     */
    private fun loadFromJdbc(jdbc: JdbcTemplate): List<BackendConfig> {
        // Query 1: enabled backends
        val rows = jdbc.queryForList(QUERY_BACKENDS)
        if (rows.isEmpty()) return emptyList()

        // Query 2: env vars for all backends (batch)
        val envMap = loadAllEnvVars(jdbc)

        return rows.map { row ->
            val name = row["name"] as String
            val argsJson = row["args"] as? String ?: "[]"
            val envVars = envMap[name].orEmpty()
            BackendConfig(
                name = name,
                displayName = row["display_name"] as String,
                enabled = row["enabled"] as Boolean,
                cmd = row["cmd"] as String,
                args = BackendConfig.parseArgs(argsJson),
                cwd = row["cwd"] as? String,
                spawnTimeoutMs = (row["spawn_timeout_ms"] as Number).toLong(),
                idleTimeoutMs = (row["idle_timeout_ms"] as Number).toLong(),
                maxRestarts = (row["max_restarts"] as Number).toInt(),
                eager = row["eager"] as Boolean,
                protocol = row["protocol"] as String,
                env = envVars,
                version = (row["version"] as Number).toInt(),
                notes = row["notes"] as? String
            )
        }
    }

    /**
     * Load all env vars for all backends in one query.
     * Returns: Map<backendName, Map<envKey, resolvedValue>>
     */
    @Suppress("UNUSED_PARAMETER")
    private fun loadAllEnvVars(jdbc: JdbcTemplate): Map<String, Map<String, String>> {
        val rows = jdbc.queryForList(QUERY_ALL_ENV)
        val result = mutableMapOf<String, MutableMap<String, String>>()
        for (row in rows) {
            val backend = row["backend_name"] as String
            val key = row["env_key"] as String
            val value = when {
                row["env_value"] != null -> row["env_value"] as String
                row["secret_ref"] != null -> {
                    val ref = row["secret_ref"] as String
                    runCatching { secretResolver.resolve(ref) }
                        .onFailure {
                            log.error("failed to resolve secret_ref for {}.{}: {}", backend, key, it.message)
                            throw BackendsRegistryException(
                                "secret resolution failed for $backend.$key: ${it.message}", it
                            )
                        }
                        .getOrThrow()
                }
                else -> {
                    log.warn("env entry {}.{} has neither value nor secret_ref — skipping", backend, key)
                    continue
                }
            }
            result.getOrPut(backend) { mutableMapOf() }[key] = value
        }
        return result
    }

    companion object {
        private const val QUERY_BACKENDS = """
            SELECT name, display_name, enabled, cmd, args, cwd,
                   spawn_timeout_ms, idle_timeout_ms, max_restarts, eager, protocol,
                   version, notes
            FROM mcp_backend
            WHERE enabled = TRUE
            ORDER BY name
        """

        private const val QUERY_ALL_ENV = """
            SELECT backend_name, env_key, env_value, secret_ref, is_secret
            FROM mcp_backend_env
            ORDER BY backend_name, env_key
        """
    }
}

/**
 * Exception thrown when both primary and backup DB are unavailable.
 */
class BackendsRegistryException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * SecretRefResolver — resolves secret_ref pointers to actual values.
 *
 * Supported formats:
 *   - file:/absolute/path#KEY_NAME → read KEY_NAME=value line from file
 *   - literal:VALUE → return VALUE directly (dev/test only)
 *
 * File format expected (chmod 600):
 *   KEY_NAME=value
 *   OTHER_KEY=other_value
 */
interface SecretRefResolver {
    fun resolve(secretRef: String): String
}

/**
 * Default SecretRefResolver — parses file:/path#KEY format.
 */
object DefaultSecretRefResolver : SecretRefResolver {
    private val log = LoggerFactory.getLogger(DefaultSecretRefResolver::class.java)

    override fun resolve(secretRef: String): String {
        return when {
            secretRef.startsWith("file:") -> resolveFile(secretRef)
            secretRef.startsWith("literal:") -> {
                val value = secretRef.substringAfter("literal:")
                log.warn("using literal secret_ref — only acceptable for dev/test!")
                value
            }
            else -> throw IllegalArgumentException(
                "unsupported secret_ref format: $secretRef (expected 'file:...' or 'literal:...')"
            )
        }
    }

    private fun resolveFile(secretRef: String): String {
        // Parse "file:/path/to/file#KEY_NAME"
        val withoutPrefix = secretRef.removePrefix("file:")
        val hashIdx = withoutPrefix.indexOf('#')
        if (hashIdx < 0) {
            throw IllegalArgumentException(
                "file: secret_ref must include '#KEY_NAME': $secretRef"
            )
        }
        val path = withoutPrefix.substring(0, hashIdx)
        val key = withoutPrefix.substring(hashIdx + 1)

        val file = Paths.get(path)
        if (!Files.exists(file)) {
            throw IllegalStateException("secret file does not exist: $path")
        }

        // Read file line by line, find matching KEY=value
        Files.newBufferedReader(file).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx < 0) continue
                val lineKey = trimmed.substring(0, eqIdx).trim()
                if (lineKey == key) {
                    return trimmed.substring(eqIdx + 1).trim()
                }
            }
        }
        throw IllegalStateException("key '$key' not found in secret file: $path")
    }
}
