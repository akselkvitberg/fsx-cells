package fsxcells

import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.components.JBTextField
import com.intellij.util.Consumer
import java.awt.Component
import java.io.File
import javax.swing.JComponent
import javax.swing.JTextArea

/**
 * "Report to FSX Cells" in the IDE's error dialog, for errors thrown by this plugin: shows what the report will say,
 * then opens a pre-filled GitHub issue form in the browser. Nothing is posted until the user submits that form.
 */
class FsxErrorReportSubmitter : ErrorReportSubmitter() {
    override fun getReportActionText(): String = "Report on GitHub"

    override fun getPrivacyNoticeText(): String =
        "Opens a GitHub issue form in your browser, filled in with the error, the plugin, IDE, OS and .NET SDK " +
            "versions and the F# host's last messages. You see all of it before anything is posted."

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(parentComponent))
        val pluginVersion = pluginDescriptor?.version ?: "unknown"
        val error = events.joinToString("\n\n") { it.throwableText.ifBlank { it.message.orEmpty() } }

        // `dotnet --version` starts a process: not on the UI thread.
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing the bug report", true) {
            private lateinit var info: BugReportInfo

            override fun run(indicator: ProgressIndicator) {
                val home = System.getProperty("user.home")
                val user = System.getProperty("user.name")
                fun redact(text: String) = BugReport.redact(text, home, user)
                info = BugReportInfo(
                    pluginVersion = pluginVersion,
                    ideVersion = ideVersion(),
                    os = with(System.getProperties()) { "${get("os.name")} ${get("os.version")} (${get("os.arch")})" },
                    dotnetVersion = redact(dotnetVersion()),
                    description = additionalInfo,
                    error = redact(error),
                    hostStderr = RecentHostStderr.lines().map(::redact),
                )
            }

            override fun onSuccess() {
                val dialog = BugReportDialog(project, BugReport.title(info), BugReport.fields(info))
                if (dialog.showAndGet()) {
                    BrowserUtil.browse(BugReport.issueUrl(dialog.issueTitle, dialog.fields))
                    consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
                }
            }
        })
        return true
    }

    private fun ideVersion(): String {
        val app = ApplicationInfo.getInstance()
        return "${app.fullApplicationName} (build ${app.build.asString()})"
    }

    /** What `dotnet --version` prints in the user's home folder, with the dotnet the plugin would use. */
    private fun dotnetVersion(): String = try {
        val env = Dotnet.environment()
        val dotnet = Dotnet.locate(FsxCellsSettings.getInstance().dotnetPath, env)
        Dotnet.version(dotnet, File(System.getProperty("user.home")), Dotnet.environment(dotnet, env))
    } catch (e: SetupException) {
        e.title
    } catch (e: Exception) {
        "unknown (${e.message})"
    }
}

/** Shows the bug report before it goes to the browser; every field can be edited. */
private class BugReportDialog(
    project: Project?,
    title: String,
    initialFields: Map<String, String>,
) : DialogWrapper(project) {
    private val titleField = JBTextField(title)
    val issueTitle: String get() = titleField.text
    private val areas = LinkedHashMap<String, JTextArea>()
    val fields: Map<String, String> get() = areas.mapValues { it.value.text }

    init {
        this.title = "Report a Bug in FSX Cells"
        setOKButtonText("Open in Browser")
        initialFields.forEach { (key, value) -> areas[key] = JTextArea(value) }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            text(
                "This opens a new GitHub issue in your browser, filled in with the text below. Nothing is posted " +
                    "until you submit the issue there. Edit or remove anything you don't want to share, here or in " +
                    "the browser. Your home folder is shown as ~.",
                maxLineLength = 90,
            )
        }
        row("Title:") {
            cell(titleField).align(AlignX.FILL)
        }
        for ((key, area) in areas) {
            area.rows = if (key == "error") 10 else 4
            area.columns = 90
            row(LABELS[key] ?: key) {}
            row {
                scrollCell(area).align(Align.FILL)
            }.resizableRow()
        }
    }

    companion object {
        private val LABELS = mapOf(
            "what-happened" to "What happened (what you were doing, what you expected):",
            "versions" to "Versions:",
            "error" to "Error:",
            "host-stderr" to "The F# host's last messages:",
        )
    }
}
