package com.luckyagent.android.ui.components

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A self-contained SVG can be drawn without the HTML page viewer.
 * A graphic that embeds a script, a foreign document, or an external
 * reference stays on the page path, where resource policy already applies.
 */
internal fun isStaticSvgDocument(source: String): Boolean {
    val root = svgRoot(source) ?: return false
    if (root.selectFirst("script, foreignObject, iframe, object, embed, video, audio") != null) return false
    if (root.getElementsByAttribute("src").isNotEmpty()) return false
    if (root.getElementsByAttribute("href").isNotEmpty()) return false
    if (root.getElementsByAttribute("xlink:href").isNotEmpty()) return false
    return true
}

internal data class SvgFrame(
    val width: Float,
    val height: Float,
)

/**
 * Intrinsic frame from viewBox, then width/height. A missing frame still
 * previews; the card uses a square until the laid-out size comes back.
 */
internal fun svgFrame(source: String): SvgFrame? {
    val root = svgRoot(source) ?: return null
    viewBoxFrame(root.attr("viewBox"))?.let { return it }
    val width = svgLength(root.attr("width"))
    val height = svgLength(root.attr("height"))
    if (width == null || height == null || width <= 0f || height <= 0f) return null
    return SvgFrame(width, height)
}

/** Message card height for the full graphic. Tall drawings stay scrollable instead of being cropped. */
internal fun svgCardHeight(frame: SvgFrame?, widthDp: Float, viewportDp: Float): Float {
    val width = widthDp.coerceAtLeast(1f)
    val ratio = if (frame == null || frame.width <= 0f) 1f else frame.height / frame.width
    val natural = width * ratio
    val cap = viewportDp.coerceAtLeast(160f) * 0.72f
    return natural.coerceIn(72f, cap)
}

internal fun svgCardScrolls(frame: SvgFrame?, widthDp: Float, viewportDp: Float): Boolean {
    if (frame == null || frame.width <= 0f) return false
    val natural = widthDp.coerceAtLeast(1f) * (frame.height / frame.width)
    return natural > svgCardHeight(frame, widthDp, viewportDp) + 1f
}

/** Measure the root graphic once. Scanning every node made large drawings stall the chat. */
internal const val SVG_SIZE_JS =
    "(function(){var s=document.documentElement;if(!s||s.tagName.toLowerCase()!=='svg')return '0,0';" +
        "var b=s.getBBox();var w=b.width,h=b.height;" +
        "if(!(w>0)||!(h>0)){w=s.clientWidth;h=s.clientHeight}" +
        "return Math.ceil(w)+','+Math.ceil(h)})()"

/**
 * Wrap a bare SVG so the WebView can scale it. The source is parsed and
 * serialized; it is not concatenated into the page as raw markup.
 */
internal fun svgPreviewDocument(source: String, fit: Boolean): String? {
    val root = svgRoot(source) ?: return null
    val doc = Document.createShell("")
    doc.select("base").remove()
    doc.head().prependElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
    doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr(
        "content",
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; " +
            "font-src data:; connect-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'",
    )
    val fitCss = if (fit) {
        "html,body{width:100%;height:100%;overflow:hidden}svg{width:100%;height:100%;max-width:100%;max-height:100%}"
    } else {
        "html,body{margin:0;background:transparent;overflow:auto}svg{max-width:none;height:auto}"
    }
    doc.head().appendElement("style").appendText(
        "html,body{margin:0;background:transparent}$fitCss",
    )
    doc.body().appendChild(root.clone())
    return doc.outerHtml()
}

private fun svgRoot(source: String): Element? {
    if (!source.contains("<svg", ignoreCase = true)) return null
    val root = Jsoup.parse(source).selectFirst("svg") ?: return null
    return root.takeIf { it.normalName() == "svg" }
}

private fun viewBoxFrame(value: String): SvgFrame? {
    val parts = value.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
    if (parts.size != 4) return null
    val width = parts[2]
    val height = parts[3]
    if (width <= 0f || height <= 0f) return null
    return SvgFrame(width, height)
}

private fun svgLength(value: String): Float? {
    val number = Regex("""^\s*([0-9]*\.?[0-9]+)""").find(value)?.groupValues?.get(1) ?: return null
    return number.toFloatOrNull()?.takeIf { it > 0f }
}
