package issue.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JiraCommandTest {
    @Test
    fun `builds jira url from issue id`() {
        assertEquals(
            "https://knime-com.atlassian.net/browse/NXT-4412",
            jiraUrl("NXT-4412")
        )
    }

    @Test
    fun `fails when issue id is blank`() {
        val ex = assertFailsWith<CliException> { jiraUrl(" ") }
        assertEquals("config.yaml must contain a non-empty 'issueId'", ex.message)
    }
}
