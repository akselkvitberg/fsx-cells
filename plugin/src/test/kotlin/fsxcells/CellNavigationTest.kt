package fsxcells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellNavigationTest {
    // Lines: 0 let a, 1 // %%, 2 let b, 3 blank, 4 // %%, 5 let c
    private val lines = listOf("let a = 1", "// %%", "let b = 2", "", "// %%", "let c = 3")
    private val cells = CellParser.parse(lines)

    @Test
    fun `finds the cell under the caret`() {
        assertEquals(0, CellNavigation.indexAt(cells, 0))
        assertEquals(1, CellNavigation.indexAt(cells, 1))
        assertEquals(1, CellNavigation.indexAt(cells, 3))
        assertEquals(2, CellNavigation.indexAt(cells, 5))
    }

    @Test
    fun `run below starts at the cell under the caret`() {
        assertEquals(1, CellNavigation.runBelowStart(cells, 2))
        assertEquals(0, CellNavigation.runBelowStart(CellParser.parse(listOf("", "// %%", "x")), 0))
    }

    @Test
    fun `next and previous move to the first line of the neighbouring cell`() {
        assertEquals(2, CellNavigation.neighbourLine(cells, 0, forward = true, lineCount = lines.size))
        assertEquals(5, CellNavigation.neighbourLine(cells, 2, forward = true, lineCount = lines.size))
        assertEquals(0, CellNavigation.neighbourLine(cells, 2, forward = false, lineCount = lines.size))
        assertEquals(2, CellNavigation.neighbourLine(cells, 5, forward = false, lineCount = lines.size))
    }

    @Test
    fun `no neighbour at the ends`() {
        assertNull(CellNavigation.neighbourLine(cells, 5, forward = true, lineCount = lines.size))
        assertNull(CellNavigation.neighbourLine(cells, 0, forward = false, lineCount = lines.size))
    }

    @Test
    fun `target line stays inside the document for an empty last cell`() {
        val l = listOf("a", "// %%")
        assertEquals(1, CellNavigation.neighbourLine(CellParser.parse(l), 0, forward = true, lineCount = l.size))
    }
}
