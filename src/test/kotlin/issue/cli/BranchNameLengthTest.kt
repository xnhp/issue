package issue.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchNameLengthTest {
    @Test
    fun `branch name is capped to 50 chars`() {
        val prefix = "issue"
        val issueId = "PDE-1234"
        val base = "${issueId}-${"a".repeat(80)}"

        val result = formatBranchName(prefix, issueId, base)

        assertTrue(result.length <= 50, "Expected branch name length <= 50, got ${result.length}")
        assertTrue(result.startsWith("${prefix}/${issueId}"))
    }

    @Test
    fun `prefix is truncated when it exceeds limit`() {
        val prefix = "a".repeat(60)
        val issueId = "PDE-1"
        val base = issueId

        val result = formatBranchName(prefix, issueId, base)

        assertEquals(prefix.take(50), result)
    }
}
