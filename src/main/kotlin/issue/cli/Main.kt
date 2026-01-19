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
    subcommands = [CloneCommand::class, IjInitCommand::class, IjInitBundlesCommand::class]
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

@Command(
    name = "ij-init",
    description = ["Copy the ij-project template into the current directory"],
    mixinStandardHelpOptions = true
)
class IjInitCommand : Runnable {
    override fun run() {
        val cwd = Paths.get("").toAbsolutePath()
        ensureIjProjectDir(cwd)
    }
}

@Command(
    name = "ij-init-bundles",
    description = ["Create IntelliJ modules for bundles from config.yaml"],
    mixinStandardHelpOptions = true
)
class IjInitBundlesCommand : Runnable {
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
            if (entry.bundles.isEmpty()) {
                fail("Repo '${repoName}' has no bundles specified")
            }
            val repoDir = cwd.resolve(repoName)
            if (!repoDir.isDirectory()) {
                fail("Repo directory not found for '${repoName}': ${repoDir}")
            }

            vcsMappings.add(repoName)

            for (bundle in entry.bundles) {
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
                    contentRoot = "\$PROJECT_DIR$/../${repoName}/${bundle}",
                    sourceRoots = sourceRoots.map { bundleDir.relativize(it).toString().replace('\\', '/') }
                )
                modules.add(moduleFileName)
            }
        }

        writeModulesXml(projectDir, modules)
        writeVcsXml(projectDir, vcsMappings.toList().sorted())
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
    TemplateFile("ij-project/.idea/vcs.xml", ".idea/vcs.xml"),
    TemplateFile(
        "ij-project/.idea/inspectionProfiles/Project_Default.xml",
        ".idea/inspectionProfiles/Project_Default.xml"
    ),
    TemplateFile("ij-project/ij-project.iml", "ij-project.iml")
)

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

private fun writeModuleFile(moduleFile: Path, contentRoot: String, sourceRoots: List<String>) {
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
    builder.appendLine("""    <content url="file://${xmlEscape(contentRoot)}">""")
    for (root in sourceRoots) {
        val escaped = xmlEscape(root)
        builder.appendLine(
            """      <sourceFolder url="file://${xmlEscape(contentRoot)}/${escaped}" isTestSource="false" />"""
        )
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
