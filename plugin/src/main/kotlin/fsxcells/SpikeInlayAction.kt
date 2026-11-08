package fsxcells

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

private val SPIKE_INLAYS = Key.create<MutableList<Inlay<*>>>("fsxcells.spikeInlays")

/** Phase 0 test: put a hardcoded multi-line output block under the caret's line. */
class SpikeInlayAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val line = editor.caretModel.logicalPosition.line
        val offset = document.getLineEndOffset(line)

        val lines = listOf(
            OutputLine("stdout: parsed 7 rows", OutputLineKind.Dim),
            OutputLine("....#....."),
            OutputLine("...###...."),
            OutputLine("..#####..."),
            OutputLine(".#######.."),
            OutputLine("#########."),
            OutputLine("...###...."),
            OutputLine("...###...."),
            OutputLine(": char[,]  (12 ms)", OutputLineKind.Dim),
            OutputLine("example error line", OutputLineKind.Error),
        )

        val inlay = editor.inlayModel.addBlockElement(
            offset,
            /* relatesToPrecedingText = */ true,
            /* showAbove = */ false,
            /* priority = */ 0,
            OutputBlockRenderer(lines),
        ) ?: return
        inlays(editor).add(inlay)
    }
}

class ClearSpikeInlaysAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        inlays(editor).forEach { Disposer.dispose(it) }
        inlays(editor).clear()
    }
}

private fun inlays(editor: Editor): MutableList<Inlay<*>> =
    editor.getUserData(SPIKE_INLAYS) ?: mutableListOf<Inlay<*>>().also { editor.putUserData(SPIKE_INLAYS, it) }
