package issue.cli

import java.nio.file.Files
import kotlin.test.Test
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
}
