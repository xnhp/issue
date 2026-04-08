package issue.cli

import cn.varsa.cli.core.CliException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorktreesCommandTest {
    @Test
    fun `worktrees prints command output for configured repos`() {
        val originalDir = System.getProperty("user.dir")
        val originalOut = System.out
        val tempDir = Files.createTempDirectory("issue-worktrees-test")
        try {
            val repoDir = Files.createDirectories(tempDir.resolve("repo1"))
            Files.writeString(repoDir.resolve(".git"), "gitdir: ../.git/worktrees/repo1\n")
            val config = """
                id: NXT-1234
                branch: todo/NXT-1234-test
                title: Test issue
            """.trimIndent()
            Files.writeString(tempDir.resolve("issue.yaml"), config)

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
            val repoDir = Files.createDirectories(tempDir.resolve("repo1"))
            Files.writeString(repoDir.resolve(".git"), "gitdir: ../.git/worktrees/repo1\n")
            val config = """
                id: NXT-1234
                branch: todo/NXT-1234-test
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

    @Test
    fun `worktrees requires issue yaml`() {
        val originalDir = System.getProperty("user.dir")
        val tempDir = Files.createTempDirectory("issue-worktrees-missing-issue-yaml-test")
        try {
            System.setProperty("user.dir", tempDir.toString())

            val command = WorktreesCommand()
            command.commandParts = listOf("echo", "foo")
            val ex = assertFailsWith<CliException> {
                command.run()
            }
            assertTrue(ex.message?.contains("issue.yaml not found in current directory") == true)
        } finally {
            System.setProperty("user.dir", originalDir)
        }
    }

    @Test
    fun `worktrees supports excluding issue yaml worktrees`() {
        val originalDir = System.getProperty("user.dir")
        val originalOut = System.out
        val tempDir = Files.createTempDirectory("issue-worktrees-issue-yaml-excluded-test")
        try {
            val repoDir = Files.createDirectories(tempDir.resolve("items"))
            Files.writeString(repoDir.resolve(".git"), "gitdir: ../.git/worktrees/items\n")
            val config = """
                id: NXT-1234
                branch: todo/NXT-1234-test
                title: Test issue
                excludedWorktrees:
                  - items
            """.trimIndent()
            Files.writeString(tempDir.resolve("issue.yaml"), config)

            System.setProperty("user.dir", tempDir.toString())
            val captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured))

            val command = WorktreesCommand()
            command.commandParts = listOf("echo", "foo")
            command.noRepoHeaders = true
            command.run()

            val output = captured.toString().trim()
            assertTrue(output.contains("Skipping excluded worktree: ${repoDir.toAbsolutePath().normalize()}"))
            assertFalse(output.contains("foo"))
        } finally {
            System.setOut(originalOut)
            System.setProperty("user.dir", originalDir)
        }
    }

    @Test
    fun `worktrees skips excluded current worktree from config`() {
        val originalDir = System.getProperty("user.dir")
        val originalOut = System.out
        val tempDir = Files.createTempDirectory("issue-worktrees-excluded-worktree-test")
        try {
            val repoDir = Files.createDirectories(tempDir.resolve("repo1"))
            Files.writeString(repoDir.resolve(".git"), "gitdir: ../.git/worktrees/repo1\n")
            val config = """
                id: NXT-1234
                branch: todo/NXT-1234-test
                title: Test issue
                excludedWorktrees:
                  - ${tempDir.toAbsolutePath().normalize()}
            """.trimIndent()
            Files.writeString(tempDir.resolve("issue.yaml"), config)

            System.setProperty("user.dir", tempDir.toString())
            val captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured))

            val command = WorktreesCommand()
            command.commandParts = listOf("echo", "foo")
            command.noRepoHeaders = true
            command.run()

            val output = captured.toString().trim()
            assertTrue(output.contains("Skipping excluded worktree: ${tempDir.toAbsolutePath().normalize()}"))
            assertFalse(output.contains("foo"))
        } finally {
            System.setOut(originalOut)
            System.setProperty("user.dir", originalDir)
        }
    }
}
