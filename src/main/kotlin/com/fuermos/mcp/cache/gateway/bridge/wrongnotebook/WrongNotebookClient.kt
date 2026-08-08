package com.fuermos.mcp.cache.gateway.bridge.wrongnotebook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * WrongNotebookClient — HTTP client for wrong-notebook REST API.
 *
 * Day 5 design (per spec §5 + tubi-mcp/wrong-notebook-bridge.js):
 *   - Thin wrapper around java.net.http.HttpClient (no WebClient — Day 5 minimal)
 *   - Auth-injected via Cookie header (from WrongNotebookAuth)
 *   - Returns parsed JsonElement (raw) — gateway orchestrator decides cache/serialize
 *
 * Pattern references (借鉴 tubi-mcp/wrong-notebook-bridge.js):
 *   - _authed(method, path, body, opts, headers) → unified auth wrapper
 *   - _classifyHttp(method, path, res) → HTTP status → throw or pass through
 *   - _attributionHeaders(args) → writtenBy + writtenBySession
 *
 * Day 5 keeps synchronous API; future Day 6+ could move to coroutines + WebClient.
 */
class WrongNotebookClient(
    private val auth: WrongNotebookAuth
) {

    private val log = LoggerFactory.getLogger(WrongNotebookClient::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * GET /api/notebooks — list all notebooks (subjects).
     */
    fun listNotebooks(): JsonElement = httpGet("/api/notebooks")

    /**
     * GET /api/notebooks/:id — fetch a single notebook by id.
     */
    fun getNotebook(id: String): JsonElement {
        require(id.isNotBlank()) { "id must not be blank" }
        return httpGet("/api/notebooks/${urlEncode(id)}")
    }

    /**
     * POST /api/error-items — add a question to a notebook (subject).
     *
     * @param subjectId notebook id (the wrong-notebook API uses 'subject')
     * @param payload question fields (description, errorType, difficulty, etc.)
     */
    fun addQuestion(subjectId: String, payload: JsonElement): JsonElement {
        require(subjectId.isNotBlank()) { "subjectId must not be blank" }
        val body = jsonObjectWith("subject", kotlinx.serialization.json.JsonPrimitive(subjectId))
        val merged = mergeJsonObjects(body, payload)
        return httpPost("/api/error-items", merged.toString())
    }

    /**
     * PUT /api/error-items/:id — update a question by id.
     */
    fun updateQuestion(id: String, payload: JsonElement): JsonElement {
        require(id.isNotBlank()) { "id must not be blank" }
        return httpPut("/api/error-items/${urlEncode(id)}", payload.toString())
    }

    /**
     * DELETE /api/error-items/:id — delete a question by id.
     */
    fun deleteQuestion(id: String): JsonElement {
        require(id.isNotBlank()) { "id must not be blank" }
        return httpDelete("/api/error-items/${urlEncode(id)}")
    }

    // ===== HTTP helpers =====

    private fun httpGet(path: String): JsonElement {
        val req = buildRequest(path, "GET", null).build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return handleResponse("GET", path, resp)
    }

    private fun httpPost(path: String, body: String): JsonElement {
        val req = buildRequest(path, "POST", body).build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return handleResponse("POST", path, resp)
    }

    private fun httpPut(path: String, body: String): JsonElement {
        val req = buildRequest(path, "PUT", body).build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return handleResponse("PUT", path, resp)
    }

    private fun httpDelete(path: String): JsonElement {
        val req = buildRequest(path, "DELETE", null).build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return handleResponse("DELETE", path, resp)
    }

    private fun buildRequest(path: String, method: String, body: String?): HttpRequest.Builder {
        return HttpRequest.newBuilder()
            .uri(URI.create("${auth.baseUrlForRequests()}$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Cookie", auth.getSessionCookie())
            .header("Accept", "application/json")
            .apply {
                when (method) {
                    "GET" -> GET()
                    "POST" -> POST(HttpRequest.BodyPublishers.ofString(body ?: "", StandardCharsets.UTF_8))
                    "PUT" -> PUT(HttpRequest.BodyPublishers.ofString(body ?: "", StandardCharsets.UTF_8))
                    "DELETE" -> DELETE()
                }
                if (body != null && method in setOf("POST", "PUT")) {
                    header("Content-Type", "application/json")
                }
            }
    }

    private fun handleResponse(method: String, path: String, resp: HttpResponse<String>): JsonElement {
        val status = resp.statusCode()
        val body = resp.body()
        if (status == 401) {
            throw WrongNotebookApiException("WRONGNOTEBOOK_HTTP_401", "auth expired or invalid", status, body)
        }
        if (status == 404) {
            throw WrongNotebookApiException("WRONGNOTEBOOK_HTTP_404", "resource not found: $path", status, body)
        }
        if (status in setOf(409, 422)) {
            throw WrongNotebookApiException("WRONGNOTEBOOK_HTTP_$status", "conflict or validation failed: $body", status, body)
        }
        if (status in 500..599) {
            throw WrongNotebookApiException("WRONGNOTEBOOK_HTTP_$status", "server error", status, body)
        }
        if (status !in setOf(200, 201, 204)) {
            throw WrongNotebookApiException("WRONGNOTEBOOK_HTTP_$status", "unexpected status: $status", status, body)
        }
        return if (body.isBlank()) {
            kotlinx.serialization.json.JsonNull
        } else {
            try {
                json.parseToJsonElement(body)
            } catch (e: Exception) {
                log.warn("response not JSON (status={}): {}", status, body.take(200))
                kotlinx.serialization.json.JsonNull
            }
        }
    }

    // ===== JSON helpers =====

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun jsonObjectWith(key: String, value: JsonElement): JsonElement =
        kotlinx.serialization.json.JsonObject(mapOf(key to value))

    private fun mergeJsonObjects(a: JsonElement, b: JsonElement): JsonElement {
        if (a !is kotlinx.serialization.json.JsonObject) return b
        if (b !is kotlinx.serialization.json.JsonObject) return a
        @Suppress("UNCHECKED_CAST")
        val merged = (a.toMap() + b.toMap()) as Map<String, JsonElement>
        return kotlinx.serialization.json.JsonObject(merged)
    }

    /**
     * Extract base URL from auth (helper — auth holds baseUrl).
     */
    private fun WrongNotebookAuth.baseUrlForRequests(): String = baseUrl
}

/**
 * API exception — thrown when wrong-notebook returns non-2xx or auth fails.
 */
class WrongNotebookApiException(
    val code: String,
    message: String,
    val httpStatus: Int,
    val body: String
) : RuntimeException(message)