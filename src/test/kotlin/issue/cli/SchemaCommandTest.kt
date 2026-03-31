package issue.cli

import java.nio.file.Files
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaCommandTest {
    @Test
    fun `resolves schema path as absolute existing file`() {
        val schemaPath = resolveIssueSchemaPath()

        assertTrue(schemaPath.isAbsolute)
        assertTrue(Files.exists(schemaPath))
        assertTrue(schemaPath.fileName.toString().endsWith(".yaml"))
        assertTrue(!schemaPath.toString().startsWith("/tmp/"))
    }

    @Test
    fun `finds installed schema next to distribution lib directory`() {
        val distDir = Files.createTempDirectory("issue-dist-")
        val libDir = Files.createDirectories(distDir.resolve("lib"))
        val schemaDir = Files.createDirectories(distDir.resolve("schema"))
        val jarPath = libDir.resolve("issue.jar")
        Files.writeString(jarPath, "")
        val schemaPath = schemaDir.resolve("issue.schema.yaml")
        Files.writeString(schemaPath, "type: object\n")

        val resolved = findInstalledIssueSchemaPath(jarPath.toUri())

        assertEquals(schemaPath.toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `returns null for non file code source location`() {
        val resolved = findInstalledIssueSchemaPath(URI("jar:file:/tmp/issue.jar!/"))
        assertNull(resolved)
    }
}
