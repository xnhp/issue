package issue.cli

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
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

    @Test
    fun `init command writes issue metadata in current directory`() {
        val originalHome = System.getProperty("user.home")
        val originalDir = System.getProperty("user.dir")
        val tempHome = Files.createTempDirectory("issue-init-home-test")
        val tempDir = Files.createTempDirectory("issue-init-dir-test")
        System.setProperty("user.home", tempHome.toString())
        System.setProperty("user.dir", tempDir.toString())
        try {
            val baseDir = tempHome.resolve("Desktop").resolve("issues")
            Files.createDirectories(baseDir)

            val command = InitCommand()
            command.issueId = "NXT-9876"
            command.run()

            val metadataPath = tempDir.resolve("issue.yaml")
            assertTrue(Files.isRegularFile(metadataPath))
            assertFalse(Files.exists(baseDir.resolve("issue_NXT-9876")))

            val root = Yaml().load<Any>(Files.readString(metadataPath)) as Map<*, *>
            assertEquals("NXT-9876", root["id"])
            assertEquals("issue/NXT-9876", root["branch"])
            val lines = Files.readAllLines(metadataPath)
            assertEquals("id: 'NXT-9876'", lines[0])
            assertEquals("branch: 'issue/NXT-9876'", lines[1])
        } finally {
            System.setProperty("user.home", originalHome)
            System.setProperty("user.dir", originalDir)
        }
    }
}
