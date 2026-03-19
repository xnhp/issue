package issue.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class JiraIssueParsingTest {
    @Test
    fun `parses jira summary containing escaped quotes`() {
        val body =
            """
            {
              "fields": {
                "summary": "Remove \"copy link\" action",
                "issuetype": { "name": "Task" }
              }
            }
            """.trimIndent()

        val parsed = parseJiraIssueInfo(body)

        assertEquals("Remove \"copy link\" action", parsed.summary)
        assertEquals("Task", parsed.issueType)
    }
}
