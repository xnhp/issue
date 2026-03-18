package issue.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PickCommandTest {
    @Test
    fun `discover uses issue yaml and ignores non-issue directories`() {
        val baseDir = Files.createTempDirectory("issue-pick-discovery-")
        val validDir = baseDir.resolve("issue_NXT-1")
        Files.createDirectories(validDir)
        Files.writeString(
            validDir.resolve("issue.yaml"),
            """
            id: NXT-1
            branch: issue/NXT-1
            title: Add picker command
            """.trimIndent()
        )

        val nonIssueDir = baseDir.resolve("legacy")
        Files.createDirectories(nonIssueDir)

        val malformedDir = baseDir.resolve("broken")
        Files.createDirectories(malformedDir)
        Files.writeString(
            malformedDir.resolve("issue.yaml"),
            """
            id: NXT-2
            """.trimIndent()
        )

        val nestedIssueDir = baseDir.resolve("group").resolve("issue_NXT-9")
        Files.createDirectories(nestedIssueDir)
        Files.writeString(
            nestedIssueDir.resolve("issue.yaml"),
            """
            id: NXT-9
            branch: issue/NXT-9
            title: Should not be discovered
            """.trimIndent()
        )

        val candidates = discoverIssuePickCandidates(baseDir)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("NXT-1", candidate.id)
        assertEquals("Add picker command", candidate.title)
        assertEquals(validDir.toAbsolutePath().normalize(), candidate.issueDir.toAbsolutePath().normalize())
    }

    @Test
    fun `resolve base path expands home shorthand`() {
        val originalHome = System.getProperty("user.home")
        val tempHome = Files.createTempDirectory("issue-pick-home-resolve-")
        try {
            System.setProperty("user.home", tempHome.toString())
            val resolved = resolveIssuePickBasePath("~/Desktop/issues", Path.of("/tmp"))
            assertEquals(tempHome.resolve("Desktop/issues").toAbsolutePath().normalize(), resolved)
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun `resolve base path handles relative input from cwd`() {
        val cwd = Path.of("/tmp/project")
        val resolved = resolveIssuePickBasePath("issues", cwd)
        assertEquals(cwd.resolve("issues").toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `rank uses global recency and keeps most recent candidate first`() {
        val a = pickCandidate("NXT-1", "/tmp/repo-a/issue_NXT-1", "Alpha")
        val b = pickCandidate("NXT-2", "/tmp/repo-b/issue_NXT-2", "Beta")
        val c = pickCandidate("NXT-3", "/tmp/repo-c/issue_NXT-3", "Gamma")

        val ranked = rankIssuePickCandidates(
            listOf(a, b, c),
            recency = listOf(c.issueDir.toString(), a.issueDir.toString())
        )

        assertEquals(listOf(c, a, b), ranked)
        assertEquals(
            c,
            ranked.first(),
            "Top-ranked candidate must be first so immediate RET selection chooses it"
        )
    }

    @Test
    fun `label includes issue id and title`() {
        val label = issuePickLabel(
            IssuePickCandidate(
                issueDir = Path.of("/tmp/issue_NXT-7"),
                id = "NXT-7",
                title = "Improve fuzzy selection"
            )
        )

        assertEquals("NXT-7 Improve fuzzy selection", label)
    }

    @Test
    fun `blank title is rendered as placeholder`() {
        val label = issuePickLabel(
            IssuePickCandidate(
                issueDir = Path.of("/tmp/issue_NXT-8"),
                id = "NXT-8",
                title = ""
            )
        )

        assertEquals("NXT-8 (no title)", label)
    }

    @Test
    fun `selected issue directory parses from picker output`() {
        val parsed = parseSelectedIssueDirectory("NXT-10 Add command\t/tmp/issue_NXT-10")
        assertNotNull(parsed)
        assertEquals(Path.of("/tmp/issue_NXT-10"), parsed)
    }

    @Test
    fun `empty picker output returns null`() {
        assertNull(parseSelectedIssueDirectory("  \n  "))
    }

    @Test
    fun `recency store keeps selected issue first and unique`() {
        val home = Files.createTempDirectory("issue-pick-home-")
        val recencyPath = issuePickRecencyPath(home)

        storeIssuePickRecency(recencyPath, Path.of("/tmp/a"))
        storeIssuePickRecency(recencyPath, Path.of("/tmp/b"))
        storeIssuePickRecency(recencyPath, Path.of("/tmp/a"))

        val recency = loadIssuePickRecency(recencyPath)
        assertEquals(listOf("/tmp/a", "/tmp/b"), recency)
    }

    private fun pickCandidate(id: String, path: String, title: String): IssuePickCandidate {
        return IssuePickCandidate(issueDir = Path.of(path), id = id, title = title)
    }
}
