package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpPathRulesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesRelativeBackslashAndRootAbsolutePathsInsideTheRoot() {
        val root = temporaryFolder.newFolder("root")
        val nested = File(root, "a/b").apply { mkdirs() }

        assertEquals(nested.canonicalFile, McpPathRules.resolve(root, "a/b"))
        assertEquals(nested.canonicalFile, McpPathRules.resolve(root, "a\\b"))
        assertEquals(nested.canonicalFile, McpPathRules.resolve(root, nested.absolutePath))
        assertEquals(nested.canonicalFile, McpPathRules.resolve(root, nested.absolutePath.replace('\\', '/')))
        assertEquals(root.canonicalFile, McpPathRules.resolve(root, ""))
    }

    @Test
    fun rejectsParentAbsoluteEscapesAndNulBytes() {
        val root = temporaryFolder.newFolder("root")
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "secret.txt").writeText("secret")

        listOf(
            "../outside/secret.txt",
            "a/../../outside/secret.txt",
            outside.absolutePath,
            outside.absolutePath.replace('\\', '/'),
            "/outside-${System.nanoTime()}",
            "\\outside-${System.nanoTime()}",
            "a\u0000b"
        ).forEach { path ->
            val error = runCatching { McpPathRules.resolve(root, path) }.exceptionOrNull()
            assertTrue("Expected rejection for $path, got $error", error is IllegalArgumentException)
        }
    }
}
