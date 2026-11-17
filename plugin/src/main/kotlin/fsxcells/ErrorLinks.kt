package fsxcells

/** A clickable script position inside one line of output: [start] until [end] is the linked text. Line and column are 1-based. */
data class ErrorLink(val start: Int, val end: Int, val line: Int, val column: Int)

/** Finds script positions in error output: compile errors `(line,col): message` and stack frames `in path:line N`. */
object ErrorLinks {
    private val COMPILE_ERROR = Regex("""^\s*\((\d+),(\d+)\):""")
    private val STACK_FRAME = Regex("""in (.+?):line (\d+)""")

    /** Positions in [text] (one line); stack frames count only when [isScript] accepts their path. */
    fun find(text: String, isScript: (String) -> Boolean): List<ErrorLink> {
        val links = mutableListOf<ErrorLink>()
        COMPILE_ERROR.find(text)?.let { m ->
            val line = m.groupValues[1].toIntOrNull()
            val col = m.groupValues[2].toIntOrNull()
            if (line != null && col != null && line >= 1) {
                links += ErrorLink(m.value.indexOf('('), m.value.length - 1, line, col.coerceAtLeast(1))
            }
        }
        for (m in STACK_FRAME.findAll(text)) {
            val path = m.groupValues[1]
            val line = m.groupValues[2].toIntOrNull() ?: continue
            if (line < 1 || !isScript(path)) continue
            val pathGroup = m.groups[1]!!
            links += ErrorLink(pathGroup.range.first, m.range.last + 1, line, 1)
        }
        return links
    }
}

/** Decides when an HTML block keeps its embedded browser: near the visible area it has one, far away it doesn't. */
object HtmlVisibility {
    /** A browser is created when the block is within this many viewport heights of the visible area. */
    const val NEAR = 1.0
    /** A browser is disposed when the block is farther than this many viewport heights away. */
    const val FAR = 2.0

    /**
     * Whether a block spanning [blockTop]..[blockBottom] should have a browser, given the visible
     * [visibleTop]..[visibleBottom] (editor pixel coordinates). Between near and far, [currentlyLive] stays as it is.
     */
    fun shouldBeLive(currentlyLive: Boolean, blockTop: Int, blockBottom: Int, visibleTop: Int, visibleBottom: Int): Boolean {
        val viewport = (visibleBottom - visibleTop).coerceAtLeast(1)
        val distance = maxOf(blockTop - visibleBottom, visibleTop - blockBottom, 0)
        return when {
            distance <= viewport * NEAR -> true
            distance > viewport * FAR -> false
            else -> currentlyLive
        }
    }
}
