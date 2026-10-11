package com.luckyagent.android.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import ru.noties.jlatexmath.JLatexMathDrawable

/** Alignment line exposed by an inline LaTeX image for baseline-aware layouts. */
internal val LatexBaseline = HorizontalAlignmentLine { first, second -> minOf(first, second) }

@Composable
internal fun LatexView(
    latex: String,
    display: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    textSizePx: Float? = null,
) {
    val argb = color.toArgb()
    val textSize = when {
        inline && textSizePx != null -> textSizePx * if (display) 1.15f else 1f
        display && !inline -> 52f
        display -> 46f
        else -> 40f
    }
    val drawable = remember(latex, display, argb, textSize) {
        runCatching {
            JLatexMathDrawable.builder(latex.ifBlank { " " })
                .textSize(textSize)
                .color(argb)
                .background(AndroidColor.TRANSPARENT)
                .align(if (inline) JLatexMathDrawable.ALIGN_LEFT else JLatexMathDrawable.ALIGN_CENTER)
                .padding(if (inline) 2 else 8)
                .build()
        }.getOrNull()
    }
    if (drawable == null) {
        androidx.compose.material3.Text(latex, color = color, modifier = modifier)
        return
    }
    val scroll = if (inline) modifier else modifier.horizontalScroll(rememberScrollState())
    val baselineModifier = if (inline) {
        // TeXIcon.iconHeight is the height above the baseline, while
        // iconDepth is the part below it.  Using iconHeight as the baseline
        // made the whole drawable sit too high in the text line.
        Modifier.latexBaseline(drawable.icon().iconHeight - drawable.icon().iconDepth)
    } else {
        Modifier
    }
    Box(scroll.heightIn(min = if (inline) 22.dp else 36.dp).then(baselineModifier)) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    adjustViewBounds = true
                    setImageDrawable(drawable)
                }
            },
            update = { view -> view.setImageDrawable(drawable) },
        )
    }
}

private fun Modifier.latexBaseline(baselinePx: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = baselinePx.coerceIn(0, placeable.height)
    layout(
        placeable.width,
        placeable.height,
        mapOf(LatexBaseline to baseline),
    ) {
        placeable.place(0, 0)
    }
}
