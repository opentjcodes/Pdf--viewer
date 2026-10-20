// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.tableofcontents

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.databinding.TableOfContentsRowItemBinding
import com.google.android.material.color.MaterialColors

class TableOfContentsViewHolder(
    private val binding: TableOfContentsRowItemBinding,
    private val tableOfContentsAdapter: TableOfContentsAdapter,
) : RecyclerView.ViewHolder(binding.root) {

    private val defaultCardColor = binding.root.cardBackgroundColor

    fun bind(row: TableOfContentsRow) {
        val entry = row.entry
        val textSize = PDF.TABLE_OF_CONTENTS_TEXT_SIZE - entry.level * PDF.TABLE_OF_CONTENTS_TEXT_SIZE_DEC

        indent(entry.level)
        highlightCurrentEntry(entry.path)

        val resolvable = entry.pageIdx >= 0
        binding.bookmarkText.text = entry.title
        binding.bookmarkText.textSize = textSize
        binding.bookmarkText.alpha = if (resolvable) 1f else UNRESOLVED_ENTRY_ALPHA
        binding.bookmarkPageNumber.text = if (resolvable) (entry.pageIdx + 1).toString() else ""
        binding.bookmarkPageNumber.textSize = textSize

        val onClick = if (resolvable) {
            View.OnClickListener { tableOfContentsAdapter.bookmarkFunctions.onEntryClicked(entry) }
        } else {
            null
        }
        bindEntryClickTarget(binding.root, onClick)
        bindEntryClickTarget(binding.bookmarkText, onClick)
        bindEntryClickTarget(binding.bookmarkPageNumber, onClick)

        bindToggle(row)
    }

    private fun bindEntryClickTarget(target: View, listener: View.OnClickListener?) {
        target.setOnClickListener(listener)
        val actionable = listener != null
        target.isClickable = actionable
        target.isFocusable = actionable
    }

    private fun bindToggle(row: TableOfContentsRow) {
        val toggle = binding.toggleButton
        if (!row.expandable) {
            toggle.setImageResource(R.drawable.ic_bullet_point)
            toggle.setOnClickListener(null)
            toggle.isClickable = false
            return
        }

        toggle.setImageResource(
            if (row.expanded) R.drawable.ic_small_arrow_down else R.drawable.ic_small_arrow_right
        )

        if (tableOfContentsAdapter.isFiltering()) {
            toggle.setOnClickListener(null)
            toggle.isClickable = false
        } else {
            toggle.setOnClickListener { tableOfContentsAdapter.onToggleClicked(row.entry) }
        }
    }

    private fun highlightCurrentEntry(path: String) {
        val currentPath = tableOfContentsAdapter.currentEntryPath
        val onCurrentChain = currentPath != null
                && (currentPath == path || currentPath.startsWith("$path."))
        if (onCurrentChain) {
            val highlightColor = MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorSecondaryContainer,
            )
            binding.root.setCardBackgroundColor(highlightColor)
        } else {
            binding.root.setCardBackgroundColor(defaultCardColor)
        }
    }

    private fun indent(level: Int) {
        val step = itemView.resources.getDimensionPixelSize(R.dimen.bookmark_indent_step)
        val baseMargin = itemView.resources.getDimensionPixelSize(R.dimen.bookmark_card_horizontal_margin)
        val params = binding.root.layoutParams as ViewGroup.MarginLayoutParams
        params.marginStart = baseMargin + level * step
        binding.root.layoutParams = params
    }

    private companion object {
        const val UNRESOLVED_ENTRY_ALPHA = 0.5f
    }
}
