package fsxcells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorLinksTest {
    private val any = { _: String -> true }

    @Test
    fun compileErrorLinksPosition() {
        val text = "(3,5): The value 'x' is not defined"
        assertEquals(listOf(ErrorLink(0, 5, 3, 5)), ErrorLinks.find(text, any))
        assertEquals("(3,5)", text.substring(0, 5))
    }

    @Test
    fun plainTextHasNoLinks() {
        assertTrue(ErrorLinks.find("something (3,5) happened", any).isEmpty())
        assertTrue(ErrorLinks.find("", any).isEmpty())
    }

    @Test
    fun stackFrameInScriptIsLinked() {
        val text = "   at Foo.bar() in C:\\work\\a.fsx:line 12"
        val links = ErrorLinks.find(text) { it == "C:\\work\\a.fsx" }
        assertEquals(1, links.size)
        assertEquals(12, links[0].line)
        assertEquals("C:\\work\\a.fsx:line 12", text.substring(links[0].start, links[0].end))
    }

    @Test
    fun stackFrameInOtherFileIsNotLinked() {
        val text = "   at Foo.bar() in C:\\work\\other.fsx:line 12"
        assertTrue(ErrorLinks.find(text) { it == "C:\\work\\a.fsx" }.isEmpty())
    }

    @Test
    fun zeroLineIsIgnored() {
        assertTrue(ErrorLinks.find("(0,1): x", any).isEmpty())
    }
}

class HtmlVisibilityTest {
    // Visible area 1000..2000, so a viewport is 1000 px.
    private fun live(current: Boolean, top: Int, bottom: Int) = HtmlVisibility.shouldBeLive(current, top, bottom, 1000, 2000)

    @Test
    fun visibleBlockIsLive() {
        assertTrue(live(false, 1200, 1400))
    }

    @Test
    fun blockWithinOneViewportBecomesLive() {
        assertTrue(live(false, 3000, 3200))
        assertTrue(live(false, -300, 0))
    }

    @Test
    fun blockBetweenNearAndFarKeepsState() {
        assertTrue(live(true, 3500, 3600))
        assertFalse(live(false, 3500, 3600))
        assertTrue(live(true, -1200, -1000))
        assertFalse(live(false, -1200, -1000))
    }

    @Test
    fun blockBeyondTwoViewportsIsNotLive() {
        assertFalse(live(true, 4100, 4200))
        assertFalse(live(true, -2200, -2100))
    }
}
