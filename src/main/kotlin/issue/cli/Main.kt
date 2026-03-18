package issue.cli

import cn.varsa.cli.core.CliException
import cn.varsa.cli.core.CliMain
import cn.varsa.cli.core.CliProcess
import cn.varsa.cli.core.CliStyle
import cn.varsa.cli.core.ColorMode
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
        ReadCommand::class,
        WorktreesCommand::class,
        CloneCommand::class,
        CheckoutCommand::class,
        PullCommand::class,
        RebaseCommand::class,
        ForeachCommand::class
    ]
)
class IssueCommand

@Command(
    name = "worktrees",
    description = ["Worktree operations based on config.yaml"],
    mixinStandardHelpOptions = true,
    subcommands = [WorktreesInitCommand::class, WorktreesForeachCommand::class]
)
class WorktreesCommand : Runnable {
    @CommandLine.Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}

@Command(
    name = "init",
    description = ["Initialize repositories/worktrees from config.yaml"],
    mixinStandardHelpOptions = true
)
class WorktreesInitCommand : Runnable {
    override fun run() {
        CloneCommand().run()
    }
}

@Command(
    name = "foreach",
    description = ["Run a shell command in each configured repo worktree"],
    mixinStandardHelpOptions = true
)
class WorktreesForeachCommand : Runnable {
    @CommandLine.Parameters(index = "0", paramLabel = "<command>", description = ["Shell command to run"])
    lateinit var command: String

    @Option(
        names = ["--no-repo-headers"],
        description = ["Disable printing repo names before command output"]
    )
    var noRepoHeaders: Boolean = false

    override fun run() {
        val delegate = ForeachCommand()
        delegate.command = command
        delegate.noRepoHeaders = noRepoHeaders
        delegate.run()
    }
}

@Command(
    name = "new",
    description = ["Create a new issue directory with issue.yaml metadata"],
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

        val destination = issueDir.resolve("issue.yaml")
        writeIssueMetadata(destination, IssueMetadata(id = normalizedIssueId, branch = branch))
        info("Created issue metadata at ${destination}")
    }
}

@Command(
    name = "read",
    description = ["Read a property value from issue.yaml"],
    mixinStandardHelpOptions = true
)
class ReadCommand : Runnable {
    @CommandLine.Parameters(
        index = "0",
        paramLabel = "<prop>",
        description = ["Property name to read from issue.yaml"]
    )
    lateinit var prop: String

    override fun run() {
        val property = requireNonBlank(prop, "Property name must be non-empty")
        val value = readIssueProperty(currentWorkingDir(), property)
        println(value)
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
        val issueContext = resolveIssueContext(cwd)
        val configuredBranch = issueContext.branch
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
    name = "checkout",
    description = ["Checkout the branch matching issue metadata in each repo"],
    mixinStandardHelpOptions = true
)
class CheckoutCommand : Runnable {
    @Option(
        names = ["-b"],
        description = ["Create branch from issue.yaml branch if missing"]
    )
    var createBranch: Boolean = false

    override fun run() {
        val cwd = currentWorkingDir()
        val configPath = cwd.resolve("config.yaml")
        if (!Files.exists(configPath)) {
            fail("config.yaml not found in current directory: ${cwd}")
        }

        val config = loadConfig(configPath)
        val issueContext = resolveIssueContext(cwd)
        val issueId = issueContext.id
        if (config.bundlesPerRepo.isEmpty()) {
            fail("config.yaml has no bundlesPerRepo entries")
        }
        val branch = issueContext.branch

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
                println(CliStyle.bold(repoDir.name, true))
            }
            runShellCommand(
                repoDir.path,
                normalizedCommand,
                "Failed to run command in repo '${repoDir.name}'"
            )
        }
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
    return CliProcess.runCapture(workingDir, listOf("git") + args, errorMessage)
}

private fun runCommand(workingDir: Path, command: List<String>, errorMessage: String) {
    CliProcess.runCapture(workingDir, command, errorMessage)
}

private fun runShellCommand(workingDir: Path, command: String, errorMessage: String) {
    CliProcess.runStreaming(workingDir, listOf("sh", "-c", command), errorMessage)
}

private fun currentWorkingDir(): Path =
    Paths.get(System.getProperty("user.dir")).toAbsolutePath()

private fun issueBaseDir(): Path =
    Paths.get(System.getProperty("user.home"), "Desktop", "issues").toAbsolutePath()

internal fun findIssueMetadataPath(startDir: Path): Path? {
    var current = startDir.toAbsolutePath()
    while (true) {
        val candidate = current.resolve("issue.yaml")
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

internal data class IssueMetadata(
    val id: String,
    val branch: String
)

private data class IssueContext(
    val id: String,
    val branch: String,
    val issueDir: Path
)

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

private fun ensureGitRepo(workingDir: Path, repoDir: Path, repoName: String) {
    runGit(
        workingDir,
        listOf("-C", repoDir.toString(), "rev-parse", "--git-dir"),
        "Existing repo '${repoName}' is not a git repository"
    )
}

internal data class Config(
    val bundlesPerRepo: List<RepoEntry>,
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

private fun loadIssueMetadata(path: Path): IssueMetadata {
    val contents = path.toFile().readText()
    return parseIssueMetadata(contents)
}

private fun loadIssueMetadataMap(path: Path): Map<*, *> {
    val contents = path.toFile().readText()
    val yaml = Yaml()
    val root = yaml.load<Any>(contents)
        ?: fail("issue.yaml is empty")
    return root as? Map<*, *>
        ?: fail("issue.yaml must be a mapping at the root")
}

internal fun parseIssueMetadata(contents: String): IssueMetadata {
    val yaml = Yaml()
    val root = yaml.load<Any>(contents)
        ?: fail("issue.yaml is empty")
    val rootMap = root as? Map<*, *>
        ?: fail("issue.yaml must be a mapping at the root")
    val id = requireNonBlank(
        rootMap["id"] as? String ?: fail("issue.yaml must contain key 'id'"),
        "issue.yaml key 'id' must be non-empty"
    )
    val branch = requireNonBlank(
        rootMap["branch"] as? String ?: fail("issue.yaml must contain key 'branch'"),
        "issue.yaml key 'branch' must be non-empty"
    )
    return IssueMetadata(id = id, branch = branch)
}

internal fun writeIssueMetadata(path: Path, metadata: IssueMetadata) {
    val output = "id: ${yamlScalar(metadata.id)}\nbranch: ${yamlScalar(metadata.branch)}\n"
    path.toFile().writeText(output)
}

private fun yamlScalar(value: String): String {
    val escaped = value.replace("'", "''")
    return "'${escaped}'"
}

private fun resolveIssueContext(startDir: Path): IssueContext {
    val issueMetadataPath = findIssueMetadataPath(startDir)
        ?: fail("issue.yaml not found in current directory or parent directories: ${startDir}")
    val metadata = loadIssueMetadata(issueMetadataPath)
    val issueDir = issueMetadataPath.parent
        ?: fail("issue.yaml must have a parent directory: ${issueMetadataPath}")
    return IssueContext(id = metadata.id, branch = metadata.branch, issueDir = issueDir)
}

internal fun readIssueProperty(startDir: Path, property: String): String {
    val key = requireNonBlank(property, "Property name must be non-empty")
    val issueMetadataPath = findIssueMetadataPath(startDir)
        ?: fail("issue.yaml not found in current directory or parent directories: ${startDir}")
    val rootMap = loadIssueMetadataMap(issueMetadataPath)
    if (!rootMap.containsKey(key)) {
        fail("issue.yaml must contain key '${key}'")
    }
    val value = rootMap[key] ?: fail("issue.yaml key '${key}' must be non-null")
    return value.toString()
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
    val base = if (summarySlug.isBlank()) issueId else "${issueId}-${summarySlug}"
    return formatBranchName(prefix, issueId, base)
}

internal fun formatBranchName(prefix: String, issueId: String, base: String): String {
    val maxLength = 50
    val rawPrefix = "${prefix}/"
    val prefixPart = if (rawPrefix.length > maxLength) rawPrefix.take(maxLength) else rawPrefix
    val remaining = maxLength - prefixPart.length
    if (remaining <= 0) {
        return prefixPart
    }
    if (prefixPart.length + base.length <= maxLength) {
        return "${prefixPart}${base}"
    }

    val trimmedIssueId = issueId.take(remaining)
    if (trimmedIssueId.length >= remaining) {
        return "${prefixPart}${trimmedIssueId}"
    }

    val summaryLimit = remaining - trimmedIssueId.length - 1
    val truncatedSummary = if (summaryLimit > 0) {
        base.removePrefix(issueId).removePrefix("-").take(summaryLimit).trimEnd('-')
    } else {
        ""
    }
    return if (truncatedSummary.isBlank()) {
        "${prefixPart}${trimmedIssueId}"
    } else {
        "${prefixPart}${trimmedIssueId}-${truncatedSummary}"
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
        bundlesPerRepo = entries,
        profilePath = profilePath,
        formatterConfigPath = formatterConfigPath
    )
}

private fun fail(message: String): Nothing {
    throw CliException(message)
}

private fun info(message: String) {
    println(CliStyle.success(message, CliStyle.useColor(ColorMode.AUTO)))
}

private fun warn(message: String) {
    System.err.println("${CliStyle.warn(CliStyle.useColor(ColorMode.AUTO))} ${message}")
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
    val exitCode = CliMain.run(IssueCommand(), args)
    exitProcess(exitCode)
}
