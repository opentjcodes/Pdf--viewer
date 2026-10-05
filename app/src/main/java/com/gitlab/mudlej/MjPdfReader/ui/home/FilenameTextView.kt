// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.text.method.TransformationMethod
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.AppCompatTextView
import java.text.BreakIterator

class FilenameTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = text
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val needsTruncation = maxLines > 1 &&
            (ellipsize == TextUtils.TruncateAt.START || ellipsize == TextUtils.TruncateAt.MIDDLE)
        if (!needsTruncation && transformationMethod is FilenameTransformation) {
            transformationMethod = null
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!needsTruncation) return

        val availableWidth = measuredWidth - compoundPaddingLeft - compoundPaddingRight
        if (availableWidth <= 0) return
        val original = text.toString()
        val shortened = shorten(original, availableWidth)
        if (layout.text.toString() != shortened) {
            transformationMethod = if (shortened == original) null else FilenameTransformation(original, shortened)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun shorten(original: String, width: Int): String {
        fun fits(value: String): Boolean {
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(breakStrategy)
                .setHyphenationFrequency(hyphenationFrequency)
                .setMaxLines(maxLines + 1)
                .build()
            return layout.lineCount <= maxLines &&
                (0 until layout.lineCount).all { layout.getLineWidth(it) <= width }
        }

        if (fits(original)) return original
        val iterator = BreakIterator.getCharacterInstance(textLocale)
        iterator.setText(original)
        val boundaries = mutableListOf<Int>()
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            boundaries.add(boundary)
            boundary = iterator.next()
        }
        val characters = boundaries.size - 1
        fun candidate(kept: Int): String {
            val prefix = if (ellipsize == TextUtils.TruncateAt.MIDDLE) (kept + 1) / 2 else 0
            val suffix = kept - prefix
            return original.substring(0, boundaries[prefix]) + "…" +
                original.substring(boundaries[characters - suffix])
        }

        var low = 0
        var high = characters - 1
        var result = "…"
        while (low <= high) {
            val kept = (low + high) / 2
            val value = candidate(kept)
            if (fits(value)) {
                result = value
                low = kept + 1
            } else {
                high = kept - 1
            }
        }
        return result
    }

    private class FilenameTransformation(
        private val original: String,
        private val shortened: String,
    ) : TransformationMethod {
        override fun getTransformation(source: CharSequence, view: View): CharSequence =
            if (source.toString() == original) shortened else source

        override fun onFocusChanged(
            view: View,
            sourceText: CharSequence,
            focused: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect?,
        ) = Unit
    }
}
