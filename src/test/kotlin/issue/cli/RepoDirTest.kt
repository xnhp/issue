package issue.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RepoDirTest {
    @Test
    fun `resolves repo directories`() {
        val cwd = Files.createTempDirectory("issue-")
        val repoA = Files.createDirectories(cwd.resolve("repo-a"))
        val repoB = Files.createDirectories(cwd.resolve("repo-b"))

        val entries = listOf(
            RepoEntry(repo = "repo-a", bundles = listOf("a"), nonPdeBundles = emptyList()),
            RepoEntry(repo = "repo-b", bundles = listOf("b"), nonPdeBundles = emptyList())
        )

        val resolved = resolveRepoDirs(cwd, entries)

        assertEquals(listOf(repoA, repoB), resolved.map { it.path })
        assertEquals(listOf("repo-a", "repo-b"), resolved.map { it.name })
    }

    @Test
    fun `fails when repo name is blank`() {
        val cwd = Files.createTempDirectory("issue-")
        val entries = listOf(RepoEntry(repo = "", bundles = listOf("a"), nonPdeBundles = emptyList()))

        val ex = assertFailsWith<CliException> { resolveRepoDirs(cwd, entries) }

        assertEquals("Found repo entry with empty name", ex.message)
    }

    @Test
    fun `fails when repo directory is missing`() {
        val cwd = Files.createTempDirectory("issue-")
        val entries = listOf(
            RepoEntry(repo = "missing-repo", bundles = listOf("a"), nonPdeBundles = emptyList())
        )

        val ex = assertFailsWith<CliException> { resolveRepoDirs(cwd, entries) }

        assertEquals(
            "Repo directory not found for 'missing-repo': ${cwd.resolve("missing-repo")}",
            ex.message
        )
    }
}
