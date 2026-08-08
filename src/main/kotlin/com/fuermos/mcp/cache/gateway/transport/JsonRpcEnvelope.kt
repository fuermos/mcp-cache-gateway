package com.fuermos.mcp.cache.gateway.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON-RPC 2.0 message types.
 *
 * Wire format reference:
 *   https://www.jsonrpc.org/specification
 *   https://modelcontextprotocol.io/specification (MCP uses JSON-RPC 2.0)
 *
 * Three message shapes:
 *   - Request:       has id (non-null) + method + params
 *   - Response:      has id + result OR error
 *   - Notification:  has method (no id, fire-and-forget)
 *
 * Design choice (2026-08-08 shrek):
 *   - `id` is String in this codebase (not Int) — UUID v7 (see RequestId.kt)
 *   - Sealed interface + @Serializable allows kotlinx.serialization to round-trip
 *     any message without manual parsing
 *   - `params` is JsonElement (not Map<String,Any?>) — preserves raw JSON for
 *     downstream normalization in CacheKey.kt
 */
@Serializable
sealed interface JsonRpcMessage

/**
 * JSON-RPC 2.0 request (client → server or server → client).
 *
 * `id` MUST be non-null for a request. JSON-RPC 2.0 allows number | string | null,
 * but we use String only (UUID v7) for first-class idempotency (see design.md §3.2).
 */
@Serializable
data class JsonRpcRequest(
    val id: RequestId,
    val method: String,
    val params: JsonElement? = null,
    val jsonrpc: String = "2.0"
) : JsonRpcMessage

/**
 * JSON-RPC 2.0 response (success). Exactly one of result OR error MUST be set.
 * We enforce via `error` defaulting to null and helper methods (see Companion).
 */
@Serializable
data class JsonRpcResponse(
    val id: RequestId? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    val jsonrpc: String = "2.0"
) : JsonRpcMessage {
    val isSuccess: Boolean get() = error == null
    val isError: Boolean get() = error != null

    companion object {
        /** Build a success response. */
        fun success(id: RequestId?, result: JsonElement): JsonRpcResponse =
            JsonRpcResponse(id = id, result = result)

        /** Build an error response from a JsonRpcError. */
        fun failure(id: RequestId?, error: JsonRpcError): JsonRpcResponse =
            JsonRpcResponse(id = id, error = error)

        /** Convenience: standard JSON-RPC error codes. */
        const val ERR_PARSE_ERROR = -32700
        const val ERR_INVALID_REQUEST = -32600
        const val ERR_METHOD_NOT_FOUND = -32601
        const val ERR_INVALID_PARAMS = -32602
        const val ERR_INTERNAL_ERROR = -32603

        /** Server-defined range: -32000 to -32099. */
        const val ERR_SERVER_ERROR_BASE = -32000
    }
}

/**
 * JSON-RPC 2.0 notification (fire-and-forget, no response expected).
 *
 * `id` is omitted entirely (not null). Receiving side MUST NOT reply.
 */
@Serializable
data class JsonRpcNotification(
    val method: String,
    val params: JsonElement? = null,
    val jsonrpc: String = "2.0"
) : JsonRpcMessage

/**
 * JSON-RPC 2.0 error object.
 *
 * Standard codes per spec §5.1:
 *   -32700 Parse error      Invalid JSON
 *   -32600 Invalid request  Not a valid Request object
 *   -32601 Method not found
 *   -32602 Invalid params
 *   -32603 Internal error
 *   -32000 to -32099 Server error (implementation-defined)
 *
 * `data` is optional, structure defined by server.
 */
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/**
 * Type-safe alias for JSON-RPC request id. Always a String (UUID v7).
 *
 * Note: per JSON-RPC 2.0 spec, id can also be number or null, but we standardize
 * on String for distributed-system idempotency (see RequestId.kt + design.md §3.2).
 */
typealias RequestId = String
