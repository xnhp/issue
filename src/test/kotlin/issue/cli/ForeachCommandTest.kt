package issue.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ForeachCommandTest {
    @Test
    fun `foreach prints command output`() {
        val originalDir = System.getProperty("user.dir")
        val originalOut = System.out
        val tempDir = Files.createTempDirectory("issue-foreach-test")
        try {
            Files.createDirectories(tempDir.resolve("repo1"))
            val config = """
                bundlesPerRepo:
                  - repo: repo1
                    bundles: []
            """.trimIndent()
            Files.writeString(tempDir.resolve("config.yaml"), config)

            System.setProperty("user.dir", tempDir.toString())
            val captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured))

            val command = ForeachCommand()
            command.command = "echo foo"
            command.run()

            assertEquals("repo1\nfoo", captured.toString().trim())
        } finally {
            System.setOut(originalOut)
            System.setProperty("user.dir", originalDir)
        }
    }
}
