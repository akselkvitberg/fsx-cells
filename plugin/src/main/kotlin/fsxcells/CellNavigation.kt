package fsxcells

/** Pure helpers for choosing cells by caret position. */
object CellNavigation {
    /** The line where [cell] starts: its marker, or the first line for the implicit first cell. */
    private fun startLine(cell: Cell) = if (cell.markerLine >= 0) cell.markerLine else cell.firstLine

    /** Index of the cell the caret on [line] is in (the last one starting at or before it), or -1 before the first. */
    fun indexAt(cells: List<Cell>, line: Int): Int = cells.indexOfLast { startLine(it) <= line }

    /** Index of the first cell to run for "run cells below": the one under the caret, or the first when above them all. */
    fun runBelowStart(cells: List<Cell>, line: Int): Int = indexAt(cells, line).coerceAtLeast(0)

    /**
     * The line to move the caret to for Next Cell ([forward]) or Previous Cell: the first body line of the neighbouring
     * cell, kept inside the document of [lineCount] lines. Null when there is no such cell.
     */
    fun neighbourLine(cells: List<Cell>, line: Int, forward: Boolean, lineCount: Int): Int? {
        val target = cells.getOrNull(indexAt(cells, line) + if (forward) 1 else -1) ?: return null
        return target.firstLine.coerceAtMost(lineCount - 1).coerceAtLeast(0)
    }
}
