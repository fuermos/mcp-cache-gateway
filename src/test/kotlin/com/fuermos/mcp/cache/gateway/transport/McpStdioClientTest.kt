package com.fuermos.mcp.cache.gateway.transport

import com.fuermos.mcp.cache.gateway.config.BackendConfig
import com.fuermos.mcp.cache.gateway.server.ServerHandle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows

/**
 * Unit tests for McpStdioClient — uses real `/bin/sh -c "cat"` subprocess for roundtrip testing.
 *
 * Coverage:
 *   - sendRequest roundtrip with cat (echo stdin → stdout)
 *   - sendRequest parses error response
 *   - listTools returns empty / non-empty arrays
 *   - wrap factory creates client
 *   - Backend name in error messages
 */
class McpStdioClientTest {

    private fun makeBackend(name: String = "test-backend"): BackendConfig = BackendConfig(
        name = name,
        displayName = "Test Backend",
        enabled = true,
        cmd = "/bin/sh",
        args = listOf("-c", "cat"),
        cwd = null,
        spawnTimeoutMs = 5000,
        idleTimeoutMs = 60_000,
        maxRestarts = 3,
        eager = false,
        protocol = "stdio",
        env = emptyMap(),
        version = 1
    )

    private fun makeEchoHandle(serverId: String, script: String): ServerHandle {
        val pb = ProcessBuilder("/bin/sh", "-c", script)
        pb.redirectInput(ProcessBuilder.Redirect.PIPE)
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
        pb.redirectError(ProcessBuilder.Redirect.PIPE)
        return ServerHandle(
            serverId = serverId,
            cmd = "/bin/sh",
            args = emptyList(),
            process = pb.start()
        )
    }

    @Test
    fun `wrap factory creates client`() {
        val handle = makeEchoHandle("test", "cat")
        val backend = makeBackend()
        val client = McpStdioClient.wrap(backend, handle)
        assertNotNull(client)
        handle.close()
    }

    @Test
    fun `sendRequest with cat that wraps request as response`() = runBlocking {
        val handle = makeEchoHandle("fake-mcp", """
            read line
            echo '{"id":"test-1","jsonrpc":"2.0","result":{"tools":[]}}'
        """.trimIndent())
        val backend = makeBackend("fake-mcp")
        val client = McpStdioClient(backend, handle)

        val result = client.sendRequest("tools/list", null)
        assertNotNull(result, "should return result object")
        handle.close()
    }

    @Test
    fun `listTools returns empty array for tools`() = runBlocking {
        val handle = makeEchoHandle("fake-mcp", """
            read line
            echo '{"id":"t1","jsonrpc":"2.0","result":{"tools":[]}}'
        """.trimIndent())
        val backend = makeBackend("fake-mcp")
        val client = McpStdioClient(backend, handle)

        val tools = client.listTools()
        assertEquals(0, tools.size, "empty tools array should return 0 tools")
        handle.close()
    }

    @Test
    fun `listTools returns parsed tool objects`() = runBlocking {
        val handle = makeEchoHandle("fake-mcp", """
            read line
            echo '{"id":"t1","jsonrpc":"2.0","result":{"tools":[{"name":"toolA","description":"First tool","inputSchema":{"type":"object"}},{"name":"toolB","description":"Second tool","inputSchema":{"type":"object"}}]}}'
        """.trimIndent())
        val backend = makeBackend("fake-mcp")
        val client = McpStdioClient(backend, handle)

        val tools = client.listTools()
        assertEquals(2, tools.size)
        assertEquals("toolA", (tools[0]["name"] as JsonPrimitive).content)
        assertEquals("First tool", (tools[0]["description"] as JsonPrimitive).content)
        handle.close()
    }

    @Test
    fun `sendRequest throws on error response`() = runBlocking {
        val handle = makeEchoHandle("fake-mcp", """
            read line
            echo '{"id":"t1","jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found"}}'
        """.trimIndent())
        val backend = makeBackend("fake-mcp")
        val client = McpStdioClient(backend, handle)

        val ex = assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking { client.sendRequest("unknown/method") }
        }
        assertTrue(ex.message!!.contains("Method not found"))
        assertTrue(ex.message!!.contains("fake-mcp"), "should include backend name")
        handle.close()
    }

    @Test
    fun `sendRequest with malformed JSON returns parse error`() = runBlocking {
        // ServerHandle.execute will return -32603 parse error if subprocess returns non-JSON
        val handle = makeEchoHandle("fake-mcp", """
            read line
            echo 'this is not json'
        """.trimIndent())
        val backend = makeBackend("fake-mcp")
        val client = McpStdioClient(backend, handle)

        // ServerHandle.execute returns JsonRpcResponse.failure, but sendRequest checks isError
        // and throws. Verify the error path.
        val ex = assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking { client.sendRequest("tools/list") }
        }
        assertNotNull(ex.message)
        handle.close()
    }
}