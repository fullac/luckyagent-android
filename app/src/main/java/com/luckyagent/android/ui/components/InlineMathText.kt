package com.luckyagent.android.ui.components

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One paragraph run consumed by [InlineMathText]. */
internal sealed class InlineMathRun {
    data class Text(val value: AnnotatedString) : InlineMathRun()

    data class Math(val latex: String, val displaySize: Boolean) : InlineMathRun()
}

/**
 * Draws text and inline formulas in one native paragraph. TextView owns line
 * breaking, so a formula can move to the next line without taking the rest of
 * a text run with it.
 */
@Composable
internal fun InlineMathText(
    runs: List<InlineMathRun>,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val colorArgb = color.toArgb()
    val textSizePx = with(density) {
        if (style.fontSize != TextUnit.Unspecified) {
            runCatching { style.fontSize.toPx() }.getOrNull()
        } else {
            null
        }
    }?.takeIf { it.isFinite() && it > 0f } ?: with(density) { 16.sp.toPx() }
    val lineHeightPx = with(density) {
        runCatching { style.lineHeight.toPx() }.getOrNull()
    }?.takeIf { it.isFinite() && it > 0f }

    BoxWithConstraints(modifier = modifier) {
        // BoxWithConstraints has the parent's final width on the first pass.
        // That lets formula spans choose their size before TextView measures.
        val maxFormulaWidthPx = if (maxWidth.value.isFinite()) {
            with(density) { maxWidth.toPx().roundToInt() }.coerceAtLeast(0)
        } else {
            Int.MAX_VALUE
        }
        val spannable = remember(runs, style, colorArgb, textSizePx, maxFormulaWidthPx) {
            buildInlineMathSpannable(runs, textSizePx, colorArgb, maxFormulaWidthPx)
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                TextView(context).apply {
                    includeFontPadding = false
                    setTextIsSelectable(true)
                    linksClickable = true
                    movementMethod = LinkMovementMethod.getInstance()
                    setPadding(0, 0, 0, 0)
                }
            },
            update = { view ->
                view.includeFontPadding = false
                view.setTextColor(colorArgb)
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                view.typeface = baseTypeface(style)
                val naturalLineHeight = view.paint.fontMetricsInt.let { it.descent - it.ascent }
                view.setLineSpacing(
                    (lineHeightPx?.minus(naturalLineHeight)?.coerceAtLeast(0f) ?: 0f),
                    1f,
                )
                view.setText(spannable, TextView.BufferType.SPANNABLE)
            },
        )
    }
}

/**
 * Converts the Compose runs to one native spannable. This is intentionally
 * internal so JVM/UI tests can inspect spans and formula metrics without
 * composing the whole chat screen.
 */
internal fun buildInlineMathSpannable(
    runs: List<InlineMathRun>,
    textSizePx: Float,
    colorArgb: Int,
    maxFormulaWidthPx: Int,
): SpannableStringBuilder {
    val out = SpannableStringBuilder()
    runs.forEach { run ->
        when (run) {
            is InlineMathRun.Text -> appendAnnotatedText(out, run.value)
            is InlineMathRun.Math -> {
                val start = out.length
                out.append('\uFFFC')
                out.setSpan(
                    NativeLatexSpan(
                        latex = run.latex,
                        textSizePx = textSizePx,
                        colorArgb = colorArgb,
                        maxFormulaWidthPx = maxFormulaWidthPx,
                        displaySize = run.displaySize,
                    ),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }
    return out
}

private fun appendAnnotatedText(out: SpannableStringBuilder, value: AnnotatedString) {
    val start = out.length
    out.append(value.text)
    val end = out.length
    if (start == end) return

    value.spanStyles.forEach { range ->
        val spanStart = (start + range.start).coerceIn(start, end)
        val spanEnd = (start + range.end).coerceIn(spanStart, end)
        if (spanStart < spanEnd) applyNativeSpan(out, range.item, spanStart, spanEnd)
    }
    value.getStringAnnotations("url", 0, value.length).forEach { range ->
        val spanStart = (start + range.start).coerceIn(start, end)
        val spanEnd = (start + range.end).coerceIn(spanStart, end)
        if (spanStart < spanEnd && range.item.isNotBlank()) {
            out.setSpan(URLSpan(range.item), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

private fun applyNativeSpan(
    out: SpannableStringBuilder,
    style: SpanStyle,
    start: Int,
    end: Int,
) {
    val fontWeight = style.fontWeight
    val typefaceStyle = when {
        fontWeight != null && fontWeight.weight >= FontWeight.SemiBold.weight &&
            style.fontStyle == FontStyle.Italic -> Typeface.BOLD_ITALIC
        fontWeight != null && fontWeight.weight >= FontWeight.SemiBold.weight -> Typeface.BOLD
        style.fontStyle == FontStyle.Italic -> Typeface.ITALIC
        fontWeight != null -> Typeface.NORMAL
        else -> null
    }
    if (typefaceStyle != null) out.setSpan(StyleSpan(typefaceStyle), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (style.fontFamily == FontFamily.Monospace) {
        out.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (style.color != Color.Unspecified) {
        out.setSpan(ForegroundColorSpan(style.color.toArgb()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (style.background != Color.Unspecified) {
        out.setSpan(BackgroundColorSpan(style.background.toArgb()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (style.textDecoration?.contains(TextDecoration.Underline) == true) {
        out.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (style.textDecoration?.contains(TextDecoration.LineThrough) == true) {
        out.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    when (style.baselineShift) {
        BaselineShift.Superscript -> out.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        BaselineShift.Subscript -> out.setSpan(SubscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (style.fontSize.isEm) {
        out.setSpan(RelativeSizeSpan(style.fontSize.value), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private fun baseTypeface(style: androidx.compose.ui.text.TextStyle): Typeface {
    val fontWeight = style.fontWeight
    val weightStyle = when {
        fontWeight != null && fontWeight.weight >= FontWeight.SemiBold.weight &&
            style.fontStyle == FontStyle.Italic -> Typeface.BOLD_ITALIC
        fontWeight != null && fontWeight.weight >= FontWeight.SemiBold.weight -> Typeface.BOLD
        style.fontStyle == FontStyle.Italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    val family = when (style.fontFamily) {
        FontFamily.Serif -> Typeface.SERIF
        FontFamily.Monospace -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
    return Typeface.create(family, weightStyle)
}

/** Native inline formula span with real TeX ascent/descent metrics. */
internal class NativeLatexSpan(
    latex: String,
    textSizePx: Float,
    colorArgb: Int,
    maxFormulaWidthPx: Int,
    displaySize: Boolean = false,
) : ReplacementSpan() {
    private val source = latex.ifBlank { "?" }
    private val drawable: JLatexMathDrawable? = runCatching {
        JLatexMathDrawable.builder(source)
            .textSize((textSizePx * if (displaySize) 1.15f else 1f).takeIf { it.isFinite() && it > 0f } ?: 16f)
            .color(colorArgb)
            .background(AndroidColor.TRANSPARENT)
            // TeXIcon already contributes its own natural insets. Keeping this
            // at zero avoids adding an untracked vertical gap to a text line.
            .padding(0)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.getOrNull()
    private val fallback: String = if (drawable == null) {
        runCatching { renderLatex(source).text }
            .getOrNull()
            ?.ifBlank { source }
            ?: source
    } else {
        ""
    }
    private val scale: Float
    private val width: Int
    private val height: Int
    private val baseline: Int

    init {
        val icon = drawable?.icon()
        if (icon == null) {
            scale = 1f
            width = 0
            height = 0
            baseline = 0
        } else {
            val intrinsicWidth = icon.iconWidth.coerceAtLeast(1)
            val maxWidth = maxFormulaWidthPx
            scale = if (maxWidth > 0 && maxWidth < intrinsicWidth) {
                maxWidth.toFloat() / intrinsicWidth.toFloat()
            } else {
                1f
            }
            width = ceil(icon.iconWidth * scale).roundToInt().coerceAtLeast(1)
            height = ceil(icon.iconHeight * scale).roundToInt().coerceAtLeast(1)
            // Use the unrounded TeX box metrics for the baseline. iconHeight
            // is the total raster height and iconDepth includes the bottom
            // inset, so subtracting those values can drift by a pixel.
            val baselineFromTop = icon.insets.top.toFloat() +
                icon.trueIconHeight - icon.trueIconDepth
            baseline = ceil(baselineFromTop * scale)
                .roundToInt()
                .coerceIn(0, height)
        }
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        if (drawable == null) return paint.measureText(fallback).ceilToInt()
        if (fm != null) {
            fm.ascent = min(fm.ascent, -baseline)
            fm.descent = max(fm.descent, height - baseline)
            fm.top = min(fm.top, fm.ascent)
            fm.bottom = max(fm.bottom, fm.descent)
        }
        return width
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val currentDrawable = drawable
        if (currentDrawable == null) {
            canvas.drawText(fallback, x, y.toFloat(), paint)
            return
        }
        val save = canvas.save()
        currentDrawable.setBounds(0, 0, width, height)
        canvas.translate(x, y - baseline.toFloat())
        currentDrawable.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun Float.ceilToInt(): Int = ceil(this).roundToInt()
}
