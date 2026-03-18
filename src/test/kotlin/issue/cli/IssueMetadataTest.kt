package issue.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IssueMetadataTest {
    @Test
    fun `parses valid issue metadata`() {
        val metadata = parseIssueMetadata(
            """
                id: NXT-1234
                branch: feature/NXT-1234-test
            """.trimIndent()
        )

        assertEquals("NXT-1234", metadata.id)
        assertEquals("feature/NXT-1234-test", metadata.branch)
    }

    @Test
    fun `parses issue metadata independent of key order`() {
        val metadata = parseIssueMetadata(
            """
                branch: issue/NXT-12
                id: NXT-12
            """.trimIndent()
        )

        assertEquals("NXT-12", metadata.id)
        assertEquals("issue/NXT-12", metadata.branch)
    }

    @Test
    fun `fails when id missing`() {
        val ex = assertFailsWith<CliException> {
            parseIssueMetadata(
                """
                    branch: issue/NXT-12
                """.trimIndent()
            )
        }

        assertEquals("issue.yaml must contain key 'id'", ex.message)
    }

    @Test
    fun `fails when branch blank`() {
        val ex = assertFailsWith<CliException> {
            parseIssueMetadata(
                """
                    id: NXT-12
                    branch: ""
                """.trimIndent()
            )
        }

        assertEquals("issue.yaml key 'branch' must be non-empty", ex.message)
    }

    @Test
    fun `reads issue property from issue root`() {
        val issueDir = Files.createTempDirectory("issue-metadata-read-")
        val issueFile = issueDir.resolve("issue.yaml")
        writeIssueMetadata(issueFile, IssueMetadata(id = "NXT-42", branch = "issue/NXT-42"))

        val id = readIssueProperty(issueDir, "id")
        val branch = readIssueProperty(issueDir, "branch")

        assertEquals("NXT-42", id)
        assertEquals("issue/NXT-42", branch)
    }

    @Test
    fun `reads issue property from nested directory`() {
        val issueDir = Files.createTempDirectory("issue-metadata-read-nested-")
        val nested = issueDir.resolve("knime-gateway/src")
        Files.createDirectories(nested)
        val issueFile = issueDir.resolve("issue.yaml")
        writeIssueMetadata(issueFile, IssueMetadata(id = "NXT-99", branch = "enh/NXT-99"))

        val id = readIssueProperty(nested, "id")

        assertEquals("NXT-99", id)
    }

    @Test
    fun `fails when requested property is missing`() {
        val issueDir = Files.createTempDirectory("issue-metadata-read-missing-")
        val issueFile = issueDir.resolve("issue.yaml")
        writeIssueMetadata(issueFile, IssueMetadata(id = "NXT-77", branch = "issue/NXT-77"))

        val ex = assertFailsWith<CliException> {
            readIssueProperty(issueDir, "foo")
        }

        assertEquals("issue.yaml must contain key 'foo'", ex.message)
    }
}
