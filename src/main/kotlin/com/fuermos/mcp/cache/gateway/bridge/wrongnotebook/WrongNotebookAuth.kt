package com.fuermos.mcp.cache.gateway.bridge.wrongnotebook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * WrongNotebookAuth — NextAuth.js credentials auto-login + cookie persistence.
 *
 * Day 5 design (per spec §4 Day 5 morning + tubi-mcp/wrong-notebook-bridge.js):
 *   - Fetch CSRF token from /api/auth/csrf (GET)
 *   - POST credentials to /api/auth/callback/credentials with CSRF token
 *   - Capture session cookie from response
 *   - Persist cookie + cookie name to ~/.openclaw/state/wrongnotebook-credentials.json
 *   - Re-login when cookie expires (NextAuth default 30 days; we re-login at 7 days)
 *
 * Pattern references (借鉴 tubi-mcp/wrong-notebook-bridge.js):
 *   - Line 137-145: _requestRaw GET /api/auth/csrf
 *   - Line 250-280: NextAuth credentials signin flow
 *   - Line 145-160: cookie persistence to local JSON file
 *
 * Day 5 keeps it synchronous (no coroutines yet for auth flow).
 * Future (Day 6+): could move to coroutines + WebClient.
 */
class WrongNotebookAuth(
    val baseUrl: String,
    private val username: String,
    private val password: String,
    private val credentialsPath: Path = DEFAULT_CREDENTIALS_PATH
) {

    private val log = LoggerFactory.getLogger(WrongNotebookAuth::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var sessionCookie: String? = null

    @Volatile
    private var sessionCookieName: String? = null

    @Volatile
    private var obtainedAtMs: Long = 0

    init {
        // Try to load existing credentials from disk
        runCatching { loadFromDisk() }
            .onFailure { log.debug("no existing credentials file: {}", it.message) }
    }

    /**
     * Get the current session cookie (auto-login if missing or expired).
     */
    fun getSessionCookie(): String {
        if (isCookieValid()) return sessionCookie!!
        login()
        return sessionCookie!!
    }

    /**
     * Force re-login (useful for testing + auth refresh).
     */
    fun login() {
        log.info("logging in as {} to {}", username, baseUrl)
        // Step 1: fetch CSRF token + initial cookie
        val csrfResp = httpGet("/api/auth/csrf", null)
        if (csrfResp.statusCode() != 200) {
            throw WrongNotebookAuthException("csrf GET failed: HTTP ${csrfResp.statusCode()}")
        }
        val csrfBody = json.parseToJsonElement(csrfResp.body()).jsonObject
        val csrfToken = csrfBody["csrfToken"]?.jsonPrimitive?.contentOrNull
            ?: throw WrongNotebookAuthException("csrf response missing csrfToken")
        // Capture initial cookie (NextAuth sets csrf-token or similar)
        val initialCookie = extractSetCookie(csrfResp)

        // Step 2: POST credentials with CSRF token
        val formBody = "csrfToken=$csrfToken&username=${urlEncode(username)}&password=${urlEncode(password)}&redirect=false&json=true"
        val baseHeaders = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "application/json"
        )
        val headers: Map<String, String> = if (initialCookie != null) {
            baseHeaders + ("Cookie" to initialCookie)
        } else {
            baseHeaders
        }
        val signinResp = httpPost("/api/auth/callback/credentials", formBody, headers)
        if (signinResp.statusCode() !in setOf(200, 302)) {
            throw WrongNotebookAuthException("signin failed: HTTP ${signinResp.statusCode()}")
        }

        // Capture session cookie from Set-Cookie header (or any auth-like cookie)
        val authCookie = extractSetCookie(signinResp) ?: initialCookie
            ?: throw WrongNotebookAuthException("no auth cookie received from signin")

        sessionCookie = authCookie
        sessionCookieName = extractCookieName(authCookie)
        obtainedAtMs = System.currentTimeMillis()
        log.info("login successful: cookieName={}, cookieLength={}",
            sessionCookieName, authCookie.length)
        saveToDisk()
    }

    /**
     * Check if cookie is still valid (or close to expiry).
     */
    private fun isCookieValid(): Boolean {
        val cookie = sessionCookie ?: return false
        if (cookie.isEmpty()) return false
        // Re-login at 7 days (NextAuth default is 30 days)
        val ageMs = System.currentTimeMillis() - obtainedAtMs
        return ageMs < 7L * 24 * 3600 * 1000
    }

    /**
     * Build a Cookie header from raw Set-Cookie value.
     */
    private fun extractSetCookie(resp: HttpResponse<String>): String? {
        val setCookieHeader = resp.headers().firstValue("set-cookie").orElse(null) ?: return null
        // Take first cookie (split on comma if multiple cookies present)
        return setCookieHeader.split(",").firstOrNull()?.let { raw ->
            // Strip metadata after first `;` (e.g. "Path=/; HttpOnly")
            raw.substringBefore(";").trim()
        }
    }

    /**
     * Extract cookie name from raw cookie string ("name=value" → "name").
     */
    private fun extractCookieName(rawCookie: String): String? =
        rawCookie.substringBefore("=", missingDelimiterValue = "").takeIf { it.isNotEmpty() }

    /**
     * URL-encode (form encoding).
     */
    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)

    /**
     * HTTP GET helper.
     */
    private fun httpGet(path: String, extraHeaders: Map<String, String>?): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .apply {
                extraHeaders?.forEach { (k, v) -> header(k, v) }
                header("Accept", "application/json")
            }
            .build()
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }

    /**
     * HTTP POST helper.
     */
    private fun httpPost(path: String, body: String, extraHeaders: Map<String, String>?): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .apply {
                extraHeaders?.forEach { (k, v) -> header(k, v) }
            }
            .build()
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }

    // ===== Persistence =====

    @Serializable
    private data class CredentialsFile(
        val cookie: String,
        val cookieName: String? = null,
        val obtainedAt: Long = System.currentTimeMillis(),
        val user: String = ""
    )

    private fun saveToDisk() {
        runCatching {
            val data = CredentialsFile(
                cookie = sessionCookie!!,
                cookieName = sessionCookieName,
                obtainedAt = obtainedAtMs,
                user = username
            )
            credentialsPath.parent?.let { Files.createDirectories(it) }
            Files.writeString(
                credentialsPath,
                json.encodeToString(CredentialsFile.serializer(), data)
            )
            // chmod 600 (owner read/write only)
            runCatching {
                val perms = java.util.HashSet<java.nio.file.attribute.PosixFilePermission>()
                perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ)
                perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
                Files.setPosixFilePermissions(credentialsPath, perms)
            }
            log.info("credentials persisted to {}", credentialsPath)
        }.onFailure { log.warn("save credentials failed: {}", it.message) }
    }

    private fun loadFromDisk() {
        if (!Files.exists(credentialsPath)) {
            throw WrongNotebookAuthException("credentials file does not exist: $credentialsPath")
        }
        val data = json.decodeFromString(
            CredentialsFile.serializer(),
            Files.readString(credentialsPath, StandardCharsets.UTF_8)
        )
        sessionCookie = data.cookie
        sessionCookieName = data.cookieName
        obtainedAtMs = data.obtainedAt
        log.info("credentials loaded: user={}, obtainedAt={}", data.user, data.obtainedAt)
        // Verify still valid
        if (!isCookieValid()) {
            log.info("loaded credentials expired, will re-login")
            sessionCookie = null
        }
    }

    companion object {
        /**
         * Default path for credential persistence.
         * ~/.openclaw/state/wrongnotebook-credentials.json (chmod 600)
         */
        val DEFAULT_CREDENTIALS_PATH: Path =
            Paths.get(System.getProperty("user.home"), ".openclaw", "state", "wrongnotebook-credentials.json")

        const val DEFAULT_BASE_URL = "http://localhost:3032"
    }
}

/**
 * Auth exception — thrown when login fails or credentials invalid.
 */
class WrongNotebookAuthException(message: String) : RuntimeException(message)
