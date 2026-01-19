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

        val selected = selectBranch(branches, "NXT-1234")

        assertEquals("enh/NXT-1234-my-improvement", selected)
    }

    @Test
    fun `returns null when no match`() {
        val branches = """
            * main
              feature/ABC-1-test
        """.trimIndent()

        val selected = selectBranch(branches, "NXT-9999")

        assertNull(selected)
    }

    @Test
    fun `fails when multiple matches`() {
        val branches = """
            * main
              enh/NXT-1234-one
              fix/NXT-1234-two
        """.trimIndent()

        val ex = assertFailsWith<CliException> { selectBranch(branches, "NXT-1234") }
        assertEquals("Multiple local branches match 'NXT-1234': enh/NXT-1234-one, fix/NXT-1234-two", ex.message)
    }
}
