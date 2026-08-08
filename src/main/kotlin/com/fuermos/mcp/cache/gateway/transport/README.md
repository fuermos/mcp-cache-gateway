# Transport Module

JSON-RPC 2.0 over stdio (line-delimited NDJSON).

## Files

### `JsonRpcEnvelope.kt`
Data classes for JSON-RPC 2.0:
- `JsonRpcRequest` (id, method, params, jsonrpc)
- `JsonRpcResponse` (id nullable, result, error, jsonrpc)
- `JsonRpcNotification` (no id, fire-and-forget)
- `JsonRpcError` (code, message, data)

Typealias `RequestId = String` (UUID v7).

Standard error codes:
- `-32700` Parse error
- `-32600` Invalid request
- `-32601` Method not found
- `-32602` Invalid params
- `-32603` Internal error
- `-32000..-32099` Server-defined errors

### `RequestId.kt`
UUID v7 generation + validation:
- `generate()` → `timeBasedEpochGenerator().generate().toString()`
- `isValid(id)` → matches UUID v7 pattern

### `StdioTransport.kt`
Synchronous line-delimited JSON-RPC over stdio:
- `readMessage()` → blocks on next newline, parses JSON-RPC message
- `writeMessage(msg)` → writes single-line JSON + '\n'
- `writeParseError(detail)` → emits `-32700` parse error with `id=null`

Thread-safe: NOT thread-safe. Wrap with Mutex if used from multiple coroutines.

## Usage

```kotlin
val transport = StdioTransport(System.`in`, System.out)
while (true) {
    val message = transport.readMessage() ?: break
    when (message) {
        is JsonRpcRequest -> handleRequest(message)
        is JsonRpcResponse -> handleResponse(message)
        is JsonRpcNotification -> handleNotification(message)
    }
}
```

## Pattern References

- `orchestrator/McpMethodRouter.kt` — method dispatch
- `orchestrator/GatewayOrchestrator.kt` — main request loop
- [JSON-RPC 2.0 spec](https://www.jsonrpc.org/specification)
- `tubi-mcp/mcp-cache-proxy.js` — original inspiration

## KDoc Caveat

**Avoid `/*` sequences inside KDoc!** Kotlin lexer treats `/*` inside KDoc as nested comment start. Use `/{star}` or escape `*` in literal strings.

```kotlin
// ❌ BAD: triggers nested comment
/** Match notifications/<star> tools */

// ✅ GOOD: use different notation
/** Match notifications/{star} tools */
```
