package com.fuermos.mcp.cache.gateway.http

import com.fuermos.mcp.cache.gateway.transport.JsonRpcError
import com.fuermos.mcp.cache.gateway.transport.JsonRpcRequest
import com.fuermos.mcp.cache.gateway.transport.JsonRpcResponse
import kotlinx.coroutines.reactor.mono
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * McpHttpController — REST endpoints exposing MCP Streamable HTTP transport.
 *
 * Phase 3 (per skill-master 8/9 15:12 CST directive):
 *   - POST /mcp/tools/list → aggregateTools (returns { tools: [...] })
 *   - POST /mcp/tools/call → routeCall (returns { result } or { error })
 *
 * Both endpoints accept JSON-RPC 2.0 wrapped requests:
 *   { "jsonrpc": "2.0", "id": "...", "method": "tools/list|tools/call", "params": {...} }
 *
 * Response: application/json with JSON-RPC 2.0 envelope.
 *
 * Implementation note (Phase 3 fix):
 *   - Spring WebFlux 6.1 + Kotlin suspend fun: continuation NPE when suspending
 *     through chained suspend functions (StreamableHttpHandler → GeneralProxy).
 *   - Fix: wrap in `mono { ... }` from kotlinx-coroutines-reactor — provides
 *     explicit CoroutineContext with ReactorDispatcher, avoids null context
 *     when Spring's auto-suspend wrapping doesn't propagate properly.
 *
 * Pattern reference:
 *   - MCP 2025-03-26 spec §transports (HTTP POST + JSON-RPC body)
 *   - design.md §1.2 (Streamable HTTP transport)
 *   - kotlinx-coroutines-reactor `mono` builder (https://github.com/Kotlin/kotlinx.coroutines/tree/master/reactor)
 */
@RestController
class McpHttpController(
    private val handler: StreamableHttpHandler
) {

    private val log = LoggerFactory.getLogger(McpHttpController::class.java)

    @PostMapping(
        value = ["/mcp/tools/list"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun listTools(@RequestBody body: String): Mono<ResponseEntity<String>> = mono {
        val request = parseRequest(body) ?: return@mono parseErrorResponse(null)
        val response = handler.handleToolsList(request)
        jsonResponse(response)
    }

    @PostMapping(
        value = ["/mcp/tools/call"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun callTool(@RequestBody body: String): Mono<ResponseEntity<String>> = mono {
        val request = parseRequest(body) ?: return@mono parseErrorResponse(null)
        val response = handler.handleToolsCall(request)
        jsonResponse(response)
    }

    /**
     * Parse JSON-RPC request from raw body string.
     * Returns null on parse error (caller should respond with -32700).
     */
    private fun parseRequest(body: String): JsonRpcRequest? {
        return try {
            handler.decode(body)
        } catch (e: SerializationException) {
            log.warn("JSON parse error: {}", e.message)
            null
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid JSON-RPC request: {}", e.message)
            null
        }
    }

    /**
     * Build a parse error response (-32700) for malformed input.
     */
    private fun parseErrorResponse(id: String?): ResponseEntity<String> {
        val response = JsonRpcResponse.failure(
            id = id,
            error = JsonRpcError(
                code = JsonRpcResponse.ERR_PARSE_ERROR,
                message = "Parse error: malformed JSON-RPC request"
            )
        )
        return jsonResponse(response)
    }

    /**
     * Wrap a JsonRpcResponse in a ResponseEntity with application/json content type.
     */
    private fun jsonResponse(response: JsonRpcResponse): ResponseEntity<String> {
        return try {
            val body = handler.encode(response)
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
        } catch (e: Exception) {
            log.error("Failed to encode response: {}", e.message, e)
            ResponseEntity.internalServerError()
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"Internal error: ${escapeJson(e.message ?: "unknown")}"}}""")
        }
    }

    /**
     * Escape a string for safe embedding in JSON.
     */
    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}