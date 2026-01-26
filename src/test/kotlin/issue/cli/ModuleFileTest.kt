package issue.cli

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class ModuleFileTest {
    @Test
    fun `writes bin folder as exclude even when missing`() {
        val tempDir = Files.createTempDirectory("issue-module-")
        val bundleDir = tempDir.resolve("example.bundle")
        Files.createDirectories(bundleDir.resolve("src"))

        val moduleFile = tempDir.resolve("example.bundle.iml")
        val contentRoot = bundleDir.toAbsolutePath().toUri().toString()

        writeModuleFile(
            moduleFile = moduleFile,
            contentRoot = contentRoot,
            sourceRoots = listOf("src"),
            excludeFolders = determineExcludedFolders(bundleDir)
        )

        val moduleContents = moduleFile.readText()
        assertTrue(
            moduleContents.contains("<excludeFolder url=\"${contentRoot}/bin\" />"),
            "Expected bin folder to be excluded"
        )
    }
}
