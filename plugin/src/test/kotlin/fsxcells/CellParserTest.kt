package fsxcells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellParserTest {
    private fun parse(text: String) = CellParser.parse(text.trimIndent().lines())

    @Test
    fun `recognizes both marker styles`() {
        assertEquals("", CellParser.markerTitle("(* cell *)"))
        assertEquals("", CellParser.markerTitle("  (*cell*)  "))
        assertEquals("", CellParser.markerTitle("// %%"))
        assertEquals("Part 1", CellParser.markerTitle("// %% Part 1"))
        assertEquals("Part 2", CellParser.markerTitle("(* cell Part 2 *)"))
        assertNull(CellParser.markerTitle("// just a comment"))
        assertNull(CellParser.markerTitle("let x = 1 // %%"))
        assertNull(CellParser.markerTitle("(* cellular *)"))
    }

    @Test
    fun `splits cells at markers and keeps leading content as cell 0`() {
        val cells = parse(
            """
            #load "../utils.fsx"
            // %% Parse
            let x = 1
            x
            (* cell *)
            x + 1
            """
        )
        assertEquals(3, cells.size)
        assertEquals(Cell(-1, 0, 0, null), cells[0])
        assertEquals(Cell(1, 2, 3, "Parse"), cells[1])
        assertEquals(Cell(4, 5, 5, null), cells[2])
    }

    @Test
    fun `no leading cell when only blank lines precede the first marker`() {
        val cells = parse(
            """

            // %%
            1
            """
        )
        assertEquals(1, cells.size)
        assertEquals(1, cells[0].markerLine)
    }

    @Test
    fun `trailing blank lines are trimmed and empty cells are flagged`() {
        val cells = parse(
            """
            // %%
            1


            // %%

            // %%
            2
            """
        )
        assertEquals(Cell(0, 1, 1, null), cells[0])
        assertTrue(cells[1].isEmpty)
        assertEquals(Cell(6, 7, 7, null), cells[2])
    }

    @Test
    fun `file without markers is a single cell`() {
        val cells = parse("let a = 1\na")
        assertEquals(listOf(Cell(-1, 0, 1, null)), cells)
    }

    @Test
    fun `cellAt finds the cell including its marker line`() {
        val cells = parse("a\n// %%\nb\nc")
        assertEquals(cells[0], CellParser.cellAt(cells, 0))
        assertEquals(cells[1], CellParser.cellAt(cells, 1))
        assertEquals(cells[1], CellParser.cellAt(cells, 3))
    }
}
