package fsxcells

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext

/**
 * Ctrl+Enter is also Split Line (and Ctrl+Shift+Enter is Complete Statement), and the editor's own actions win by
 * default. Putting our actions first lets them run in .fsx editors; elsewhere they are disabled, so the editor's
 * action runs as usual.
 */
class FsxActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> =
        actions.filterIsInstance<FsxAction>()
}
