package issue.cli

import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class HelpCommandTest {
    @Test
    fun `issue help lists foreach flags`() {
        val output = ByteArrayOutputStream()
        val commandLine = CommandLine(IssueCommand())
        commandLine.usage(PrintStream(output))

        val help = output.toString()
        assertTrue(help.contains("--no-repo-headers"))
    }
}
