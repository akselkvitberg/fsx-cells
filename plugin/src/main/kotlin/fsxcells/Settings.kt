package fsxcells

import com.intellij.ide.BrowserUtil
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/** Application-wide settings (Settings | Tools | FSX Cells). */
@Service(Service.Level.APP)
@State(name = "FsxCellsSettings", storages = [Storage("fsxcells.xml")])
class FsxCellsSettings : SimplePersistentStateComponent<FsxCellsSettings.SettingsState>(SettingsState()) {
    class SettingsState : BaseState() {
        var dotnetPath by string()
    }

    /** The dotnet executable to use; empty to find it automatically. */
    var dotnetPath: String
        get() = state.dotnetPath.orEmpty()
        set(value) {
            state.dotnetPath = value.trim().ifEmpty { null }
        }

    companion object {
        fun getInstance(): FsxCellsSettings = service()
    }
}

class FsxCellsConfigurable : BoundConfigurable("FSX Cells") {
    override fun createPanel() = panel {
        val settings = FsxCellsSettings.getInstance()
        val detected = DotnetLocator.find(Dotnet.environment(), SystemInfo.isWindows, System.getProperty("user.home")).found
        row("dotnet executable:") {
            textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select the dotnet Executable"),
            )
                .bindText(settings::dotnetPath)
                .align(AlignX.FILL)
                .comment(
                    "Leave empty to find it automatically (" +
                        (detected?.let { "found: ${StringUtil.escapeXmlEntities(it.path)}" } ?: "not found") +
                        "). Cells need the .NET SDK ${Dotnet.MIN_SDK_MAJOR} or newer. " +
                        "Applies to F# sessions started after the change (Tools | FSX Cells | Restart F# Session).",
                )
        }
    }
}

/** Balloon for setup problems: links to download .NET and to the settings, or to trust the project. */
object SetupNotifier {
    private const val GROUP = "FSX Cells"

    fun notify(project: Project, problem: SetupException) {
        val content = StringUtil.escapeXmlEntities(problem.message.orEmpty()).replace("\n", "<br>")
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(problem.title, content, NotificationType.ERROR)
        when (problem) {
            is UntrustedProjectException -> notification.addAction(
                NotificationAction.createSimpleExpiring("Trust Project") {
                    TrustedProjects.setProjectTrusted(project, true)
                },
            )
            else -> notification
                .addAction(NotificationAction.createSimple("Download .NET") { BrowserUtil.browse(Dotnet.DOWNLOAD_URL) })
                .addAction(NotificationAction.createSimple("Set dotnet path…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, FsxCellsConfigurable::class.java)
                })
        }
        notification.notify(project)
    }
}
