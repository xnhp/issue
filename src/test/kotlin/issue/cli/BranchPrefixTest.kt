package issue.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class BranchPrefixTest {
    @Test
    fun `enhancement issue type uses enh prefix`() {
        assertEquals("enh", branchPrefixForIssueType("Enhancement"))
    }

    @Test
    fun `blank issue type uses issue prefix`() {
        assertEquals("issue", branchPrefixForIssueType(""))
    }

    @Test
    fun `issue type slug is used when not mapped`() {
        assertEquals("feature-request", branchPrefixForIssueType("Feature Request"))
    }
}
