package issue.cli

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NewCommandTest {
    @Test
    fun `new command writes config template`() {
        val originalHome = System.getProperty("user.home")
        val tempHome = Files.createTempDirectory("issue-new-test")
        System.setProperty("user.home", tempHome.toString())
        try {
            val baseDir = tempHome.resolve("Desktop").resolve("issues")
            Files.createDirectories(baseDir)
            val template = """
                issueId:
                branch:
                bundlesPerRepo:
                  - repo: knime-gateway
                    bundles: []
            """.trimIndent()
            Files.writeString(baseDir.resolve("config.template.yaml"), template)

            val command = NewCommand()
            command.issueId = "NXT-1234"
            command.run()

            val issueDir = baseDir.resolve("issue/NXT-1234")
            assertTrue(Files.isDirectory(issueDir))

            val configPath = issueDir.resolve("config.template")
            assertTrue(Files.isRegularFile(configPath))

            val root = Yaml().load<Any>(Files.readString(configPath)) as Map<*, *>
            assertEquals("NXT-1234", root["issueId"])
            assertEquals("issue/NXT-1234", root["branch"])
            assertNotNull(root["bundlesPerRepo"])
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }
}
