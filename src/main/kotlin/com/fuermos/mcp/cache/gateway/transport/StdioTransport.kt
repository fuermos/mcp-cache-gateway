package com.fuermos.mcp.cache.gateway.transport

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * stdio transport for JSON-RPC 2.0 messages.
 *
 * Wire format: newline-delimited JSON (NDJSON). Each line is one complete
 * JSON-RPC message (request / response / notification).
 *
 * Pattern reference (借鉴 tubi-mcp/mcp-cache-proxy.js):
 *   - Read one line at a time (BufferedReader.readLine)
 *   - Skip empty lines (e.g. heartbeat newlines)
 *   - Try to parse as JSON; on failure return null + log (don't crash the gateway)
 *   - Dispatch based on field presence:
 *       has "method" + has "id"     → JsonRpcRequest
 *       has "result" or "error"     → JsonRpcResponse (id required)
 *       has "method" only           → JsonRpcNotification
 *   - Write messages as single-line JSON + "\n" to stdout
 *
 * Design choice (2026-08-08 shrek):
 *   - Synchronous (blocking) read/write — gateway is single-threaded for stdio
 *     (see spec §1.2, only 1 client per stdio server)
 *   - Use kotlinx.serialization JsonElement (not Map<String,Any?>) for parsing
 *     because downstream cache key normalization needs raw JSON access
 *   - logger goes to STDERR (not stdout) — stdout is JSON-RPC wire
 *
 * Thread safety: NOT thread-safe. Wrap with Mutex if used from multiple coroutines.
 */
class StdioTransport(
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
    private val err: PrintStream = System.err
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(StdioTransport::class.java)

    private val reader: BufferedReader = BufferedReader(
        InputStreamReader(input, StandardCharsets.UTF_8)
    )

    // Use lenient JSON: accept unknown fields, allow numbers/strings for ids,
    // do NOT enforce strict JSON-RPC shape (we validate that ourselves below).
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Read one JSON-RPC message from stdin.
     *
     * Returns null on EOF or unparseable line (logs warning). Callers should
     * loop until null and treat null as end-of-stream.
     */
    fun readMessage(): JsonRpcMessage? {
        val line = reader.readLine() ?: return null  // EOF
        if (line.isBlank()) return null  // skip empty / heartbeat lines

        return try {
            parseMessage(line)
        } catch (e: SerializationException) {
            log.warn("[StdioTransport] malformed JSON line ({} bytes): {}",
                line.length, line.take(200))
            null
        } catch (e: IllegalArgumentException) {
            log.warn("[StdioTransport] invalid JSON-RPC message: {}", e.message)
            null
        }
    }

    /**
     * Write one JSON-RPC message to stdout (as single-line JSON + newline).
     */
    fun writeMessage(message: JsonRpcMessage) {
        val jsonStr = when (message) {
            is JsonRpcRequest -> json.encodeToString(message)
            is JsonRpcResponse -> json.encodeToString(message)
            is JsonRpcNotification -> json.encodeToString(message)
        }
        // Stdout is wire-format — never use println (adds platform line separator)
        val bytes = (jsonStr + "\n").toByteArray(StandardCharsets.UTF_8)
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    /**
     * Dispatch on JSON shape: extract id/method/result/error and route.
     */
    private fun parseMessage(line: String): JsonRpcMessage {
        val obj: JsonObject = json.parseToJsonElement(line).jsonObject

        // Common fields
        val rawId: JsonPrimitive? = obj["id"] as? JsonPrimitive
        val idStr: String? = rawId?.let { primitive ->
            primitive.contentOrNull ?: primitive.intOrNull?.toString()
        }

        val method: String? = (obj["method"] as? JsonPrimitive)?.contentOrNull

        // Case 1: Response — has result OR error, no method, id present
        if (method == null && idStr != null && ("result" in obj || "error" in obj)) {
            val response = json.decodeFromString(JsonRpcResponse.serializer(), line)
            return response
        }

        // Case 2: Notification — has method, no id
        if (method != null && idStr == null) {
            return json.decodeFromString(JsonRpcNotification.serializer(), line)
        }

        // Case 3: Request — has method + id
        if (method != null && idStr != null) {
            return json.decodeFromString(JsonRpcRequest.serializer(), line)
        }

        throw IllegalArgumentException(
            "JSON-RPC message must have (method+id) | (method) | (result|error+id); got: ${line.take(200)}"
        )
    }

    /**
     * Send a low-level parse error response (no incoming id — JSON-RPC §5.1).
     */
    fun writeParseError(detail: String? = null) {
        val error = JsonRpcError(
            code = JsonRpcResponse.ERR_PARSE_ERROR,
            message = "Parse error",
            data = detail?.let { JsonPrimitive(it) }
        )
        // Per spec, parse errors have id=null
        val response = JsonRpcResponse(
            id = null,
            error = error
        )
        writeMessage(response)
    }

    override fun close() {
        runCatching { reader.close() }
        runCatching { output.flush() }
    }
}
