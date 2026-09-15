// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.text_mode

import android.graphics.Typeface
import androidx.annotation.MainThread
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.BuildConfig

data class TextModePageSnapshot(
    val position: Int,
    val pageText: CharSequence?,
    val pageTextVisible: Boolean,
    val textSizePx: Float,
    val lineSpacingMultiplier: Float,
    val typeface: Typeface?,
    val containerPaddingLeft: Int,
    val containerPaddingTop: Int,
    val containerPaddingRight: Int,
    val containerPaddingBottom: Int,
    val containerMeasuredWidth: Int,
    val paragraphDirection: Int,
)

object TextModeDebug {

    @MainThread
    fun pageSnapshot(pages: RecyclerView, position: Int): TextModePageSnapshot? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        val holder = pages.findViewHolderForAdapterPosition(position) as? TextModePageViewHolder ?: return null
        return holder.debugSnapshot(position)
    }
}
