package issue.cli

import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AddTestCommandTest {
    private val originalUserDir = System.getProperty("user.dir")
    private val originalOut = System.out

    @AfterTest
    fun tearDown() {
        System.setProperty("user.dir", originalUserDir)
        System.setOut(originalOut)
    }

    @Test
    fun addsTestEntry() {
        val tempDir = Files.createTempDirectory("issue-add-test")
        val configPath = tempDir.resolve("config.yaml")
        configPath.writeText(
            """
            bundlesPerRepo: []
            """.trimIndent()
        )

        System.setProperty("user.dir", tempDir.toString())
        val stdout = ByteArrayOutputStream()
        System.setOut(PrintStream(stdout))

        val command = AddTestCommand()
        command.pluginName = "org.knime.gateway.impl"
        command.className = "org.knime.gateway.impl.webui.service.GatewayDefaultServiceTests"
        command.run()

        val rootMap = readYamlMap(configPath.toFile().readText())
        val tests = rootMap["tests"] as? List<*>
        assertNotNull(tests)
        assertEquals(1, tests.size)

        val entry = tests[0] as? Map<*, *>
        assertNotNull(entry)
        assertEquals(command.pluginName, entry["testpluginname"])
        assertEquals(command.className, entry["classname"])

        assertEquals("Added test entry to config.yaml\n", stdout.toString())
    }

    private fun readYamlMap(contents: String): Map<*, *> {
        val yaml = Yaml()
        return yaml.load<Any>(contents) as Map<*, *>
    }
}
