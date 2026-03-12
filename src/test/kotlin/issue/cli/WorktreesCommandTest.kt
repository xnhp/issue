package issue.cli

import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class WorktreesCommandTest {
    @Test
    fun `worktrees help lists init and foreach`() {
        val output = ByteArrayOutputStream()
        val commandLine = CommandLine(IssueCommand())
        val worktrees = commandLine.subcommands["worktrees"]!!
        worktrees.usage(PrintStream(output))

        val help = output.toString()
        assertTrue(help.contains("init"))
        assertTrue(help.contains("foreach"))
    }

    @Test
    fun `worktrees foreach help exposes repo header option`() {
        val output = ByteArrayOutputStream()
        val commandLine = CommandLine(IssueCommand())
        val worktrees = commandLine.subcommands["worktrees"]!!
        val foreach = worktrees.subcommands["foreach"]!!
        foreach.usage(PrintStream(output))

        val help = output.toString()
        assertTrue(help.contains("<command>"))
        assertTrue(help.contains("--no-repo-headers"))
    }
}
