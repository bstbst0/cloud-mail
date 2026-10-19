package com.cloudmail.app.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichTextTest {
    @Test fun contentIsEscapedEvenWhenFormatted() {
        val formatted = RichText("<script>alert('x')</script> &").toggle(0, 8, "b")
        assertFalse(formatted.html().contains("<script>"))
        assertTrue(formatted.html().contains("&lt;"))
        assertTrue(formatted.html().contains("&amp;"))
    }
    @Test fun formattedDraftRestoresWithoutExecutingMarkup() {
        val rich = RichText("A & B\nHello").toggle(0, 5, "b").toggle(6, 11, "i")
        val restored = RichText.restore(rich.text, rich.html())
        assertEquals(rich, restored)
    }
    @Test fun overlappingStylesProduceBalancedHtml() {
        val rich = RichText("abcde").toggle(0, 3, "b").toggle(2, 5, "i")
        assertEquals("<div><b>ab</b><b><i>c</i></b><i>de</i></div>", rich.html())
        assertEquals(rich.html(), RichText.restore(rich.text, rich.html()).html())
    }
    @Test fun textInsertionMovesTrailingFormatting() {
        val rich = RichText("Hello world").toggle(6, 11, "b").edit("Hello dear world")
        assertEquals("<div>Hello dear <b>world</b></div>", rich.html())
    }
    @Test fun unknownMarkupDoesNotBecomeAnEditorStyle() {
        val rich = RichText.restore("Hello", "<img src=x onerror=evil()>Hello")
        assertEquals("<div>Hello</div>", rich.html())
    }
    @Test fun destructiveEditDropsEmptyRanges() {
        val rich = RichText("Hello world").toggle(6, 11, "b").edit("Hello ")
        assertEquals(emptyList(), rich.marks)
    }
}
