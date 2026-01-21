package issue.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodegenConfigTest {
    @Test
    fun `detects codegen bundle in config`() {
        val contents = """
            bundlesPerRepo:
              - repo: knime-com-shared
                bundles:
                  - com.knime.gateway.codegen
              - repo: knime-gateway
                bundles:
                  - org.knime.gateway.api
        """.trimIndent()

        val config = parseConfig(contents)

        val comShared = findRepoEntry(config, "knime-com-shared")
        assertNotNull(comShared)
        assertTrue(hasBundle(comShared, "com.knime.gateway.codegen"))
        assertEquals("knime-gateway", findRepoEntry(config, "knime-gateway")?.repo)
    }

    @Test
    fun `detects codegen bundle in nonPdeBundles`() {
        val contents = """
            bundlesPerRepo:
              - repo: knime-com-shared
                nonPdeBundles:
                  - com.knime.gateway.codegen
              - repo: knime-gateway
                bundles:
                  - org.knime.gateway.api
        """.trimIndent()

        val config = parseConfig(contents)

        val comShared = findRepoEntry(config, "knime-com-shared")
        assertNotNull(comShared)
        assertTrue(hasBundle(comShared, "com.knime.gateway.codegen"))
    }

    @Test
    fun `returns null when repo missing`() {
        val contents = """
            bundlesPerRepo:
              - repo: knime-gateway
                bundles:
                  - org.knime.gateway.api
        """.trimIndent()

        val config = parseConfig(contents)

        assertNull(findRepoEntry(config, "knime-com-shared"))
    }
}
