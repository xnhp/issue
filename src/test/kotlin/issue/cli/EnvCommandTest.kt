package issue.cli

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvCommandTest {
    @Test
    fun `exports env variables`() {
        val config = parseConfig(
            """
            issueId: NXT-1234
            bundlesPerRepo:
              - repo: knime-gateway
                bundles:
                  - org.knime.gateway.api
            """.trimIndent()
        )
        val cwd = Paths.get("/tmp/issue")

        val lines = buildEnvExports(cwd, config, "zsh")

        assertEquals(
            listOf(
                "export ISSUE_ID='NXT-1234'",
                "export ISSUE_DIR='/tmp/issue'",
                "export ISSUE_CONFIG='/tmp/issue/config.yaml'"
            ),
            lines
        )
    }

    @Test
    fun `escapes single quotes in values`() {
        val config = parseConfig(
            """
            issueId: NXT-12'34
            bundlesPerRepo:
              - repo: knime-gateway
                bundles:
                  - org.knime.gateway.api
            """.trimIndent()
        )
        val cwd = Paths.get("/tmp/issue's")

        val lines = buildEnvExports(cwd, config, "bash")

        assertEquals("export ISSUE_ID='NXT-12'\"'\"'34'", lines[0])
        assertEquals("export ISSUE_DIR='/tmp/issue'\"'\"'s'", lines[1])
        assertEquals("export ISSUE_CONFIG='/tmp/issue'\"'\"'s/config.yaml'", lines[2])
    }

    @Test
    fun `rejects unsupported shells`() {
        val config = parseConfig(
            """
            issueId: NXT-1234
            bundlesPerRepo:
              - repo: knime-gateway
                bundles:
                  - org.knime.gateway.api
            """.trimIndent()
        )
        val cwd = Paths.get("/tmp/issue")

        val ex = assertFailsWith<CliException> { buildEnvExports(cwd, config, "fish") }
        assertEquals("Unsupported shell 'fish'. Supported shells: sh, bash, zsh", ex.message)
    }
}
