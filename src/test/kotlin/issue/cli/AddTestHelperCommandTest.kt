package issue.cli

import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AddTestHelperCommandTest {
    private val originalUserDir = System.getProperty("user.dir")
    private val originalOut = System.out

    @BeforeTest
    fun setUp() {
        // no-op; keep hooks for symmetry and future additions
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.dir", originalUserDir)
        System.setOut(originalOut)
    }

    @Test
    fun addsTestHelperEntryWithMethods() {
        val tempDir = Files.createTempDirectory("issue-add-test-helper")
        val configPath = tempDir.resolve("config.yaml")
        configPath.writeText(
            """
            issueId: ABC-123
            bundlesPerRepo: []
            """.trimIndent()
        )

        System.setProperty("user.dir", tempDir.toString())
        val stdout = ByteArrayOutputStream()
        System.setOut(PrintStream(stdout))

        val command = AddTestHelperCommand()
        command.testClass = "org.knime.gateway.impl.webui.service.GatewayDefaultServiceTests"
        command.testMethods = "testOne,testTwo"
        command.run()

        val rootMap = readYamlMap(configPath.toFile().readText())
        val tests = rootMap["tests"] as? List<*>
        assertNotNull(tests)
        assertEquals(1, tests.size)

        val entry = tests[0] as? Map<*, *>
        assertNotNull(entry)
        assertEquals("org.knime.gateway.impl", entry["testpluginname"])
        assertEquals(
            "org.knime.gateway.impl.webui.service.GatewayDefaultServiceTests",
            entry["classname"]
        )
        val vmArgs = entry["vmArgs"] as? List<*>
        assertNotNull(vmArgs)
        assertEquals(
            listOf(
                "-Dorg.knime.gateway.testing.helper.test_class=${command.testClass}",
                "-Dorg.knime.gateway.testing.helper.test_method=${command.testMethods}"
            ),
            vmArgs
        )

        assertEquals("Added test helper entry to config.yaml\n", stdout.toString())
    }

    @Test
    fun addsTestHelperEntryWithoutMethods() {
        val tempDir = Files.createTempDirectory("issue-add-test-helper")
        val configPath = tempDir.resolve("config.yaml")
        configPath.writeText(
            """
            bundlesPerRepo: []
            """.trimIndent()
        )

        System.setProperty("user.dir", tempDir.toString())
        val stdout = ByteArrayOutputStream()
        System.setOut(PrintStream(stdout))

        val command = AddTestHelperCommand()
        command.testClass = "org.knime.gateway.impl.webui.service.GatewayDefaultServiceTests"
        command.run()

        val rootMap = readYamlMap(configPath.toFile().readText())
        val tests = rootMap["tests"] as? List<*>
        assertNotNull(tests)
        assertEquals(1, tests.size)

        val entry = tests[0] as? Map<*, *>
        assertNotNull(entry)
        val vmArgs = entry["vmArgs"] as? List<*>
        assertNotNull(vmArgs)
        assertEquals(
            listOf(
                "-Dorg.knime.gateway.testing.helper.test_class=${command.testClass}"
            ),
            vmArgs
        )

        assertEquals("Added test helper entry to config.yaml\n", stdout.toString())
    }

    private fun readYamlMap(contents: String): Map<*, *> {
        val yaml = Yaml()
        return yaml.load<Any>(contents) as Map<*, *>
    }
}
