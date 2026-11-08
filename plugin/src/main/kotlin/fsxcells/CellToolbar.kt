package fsxcells

import com.intellij.openapi.editor.Editor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JPanel

/** A row of small links shown above a cell marker (or below the last line of the file). */
class CellToolbar(editor: Editor, items: List<Item>) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(14), 0)) {
    class Item(val text: String, val icon: Icon?, val tooltip: String, val perform: () -> Unit)

    init {
        isOpaque = true
        background = editor.colorsScheme.defaultBackground
        border = JBUI.Borders.empty(1, 0, 0, 0)
        for (item in items) {
            add(ActionLink(item.text) { item.perform() }.apply {
                font = JBUI.Fonts.smallFont()
                icon = item.icon
                toolTipText = item.tooltip
                isFocusable = false
            })
        }
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width + JBUI.scale(4), size.height)
    }
}
