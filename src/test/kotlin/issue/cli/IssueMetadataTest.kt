package issue.cli

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
}
