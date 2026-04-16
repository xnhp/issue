package issue.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JiraAuthConfigTest {
    @Test
    fun `loads jira auth from shell environment`() {
        val baseDir = Files.createTempDirectory("issue-jira-auth-env-")

        val auth = loadJiraAuth(
            baseDir,
            mapOf(
                "JIRA_URL" to "https://example.atlassian.net",
                "JIRA_EMAIL" to "user@example.com",
                "JIRA_API_TOKEN" to "token-from-env"
            )
        )

        assertNotNull(auth)
        assertEquals("https://example.atlassian.net", auth.baseUrl)
        assertEquals("user@example.com", auth.email)
        assertEquals("token-from-env", auth.apiToken)
    }

    @Test
    fun `shell environment overrides env file values`() {
        val baseDir = Files.createTempDirectory("issue-jira-auth-override-")
        Files.writeString(
            baseDir.resolve(".env"),
            """
                JIRA_URL=https://file.atlassian.net
                JIRA_EMAIL=file@example.com
                JIRA_API_TOKEN=file-token
            """.trimIndent()
        )

        val auth = loadJiraAuth(
            baseDir,
            mapOf(
                "JIRA_URL" to "https://env.atlassian.net",
                "JIRA_EMAIL" to "env@example.com",
                "JIRA_API_TOKEN" to "env-token"
            )
        )

        assertNotNull(auth)
        assertEquals("https://env.atlassian.net", auth.baseUrl)
        assertEquals("env@example.com", auth.email)
        assertEquals("env-token", auth.apiToken)
    }

    @Test
    fun `falls back to env file when shell environment is missing`() {
        val baseDir = Files.createTempDirectory("issue-jira-auth-file-")
        Files.writeString(
            baseDir.resolve(".env"),
            """
                JIRA_URL=https://file.atlassian.net
                JIRA_EMAIL=file@example.com
                JIRA_API_TOKEN=file-token
            """.trimIndent()
        )

        val auth = loadJiraAuth(baseDir, emptyMap())

        assertNotNull(auth)
        assertEquals("https://file.atlassian.net", auth.baseUrl)
        assertEquals("file@example.com", auth.email)
        assertEquals("file-token", auth.apiToken)
    }
}
