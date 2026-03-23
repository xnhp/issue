package issue.cli

import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class HelpCommandTest {
    @Test
    fun `issue help lists core commands`() {
        val output = ByteArrayOutputStream()
        val commandLine = CommandLine(IssueCommand())
        commandLine.usage(PrintStream(output))

        val help = output.toString()
        assertTrue(help.contains("new"))
        assertTrue(help.contains("pick"))
        assertTrue(help.contains("init"))
        assertTrue(help.contains("read"))
        assertTrue(help.contains("schema"))
    }
}
