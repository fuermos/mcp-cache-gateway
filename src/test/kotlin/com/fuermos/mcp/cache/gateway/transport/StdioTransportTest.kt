package com.fuermos.mcp.cache.gateway.transport

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for StdioTransport — line-delimited JSON parsing + serialization.
 *
 * Coverage:
 *   - Parse valid request / response / notification
 *   - Round-trip write then read
 *   - Skip blank lines (heartbeats)
 *   - Return null on EOF
 *   - Return null on malformed JSON (with log warn)
 *   - writeMessage adds newline terminator
 *   - writeParseError emits id=null response (per JSON-RPC §5.1)
 *   - Invalid message shape throws IllegalArgumentException
 */
class StdioTransportTest {

    private lateinit var out: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream

    @BeforeEach
    fun setUp() {
        // Capture stderr to keep test output clean
        originalOut = System.err
        System.setErr(PrintStream(ByteArrayOutputStream()))
        // Allocate output buffer (one per test for isolation)
        out = ByteArrayOutputStream()
    }

    @AfterEach
    fun tearDown() {
        System.setErr(originalOut)
    }

    private fun makeTransport(input: String): StdioTransport {
        val inStream = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8))
        return StdioTransport(input = inStream, output = out)
    }

    @Test
    fun `reads valid JSON-RPC request from stdin`() {
        val input = """{"jsonrpc":"2.0","id":"req-1","method":"tools/list"}""" + "\n"
        val transport = makeTransport(input)

        val msg = transport.readMessage()
        assertNotNull(msg)
        val req = msg as JsonRpcRequest
        assertEquals("req-1", req.id)
        assertEquals("tools/list", req.method)
        assertNull(req.params)
    }

    @Test
    fun `reads JSON-RPC response with result`() {
        val input = """{"jsonrpc":"2.0","id":"req-1","result":{"tools":[]}}""" + "\n"
        val transport = makeTransport(input)

        val msg = transport.readMessage()
        assertNotNull(msg)
        val resp = msg as JsonRpcResponse
        assertEquals("req-1", resp.id)
        assertTrue(resp.isSuccess)
        assertNull(resp.error)
    }

    @Test
    fun `reads JSON-RPC response with error`() {
        val input = """{"jsonrpc":"2.0","id":"req-2","error":{"code":-32601,"message":"not found"}}""" + "\n"
        val transport = makeTransport(input)

        val msg = transport.readMessage()
        assertNotNull(msg)
        val resp = msg as JsonRpcResponse
        assertTrue(resp.isError)
        assertEquals(-32601, resp.error!!.code)
        assertEquals("not found", resp.error!!.message)
    }

    @Test
    fun `reads JSON-RPC notification (no id)`() {
        val input = """{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n"
        val transport = makeTransport(input)

        val msg = transport.readMessage()
        assertNotNull(msg)
        msg as JsonRpcNotification
    }

    @Test
    fun `reads multiple messages in sequence`() {
        val input = """
            {"jsonrpc":"2.0","id":"a","method":"ping"}
            {"jsonrpc":"2.0","id":"b","method":"ping"}
            {"jsonrpc":"2.0","id":"c","method":"ping"}
        """.trimIndent() + "\n"
        val transport = makeTransport(input)

        val ids = (1..3).map {
            val m = transport.readMessage()
            assertNotNull(m)
            (m as JsonRpcRequest).id
        }
        assertEquals(listOf("a", "b", "c"), ids)
        // EOF
        assertNull(transport.readMessage())
    }

    @Test
    fun `skips blank lines (heartbeats)`() {
        val input = "\n\n  \n" + """{"jsonrpc":"2.0","id":"x","method":"ping"}""" + "\n\n"
        val transport = makeTransport(input)

        // First three reads return null (blanks skipped)
        assertNull(transport.readMessage())
        assertNull(transport.readMessage())
        assertNull(transport.readMessage())
        // Fourth read is the real message
        val msg = transport.readMessage()
        assertNotNull(msg)
        assertEquals("x", (msg as JsonRpcRequest).id)
    }

    @Test
    fun `returns null on malformed JSON (does not throw)`() {
        val input = "this is not json\n"
        val transport = makeTransport(input)
        val msg = transport.readMessage()
        assertNull(msg, "malformed JSON should return null, not throw")
    }

    @Test
    fun `returns null on valid JSON but invalid JSON-RPC shape`() {
        // Valid JSON but missing both method and result/error
        val input = """{"foo":"bar"}""" + "\n"
        val transport = makeTransport(input)
        val msg = transport.readMessage()
        assertNull(msg, "invalid shape should return null")
    }

    @Test
    fun `writeMessage adds newline terminator`() {
        val transport = makeTransport("")
        transport.writeMessage(
            JsonRpcRequest(id = "x", method = "ping")
        )
        val written = out.toString(Charsets.UTF_8.name())
        assertTrue(written.endsWith("\n"), "must end with newline, got: $written")
        assertTrue(written.contains("\"id\":\"x\""), "should contain id, got: $written")
        assertTrue(written.contains("\"method\":\"ping\""), "should contain method, got: $written")
    }

    @Test
    fun `writeMessage output is single-line JSON (no embedded newlines)`() {
        val transport = makeTransport("")
        transport.writeMessage(
            JsonRpcRequest(
                id = "x",
                method = "tools/call",
                params = buildJsonObject { put("name", "get_weather") }
            )
        )
        val written = out.toString(Charsets.UTF_8.name())
        // Single line means one trailing newline only
        val lineCount = written.count { it == '\n' }
        assertEquals(1, lineCount, "should be exactly 1 newline (terminator), got $lineCount in: $written")
    }

    @Test
    fun `round-trip write then read preserves message`() {
        // Write to buffer, then read back from same buffer
        val transport = makeTransport("")
        val original = JsonRpcRequest(
            id = "round-trip-1",
            method = "resources/read",
            params = buildJsonObject { put("uri", "file:///x") }
        )
        transport.writeMessage(original)

        // Now construct a transport that reads what we wrote
        val written = out.toString(Charsets.UTF_8.name())
        val reader = StdioTransport(
            input = ByteArrayInputStream(written.toByteArray(Charsets.UTF_8)),
            output = ByteArrayOutputStream()  // discard
        )
        val readBack = reader.readMessage()
        assertNotNull(readBack)
        val req = readBack as JsonRpcRequest
        assertEquals(original.id, req.id)
        assertEquals(original.method, req.method)
    }

    @Test
    fun `writeParseError emits JSON-RPC parse error response with id=null`() {
        val transport = makeTransport("")
        transport.writeParseError("Unexpected token at position 5")
        val written = out.toString(Charsets.UTF_8.name())
        assertTrue(written.contains("\"code\":-32700"), "should have parse error code: $written")
        assertTrue(written.contains("\"id\":null"), "parse error must have id=null: $written")
        assertTrue(written.contains("Parse error"), "should have 'Parse error' message: $written")
    }

    @Test
    fun `accepts numeric id (JSON-RPC spec allows)`() {
        // Spec allows id to be string | number | null. Even though we use String
        // for our own ids, we should accept numeric ids from external clients.
        val input = """{"jsonrpc":"2.0","id":42,"method":"ping"}""" + "\n"
        val transport = makeTransport(input)
        val msg = transport.readMessage()
        assertNotNull(msg)
        val req = msg as JsonRpcRequest
        // numeric id is converted to string ("42") in our model
        assertEquals("42", req.id)
    }
}
