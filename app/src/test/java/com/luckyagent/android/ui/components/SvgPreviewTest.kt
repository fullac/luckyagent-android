package com.luckyagent.android.ui.components

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgPreviewTest {
    @Test fun staticSvgKeepsItsFrameAndDoesNotUseThePageViewer() {
        val source = """<svg viewBox="0 0 200 80" xmlns="http://www.w3.org/2000/svg"><rect width="200" height="80"/></svg>"""
        assertTrue(isStaticSvgDocument(source))
        assertTrue(requiresHtmlPage(source))
        assertEquals(SvgFrame(200f, 80f), svgFrame(source))
        assertEquals(128f, svgCardHeight(svgFrame(source), widthDp = 320f, viewportDp = 800f))
        assertFalse(svgCardScrolls(svgFrame(source), widthDp = 320f, viewportDp = 800f))
    }

    @Test fun tallSvgScrollsInsideTheCardInsteadOfCroppingAt480() {
        val frame = SvgFrame(100f, 2000f)
        val height = svgCardHeight(frame, widthDp = 300f, viewportDp = 800f)
        assertEquals(576f, height)
        assertTrue(height < 300f * 20f)
        assertTrue(svgCardScrolls(frame, widthDp = 300f, viewportDp = 800f))
    }

    @Test fun missingFrameUsesASquareUntilLayoutReturns() {
        val source = "<svg xmlns='http://www.w3.org/2000/svg'><circle r='4'/></svg>"
        assertTrue(isStaticSvgDocument(source))
        assertNull(svgFrame(source))
        assertEquals(200f, svgCardHeight(null, widthDp = 200f, viewportDp = 800f))
    }

    @Test fun scriptOrExternalReferenceStaysOnTheHtmlPagePath() {
        assertFalse(isStaticSvgDocument("<svg viewBox='0 0 10 10'><script>alert(1)</script></svg>"))
        assertFalse(isStaticSvgDocument("<svg viewBox='0 0 10 10'><image href='https://example.test/a.png'/></svg>"))
        assertFalse(isStaticSvgDocument("<svg viewBox='0 0 10 10'><foreignObject><div>x</div></foreignObject></svg>"))
        assertFalse(isStaticSvgDocument("<div><p>not a drawing</p></div>"))
    }

    @Test fun previewDocumentContainsTheGraphicAndBlocksNetwork() {
        val source = """<svg viewBox="0 0 10 10"><rect id="box" width="10" height="10"/></svg>"""
        val doc = Jsoup.parse(svgPreviewDocument(source, fit = true)!!)
        assertNotNull(doc.selectFirst("svg rect#box"))
        val csp = doc.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        assertTrue(csp.contains("connect-src 'none'"))
        assertTrue(csp.contains("default-src 'none'"))
        assertTrue(doc.select("style").last()!!.data().contains("max-width:100%"))
        val full = Jsoup.parse(svgPreviewDocument(source, fit = false)!!)
        assertFalse(full.select("style").last()!!.data().contains("max-width:100%"))
    }

    @Test fun sizeScriptReadsTheRootOnce() {
        assertTrue(SVG_SIZE_JS.contains("getBBox"))
        assertFalse(SVG_SIZE_JS.contains("querySelectorAll"))
    }

    @Test fun fencedStaticSvgIsStillAnHtmlBlock() {
        val block = splitMarkdownBlocks("```html\n<svg viewBox='0 0 8 8'></svg>\n```").single() as MdBlock.Html
        assertTrue(block.showPreview)
        assertTrue(isStaticSvgDocument(block.source))
    }
}
