package issue.cli

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewCommandTest {
    @Test
    fun `new command writes issue metadata file`() {
        val originalHome = System.getProperty("user.home")
        val tempHome = Files.createTempDirectory("issue-new-test")
        System.setProperty("user.home", tempHome.toString())
        try {
            val baseDir = tempHome.resolve("Desktop").resolve("issues")
            Files.createDirectories(baseDir)

            val command = NewCommand()
            command.issueId = "NXT-1234"
            command.run()

            val issueDir = baseDir.resolve("issue_NXT-1234")
            assertTrue(Files.isDirectory(issueDir))

            val metadataPath = issueDir.resolve("issue.yaml")
            assertTrue(Files.isRegularFile(metadataPath))

            val root = Yaml().load<Any>(Files.readString(metadataPath)) as Map<*, *>
            assertEquals("NXT-1234", root["id"])
            assertEquals("issue/NXT-1234", root["branch"])
            val lines = Files.readAllLines(metadataPath)
            assertEquals("id: 'NXT-1234'", lines[0])
            assertEquals("branch: 'issue/NXT-1234'", lines[1])
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }
}
