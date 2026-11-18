package fsxcells

/** How a cell's value is shown, set by a trailing `[image]` / `[html]` on the marker (Jupytext style). */
enum class CellDisplay(val wireName: String) {
    IMAGE("image"),
    HTML("html"),
}

/**
 * A cell in an .fsx file.
 *
 * @property markerLine 0-based line of the `(* cell *)` / `// %%` marker, or -1 for the implicit first cell
 *                      (text before the first marker).
 * @property firstLine  0-based first line of the cell body (the line after the marker).
 * @property lastLine   0-based last line of code (inclusive), with trailing blank lines left out: the output block
 *                      goes under it. Can be < [firstLine] for an empty cell.
 * @property title      Optional title written after `%%` (e.g. `// %% Part 1`), without the display annotation.
 * @property endLine    0-based last line of the cell including trailing blank lines (the line before the next
 *                      marker, or the last line of the file). Decides which cell the caret is in.
 */
data class Cell(
    val markerLine: Int,
    val firstLine: Int,
    val lastLine: Int,
    val title: String?,
    val endLine: Int = lastLine,
    /** Display annotation at the end of the marker (`// %% Plot [image]`), or null. */
    val display: CellDisplay? = null,
) {
    val isEmpty: Boolean get() = lastLine < firstLine

    /** True if [line] belongs to this cell: its marker line, code, or the blank lines after it. */
    fun contains(line: Int): Boolean = line in (if (markerLine >= 0) markerLine else firstLine)..maxOf(endLine, lastLine)
}

object CellParser {
    private val blockMarker = Regex("""^\s*\(\*\s*cell\b(.*?)\*\)\s*$""", RegexOption.IGNORE_CASE)
    private val percentMarker = Regex("""^\s*//\s*%%(.*)$""")

    /** Returns the title if [line] is a cell marker ("" when it has none), or null if it is not a marker. */
    fun markerTitle(line: String): String? {
        val m = blockMarker.matchEntire(line) ?: percentMarker.matchEntire(line) ?: return null
        return m.groupValues[1].trim()
    }

    private val annotation = Regex("""\[(image|html)]\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Splits marker text (as returned by [markerTitle]) into the title and its trailing display annotation. Other
     * bracket words, like `[part 2]`, stay in the title.
     */
    fun splitAnnotation(text: String): Pair<String, CellDisplay?> {
        val m = annotation.find(text) ?: return text to null
        val display = CellDisplay.entries.first { it.wireName.equals(m.groupValues[1], ignoreCase = true) }
        return text.substring(0, m.range.first).trim() to display
    }

    fun isMarker(line: String): Boolean = markerTitle(line) != null

    /**
     * The marker lines (index to marker text), leaving out lines that only look like markers because they are inside
     * a triple-quoted string or a block comment. A heuristic scan, not a lexer.
     */
    private fun findMarkers(lines: List<String>): List<Pair<Int, String>> {
        val markers = mutableListOf<Pair<Int, String>>()
        var inTriple = false
        var commentDepth = 0
        for ((i, line) in lines.withIndex()) {
            if (!inTriple && commentDepth == 0) {
                val title = markerTitle(line)
                // A marker is a whole comment on its own line: it leaves the state as it was.
                if (title != null) { markers += i to title; continue }
            }
            var pos = 0
            while (pos < line.length) {
                when {
                    inTriple -> {
                        val end = line.indexOf("\"\"\"", pos)
                        if (end < 0) break
                        inTriple = false
                        pos = end + 3
                    }
                    commentDepth > 0 -> when {
                        line.startsWith("(*", pos) -> { commentDepth++; pos += 2 }
                        line.startsWith("*)", pos) -> { commentDepth--; pos += 2 }
                        else -> pos++
                    }
                    line.startsWith("//", pos) -> break
                    line.startsWith("\"\"\"", pos) -> { inTriple = true; pos += 3 }
                    line[pos] == '"' -> pos = endOfString(line, pos, verbatim = pos > 0 && line[pos - 1] == '@')
                    line.startsWith("(*)", pos) -> pos += 3
                    line.startsWith("(*", pos) -> { commentDepth++; pos += 2 }
                    // Character literal like '"'.
                    line[pos] == '\'' && pos + 2 < line.length && line[pos + 2] == '\'' && line[pos + 1] != '\\' -> pos += 3
                    else -> pos++
                }
            }
        }
        return markers
    }

    /** The index after the "..." string that starts at [start]; the end of the line if it isn't closed there. */
    private fun endOfString(line: String, start: Int, verbatim: Boolean): Int {
        var i = start + 1
        while (i < line.length) {
            when {
                !verbatim && line[i] == '\\' -> i += 2
                line[i] == '"' && verbatim && i + 1 < line.length && line[i + 1] == '"' -> i += 2
                line[i] == '"' -> return i + 1
                else -> i++
            }
        }
        return line.length
    }

    /** Splits [lines] into cells. Text before the first marker becomes a cell only if it has non-blank content. */
    fun parse(lines: List<String>): List<Cell> {
        val markers = findMarkers(lines)
        val cells = mutableListOf<Cell>()

        val firstMarker = markers.firstOrNull()?.first ?: lines.size
        if (lines.subList(0, firstMarker).any { it.isNotBlank() }) {
            cells += trim(Cell(-1, 0, firstMarker - 1, null), lines)
        }

        markers.forEachIndexed { idx, (line, title) ->
            val end = if (idx + 1 < markers.size) markers[idx + 1].first - 1 else lines.size - 1
            val (text, display) = splitAnnotation(title)
            cells += trim(Cell(line, line + 1, end, text.ifEmpty { null }, display = display), lines)
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
        return cell.copy(lastLine = last, endLine = cell.lastLine)
    }
}
