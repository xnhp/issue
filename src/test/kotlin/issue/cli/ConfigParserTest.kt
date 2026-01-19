package issue.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigParserTest {
    @Test
    fun `parses valid config`() {
        val contents = """
            bundlesPerRepo:
              - repo: knime-gateway
                bundles:
                  - org.knime.gateway.api
                  - org.knime.gateway.impl
        """.trimIndent()

        val config = parseConfig(contents)

        assertEquals(1, config.bundlesPerRepo.size)
        assertEquals("knime-gateway", config.bundlesPerRepo[0].repo)
        assertEquals(
            listOf("org.knime.gateway.api", "org.knime.gateway.impl"),
            config.bundlesPerRepo[0].bundles
        )
    }

    @Test
    fun `fails when bundlesPerRepo missing`() {
        val contents = """
            foo: bar
        """.trimIndent()

        val ex = assertFailsWith<CliException> { parseConfig(contents) }
        assertEquals("config.yaml must contain 'bundlesPerRepo'", ex.message)
    }

    @Test
    fun `fails when bundles is not list`() {
        val contents = """
            bundlesPerRepo:
              - repo: knime-gateway
                bundles: foo
        """.trimIndent()

        val ex = assertFailsWith<CliException> { parseConfig(contents) }
        assertEquals("bundlesPerRepo[0].bundles must be a list", ex.message)
    }
}
