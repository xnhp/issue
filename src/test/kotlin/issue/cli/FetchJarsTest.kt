package issue.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchJarsTest {
    @Test
    fun `finds fetch_jars directories`() {
        val root = Files.createTempDirectory("issue-fetch-")
        val repoDir = Files.createDirectories(root.resolve("knime-gateway"))
        val bundleDir = Files.createDirectories(repoDir.resolve("org.knime.gateway.impl"))
        val fetchDir = Files.createDirectories(bundleDir.resolve("lib/fetch_jars"))

        val config = Config(
            issueId = null,
            bundlesPerRepo = listOf(
                RepoEntry("knime-gateway", listOf("org.knime.gateway.impl"))
            )
        )

        val results = findFetchJarsDirs(root, config)

        assertEquals(1, results.size)
        assertTrue(results[0].endsWith("lib/fetch_jars"))
        assertEquals(fetchDir, results[0])
    }
}
