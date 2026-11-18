package fsxcells

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** The last lines the F# host processes wrote to stderr, from any session, for bug reports. */
object RecentHostStderr {
    private const val MAX_LINES = 30
    private val lines = ArrayDeque<String>()

    fun add(line: String) = synchronized(lines) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    fun lines(): List<String> = synchronized(lines) { lines.toList() }
}

/** What a bug report says about the error and the user's setup. */
data class BugReportInfo(
    val pluginVersion: String,
    val ideVersion: String,
    val os: String,
    val dotnetVersion: String,
    /** What the user typed in the IDE's error dialog, if anything. */
    val description: String?,
    /** The error's message and stack trace. */
    val error: String,
    val hostStderr: List<String>,
)

/**
 * Builds the URL of a pre-filled GitHub issue form (.github/ISSUE_TEMPLATE/bug_report.yml). Nothing is posted: the
 * user sees the form in the browser and submits it, or not.
 */
object BugReport {
    const val NEW_ISSUE_URL = "https://github.com/akselkvitberg/fsx-cells/issues/new"
    const val TEMPLATE = "bug_report.yml"

    /**
     * Longest URL to open. GitHub refuses much longer ones, and browsers and OS URL handlers have limits too, so long
     * fields (the stack trace, stderr) are cut to fit.
     */
    const val MAX_URL_LENGTH = 7500

    /** The field ids of the issue form, in the order they are cut when the URL is too long (last first). */
    private const val FIELD_WHAT = "what-happened"
    private const val FIELD_VERSIONS = "versions"
    private const val FIELD_ERROR = "error"
    private const val FIELD_STDERR = "host-stderr"

    /** The issue form's fields, as they are filled in (before URL encoding). */
    fun fields(info: BugReportInfo): LinkedHashMap<String, String> = linkedMapOf(
        FIELD_WHAT to info.description?.trim().orEmpty(),
        FIELD_VERSIONS to versions(info),
        FIELD_ERROR to info.error.trimEnd(),
        FIELD_STDERR to info.hostStderr.joinToString("\n").ifBlank { "(nothing)" },
    )

    fun versions(info: BugReportInfo): String = """
        FSX Cells: ${info.pluginVersion}
        IDE: ${info.ideVersion}
        OS: ${info.os}
        dotnet --version: ${info.dotnetVersion}
    """.trimIndent()

    /** The title of the issue: the first line of the error, shortened. */
    fun title(info: BugReportInfo): String {
        val first = info.error.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (first.length <= 100) first else first.take(99) + "…"
    }

    /** The URL that opens the issue form filled in with [fields] (as shown to the user, maybe edited) and [title]. */
    fun issueUrl(title: String, fields: Map<String, String>): String {
        val shortened = LinkedHashMap(fields)
        fun build() = buildString {
            append(NEW_ISSUE_URL).append("?template=").append(encode(TEMPLATE))
            append("&title=").append(encode(title))
            for ((k, v) in shortened) if (v.isNotEmpty()) append('&').append(k).append('=').append(encode(v))
        }

        var url = build()
        // Cut the longest field until it fits; keep the start of a stack trace or stderr, which says the most.
        while (url.length > MAX_URL_LENGTH) {
            val (key, text) = shortened.entries.map { it.key to it.value.removeSuffix(CUT_MARK) }
                .filter { it.second.isNotEmpty() }
                .maxByOrNull { it.second.length } ?: break
            // An encoded character is 1 to 9 characters long; most text here is ASCII, about 1.3.
            val drop = maxOf((url.length - MAX_URL_LENGTH) / 3, 1)
            val kept = text.take((text.length - drop).coerceAtLeast(0))
            shortened[key] = kept.substringBeforeLast('\n', kept) + CUT_MARK
            url = build()
        }
        return url
    }

    /** After a home folder or user name: not followed by more of a name. */
    private const val NOT_IN_NAME = "(?![\\p{L}\\p{N}_])"

    private const val CUT_MARK = "\n… (cut to fit in the URL)"

    /**
     * [text] with the user's home folder written as `~` and the user name replaced, so paths in stack traces and
     * stderr don't show who the user is. The user still sees everything in the form before submitting it.
     */
    fun redact(text: String, home: String?, userName: String?): String {
        var result = text
        if (!home.isNullOrBlank() && home.length > 1) {
            val variants = listOf(home, home.replace('\\', '/'), home.replace('/', '\\')).distinct().sortedByDescending { it.length }
            // Not /home/ada in /home/adam.
            for (v in variants) result = Regex(Regex.escape(v) + NOT_IN_NAME, RegexOption.IGNORE_CASE).replace(result, "~")
        }
        if (!userName.isNullOrBlank() && userName.length >= 3) {
            result = Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(userName) + NOT_IN_NAME, RegexOption.IGNORE_CASE)
                .replace(result, "<user>")
        }
        return result
    }

    private fun encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
}
