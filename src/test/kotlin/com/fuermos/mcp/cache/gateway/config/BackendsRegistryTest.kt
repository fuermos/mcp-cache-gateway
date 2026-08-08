package com.fuermos.mcp.cache.gateway.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files

/**
 * Unit tests for BackendsRegistry + SecretRefResolver.
 *
 * Coverage:
 *   - parseArgs: JSON array string → List<String>
 *   - DefaultSecretRefResolver:
 *     - file:/path#KEY → reads KEY=value from file
 *     - literal:VALUE → returns VALUE (with warning)
 *     - invalid format → throws IllegalArgumentException
 *     - missing key in file → throws IllegalStateException
 */
class BackendsRegistryTest {

    @Test
    fun `parseArgs parses JSON array of strings`() {
        val args = BackendConfig.parseArgs("""["-jar", "app.jar", "--port=8080"]""")
        assertEquals(3, args.size)
        assertEquals("-jar", args[0])
        assertEquals("app.jar", args[1])
        assertEquals("--port=8080", args[2])
    }

    @Test
    fun `parseArgs handles empty array`() {
        assertEquals(0, BackendConfig.parseArgs("[]").size)
    }

    @Test
    fun `parseArgs handles invalid JSON gracefully`() {
        // Returns empty list instead of throwing
        assertEquals(0, BackendConfig.parseArgs("not json").size)
    }

    @Test
    fun `DefaultSecretRefResolver resolves file format`() {
        val tmp = Files.createTempFile("secrets", ".env")
        try {
            Files.writeString(tmp, """
                WRONGNOTEBOOK_PASSWORD=secret123
                OTHER_KEY=other_value
            """.trimIndent())
            val resolved = DefaultSecretRefResolver.resolve("file:${tmp}#WRONGNOTEBOOK_PASSWORD")
            assertEquals("secret123", resolved)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `DefaultSecretRefResolver resolves other key from same file`() {
        val tmp = Files.createTempFile("secrets", ".env")
        try {
            Files.writeString(tmp, "KEY_A=value_a\nKEY_B=value_b\n")
            assertEquals("value_b", DefaultSecretRefResolver.resolve("file:${tmp}#KEY_B"))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `DefaultSecretRefResolver strips whitespace`() {
        val tmp = Files.createTempFile("secrets", ".env")
        try {
            Files.writeString(tmp, "KEY_WITH_SPACES   =   spaced_value   \n")
            assertEquals("spaced_value", DefaultSecretRefResolver.resolve("file:${tmp}#KEY_WITH_SPACES"))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `DefaultSecretRefResolver literal format returns value`() {
        assertEquals("dev_value", DefaultSecretRefResolver.resolve("literal:dev_value"))
    }

    @Test
    fun `DefaultSecretRefResolver throws on unknown format`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            DefaultSecretRefResolver.resolve("unknown_format:value")
        }
        assertTrue(ex.message!!.contains("unsupported secret_ref format"))
    }

    @Test
    fun `DefaultSecretRefResolver throws on missing hash in file format`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            DefaultSecretRefResolver.resolve("file:/path/without/hash")
        }
        assertTrue(ex.message!!.contains("must include '#KEY_NAME'"))
    }

    @Test
    fun `DefaultSecretRefResolver throws on missing file`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            DefaultSecretRefResolver.resolve("file:/nonexistent/path#KEY")
        }
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun `DefaultSecretRefResolver throws when key not in file`() {
        val tmp = Files.createTempFile("secrets", ".env")
        try {
            Files.writeString(tmp, "OTHER_KEY=value\n")
            val ex = assertThrows(IllegalStateException::class.java) {
                DefaultSecretRefResolver.resolve("file:${tmp}#MISSING_KEY")
            }
            assertTrue(ex.message!!.contains("not found"))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}