package issue.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class BundleSelectionTest {
    @Test
    fun `allBundles includes bundles and nonPdeBundles`() {
        val entry = RepoEntry(
            repo = "knime-repo",
            bundles = listOf("bundle-a"),
            nonPdeBundles = listOf("bundle-b")
        )

        assertEquals(listOf("bundle-a", "bundle-b"), allBundles(entry))
    }

    @Test
    fun `allBundles removes duplicates`() {
        val entry = RepoEntry(
            repo = "knime-repo",
            bundles = listOf("bundle-a", "bundle-b"),
            nonPdeBundles = listOf("bundle-b", "bundle-c")
        )

        assertEquals(listOf("bundle-a", "bundle-b", "bundle-c"), allBundles(entry))
    }
}
