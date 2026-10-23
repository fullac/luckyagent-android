package com.luckyagent.android.ui

import kotlin.collections.AbstractList

/**
 * A read-only bubble list that shares every element except one replacement.
 *
 * Streaming output updates the same assistant bubble repeatedly. Keeping the
 * unchanged prefix in place avoids copying the whole history for every flush.
 */
internal class ReplacedBubbleList internal constructor(
    internal val source: List<ChatBubble>,
    internal val replacementIndex: Int,
    internal val replacement: ChatBubble,
) : AbstractList<ChatBubble>() {
    override val size: Int
        get() = source.size

    override fun get(index: Int): ChatBubble =
        if (index == replacementIndex) replacement else source[index]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is ReplacedBubbleList &&
            source === other.source &&
            replacementIndex == other.replacementIndex
        ) {
            return replacement == other.replacement
        }
        return super.equals(other)
    }
}

internal fun List<ChatBubble>.replaceBubbleAt(
    index: Int,
    replacement: ChatBubble,
): List<ChatBubble> {
    require(index in indices) { "bubble index $index is out of bounds for size $size" }
    if (this[index] == replacement) return this
    val source = if (this is ReplacedBubbleList && replacementIndex == index) this.source else this
    return ReplacedBubbleList(source, index, replacement)
}
