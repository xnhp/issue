package issue.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreesCommandTest {
    @Test
    fun `worktrees prints command output for configured repos`() {
        val originalDir = System.getProperty("user.dir")
        val originalOut = System.out
        val tempDir = Files.createTempDirectory("issue-worktrees-test")
        try {
            Files.createDirectories(tempDir.resolve("repo1"))
            val config = """
                bundlesPerRepo:
                  - repo: repo1
                    bundles: []
            """.trimIndent()
            Files.writeString(tempDir.resolve("pde.yaml"), config)

            System.setProperty("user.dir", tempDir.toString())
            val captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured))

            val command = WorktreesCommand()
            command.commandParts = listOf("echo", "foo")
            command.run()

            assertEquals("\u001b[1mrepo1\u001b[0m\nfoo", captured.toString().trim())
        } finally {
            System.setOut(originalOut)
            System.setProperty("user.dir", originalDir)
        }
    }

    @Test
    fun `worktrees can disable repo headers`() {
        val originalDir = System.getProperty("user.dir")
        val originalOut = System.out
        val tempDir = Files.createTempDirectory("issue-worktrees-test")
        try {
            Files.createDirectories(tempDir.resolve("repo1"))
            val config = """
                bundlesPerRepo:
                  - repo: repo1
                    bundles: []
            """.trimIndent()
            Files.writeString(tempDir.resolve("pde.yaml"), config)

            System.setProperty("user.dir", tempDir.toString())
            val captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured))

            val command = WorktreesCommand()
            command.commandParts = listOf("echo", "foo")
            command.noRepoHeaders = true
            command.run()

            assertEquals("foo", captured.toString().trim())
        } finally {
            System.setOut(originalOut)
            System.setProperty("user.dir", originalDir)
        }
    }

    @Test
    fun `worktrees supports issue yaml filename`() {
        val originalDir = System.getProperty("user.dir")
        val originalOut = System.out
        val tempDir = Files.createTempDirectory("issue-worktrees-issue-yaml-test")
        try {
            val repoDir = Files.createDirectories(tempDir.resolve("repo1"))
            Files.writeString(repoDir.resolve(".git"), "gitdir: ../.git/worktrees/repo1\n")
            val config = """
                id: NXT-1234
                branch: new-event_PA-56-container-navigation
                title: Test issue
            """.trimIndent()
            Files.writeString(tempDir.resolve("issue.yaml"), config)

            System.setProperty("user.dir", tempDir.toString())
            val captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured))

            val command = WorktreesCommand()
            command.commandParts = listOf("echo", "foo")
            command.noRepoHeaders = true
            command.run()

            assertEquals("foo", captured.toString().trim())
        } finally {
            System.setOut(originalOut)
            System.setProperty("user.dir", originalDir)
        }
    }
}
