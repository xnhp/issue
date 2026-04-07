package issue.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DirCommandTest {
    @Test
    fun `dir prints issue directory when run from issue root`() {
        val originalDir = System.getProperty("user.dir")
        val originalOut = System.out
        val issueDir = Files.createTempDirectory("issue-dir-root-")
        try {
            Files.writeString(
                issueDir.resolve("issue.yaml"),
                """
                    id: NXT-1234
                    branch: todo/NXT-1234
                    title: Test issue
                """.trimIndent()
            )
            System.setProperty("user.dir", issueDir.toString())
            val captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured))

            DirCommand().run()

            assertEquals(issueDir.toAbsolutePath().normalize().toString(), captured.toString().trim())
        } finally {
            System.setOut(originalOut)
            System.setProperty("user.dir", originalDir)
        }
    }

    @Test
    fun `dir walks upwards and prints containing issue directory`() {
        val originalDir = System.getProperty("user.dir")
        val originalOut = System.out
        val issueDir = Files.createTempDirectory("issue-dir-nested-")
        try {
            val nested = Files.createDirectories(issueDir.resolve("repo/src/main"))
            Files.writeString(
                issueDir.resolve("issue.yaml"),
                """
                    id: NXT-5678
                    branch: todo/NXT-5678
                    title: Test issue nested
                """.trimIndent()
            )
            System.setProperty("user.dir", nested.toString())
            val captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured))

            DirCommand().run()

            assertEquals(issueDir.toAbsolutePath().normalize().toString(), captured.toString().trim())
        } finally {
            System.setOut(originalOut)
            System.setProperty("user.dir", originalDir)
        }
    }
}
