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
        assertTrue(targetDir.resolve(".idea/eclipse-partial.xml").toFile().isFile)
        assertTrue(targetDir.resolve(".idea/workspace.xml").toFile().isFile)

        val gitignore = targetDir.resolve(".gitignore").readText()
        assertTrue(gitignore.contains("### IntelliJ IDEA ###"))

        val workspace = targetDir.resolve(".idea/workspace.xml").readText()
        assertTrue(workspace.contains("<component name=\"ProjectViewState\">"))

        val moduleContents = targetDir.resolve("ij-project.iml").readText()
        assertTrue(moduleContents.contains("<excludeFolder url=\"file://\$MODULE_DIR\$/bin\" />"))
    }

    @Test
    fun `updates eclipse target location`() {
        val tempDir = Files.createTempDirectory("issue-ij-init-")
        val targetDir = tempDir.resolve("ij-project")

        copyIjTemplate(targetDir)

        val profilePath = "/opt/knime/target/Profile.profile"
        updateEclipseTargetLocation(targetDir, profilePath)

        val eclipsePartial = targetDir.resolve(".idea/eclipse-partial.xml").readText()
        assertTrue(eclipsePartial.contains("location=\"${profilePath}\""))
    }

    @Test
    fun `finds config in parent directory`() {
        val tempDir = Files.createTempDirectory("issue-ij-init-")
        val configPath = tempDir.resolve("config.yaml")
        Files.writeString(configPath, "bundlesPerRepo: []")
        val nestedDir = tempDir.resolve("nested/dir")
        Files.createDirectories(nestedDir)

        val resolved = findConfigPath(nestedDir)

        assertEquals(configPath, resolved)
    }
}
