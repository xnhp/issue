package issue.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class CloneBranchSelectionTest {
    @Test
    fun `prefers local branch when present`() {
        val result = selectCheckoutForConfiguredBranch(
            "feature/foo",
            localBranches = listOf("feature/foo"),
            remoteBranches = listOf("origin/feature/foo")
        )

        assertEquals(ConfiguredBranchCheckout.Local("feature/foo"), result)
    }

    @Test
    fun `uses remote tracking branch when local is missing`() {
        val result = selectCheckoutForConfiguredBranch(
            "feature/foo",
            localBranches = emptyList(),
            remoteBranches = listOf("origin/feature/foo")
        )

        assertEquals(ConfiguredBranchCheckout.Remote("origin/feature/foo"), result)
    }

    @Test
    fun `creates branch when missing locally and remotely`() {
        val result = selectCheckoutForConfiguredBranch(
            "feature/foo",
            localBranches = emptyList(),
            remoteBranches = emptyList()
        )

        assertEquals(ConfiguredBranchCheckout.Create("feature/foo"), result)
    }

    @Test
    fun `normalizes origin prefix for local lookup`() {
        val result = selectCheckoutForConfiguredBranch(
            "origin/feature/foo",
            localBranches = listOf("feature/foo"),
            remoteBranches = emptyList()
        )

        assertEquals(ConfiguredBranchCheckout.Local("feature/foo"), result)
    }
}
