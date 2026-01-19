package issue.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceRootsTest {
    @Test
    fun `prefers eclipse and generated when present`() {
        val bundleDir = Files.createTempDirectory("bundle-")
        val srcDir = Files.createDirectories(bundleDir.resolve("src"))
        Files.createDirectories(srcDir.resolve("eclipse"))
        Files.createDirectories(srcDir.resolve("generated"))

        val roots = determineSourceRoots(bundleDir).map { bundleDir.relativize(it).toString() }

        assertEquals(listOf("src/eclipse", "src/generated"), roots)
    }

    @Test
    fun `falls back to src when no subdirs`() {
        val bundleDir = Files.createTempDirectory("bundle-")
        Files.createDirectories(bundleDir.resolve("src"))

        val roots = determineSourceRoots(bundleDir).map { bundleDir.relativize(it).toString() }

        assertEquals(listOf("src"), roots)
    }
}
