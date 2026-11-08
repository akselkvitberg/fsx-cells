package fsxcells

/**
 * A cell in an .fsx file.
 *
 * @property markerLine 0-based line of the `(* cell *)` / `// %%` marker, or -1 for the implicit first cell
 *                      (text before the first marker).
 * @property firstLine  0-based first line of the cell body (the line after the marker).
 * @property lastLine   0-based last line of the cell body (inclusive). Can be < [firstLine] for an empty cell.
 * @property title      Optional title written after `%%` (e.g. `// %% Part 1`).
 */
data class Cell(val markerLine: Int, val firstLine: Int, val lastLine: Int, val title: String?) {
    val isEmpty: Boolean get() = lastLine < firstLine

    /** True if [line] belongs to this cell, including its marker line. */
    fun contains(line: Int): Boolean = line in (if (markerLine >= 0) markerLine else firstLine)..lastLine
}

object CellParser {
    private val blockMarker = Regex("""^\s*\(\*\s*cell\b(.*?)\*\)\s*$""", RegexOption.IGNORE_CASE)
    private val percentMarker = Regex("""^\s*//\s*%%(.*)$""")

    /** Returns the title if [line] is a cell marker ("" when it has none), or null if it is not a marker. */
    fun markerTitle(line: String): String? {
        val m = blockMarker.matchEntire(line) ?: percentMarker.matchEntire(line) ?: return null
        return m.groupValues[1].trim()
    }

    fun isMarker(line: String): Boolean = markerTitle(line) != null

    /** Splits [lines] into cells. Text before the first marker becomes a cell only if it has non-blank content. */
    fun parse(lines: List<String>): List<Cell> {
        val markers = lines.indices.mapNotNull { i -> markerTitle(lines[i])?.let { i to it } }
        val cells = mutableListOf<Cell>()

        val firstMarker = markers.firstOrNull()?.first ?: lines.size
        if (lines.subList(0, firstMarker).any { it.isNotBlank() }) {
            cells += trim(Cell(-1, 0, firstMarker - 1, null), lines)
        }

        markers.forEachIndexed { idx, (line, title) ->
            val end = if (idx + 1 < markers.size) markers[idx + 1].first - 1 else lines.size - 1
            cells += trim(Cell(line, line + 1, end, title.ifEmpty { null }), lines)
        }
        return cells
    }

    fun parse(text: CharSequence): List<Cell> = parse(text.lines())

    /** The cell that contains [line], if any. */
    fun cellAt(cells: List<Cell>, line: Int): Cell? = cells.lastOrNull { it.contains(line) }

    /** Drops trailing blank lines so the output block sits right under the last line of code. */
    private fun trim(cell: Cell, lines: List<String>): Cell {
        var last = cell.lastLine
        while (last >= cell.firstLine && lines[last].isBlank()) last--
        return cell.copy(lastLine = last)
    }
}
