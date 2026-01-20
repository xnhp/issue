package issue.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RebaseCommandTest {
    @Test
    fun `adds origin prefix to branch`() {
        assertEquals("origin/main", toOriginBranch("main"))
    }

    @Test
    fun `keeps origin prefix when provided`() {
        assertEquals("origin/release", toOriginBranch("origin/release"))
    }

    @Test
    fun `rejects blank branch`() {
        val ex = assertFailsWith<CliException> { toOriginBranch("  ") }
        assertEquals("Branch name must be non-empty", ex.message)
    }

    @Test
    fun `rejects blank foreach command`() {
        val ex = assertFailsWith<CliException> { requireNonBlank("   ", "Command must be non-empty") }
        assertEquals("Command must be non-empty", ex.message)
    }
}
