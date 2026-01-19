package issue.cli

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IjInitTemplateTest {
    @Test
    fun `copies ij-project template`() {
        val tempDir = Files.createTempDirectory("issue-ij-init-")
        val targetDir = tempDir.resolve("ij-project")

        copyIjTemplate(targetDir)

        assertTrue(targetDir.resolve(".idea").toFile().isDirectory)
        assertTrue(targetDir.resolve("src").toFile().isDirectory)
        assertTrue(targetDir.resolve(".gitignore").toFile().isFile)
        assertTrue(targetDir.resolve(".idea/.gitignore").toFile().isFile)
        assertTrue(targetDir.resolve(".idea/workspace.xml").toFile().isFile)

        val gitignore = targetDir.resolve(".gitignore").readText()
        assertTrue(gitignore.contains("### IntelliJ IDEA ###"))

        val workspace = targetDir.resolve(".idea/workspace.xml").readText()
        assertTrue(workspace.contains("<component name=\"ProjectViewState\">"))
    }
}
