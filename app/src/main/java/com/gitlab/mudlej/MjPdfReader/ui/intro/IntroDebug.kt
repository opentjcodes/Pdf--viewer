// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.intro

import androidx.annotation.MainThread
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.gitlab.mudlej.MjPdfReader.BuildConfig

data class IntroPagerState(
    val currentItem: Int,
    val pageCount: Int,
    val scrollState: Int,
    val settled: Boolean,
)

data class IntroPageSnapshot(
    val position: Int,
    val title: CharSequence?,
    val featureTexts: List<CharSequence>,
    val footerVisible: Boolean,
    val footerText: CharSequence?,
    val footerLinkUrls: List<String>,
)

object IntroDebug {

    @MainThread
    fun pagerState(pager: ViewPager2): IntroPagerState? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        return IntroPagerState(
            currentItem = pager.currentItem,
            pageCount = pager.adapter?.itemCount ?: 0,
            scrollState = pager.scrollState,
            settled = pager.scrollState == ViewPager2.SCROLL_STATE_IDLE,
        )
    }

    @MainThread
    fun pageSnapshot(pager: ViewPager2, position: Int): IntroPageSnapshot? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        val pages = pager.getChildAt(0) as? RecyclerView ?: return null
        val holder = pages.findViewHolderForAdapterPosition(position) as? IntroPagerAdapter.PageViewHolder
            ?: return null
        return holder.debugSnapshot(position)
    }
}
