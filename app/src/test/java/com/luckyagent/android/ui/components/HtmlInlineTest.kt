package com.luckyagent.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlInlineTest {
    @Test
    fun boldAndItalicBecomeMarkdownMarkers() {
        assertEquals("this is **bold** and *italic*", renderHtmlInline("this is <b>bold</b> and <i>italic</i>"))
    }

    @Test
    fun strongEmAndCodeAreRecognized() {
        assertEquals("**a** *b* `c`", renderHtmlInline("<strong>a</strong> <em>b</em> <code>c</code>"))
    }

    @Test
    fun httpsLinkBecomesAMarkdownLink() {
        assertEquals("[link](https://example.com)", renderHtmlInline("<a href=\"https://example.com\">link</a>"))
    }

    @Test
    fun javascriptLinkKeepsOnlyTheText() {
        assertEquals("x", renderHtmlInline("<a href=\"javascript:alert(1)\">x</a>"))
    }

    @Test
    fun scriptIsRejectedWhole() {
        val source = "<script>alert(1)</script>"

        assertNull(parseHtml(source))
        assertEquals(source, renderHtmlInline(source))
    }

    @Test
    fun unclosedTagIsLeftUntouched() {
        val source = "<b>bold"

        assertNull(parseHtml(source))
        assertEquals(source, renderHtmlInline(source))
    }

    @Test
    fun nestingPastTheLimitIsRejected() {
        val source = (1..9).joinToString("") { "<b>" } + "x" + (1..9).joinToString("") { "</b>" }

        assertNull(parseHtml(source))
    }

    @Test
    fun detailsParsesWithSummary() {
        val nodes = parseHtml("<details><summary>more</summary>body</details>")

        val details = nodes?.single() as HtmlNode.Element
        assertEquals("details", details.tag)
        assertEquals("summary", (details.children.first() as HtmlNode.Element).tag)
    }

    @Test
    fun textWithoutTagsPassesThrough() {
        assertEquals("plain text", renderHtmlInline("plain text"))
    }

    @Test
    fun lineBreakBecomesASpace() {
        assertEquals("a b", renderHtmlInline("a<br>b"))
    }

    @Test
    fun semanticColorSpanIsAccepted() {
        val nodes = parseHtml("<span data-color=\"accent\">重点</span>")
        val span = nodes?.single() as HtmlNode.Element

        assertEquals("span", span.tag)
        assertEquals(HtmlColor.ACCENT, span.color)
    }

    @Test
    fun arbitraryStyleOnColorSpanIsRejected() {
        assertNull(parseHtml("<span style=\"color:red\">危险</span>"))
    }

    @Test
    fun colorSpanRendersWithoutHtmlTags() {
        val rendered = renderHtmlInline("普通 <span data-color=\"warning\">警告</span>")

        assertTrue(rendered.contains("警告"))
        assertFalse(rendered.contains("<span"))
    }
}
