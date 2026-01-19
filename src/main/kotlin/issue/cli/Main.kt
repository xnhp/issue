package issue.cli

import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.CommandLine.Command
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

@Command(
    name = "issue",
    mixinStandardHelpOptions = true,
    subcommands = [CloneCommand::class]
)
class IssueCommand

@Command(
    name = "clone",
    description = ["Clone repositories with sparse checkout based on config.yaml"],
    mixinStandardHelpOptions = true
)
class CloneCommand : Runnable {
    override fun run() {
        val cwd = Paths.get("").toAbsolutePath()
        val configPath = cwd.resolve("config.yaml")
        if (!Files.exists(configPath)) {
            fail("config.yaml not found in current directory: ${cwd}")
        }

        val config = loadConfig(configPath)
        if (config.bundlesPerRepo.isEmpty()) {
            fail("config.yaml has no bundlesPerRepo entries")
        }

        for (entry in config.bundlesPerRepo) {
            val repoName = entry.repo
            if (repoName.isBlank()) {
                fail("Found repo entry with empty name")
            }
            if (entry.bundles.isEmpty()) {
                fail("Repo '${repoName}' has no bundles specified")
            }

            val repoDir = cwd.resolve(repoName)
            if (repoDir.exists()) {
                fail("Target directory already exists for repo '${repoName}': ${repoDir}")
            }

            val repoUrl = "git@github.com:knime/${repoName}.git"

            runGit(
                cwd,
                listOf("clone", "--filter=blob:none", "--no-checkout", repoUrl, repoDir.toString()),
                "Failed to clone repo '${repoName}' from ${repoUrl}"
            )
            runGit(
                cwd,
                listOf("-C", repoDir.toString(), "sparse-checkout", "init", "--cone"),
                "Failed to init sparse checkout for repo '${repoName}'"
            )
            runGit(
                cwd,
                listOf("-C", repoDir.toString(), "sparse-checkout", "set", "--") + entry.bundles,
                "Failed to set sparse checkout paths for repo '${repoName}'"
            )
            runGit(
                cwd,
                listOf("-C", repoDir.toString(), "checkout"),
                "Failed to checkout repo '${repoName}'"
            )

            val missing = entry.bundles.filter { !repoDir.resolve(it).isDirectory() }
            if (missing.isNotEmpty()) {
                fail(
                    "Repo '${repoName}' is missing bundle directories after checkout: " +
                        missing.joinToString(", ")
                )
            }
        }
    }
}

private fun runGit(workingDir: Path, args: List<String>, errorMessage: String) {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(workingDir.toFile())
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        val details = if (output.isBlank()) "" else "\n${output}"
        fail("${errorMessage}.${details}")
    }
}

private data class Config(val bundlesPerRepo: List<RepoEntry>)

private data class RepoEntry(val repo: String, val bundles: List<String>)

private fun loadConfig(path: Path): Config {
    val yaml = Yaml()
    val root = path.toFile().inputStream().use { input ->
        yaml.load<Any>(input)
    }
        ?: fail("config.yaml is empty")

    val rootMap = root as? Map<*, *> ?: fail("config.yaml must be a mapping at the root")
    val bundlesPerRepoAny = rootMap["bundlesPerRepo"]
        ?: fail("config.yaml must contain 'bundlesPerRepo'")

    val bundlesPerRepoList = bundlesPerRepoAny as? List<*>
        ?: fail("'bundlesPerRepo' must be a list")

    val entries = bundlesPerRepoList.mapIndexed { index, item ->
        val itemMap = item as? Map<*, *> ?: fail("bundlesPerRepo[${index}] must be a mapping")
        val repo = itemMap["repo"] as? String
            ?: fail("bundlesPerRepo[${index}].repo must be a string")
        val bundlesAny = itemMap["bundles"]
            ?: fail("bundlesPerRepo[${index}].bundles is required")
        val bundlesList = bundlesAny as? List<*>
            ?: fail("bundlesPerRepo[${index}].bundles must be a list")
        val bundles = bundlesList.mapIndexed { bundleIndex, bundle ->
            bundle as? String
                ?: fail("bundlesPerRepo[${index}].bundles[${bundleIndex}] must be a string")
        }
        RepoEntry(repo = repo, bundles = bundles)
    }

    return Config(entries)
}

private fun fail(message: String): Nothing {
    System.err.println("Error: ${message}")
    exitProcess(1)
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(IssueCommand()).execute(*args)
    exitProcess(exitCode)
}
