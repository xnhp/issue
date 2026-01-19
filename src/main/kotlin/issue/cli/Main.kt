package issue.cli

import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.CommandLine.Command
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
            val repoUrl = "git@github.com:knime/${repoName}.git"

            val repoAlreadyExists = repoDir.exists()
            if (repoAlreadyExists) {
                if (!repoDir.isDirectory()) {
                    fail("Repo path exists but is not a directory for '${repoName}': ${repoDir}")
                }
                ensureGitRepo(cwd, repoDir, repoName)
                val originUrl = runGitCapture(
                    cwd,
                    listOf("-C", repoDir.toString(), "remote", "get-url", "origin"),
                    "Failed to read origin for repo '${repoName}'"
                ).trim()
                if (originUrl != repoUrl) {
                    fail(
                        "Repo '${repoName}' has unexpected origin '${originUrl}', expected '${repoUrl}'"
                    )
                }
                runGit(
                    cwd,
                    listOf("-C", repoDir.toString(), "fetch", "--prune"),
                    "Failed to fetch repo '${repoName}'"
                )
            } else {
                runGit(
                    cwd,
                    listOf("clone", "--filter=blob:none", "--no-checkout", repoUrl, repoDir.toString()),
                    "Failed to clone repo '${repoName}' from ${repoUrl}"
                )
            }

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
            if (repoAlreadyExists) {
                runGit(
                    cwd,
                    listOf("-C", repoDir.toString(), "pull", "--ff-only"),
                    "Failed to update repo '${repoName}'"
                )
            }

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
    val output = runGitCapture(workingDir, args, errorMessage)
    if (output.isNotBlank()) {
        // already handled by runGitCapture; keep for parity with previous behavior
    }
}

private fun runGitCapture(workingDir: Path, args: List<String>, errorMessage: String): String {
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
    return output
}

private fun ensureGitRepo(workingDir: Path, repoDir: Path, repoName: String) {
    runGit(
        workingDir,
        listOf("-C", repoDir.toString(), "rev-parse", "--git-dir"),
        "Existing repo '${repoName}' is not a git repository"
    )
}

internal data class Config(val bundlesPerRepo: List<RepoEntry>)

internal data class RepoEntry(val repo: String, val bundles: List<String>)

class CliException(message: String) : RuntimeException(message)

private fun loadConfig(path: Path): Config {
    val contents = path.toFile().readText()
    return parseConfig(contents)
}

internal fun parseConfig(contents: String): Config {
    val yaml = Yaml()
    val root = yaml.load<Any>(contents)
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
    throw CliException(message)
}

fun main(args: Array<String>) {
    val commandLine = CommandLine(IssueCommand())
    commandLine.setExecutionExceptionHandler { ex, cmd, _ ->
        if (ex is CliException) {
            cmd.err.println("Error: ${ex.message}")
            return@setExecutionExceptionHandler 1
        }
        throw ex
    }
    val exitCode = commandLine.execute(*args)
    exitProcess(exitCode)
}
