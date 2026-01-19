package issue.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BranchSelectTest {
    @Test
    fun `selects single matching branch`() {
        val branches = """
            * main
              enh/NXT-1234-my-improvement
              bugfix/OTHER-1-fix
        """.trimIndent()

        val selected = selectSingleMatchingBranch(parseBranchList(branches), "NXT-1234", "local")

        assertEquals("enh/NXT-1234-my-improvement", selected)
    }

    @Test
    fun `returns null when no match`() {
        val branches = """
            * main
              feature/ABC-1-test
        """.trimIndent()

        val selected = selectSingleMatchingBranch(parseBranchList(branches), "NXT-9999", "local")

        assertNull(selected)
    }

    @Test
    fun `fails when multiple matches`() {
        val branches = """
            * main
              enh/NXT-1234-one
              fix/NXT-1234-two
        """.trimIndent()

        val ex = assertFailsWith<CliException> {
            selectSingleMatchingBranch(parseBranchList(branches), "NXT-1234", "local")
        }
        assertEquals(
            "Multiple local branches match 'NXT-1234': enh/NXT-1234-one, fix/NXT-1234-two",
            ex.message
        )
    }

    @Test
    fun `strips remote HEAD and arrow notation`() {
        val branches = """
              origin/HEAD -> origin/main
              origin/enh/NXT-1234-feature
        """.trimIndent()

        val parsed = parseBranchList(branches)

        assertEquals(listOf("origin/HEAD", "origin/enh/NXT-1234-feature"), parsed)
    }
}
