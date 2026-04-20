package issue.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NewCommandTest {
    @Test
    fun `new command fails when jira summary missing`() {
        val originalHome = System.getProperty("user.home")
        val tempHome = Files.createTempDirectory("issue-new-test")
        System.setProperty("user.home", tempHome.toString())
        try {
            val baseDir = tempHome.resolve("Desktop").resolve("issues")
            Files.createDirectories(baseDir)

            val command = NewCommand()
            command.issueId = "NXT-99999999"
            val ex = assertFailsWith<CliException> {
                command.run()
            }

            assertTrue(ex.message?.contains("missing Jira summary for title") == true)
            assertFalse(Files.exists(baseDir.resolve("issue_NXT-99999999").resolve("issue.yaml")))
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun `init command fails when jira summary missing`() {
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
            command.issueId = "NXT-99999999"
            val ex = assertFailsWith<CliException> {
                command.run()
            }

            assertTrue(ex.message?.contains("missing Jira summary for title") == true)
            val metadataPath = tempDir.resolve("issue.yaml")
            assertFalse(Files.exists(metadataPath))
            assertFalse(Files.exists(baseDir.resolve("issue_NXT-99999999")))
        } finally {
            System.setProperty("user.home", originalHome)
            System.setProperty("user.dir", originalDir)
        }
    }
}
