package issue.cli

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

@Command(
    name = "issue",
    mixinStandardHelpOptions = true,
    footerHeading = "Foreach options:%n",
    footer = ["  --no-repo-headers  Disable printing repo names before command output"],
    subcommands = [
        NewCommand::class,
        CloneCommand::class,
        IjInitCommand::class,
        CheckoutCommand::class,
        PullCommand::class,
        RebaseCommand::class,
        ForeachCommand::class,
        CodegenCommand::class,
        FetchJarsCommand::class,
        JiraCommand::class
    ]
)
class IssueCommand

@Command(
    name = "new",
    description = ["Create a new issue directory from config.template.yaml"],
    mixinStandardHelpOptions = true
)
class NewCommand : Runnable {
    @CommandLine.Parameters(
        index = "0",
        paramLabel = "<issue-id>",
        description = ["Jira issue ID (e.g. NXT-1234)"]
    )
    lateinit var issueId: String

    override fun run() {
        val normalizedIssueId = requireNonBlank(issueId, "Issue ID must be non-empty")
        configurePushAutoSetupRemote(currentWorkingDir())
        val baseDir = issueBaseDir()
        Files.createDirectories(baseDir)

        val templatePath = baseDir.resolve("config.template.yaml")
        if (!Files.exists(templatePath)) {
            fail("Template not found: ${templatePath}")
        }

        val jiraIssue = fetchJiraIssue(baseDir, normalizedIssueId)
        val branch = buildBranchName(
            issueId = normalizedIssueId,
            issueType = jiraIssue?.issueType,
            summary = jiraIssue?.summary
        )

        val issueDirName = branch.replace('/', '_')
        val issueDir = baseDir.resolve(issueDirName)
        if (issueDir.exists()) {
            fail("Issue directory already exists: ${issueDir}")
        }
        Files.createDirectories(issueDir)

        val rootMap = loadConfigYaml(templatePath)
        rootMap["issueId"] = normalizedIssueId
        rootMap["branch"] = branch

        val destination = issueDir.resolve("config.yaml")
        writeConfigYaml(destination, rootMap)
        println("Created issue config at ${destination}")
    }
}

@Command(
    name = "clone",
    description = ["Clone repositories with sparse checkout based on config.yaml"],
    mixinStandardHelpOptions = true
)
class CloneCommand : Runnable {
    override fun run() {
        val cwd = currentWorkingDir()
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
            val desiredBundles = allBundles(entry)
            if (desiredBundles.isEmpty()) {
                fail("Repo '${repoName}' has no bundles or nonPdeBundles specified")
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
                runGitWithOutput(
                    cwd,
                    listOf("-C", repoDir.toString(), "fetch", "--prune"),
                    "Failed to fetch repo '${repoName}'"
                )
            } else {
                runGitWithOutput(
                    cwd,
                    listOf("clone", "--filter=blob:none", "--no-checkout", repoUrl, repoDir.toString()),
                    "Failed to clone repo '${repoName}' from ${repoUrl}"
                )
            }

            runGitWithOutput(
                cwd,
                listOf("-C", repoDir.toString(), "sparse-checkout", "init", "--cone"),
                "Failed to init sparse checkout for repo '${repoName}'"
            )
            val existingSparsePaths = parseSparseCheckoutList(
                runGitCapture(
                    cwd,
                    listOf("-C", repoDir.toString(), "sparse-checkout", "list"),
                    "Failed to list sparse checkout paths for repo '${repoName}'"
                )
            )
            if (existingSparsePaths.isEmpty()) {
                runGitWithOutput(
                    cwd,
                    listOf("-C", repoDir.toString(), "sparse-checkout", "set", "--") + desiredBundles,
                    "Failed to set sparse checkout paths for repo '${repoName}'"
                )
            } else {
                val missingBundles = desiredBundles.filterNot { existingSparsePaths.contains(it) }
                if (missingBundles.isNotEmpty()) {
                    runGitWithOutput(
                        cwd,
                        listOf("-C", repoDir.toString(), "sparse-checkout", "add", "--") + missingBundles,
                        "Failed to add sparse checkout paths for repo '${repoName}'"
                    )
                }
            }
            runGitWithOutput(
                cwd,
                listOf("-C", repoDir.toString(), "checkout"),
                "Failed to checkout repo '${repoName}'"
            )
            val configuredBranch = config.branch?.trim().orEmpty()
            if (configuredBranch.isNotBlank()) {
                val localBranches = parseBranchList(
                    runGitCapture(
                        cwd,
                        listOf("-C", repoDir.toString(), "branch", "--list"),
                        "Failed to list branches for repo '${repoName}'"
                    )
                )
                val remoteBranches = parseBranchList(
                    runGitCapture(
                        cwd,
                        listOf("-C", repoDir.toString(), "branch", "-r", "--list"),
                        "Failed to list remote branches for repo '${repoName}'"
                    )
                ).filterNot { it == "origin/HEAD" }
                when (val selection = selectCheckoutForConfiguredBranch(
                    configuredBranch,
                    localBranches,
                    remoteBranches
                )) {
                    is ConfiguredBranchCheckout.Local -> runGitWithOutput(
                        cwd,
                        listOf("-C", repoDir.toString(), "checkout", selection.branch),
                        "Failed to checkout branch '${selection.branch}' for repo '${repoName}'"
                    )
                    is ConfiguredBranchCheckout.Remote -> runGitWithOutput(
                        cwd,
                        listOf("-C", repoDir.toString(), "checkout", "-t", selection.branch),
                        "Failed to checkout tracking branch '${selection.branch}' for repo '${repoName}'"
                    )
                    is ConfiguredBranchCheckout.Create -> runGitWithOutput(
                        cwd,
                        listOf(
                            "-C",
                            repoDir.toString(),
                            "checkout",
                            "--no-track",
                            "-b",
                            selection.branch,
                            "origin/HEAD"
                        ),
                        "Failed to create branch '${selection.branch}' for repo '${repoName}'"
                    )
                }
            }
            if (repoAlreadyExists) {
                runGitWithOutput(
                    cwd,
                    listOf("-C", repoDir.toString(), "pull", "--ff-only"),
                    "Failed to update repo '${repoName}'"
                )
            }

            val missing = desiredBundles.filter { !repoDir.resolve(it).isDirectory() }
            if (missing.isNotEmpty()) {
                fail(
                    "Repo '${repoName}' is missing bundle directories after checkout: " +
                        missing.joinToString(", ")
                )
            }
        }
    }
}

@Command(
    name = "ij-init",
    description = ["Initialize the IntelliJ project and modules from config.yaml"],
    mixinStandardHelpOptions = true
)
class IjInitCommand : Runnable {
    override fun run() {
        val cwd = currentWorkingDir()
        val configPath = findConfigPath(cwd)
        if (configPath == null) {
            ensureIjProjectDir(cwd)
            return
        }
        initIjProjectFromConfig(configPath)
    }
}

@Command(
    name = "checkout",
    description = ["Checkout the branch matching issueId from config.yaml in each repo"],
    mixinStandardHelpOptions = true
)
class CheckoutCommand : Runnable {
    @Option(
        names = ["-b"],
        description = ["Create branch from config.yaml branch if missing"]
    )
    var createBranch: Boolean = false

    override fun run() {
        val cwd = currentWorkingDir()
        val configPath = cwd.resolve("config.yaml")
        if (!Files.exists(configPath)) {
            fail("config.yaml not found in current directory: ${cwd}")
        }

        val config = loadConfig(configPath)
        val issueId = config.issueId?.trim().orEmpty()
        if (issueId.isBlank()) {
            fail("config.yaml must contain a non-empty 'issueId'")
        }
        if (config.bundlesPerRepo.isEmpty()) {
            fail("config.yaml has no bundlesPerRepo entries")
        }
        val branch = config.branch?.trim().orEmpty()
        if (createBranch && branch.isBlank()) {
            fail("config.yaml must contain a non-empty 'branch' when using -b")
        }

        for (entry in config.bundlesPerRepo) {
            val repoName = entry.repo
            if (repoName.isBlank()) {
                fail("Found repo entry with empty name")
            }
            val repoDir = cwd.resolve(repoName)
            if (!repoDir.isDirectory()) {
                fail("Repo directory not found for '${repoName}': ${repoDir}")
            }

            val branchesOutput = runGitCapture(
                cwd,
                listOf("-C", repoDir.toString(), "branch", "--list"),
                "Failed to list branches for repo '${repoName}'"
            )
            val localBranches = parseBranchList(branchesOutput)
            val localBranch = selectSingleMatchingBranch(
                localBranches,
                issueId,
                "local"
            )
            if (localBranch != null) {
                runGit(
                    cwd,
                    listOf("-C", repoDir.toString(), "checkout", localBranch),
                    "Failed to checkout branch '${localBranch}' for repo '${repoName}'"
                )
                continue
            }
            if (createBranch && localBranches.contains(branch)) {
                runGit(
                    cwd,
                    listOf("-C", repoDir.toString(), "checkout", branch),
                    "Failed to checkout branch '${branch}' for repo '${repoName}'"
                )
                continue
            }

            val remoteOutput = runGitCapture(
                cwd,
                listOf("-C", repoDir.toString(), "branch", "-r", "--list"),
                "Failed to list remote branches for repo '${repoName}'"
            )
            val remoteBranches = parseBranchList(remoteOutput).filterNot { it == "origin/HEAD" }
            val remoteBranch = selectSingleMatchingBranch(
                remoteBranches,
                issueId,
                "remote"
            )
            if (remoteBranch != null) {
                runGit(
                    cwd,
                    listOf("-C", repoDir.toString(), "checkout", "-t", remoteBranch),
                    "Failed to checkout tracking branch '${remoteBranch}' for repo '${repoName}'"
                )
                continue
            }

            if (createBranch) {
                val trackingBranch = "origin/${branch}"
                if (remoteBranches.contains(trackingBranch)) {
                    runGit(
                        cwd,
                        listOf("-C", repoDir.toString(), "checkout", "-t", trackingBranch),
                        "Failed to checkout tracking branch '${trackingBranch}' for repo '${repoName}'"
                    )
                } else {
                    runGit(
                        cwd,
                    listOf(
                        "-C",
                        repoDir.toString(),
                        "checkout",
                        "--no-track",
                        "-b",
                        branch,
                        "origin/HEAD"
                    ),
                        "Failed to create branch '${branch}' for repo '${repoName}'"
                    )
                }
                continue
            }

            fail("No local or remote branch containing '${issueId}' found for repo '${repoName}'")
        }
    }
}

@Command(
    name = "pull",
    description = ["Run git pull in each configured repo"],
    mixinStandardHelpOptions = true
)
class PullCommand : Runnable {
    override fun run() {
        val cwd = currentWorkingDir()
        val configPath = cwd.resolve("config.yaml")
        if (!Files.exists(configPath)) {
            fail("config.yaml not found in current directory: ${cwd}")
        }

        val config = loadConfig(configPath)
        if (config.bundlesPerRepo.isEmpty()) {
            fail("config.yaml has no bundlesPerRepo entries")
        }

        val repoDirs = resolveRepoDirs(cwd, config.bundlesPerRepo)
        for (repoDir in repoDirs) {
            runGit(
                cwd,
                listOf("-C", repoDir.path.toString(), "pull"),
                "Failed to pull repo '${repoDir.name}'"
            )
        }
    }
}

@Command(
    name = "rebase",
    description = ["Run git rebase origin/<branch> in each configured repo"],
    mixinStandardHelpOptions = true
)
class RebaseCommand : Runnable {
    @CommandLine.Parameters(index = "0", paramLabel = "<branch>", description = ["Branch to rebase onto"])
    lateinit var branch: String

    override fun run() {
        val cwd = currentWorkingDir()
        val configPath = cwd.resolve("config.yaml")
        if (!Files.exists(configPath)) {
            fail("config.yaml not found in current directory: ${cwd}")
        }

        val config = loadConfig(configPath)
        if (config.bundlesPerRepo.isEmpty()) {
            fail("config.yaml has no bundlesPerRepo entries")
        }

        val targetBranch = toOriginBranch(branch)
        val repoDirs = resolveRepoDirs(cwd, config.bundlesPerRepo)
        for (repoDir in repoDirs) {
            runGit(
                cwd,
                listOf("-C", repoDir.path.toString(), "rebase", targetBranch),
                "Failed to rebase repo '${repoDir.name}' onto '${targetBranch}'"
            )
        }
    }
}

@Command(
    name = "foreach",
    description = ["Run a shell command in each configured repo"],
    mixinStandardHelpOptions = true
)
class ForeachCommand : Runnable {
    @CommandLine.Parameters(index = "0", paramLabel = "<command>", description = ["Shell command to run"])
    lateinit var command: String

    @Option(
        names = ["--no-repo-headers"],
        description = ["Disable printing repo names before command output"]
    )
    var noRepoHeaders: Boolean = false

    override fun run() {
        val cwd = currentWorkingDir()
        val configPath = cwd.resolve("config.yaml")
        if (!Files.exists(configPath)) {
            fail("config.yaml not found in current directory: ${cwd}")
        }

        val config = loadConfig(configPath)
        if (config.bundlesPerRepo.isEmpty()) {
            fail("config.yaml has no bundlesPerRepo entries")
        }

        val normalizedCommand = requireNonBlank(command, "Command must be non-empty")
        val repoDirs = resolveRepoDirs(cwd, config.bundlesPerRepo)
        for (repoDir in repoDirs) {
            if (!noRepoHeaders) {
                println("\u001b[1m${repoDir.name}\u001b[0m")
            }
            runShellCommand(
                repoDir.path,
                normalizedCommand,
                "Failed to run command in repo '${repoDir.name}'"
            )
        }
    }
}

@Command(
    name = "codegen",
    description = ["Run gateway code generation for configured repositories"],
    mixinStandardHelpOptions = true
)
class CodegenCommand : Runnable {
    override fun run() {
        val cwd = currentWorkingDir()
        val configPath = cwd.resolve("config.yaml")
        if (!Files.exists(configPath)) {
            fail("config.yaml not found in current directory: ${cwd}")
        }

        val config = loadConfig(configPath)
        val comSharedEntry = findRepoEntry(config, "knime-com-shared")
            ?: fail("Repo 'knime-com-shared' not configured in config.yaml")
        if (!hasBundle(comSharedEntry, "com.knime.gateway.codegen")) {
            fail("Bundle 'com.knime.gateway.codegen' is not configured under repo 'knime-com-shared'")
        }

        val gatewayEntry = findRepoEntry(config, "knime-gateway")
            ?: fail("Repo 'knime-gateway' not configured in config.yaml")

        val codegenDir = cwd.resolve(comSharedEntry.repo).resolve("com.knime.gateway.codegen")
        if (!codegenDir.isDirectory()) {
            fail("Codegen bundle directory not found: ${codegenDir}")
        }

        val gatewayDir = cwd.resolve(gatewayEntry.repo)
        if (!gatewayDir.isDirectory()) {
            fail("Repo directory not found for '${gatewayEntry.repo}': ${gatewayDir}")
        }

        val generatedPaths = listOf(
            "org.knime.gateway.api/src/generated",
            "org.knime.gateway.json/src/generated",
            "org.knime.gateway.impl/src/generated"
        )
        val generatedDirs = generatedPaths.map { gatewayDir.resolve(it) }
        for (dir in generatedDirs) {
            deleteRecursively(dir)
        }

        runCommand(
            codegenDir,
            listOf("mvn", "compile", "exec:java", "-Dexec.mainClass=com.knime.gateway.codegen.Generate"),
            "Failed to run gateway codegen in ${codegenDir}"
        )

        val stagedPaths = generatedPaths.filter { path ->
            val absPath = gatewayDir.resolve(path)
            Files.exists(absPath) || runGitCapture(
                gatewayDir,
                listOf("ls-files", "--", path),
                "Failed to check tracked generated files in ${gatewayDir}"
            ).isNotBlank()
        }
        if (stagedPaths.isNotEmpty()) {
            runGit(
                gatewayDir,
                listOf("add", "-A", "--") + stagedPaths,
                "Failed to stage generated code in ${gatewayDir}"
            )
        }
    }
}

@Command(
    name = "fetch_jars",
    description = ["Run mvn clean package in bundle lib/fetch_jars directories"],
    mixinStandardHelpOptions = true
)
class FetchJarsCommand : Runnable {
    override fun run() {
        val cwd = currentWorkingDir()
        val configPath = cwd.resolve("config.yaml")
        if (!Files.exists(configPath)) {
            fail("config.yaml not found in current directory: ${cwd}")
        }

        val config = loadConfig(configPath)
        if (config.bundlesPerRepo.isEmpty()) {
            fail("config.yaml has no bundlesPerRepo entries")
        }

        val fetchDirs = findFetchJarsDirs(cwd, config)
        for (dir in fetchDirs) {
            runCommand(
                dir,
                listOf("mvn", "clean", "package"),
                "Failed to run mvn clean package in ${dir}"
            )
        }
    }
}

@Command(
    name = "jira",
    description = ["Open the Jira issue page for issueId from config.yaml"],
    mixinStandardHelpOptions = true
)
class JiraCommand : Runnable {
    override fun run() {
        val cwd = currentWorkingDir()
        val configPath = cwd.resolve("config.yaml")
        if (!Files.exists(configPath)) {
            fail("config.yaml not found in current directory: ${cwd}")
        }

        val config = loadConfig(configPath)
        val issueId = config.issueId?.trim().orEmpty()
        val url = jiraUrl(issueId)
        openUrl(cwd, url)
        println(url)
    }
}

private fun runGit(workingDir: Path, args: List<String>, errorMessage: String) {
    val output = runGitCapture(workingDir, args, errorMessage)
    if (output.isNotBlank()) {
        // already handled by runGitCapture; keep for parity with previous behavior
    }
}

private fun configurePushAutoSetupRemote(workingDir: Path) {
    runGit(
        workingDir,
        listOf("config", "--global", "push.autoSetupRemote", "true"),
        "Failed to set git config push.autoSetupRemote"
    )
}

private fun runGitWithOutput(workingDir: Path, args: List<String>, errorMessage: String) {
    val output = runGitCapture(workingDir, args, errorMessage)
    if (output.isNotBlank()) {
        println(output)
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

private fun runCommand(workingDir: Path, command: List<String>, errorMessage: String) {
    val process = ProcessBuilder(command)
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

private fun openUrl(workingDir: Path, url: String) {
    val uri = java.net.URI(url)
    try {
        if (java.awt.Desktop.isDesktopSupported()) {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                desktop.browse(uri)
                return
            }
        }
    } catch (_: Exception) {
        // Fall back to platform-specific command
    }

    val osName = System.getProperty("os.name").lowercase()
    val command = when {
        osName.contains("mac") -> listOf("open", url)
        osName.contains("win") -> listOf("cmd", "/c", "start", "", url)
        else -> listOf("xdg-open", url)
    }
    runCommand(workingDir, command, "Failed to open Jira URL")
}

private fun runShellCommand(workingDir: Path, command: String, errorMessage: String) {
    val process = ProcessBuilder(listOf("sh", "-c", command))
        .directory(workingDir.toFile())
        .redirectErrorStream(true)
        .start()

    val output = StringBuilder()
    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
            println(line)
            output.appendLine(line)
        }
    }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        val details = if (output.isBlank()) "" else "\n${output.toString().trimEnd()}"
        fail("${errorMessage}.${details}")
    }
}

private fun currentWorkingDir(): Path =
    Paths.get(System.getProperty("user.dir")).toAbsolutePath()

private fun issueBaseDir(): Path =
    Paths.get(System.getProperty("user.home"), "Desktop", "issues").toAbsolutePath()

internal fun findConfigPath(startDir: Path): Path? {
    var current = startDir.toAbsolutePath()
    while (true) {
        val candidate = current.resolve("config.yaml")
        if (Files.exists(candidate)) {
            return candidate
        }
        val parent = current.parent ?: return null
        if (parent == current) {
            return null
        }
        current = parent
    }
}

internal fun normalizeProfilePath(value: String): String {
    return value.trim().trimEnd('/', '\\')
}

internal fun resolveProfilePath(baseDir: Path, value: String): String {
    val normalized = normalizeProfilePath(value)
    if (normalized.isBlank()) {
        return ""
    }
    val path = Paths.get(normalized)
    val resolved = if (path.isAbsolute) path else baseDir.resolve(path)
    return resolved.normalize().toString()
}

private fun deleteRecursively(path: Path) {
    if (!path.exists()) {
        return
    }
    Files.walk(path)
        .sorted(Comparator.reverseOrder())
        .forEach { Files.deleteIfExists(it) }
}

private fun ensureGitRepo(workingDir: Path, repoDir: Path, repoName: String) {
    runGit(
        workingDir,
        listOf("-C", repoDir.toString(), "rev-parse", "--git-dir"),
        "Existing repo '${repoName}' is not a git repository"
    )
}

internal data class Config(
    val issueId: String?,
    val bundlesPerRepo: List<RepoEntry>,
    val branch: String? = null,
    val profilePath: String? = null,
    val formatterConfigPath: String? = null
)

private data class JiraAuth(
    val baseUrl: String,
    val email: String,
    val apiToken: String
)

private data class JiraIssueInfo(
    val issueType: String?,
    val summary: String?
)

internal data class RepoEntry(
    val repo: String,
    val bundles: List<String>,
    val nonPdeBundles: List<String>
)

class CliException(message: String) : RuntimeException(message)

private val IJ_TEMPLATE_DIRS = listOf(
    ".idea",
    ".idea/inspectionProfiles",
    "ij-module-files",
    "src"
)

private val SUMMARY_REGEX =
    Regex("\"summary\"\\s*:\\s*\"((?:\\\\.|[^\\\"]*)*)\"")
private val ISSUETYPE_REGEX =
    Regex("\"issuetype\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"((?:\\\\.|[^\\\"]*)*)\"", RegexOption.DOT_MATCHES_ALL)

private data class TemplateFile(val resourcePath: String, val destinationPath: String)

private val IJ_TEMPLATE_FILES = listOf(
    TemplateFile("ij-project/_gitignore", ".gitignore"),
    TemplateFile("ij-project/.idea/gitignore", ".idea/.gitignore"),
    TemplateFile("ij-project/.idea/modules.xml", ".idea/modules.xml"),
    TemplateFile("ij-project/.idea/workspace.xml", ".idea/workspace.xml"),
    TemplateFile("ij-project/.idea/misc.xml", ".idea/misc.xml"),
    TemplateFile("ij-project/.idea/eclipse-partial.xml", ".idea/eclipse-partial.xml"),
    TemplateFile("ij-project/.idea/vcs.xml", ".idea/vcs.xml"),
    TemplateFile(
        "ij-project/.idea/inspectionProfiles/Project_Default.xml",
        ".idea/inspectionProfiles/Project_Default.xml"
    ),
    TemplateFile("ij-project/ij-project.iml", "ij-project.iml")
)

private val ECLIPSE_TARGET_LOCATION_REGEX =
    Regex("""(<location[^>]*?\slocation=")([^"]+)(")""")

internal fun copyIjTemplate(targetDir: Path) {
    Files.createDirectories(targetDir)
    for (dir in IJ_TEMPLATE_DIRS) {
        Files.createDirectories(targetDir.resolve(dir))
    }

    val loader = IssueCommand::class.java.classLoader
    for (file in IJ_TEMPLATE_FILES) {
        val stream = loader.getResourceAsStream(file.resourcePath)
            ?: fail("Template resource missing: ${file.resourcePath}")
        val destination = targetDir.resolve(file.destinationPath)
        Files.createDirectories(destination.parent)
        stream.use { input ->
            Files.copy(input, destination)
        }
    }

    ensureModuleExcludesBin(targetDir.resolve("ij-project.iml"))
}

private fun ensureIjProjectDir(cwd: Path): Path {
    val targetDir = cwd.resolve("ij-project")
    if (!targetDir.exists()) {
        copyIjTemplate(targetDir)
    } else if (!targetDir.isDirectory()) {
        fail("ij-project exists but is not a directory: ${targetDir}")
    }
    return targetDir
}

internal fun updateEclipseTargetLocation(projectDir: Path, profilePath: String) {
    val eclipseFile = projectDir.resolve(".idea/eclipse-partial.xml").toFile()
    if (!eclipseFile.isFile) {
        fail("Eclipse template file not found: ${eclipseFile.toPath()}")
    }
    val contents = eclipseFile.readText()
    val escaped = xmlEscape(profilePath)
    val match = ECLIPSE_TARGET_LOCATION_REGEX.find(contents)
        ?: fail("Failed to locate eclipse target location in ${eclipseFile.toPath()}")
    val replacement = "${match.groupValues[1]}${escaped}${match.groupValues[3]}"
    val updated = contents.replaceRange(match.range, replacement)
    if (updated == contents) {
        fail("Failed to update eclipse target location in ${eclipseFile.toPath()}")
    }
    eclipseFile.writeText(updated)
}

internal fun updateEclipseFormatterConfig(projectDir: Path, formatterConfigPath: String) {
    val formatterFile = projectDir.resolve(".idea/eclipseCodeFormatter.xml").toFile()
    val escapedPath = xmlEscape(formatterConfigPath)
    val selectedProfile = xmlEscape("valid 'org.eclipse.jdt.core.prefs' config")
    val builder = StringBuilder()
    builder.appendLine("""<project version="4">""")
    builder.appendLine("""  <component name="EclipseCodeFormatterProjectSettings">""")
    builder.appendLine("""    <option name="projectSpecificProfile">""")
    builder.appendLine("""      <ProjectSpecificProfile>""")
    builder.appendLine("""        <option name="formatter" value="ECLIPSE" />""")
    builder.appendLine(
        """        <option name="pathToConfigFileJava" value="${escapedPath}" />"""
    )
    builder.appendLine(
        """        <option name="selectedJavaProfile" value="${selectedProfile}" />"""
    )
    builder.appendLine("""      </ProjectSpecificProfile>""")
    builder.appendLine("""    </option>""")
    builder.appendLine("""  </component>""")
    builder.appendLine("""</project>""")
    formatterFile.parentFile.mkdirs()
    formatterFile.writeText(builder.toString())
}

internal fun applyIjConfig(baseDir: Path, projectDir: Path, config: Config) {
    val profilePath = resolveProfilePath(baseDir, config.profilePath?.trim().orEmpty())
    if (profilePath.isNotBlank()) {
        updateEclipseTargetLocation(projectDir, profilePath)
    }
    val formatterConfigPath = config.formatterConfigPath?.trim().orEmpty()
    if (formatterConfigPath.isNotBlank()) {
        updateEclipseFormatterConfig(projectDir, formatterConfigPath)
    }
}

internal fun initIjProjectFromConfig(configPath: Path) {
    val baseDir = configPath.parent
        ?: fail("config.yaml must have a parent directory: ${configPath}")
    val config = loadConfig(configPath)
    if (config.bundlesPerRepo.isEmpty()) {
        fail("config.yaml has no bundlesPerRepo entries")
    }
    val projectDir = ensureIjProjectDir(baseDir)
    applyIjConfig(baseDir, projectDir, config)
    writeIjModules(baseDir, projectDir, config)
}

internal fun writeIjModules(baseDir: Path, projectDir: Path, config: Config) {
    val moduleDir = projectDir.resolve("ij-module-files")
    Files.createDirectories(moduleDir)

    val modules = mutableListOf<String>()
    val vcsMappings = mutableSetOf<String>()

    for (entry in config.bundlesPerRepo) {
        val repoName = entry.repo
        if (repoName.isBlank()) {
            fail("Found repo entry with empty name")
        }
        if (allBundles(entry).isEmpty()) {
            fail("Repo '${repoName}' has no bundles or nonPdeBundles specified")
        }
        val repoDir = baseDir.resolve(repoName)
        if (!repoDir.isDirectory()) {
            fail("Repo directory not found for '${repoName}': ${repoDir}")
        }

        vcsMappings.add(repoName)

        for (bundle in allBundles(entry)) {
            val bundleDir = repoDir.resolve(bundle)
            if (!bundleDir.isDirectory()) {
                fail("Bundle directory not found: ${bundleDir}")
            }

            val sourceRoots = determineSourceRoots(bundleDir)
            if (sourceRoots.isEmpty()) {
                fail("No source roots found for bundle '${bundle}' in ${bundleDir}")
            }

            val moduleFileName = "${bundle}.iml"
            val moduleFile = moduleDir.resolve(moduleFileName)
            writeModuleFile(
                moduleFile = moduleFile,
                contentRoot = bundleDir.toAbsolutePath().toUri().toString(),
                sourceRoots = sourceRoots.map { bundleDir.relativize(it).toString().replace('\\', '/') },
                excludeFolders = determineExcludedFolders(bundleDir)
            )
            modules.add(moduleFileName)
        }
    }

    writeModulesXml(projectDir, modules)
    writeVcsXml(projectDir, vcsMappings.toList().sorted())
}

internal fun ensureModuleExcludesBin(moduleFile: Path) {
    val excludeLine = """      <excludeFolder url="file://${'$'}MODULE_DIR${'$'}/bin" />"""
    if (!moduleFile.toFile().isFile) {
        fail("Module template file not found: ${moduleFile}")
    }
    val contents = moduleFile.toFile().readText()
    if (contents.contains(excludeLine)) {
        return
    }
    val marker = "    </content>"
    if (!contents.contains(marker)) {
        fail("Module template file missing content marker: ${moduleFile}")
    }
    val updated = contents.replace(marker, "$excludeLine\n$marker")
    moduleFile.toFile().writeText(updated)
}

internal fun determineSourceRoots(bundleDir: Path): List<Path> {
    val srcDir = bundleDir.resolve("src")
    if (!srcDir.isDirectory()) {
        return emptyList()
    }

    val roots = mutableListOf<Path>()
    val eclipse = srcDir.resolve("eclipse")
    if (eclipse.isDirectory()) {
        roots.add(eclipse)
    }
    val generated = srcDir.resolve("generated")
    if (generated.isDirectory()) {
        roots.add(generated)
    }

    if (roots.isEmpty()) {
        roots.add(srcDir)
    }
    return roots
}

internal fun determineExcludedFolders(@Suppress("UNUSED_PARAMETER") bundleDir: Path): List<String> {
    return listOf("bin")
}

private fun writeModulesXml(projectDir: Path, moduleFiles: List<String>) {
    val projectDirVar = "\$PROJECT_DIR\$"
    val builder = StringBuilder()
    builder.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    builder.appendLine("""<project version="4">""")
    builder.appendLine("""  <component name="ProjectModuleManager">""")
    builder.appendLine("""    <modules>""")
    builder.appendLine(
        """      <module fileurl="file://${projectDirVar}/ij-project.iml" filepath="${projectDirVar}/ij-project.iml" />"""
    )
    for (moduleFile in moduleFiles) {
        val escaped = xmlEscape(moduleFile)
        builder.appendLine(
            """      <module fileurl="file://${projectDirVar}/ij-module-files/${escaped}" filepath="${projectDirVar}/ij-module-files/${escaped}" />"""
        )
    }
    builder.appendLine("""    </modules>""")
    builder.appendLine("""  </component>""")
    builder.appendLine("""</project>""")

    Files.createDirectories(projectDir.resolve(".idea"))
    projectDir.resolve(".idea/modules.xml").toFile().writeText(builder.toString())
}

private fun writeVcsXml(projectDir: Path, repos: List<String>) {
    val projectDirVar = "\$PROJECT_DIR\$"
    val builder = StringBuilder()
    builder.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    builder.appendLine("""<project version="4">""")
    builder.appendLine("""  <component name="VcsDirectoryMappings">""")
    for (repo in repos) {
        val escaped = xmlEscape(repo)
        builder.appendLine(
            """    <mapping directory="${projectDirVar}/../${escaped}" vcs="Git" />"""
        )
    }
    builder.appendLine("""  </component>""")
    builder.appendLine("""</project>""")
    Files.createDirectories(projectDir.resolve(".idea"))
    projectDir.resolve(".idea/vcs.xml").toFile().writeText(builder.toString())
}

internal fun writeModuleFile(
    moduleFile: Path,
    contentRoot: String,
    sourceRoots: List<String>,
    excludeFolders: List<String>
) {
    val builder = StringBuilder()
    builder.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    builder.appendLine("""<module type="JAVA_MODULE" version="4">""")
    builder.appendLine("""  <component name="FacetManager">""")
    builder.appendLine(
        """    <facet type="cn.varsa.idea.pde.partial.plugin" name="Eclipse PDE Partial">"""
    )
    builder.appendLine("""      <configuration />""")
    builder.appendLine("""    </facet>""")
    builder.appendLine("""  </component>""")
    builder.appendLine("""  <component name="NewModuleRootManager" inherit-compiler-output="true">""")
    builder.appendLine("""    <exclude-output />""")
    builder.appendLine("""    <content url="${xmlEscape(contentRoot)}">""")
    for (root in sourceRoots) {
        val escaped = xmlEscape(root)
        builder.appendLine(
            """      <sourceFolder url="${xmlEscape(contentRoot)}/${escaped}" isTestSource="false" />"""
        )
    }
    for (folder in excludeFolders) {
        val escaped = xmlEscape(folder)
        builder.appendLine("""      <excludeFolder url="${xmlEscape(contentRoot)}/${escaped}" />""")
    }
    builder.appendLine("""    </content>""")
    builder.appendLine("""    <orderEntry type="inheritedJdk" />""")
    builder.appendLine("""    <orderEntry type="sourceFolder" forTests="false" />""")
    builder.appendLine("""  </component>""")
    builder.appendLine("""</module>""")

    Files.createDirectories(moduleFile.parent)
    moduleFile.toFile().writeText(builder.toString())
}

private fun xmlEscape(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

internal fun parseBranchList(output: String): List<String> {
    return output
        .lineSequence()
        .map { it.removePrefix("*").trim() }
        .filter { it.isNotBlank() }
        .map { line ->
            val arrowIndex = line.indexOf(" -> ")
            if (arrowIndex >= 0) line.substring(0, arrowIndex) else line
        }
        .distinct()
        .toList()
}

internal fun parseSparseCheckoutList(output: String): List<String> {
    return output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

internal sealed class ConfiguredBranchCheckout {
    data class Local(val branch: String) : ConfiguredBranchCheckout()
    data class Remote(val branch: String) : ConfiguredBranchCheckout()
    data class Create(val branch: String) : ConfiguredBranchCheckout()
}

internal fun selectCheckoutForConfiguredBranch(
    branch: String,
    localBranches: List<String>,
    remoteBranches: List<String>
): ConfiguredBranchCheckout {
    val trimmed = requireNonBlank(branch, "Branch name must be non-empty").removePrefix("origin/")
    val originBranch = toOriginBranch(trimmed)
    return when {
        localBranches.contains(trimmed) -> ConfiguredBranchCheckout.Local(trimmed)
        remoteBranches.contains(originBranch) -> ConfiguredBranchCheckout.Remote(originBranch)
        else -> ConfiguredBranchCheckout.Create(trimmed)
    }
}

internal fun toOriginBranch(branch: String): String {
    val trimmed = branch.trim()
    if (trimmed.isBlank()) {
        fail("Branch name must be non-empty")
    }
    val withoutPrefix = trimmed.removePrefix("origin/")
    return "origin/${withoutPrefix}"
}

internal fun requireNonBlank(value: String, errorMessage: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) {
        fail(errorMessage)
    }
    return trimmed
}

internal fun jiraUrl(issueId: String): String {
    val normalized = requireNonBlank(issueId, "config.yaml must contain a non-empty 'issueId'")
    return "https://knime-com.atlassian.net/browse/${normalized}"
}

internal fun selectSingleMatchingBranch(
    branches: List<String>,
    issueId: String,
    scopeLabel: String
): String? {
    val matches = branches.filter { it.contains(issueId) }.distinct()
    if (matches.isEmpty()) {
        return null
    }
    if (matches.size > 1) {
        fail("Multiple ${scopeLabel} branches match '${issueId}': ${matches.joinToString(", ")}")
    }
    return matches.single()
}

internal fun findRepoEntry(config: Config, repoName: String): RepoEntry? {
    return config.bundlesPerRepo.firstOrNull { it.repo == repoName }
}

internal fun hasBundle(entry: RepoEntry, bundleName: String): Boolean {
    return entry.bundles.contains(bundleName) || entry.nonPdeBundles.contains(bundleName)
}

internal fun findFetchJarsDirs(cwd: Path, config: Config): List<Path> {
    val results = mutableListOf<Path>()
    for (entry in config.bundlesPerRepo) {
        val repoName = entry.repo
        if (repoName.isBlank()) {
            fail("Found repo entry with empty name")
        }
        val repoDir = cwd.resolve(repoName)
        if (!repoDir.isDirectory()) {
            warn("Repo directory not found for '${repoName}': ${repoDir}; skipping")
            continue
        }
        for (bundle in allBundles(entry)) {
            val bundleDir = repoDir.resolve(bundle)
            if (!bundleDir.isDirectory()) {
                warn("Bundle directory not found: ${bundleDir}; skipping")
                continue
            }
            val fetchDir = bundleDir.resolve("lib/fetch_jars")
            if (fetchDir.isDirectory()) {
                results.add(fetchDir)
            }
        }
    }
    return results
}

internal data class RepoDir(val name: String, val path: Path)

internal fun allBundles(entry: RepoEntry): List<String> {
    return (entry.bundles + entry.nonPdeBundles).distinct()
}

internal fun resolveRepoDirs(cwd: Path, entries: List<RepoEntry>): List<RepoDir> {
    return entries.map { entry ->
        val repoName = entry.repo
        if (repoName.isBlank()) {
            fail("Found repo entry with empty name")
        }
        val repoDir = cwd.resolve(repoName)
        if (!repoDir.isDirectory()) {
            fail("Repo directory not found for '${repoName}': ${repoDir}")
        }
        RepoDir(repoName, repoDir)
    }
}

private fun loadConfig(path: Path): Config {
    val contents = path.toFile().readText()
    return parseConfig(contents)
}

@Suppress("UNCHECKED_CAST")
private fun loadConfigYaml(path: Path): MutableMap<Any?, Any?> {
    val contents = path.toFile().readText()
    val yaml = Yaml()
    val root = yaml.load<Any>(contents)
        ?: fail("config.yaml is empty")
    val rootMap = root as? MutableMap<Any?, Any?>
        ?: fail("config.yaml must be a mapping at the root")
    return rootMap
}

private fun writeConfigYaml(path: Path, rootMap: MutableMap<Any?, Any?>) {
    val options = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
        isPrettyFlow = true
        indent = 2
        indicatorIndent = 0
    }
    val yaml = Yaml(options)
    val output = yaml.dump(rootMap).trimEnd()
    path.toFile().writeText("${output}\n")
}

private fun loadEnvFile(path: Path): Map<String, String> {
    if (!Files.exists(path)) {
        return emptyMap()
    }
    return Files.readAllLines(path)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) {
                return@mapNotNull null
            }
            val key = line.substring(0, separatorIndex).trim()
            var value = line.substring(separatorIndex + 1).trim()
            if (value.length >= 2 &&
                ((value.startsWith('"') && value.endsWith('"')) ||
                    (value.startsWith('\'') && value.endsWith('\'')))
            ) {
                value = value.substring(1, value.length - 1)
            }
            if (key.isBlank()) null else key to value
        }
        .toMap()
}

private fun loadJiraAuth(baseDir: Path): JiraAuth? {
    val envPath = baseDir.resolve(".env")
    val env = loadEnvFile(envPath)
    if (env.isEmpty()) {
        return null
    }
    val url = env["JIRA_URL"]?.trim().orEmpty()
    val email = env["JIRA_EMAIL"]?.trim().orEmpty()
    val token = env["JIRA_API_TOKEN"]?.trim().orEmpty()
    if (url.isBlank() || email.isBlank() || token.isBlank()) {
        warn("Missing JIRA_URL/JIRA_EMAIL/JIRA_API_TOKEN in ${envPath}; falling back to local branch name")
        return null
    }
    return JiraAuth(baseUrl = url, email = email, apiToken = token)
}

private fun fetchJiraIssue(baseDir: Path, issueId: String): JiraIssueInfo? {
    val auth = loadJiraAuth(baseDir) ?: return null
    val url = auth.baseUrl.trimEnd('/') + "/rest/api/3/issue/${issueId}?fields=summary,issuetype"
    val request = HttpRequest.newBuilder()
        .uri(URI(url))
        .timeout(Duration.ofSeconds(20))
        .header("Accept", "application/json")
        .header("Authorization", jiraAuthorizationHeader(auth))
        .GET()
        .build()
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    return try {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            warn("Failed to fetch Jira issue ${issueId} (status ${response.statusCode()}); using local branch name")
            null
        } else {
            val body = response.body()
            val summary = extractJsonString(body, SUMMARY_REGEX)
            val issueType = extractJsonString(body, ISSUETYPE_REGEX)
            JiraIssueInfo(issueType = issueType, summary = summary)
        }
    } catch (ex: Exception) {
        warn("Failed to fetch Jira issue ${issueId}; using local branch name")
        null
    }
}

private fun jiraAuthorizationHeader(auth: JiraAuth): String {
    val authValue = "${auth.email}:${auth.apiToken}"
    val encoded = Base64.getEncoder().encodeToString(authValue.toByteArray(Charsets.UTF_8))
    return "Basic ${encoded}"
}

private fun extractJsonString(contents: String, regex: Regex): String? {
    val match = regex.find(contents) ?: return null
    return unescapeJsonString(match.groupValues[1])
}

private fun unescapeJsonString(value: String): String {
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val current = value[index]
        if (current == '\\' && index + 1 < value.length) {
            val next = value[index + 1]
            when (next) {
                '"', '\\', '/' -> builder.append(next)
                'b' -> builder.append('\b')
                'f' -> builder.append('\u000C')
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                'u' -> {
                    if (index + 5 < value.length) {
                        val hex = value.substring(index + 2, index + 6)
                        builder.append(hex.toInt(16).toChar())
                        index += 4
                    }
                }
                else -> builder.append(next)
            }
            index += 2
            continue
        }
        builder.append(current)
        index += 1
    }
    return builder.toString()
}

private fun buildBranchName(issueId: String, issueType: String?, summary: String?): String {
    val prefix = branchPrefixForIssueType(issueType)
    val summarySlug = slugify(summary)
    return if (summarySlug.isBlank()) {
        "${prefix}/${issueId}"
    } else {
        "${prefix}/${issueId}-${summarySlug}"
    }
}

internal fun branchPrefixForIssueType(issueType: String?): String {
    val slug = slugify(issueType)
    if (slug.isBlank()) {
        return "issue"
    }
    return when (slug) {
        "enhancement" -> "enh"
        else -> slug
    }
}

private fun slugify(value: String?): String {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isBlank()) {
        return ""
    }
    return trimmed
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

internal fun parseConfig(contents: String): Config {
    val yaml = Yaml()
    val root = yaml.load<Any>(contents)
        ?: fail("config.yaml is empty")

    val rootMap = root as? Map<*, *> ?: fail("config.yaml must be a mapping at the root")
    val bundlesPerRepoAny = rootMap["bundlesPerRepo"]
        ?: fail("config.yaml must contain 'bundlesPerRepo'")
    val issueId = rootMap["issueId"] as? String
    val branch = rootMap["branch"] as? String
    val profilePath = rootMap["profilePath"] as? String
    val formatterConfigPath = rootMap["formatterConfigPath"] as? String

    val bundlesPerRepoList = bundlesPerRepoAny as? List<*>
        ?: fail("'bundlesPerRepo' must be a list")

    val entries = bundlesPerRepoList.mapIndexed { index, item ->
        val itemMap = item as? Map<*, *> ?: fail("bundlesPerRepo[${index}] must be a mapping")
        val repo = itemMap["repo"] as? String
            ?: fail("bundlesPerRepo[${index}].repo must be a string")
        val bundlesAny = itemMap["bundles"]
        val bundles = parseBundleNames(
            bundlesAny,
            "bundlesPerRepo[${index}].bundles",
            "bundlesPerRepo[${index}].bundles must be a list"
        )
        val nonPdeBundlesAny = itemMap["nonPdeBundles"]
        val nonPdeBundles = parseBundleNames(
            nonPdeBundlesAny,
            "bundlesPerRepo[${index}].nonPdeBundles",
            "bundlesPerRepo[${index}].nonPdeBundles must be a list"
        )
        RepoEntry(repo = repo, bundles = bundles, nonPdeBundles = nonPdeBundles)
    }

    return Config(
        issueId = issueId,
        bundlesPerRepo = entries,
        branch = branch,
        profilePath = profilePath,
        formatterConfigPath = formatterConfigPath
    )
}

private fun fail(message: String): Nothing {
    throw CliException(message)
}

private fun warn(message: String) {
    System.err.println("Warning: ${message}")
}

private fun parseBundleNames(
    bundlesAny: Any?,
    pathPrefix: String,
    notListError: String
): List<String> {
    return when (bundlesAny) {
        null -> emptyList()
        is List<*> -> bundlesAny.mapIndexed { bundleIndex, bundle ->
            when (bundle) {
                is String -> bundle
                is Map<*, *> -> {
                    val name = bundle["name"] as? String
                        ?: fail("${pathPrefix}[${bundleIndex}].name must be a string")
                    name
                }
                else -> fail("${pathPrefix}[${bundleIndex}] must be a string or mapping with name")
            }
        }
        else -> fail(notListError)
    }
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
