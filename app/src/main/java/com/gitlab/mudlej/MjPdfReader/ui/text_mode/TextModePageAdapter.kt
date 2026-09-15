// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.text_mode

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.databinding.TextModePageItemBinding

class TextModePageAdapter(
    private val onRetry: (Int) -> Unit,
) : RecyclerView.Adapter<TextModePageViewHolder>() {

    private val pages = mutableListOf<TextModePageState>()
    private var settings = TextModeSettings()
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
    }

    fun submitPageCount(pageCount: Int) {
        pages.clear()
        pages.addAll(List(pageCount) { pageIndex -> TextModePageState.NotLoaded(pageIndex) })
        notifyDataSetChanged()
    }

    fun pageState(pageIndex: Int): TextModePageState? {
        return pages.getOrNull(pageIndex)
    }

    fun updatePageState(state: TextModePageState) {
        if (state.pageIndex !in pages.indices) return

        val previous = pages[state.pageIndex]
        pages[state.pageIndex] = state
        if (previous is TextModePageState.NotLoaded && state is TextModePageState.Loading) return

        notifyItemChanged(state.pageIndex)
    }

    fun applySettings(settings: TextModeSettings) {
        this.settings = settings
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextModePageViewHolder {
        return TextModePageViewHolder(
            TextModePageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onRetry,
            { recyclerView?.height ?: 0 },
        )
    }

    override fun onBindViewHolder(holder: TextModePageViewHolder, position: Int) {
        holder.bind(pages[position], settings)
    }

    override fun getItemCount() = pages.size
}
