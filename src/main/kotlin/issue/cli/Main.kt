package issue.cli

import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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
        CloneCommand::class,
        IjInitCommand::class,
        IjInitBundlesCommand::class,
        CheckoutCommand::class,
        PullCommand::class,
        RebaseCommand::class,
        ForeachCommand::class,
        CodegenCommand::class,
        FetchJarsCommand::class
    ]
)
class IssueCommand

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
            if (allBundles(entry).isEmpty()) {
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
            runGitWithOutput(
                cwd,
                listOf("-C", repoDir.toString(), "sparse-checkout", "set", "--") + allBundles(entry),
                "Failed to set sparse checkout paths for repo '${repoName}'"
            )
            runGitWithOutput(
                cwd,
                listOf("-C", repoDir.toString(), "checkout"),
                "Failed to checkout repo '${repoName}'"
            )
            if (repoAlreadyExists) {
                runGitWithOutput(
                    cwd,
                    listOf("-C", repoDir.toString(), "pull", "--ff-only"),
                    "Failed to update repo '${repoName}'"
                )
            }

            val missing = allBundles(entry).filter { !repoDir.resolve(it).isDirectory() }
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
    description = ["Copy the ij-project template into the current directory"],
    mixinStandardHelpOptions = true
)
class IjInitCommand : Runnable {
    override fun run() {
        val cwd = currentWorkingDir()
        val projectDir = ensureIjProjectDir(cwd)
        val configPath = cwd.resolve("config.yaml")
        if (Files.exists(configPath)) {
            val config = loadConfig(configPath)
            val profilePath = config.profilePath?.trim().orEmpty()
            if (profilePath.isNotBlank()) {
                updateEclipseTargetLocation(projectDir, profilePath)
            }
        }
    }
}

@Command(
    name = "ij-init-bundles",
    description = ["Create IntelliJ modules for bundles from config.yaml"],
    mixinStandardHelpOptions = true
)
class IjInitBundlesCommand : Runnable {
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

        val projectDir = ensureIjProjectDir(cwd)
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
            val repoDir = cwd.resolve(repoName)
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
}

@Command(
    name = "checkout",
    description = ["Checkout the branch matching issueId from config.yaml in each repo"],
    mixinStandardHelpOptions = true
)
class CheckoutCommand : Runnable {
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
            val localBranch = selectSingleMatchingBranch(
                parseBranchList(branchesOutput),
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

            val remoteOutput = runGitCapture(
                cwd,
                listOf("-C", repoDir.toString(), "branch", "-r", "--list"),
                "Failed to list remote branches for repo '${repoName}'"
            )
            val remoteBranch = selectSingleMatchingBranch(
                parseBranchList(remoteOutput).filterNot { it == "origin/HEAD" },
                issueId,
                "remote"
            )
                ?: fail("No local or remote branch containing '${issueId}' found for repo '${repoName}'")

            runGit(
                cwd,
                listOf("-C", repoDir.toString(), "checkout", "-t", remoteBranch),
                "Failed to checkout tracking branch '${remoteBranch}' for repo '${repoName}'"
            )
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

        val generatedDirs = listOf(
            gatewayDir.resolve("org.knime.gateway.api/src/generated"),
            gatewayDir.resolve("org.knime.gateway.json/src/generated"),
            gatewayDir.resolve("org.knime.gateway.impl/src/generated")
        )
        for (dir in generatedDirs) {
            deleteRecursively(dir)
        }

        runCommand(
            codegenDir,
            listOf("mvn", "compile", "exec:java", "-Dexec.mainClass=com.knime.gateway.codegen.Generate"),
            "Failed to run gateway codegen in ${codegenDir}"
        )
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

private fun runGit(workingDir: Path, args: List<String>, errorMessage: String) {
    val output = runGitCapture(workingDir, args, errorMessage)
    if (output.isNotBlank()) {
        // already handled by runGitCapture; keep for parity with previous behavior
    }
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
    val profilePath: String? = null
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

internal fun determineExcludedFolders(bundleDir: Path): List<String> {
    val binDir = bundleDir.resolve("bin")
    if (!binDir.isDirectory()) {
        return emptyList()
    }
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
            fail("Repo directory not found for '${repoName}': ${repoDir}")
        }
        for (bundle in allBundles(entry)) {
            val bundleDir = repoDir.resolve(bundle)
            if (!bundleDir.isDirectory()) {
                fail("Bundle directory not found: ${bundleDir}")
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

internal fun parseConfig(contents: String): Config {
    val yaml = Yaml()
    val root = yaml.load<Any>(contents)
        ?: fail("config.yaml is empty")

    val rootMap = root as? Map<*, *> ?: fail("config.yaml must be a mapping at the root")
    val bundlesPerRepoAny = rootMap["bundlesPerRepo"]
        ?: fail("config.yaml must contain 'bundlesPerRepo'")
    val issueId = rootMap["issueId"] as? String
    val profilePath = rootMap["profilePath"] as? String

    val bundlesPerRepoList = bundlesPerRepoAny as? List<*>
        ?: fail("'bundlesPerRepo' must be a list")

    val entries = bundlesPerRepoList.mapIndexed { index, item ->
        val itemMap = item as? Map<*, *> ?: fail("bundlesPerRepo[${index}] must be a mapping")
        val repo = itemMap["repo"] as? String
            ?: fail("bundlesPerRepo[${index}].repo must be a string")
        val bundlesAny = itemMap["bundles"]
        val bundles = when (bundlesAny) {
            null -> emptyList()
            is List<*> -> bundlesAny.mapIndexed { bundleIndex, bundle ->
                bundle as? String
                    ?: fail("bundlesPerRepo[${index}].bundles[${bundleIndex}] must be a string")
            }
            else -> fail("bundlesPerRepo[${index}].bundles must be a list")
        }
        val nonPdeBundlesAny = itemMap["nonPdeBundles"]
        val nonPdeBundles = when (nonPdeBundlesAny) {
            null -> emptyList()
            is List<*> -> nonPdeBundlesAny.mapIndexed { bundleIndex, bundle ->
                bundle as? String
                    ?: fail("bundlesPerRepo[${index}].nonPdeBundles[${bundleIndex}] must be a string")
            }
            else -> fail("bundlesPerRepo[${index}].nonPdeBundles must be a list")
        }
        RepoEntry(repo = repo, bundles = bundles, nonPdeBundles = nonPdeBundles)
    }

    return Config(issueId = issueId, bundlesPerRepo = entries, profilePath = profilePath)
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
