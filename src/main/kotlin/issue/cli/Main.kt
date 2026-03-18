package issue.cli

import cn.varsa.cli.core.CliException
import cn.varsa.cli.core.CliMain
import cn.varsa.cli.core.CliProcess
import cn.varsa.cli.core.CliStyle
import cn.varsa.cli.core.ColorMode
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.CommandLine.Command
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
    subcommands = [
        NewCommand::class,
        InitCommand::class,
        ReadCommand::class,
        PickCommand::class
    ]
)
class IssueCommand

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
        initializeIssueMetadata(destination, normalizedIssueId, jiraIssue)
    }
}

@Command(
    name = "init",
    description = ["Initialize issue.yaml metadata in the current directory"],
    mixinStandardHelpOptions = true
)
class InitCommand : Runnable {
    @CommandLine.Parameters(
        index = "0",
        paramLabel = "<issue-id>",
        description = ["Jira issue ID (e.g. NXT-1234)"]
    )
    lateinit var issueId: String

    override fun run() {
        val normalizedIssueId = requireNonBlank(issueId, "Issue ID must be non-empty")
        val cwd = currentWorkingDir()
        val destination = cwd.resolve("issue.yaml")
        if (destination.exists()) {
            fail("Issue metadata already exists: ${destination}")
        }

        configurePushAutoSetupRemote(cwd)
        val baseDir = issueBaseDir()
        Files.createDirectories(baseDir)

        val jiraIssue = fetchJiraIssue(baseDir, normalizedIssueId)
        initializeIssueMetadata(destination, normalizedIssueId, jiraIssue)
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
    name = "pick",
    description = ["Interactively pick an issue workspace and print its path"],
    mixinStandardHelpOptions = true
)
class PickCommand : Runnable {
    @CommandLine.Parameters(
        index = "0",
        paramLabel = "<base-path>",
        description = ["Directory whose direct children are scanned for issue.yaml"]
    )
    lateinit var basePath: String

    override fun run() {
        val baseDir = resolveIssuePickBasePath(basePath, currentWorkingDir())
        if (!baseDir.isDirectory()) {
            fail("Issue base directory not found: ${baseDir}")
        }

        val discovered = discoverIssuePickCandidates(baseDir)
        if (discovered.isEmpty()) {
            info("No issue directories with valid issue.yaml found under ${baseDir}")
            return
        }

        val recencyPath = issuePickRecencyPath()
        val ranked = rankIssuePickCandidates(discovered, loadIssuePickRecency(recencyPath))
        val selectedDir = selectIssueDirectoryWithFzf(baseDir, ranked) ?: return
        storeIssuePickRecency(recencyPath, selectedDir)
        println(selectedDir.toString())
    }
}


private fun runGit(workingDir: Path, args: List<String>, errorMessage: String) {
    val output = runGitCapture(workingDir, args, errorMessage)
    if (output.isNotBlank()) {
        // already handled by runGitCapture; keep for parity with previous behavior
    }
}

private fun initializeIssueMetadata(destination: Path, issueId: String, jiraIssue: JiraIssueInfo?) {
    val branch = buildBranchName(
        issueId = issueId,
        issueType = jiraIssue?.issueType,
        summary = jiraIssue?.summary
    )
    writeIssueMetadata(
        destination,
        IssueMetadata(
            id = issueId,
            branch = branch,
            title = jiraIssue?.summary
        )
    )
    info("Created issue metadata at ${destination}")
}

private fun configurePushAutoSetupRemote(workingDir: Path) {
    runGit(
        workingDir,
        listOf("config", "--global", "push.autoSetupRemote", "true"),
        "Failed to set git config push.autoSetupRemote"
    )
}

private fun runGitCapture(workingDir: Path, args: List<String>, errorMessage: String): String {
    return CliProcess.runCapture(workingDir, listOf("git") + args, errorMessage)
}

private fun runInteractiveCommand(workingDir: Path, command: List<String>, errorMessage: String): Int {
    val process = try {
        ProcessBuilder(command)
            .directory(workingDir.toFile())
            .inheritIO()
            .start()
    } catch (ex: Exception) {
        fail("${errorMessage}: ${ex.message}")
    }
    return try {
        process.waitFor()
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        fail("${errorMessage}: interrupted")
    }
}

private fun currentWorkingDir(): Path =
    Paths.get(System.getProperty("user.dir")).toAbsolutePath()

private fun issueBaseDir(): Path =
    Paths.get(System.getProperty("user.home"), "Desktop", "issues").toAbsolutePath()

internal fun issuePickRecencyPath(homeDir: Path = Paths.get(System.getProperty("user.home"))): Path {
    return homeDir.resolve(".local").resolve("state").resolve("issue").resolve("pick-recency.txt")
}

internal fun resolveIssuePickBasePath(rawPath: String, cwd: Path): Path {
    val trimmed = requireNonBlank(rawPath, "Base path must be non-empty")
    val home = Paths.get(System.getProperty("user.home"))
    val withTildeExpanded = when {
        trimmed == "~" -> home
        trimmed.startsWith("~/") -> home.resolve(trimmed.removePrefix("~/"))
        else -> Paths.get(trimmed)
    }
    val resolved = if (withTildeExpanded.isAbsolute) withTildeExpanded else cwd.resolve(withTildeExpanded)
    return resolved.toAbsolutePath().normalize()
}

internal fun loadIssuePickRecency(path: Path): List<String> {
    if (!Files.exists(path)) {
        return emptyList()
    }
    return Files.readAllLines(path)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun storeIssuePickRecency(path: Path, selectedIssueDir: Path, limit: Int = 200) {
    val selected = selectedIssueDir.toAbsolutePath().normalize().toString()
    val updated = listOf(selected) + loadIssuePickRecency(path).filterNot { it == selected }
    val truncated = if (updated.size > limit) updated.subList(0, limit) else updated
    path.parent?.let { Files.createDirectories(it) }
    Files.write(path, truncated)
}

internal fun issuePickLabel(candidate: IssuePickCandidate): String {
    val normalizedTitle = candidate.title
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
        .trim()
    val title = if (normalizedTitle.isBlank()) "(no title)" else normalizedTitle
    return "${candidate.id} ${title}"
}

internal fun rankIssuePickCandidates(
    candidates: List<IssuePickCandidate>,
    recency: List<String>
): List<IssuePickCandidate> {
    val recencyOrder = recency.withIndex().associate { (index, value) -> value to index }
    return candidates.sortedWith(
        compareBy<IssuePickCandidate> {
            recencyOrder[it.issueDir.toAbsolutePath().normalize().toString()] ?: Int.MAX_VALUE
        }
            .thenBy { it.id }
            .thenBy { it.issueDir.toAbsolutePath().normalize().toString() }
    )
}

internal fun discoverIssuePickCandidates(baseDir: Path): List<IssuePickCandidate> {
    if (!baseDir.isDirectory()) {
        return emptyList()
    }
    val candidates = mutableListOf<IssuePickCandidate>()
    Files.list(baseDir).use { stream ->
        stream
            .filter { Files.isDirectory(it) }
            .forEach { issueDir ->
                val issueMetadataPath = issueDir.resolve("issue.yaml")
                if (!Files.isRegularFile(issueMetadataPath)) {
                    return@forEach
                }
                try {
                    val metadata = loadIssueMetadata(issueMetadataPath)
                    val rootMap = loadIssueMetadataMap(issueMetadataPath)
                    val title = (rootMap["title"] as? String)?.trim().orEmpty()
                    candidates.add(IssuePickCandidate(issueDir = issueDir, id = metadata.id, title = title))
                } catch (ex: CliException) {
                    warn("Skipping invalid issue metadata at ${issueMetadataPath}: ${ex.message}")
                }
            }
    }
    return candidates.sortedWith(
        compareBy<IssuePickCandidate> { it.id }
            .thenBy { it.issueDir.toAbsolutePath().normalize().toString() }
    )
}

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

internal fun parseSelectedIssueDirectory(rawSelection: String): Path? {
    val selected = rawSelection.trim()
    if (selected.isBlank()) {
        return null
    }
    val delimiterIndex = selected.lastIndexOf('\t')
    if (delimiterIndex < 0 || delimiterIndex + 1 >= selected.length) {
        fail("Unexpected picker output: ${selected}")
    }
    return Paths.get(selected.substring(delimiterIndex + 1))
        .toAbsolutePath()
        .normalize()
}

internal fun selectIssueDirectoryWithFzf(baseDir: Path, rankedCandidates: List<IssuePickCandidate>): Path? {
    if (rankedCandidates.isEmpty()) {
        return null
    }
    val entries = rankedCandidates.map { candidate ->
        "${issuePickLabel(candidate)}\t${candidate.issueDir.toAbsolutePath().normalize()}"
    }
    val entriesFile = Files.createTempFile("issue-pick-entries", ".txt")
    val selectionFile = Files.createTempFile("issue-pick-selection", ".txt")
    try {
        Files.write(entriesFile, entries)
        val command = buildString {
            append("command -v fzf >/dev/null 2>&1 || { echo 'fzf not found in PATH' >&2; exit 127; }; ")
            append("fzf --prompt=\"Issue > \" --height=40% --reverse --delimiter='\\t' --with-nth=1 < ")
            append(shellQuote(entriesFile.toString()))
            append(" > ")
            append(shellQuote(selectionFile.toString()))
            append(" || true")
        }
        val exitCode = runInteractiveCommand(
            baseDir,
            listOf("sh", "-c", command),
            "Failed to pick issue directory"
        )
        if (exitCode == 127) {
            fail("fzf not found in PATH")
        }
        if (!Files.exists(selectionFile)) {
            return null
        }
        val output = Files.readString(selectionFile)
        return parseSelectedIssueDirectory(output)
    } finally {
        Files.deleteIfExists(entriesFile)
        Files.deleteIfExists(selectionFile)
    }
}

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
    val branch: String,
    val title: String? = null
)

internal data class IssuePickCandidate(
    val issueDir: Path,
    val id: String,
    val title: String
)

private data class IssueContext(
    val id: String,
    val branch: String,
    val issueDir: Path
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

private val SUMMARY_REGEX =
    Regex("\"summary\"\\s*:\\s*\"((?:\\\\.|[^\\\"]*)*)\"")
private val ISSUETYPE_REGEX =
    Regex("\"issuetype\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"((?:\\\\.|[^\\\"]*)*)\"", RegexOption.DOT_MATCHES_ALL)

internal fun requireNonBlank(value: String, errorMessage: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) {
        fail(errorMessage)
    }
    return trimmed
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
    val titleLine = metadata.title
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "title: ${yamlScalar(it)}\n" }
        .orEmpty()
    val output = "id: ${yamlScalar(metadata.id)}\nbranch: ${yamlScalar(metadata.branch)}\n${titleLine}"
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


private fun fail(message: String): Nothing {
    throw CliException(message)
}

private fun info(message: String) {
    println(CliStyle.success(message, CliStyle.useColor(ColorMode.AUTO)))
}

private fun warn(message: String) {
    System.err.println("${CliStyle.warn(CliStyle.useColor(ColorMode.AUTO))} ${message}")
}

fun main(args: Array<String>) {
    val exitCode = CliMain.run(IssueCommand(), args)
    exitProcess(exitCode)
}
