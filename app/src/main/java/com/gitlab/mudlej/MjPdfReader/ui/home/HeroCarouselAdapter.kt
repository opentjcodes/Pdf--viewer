// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeHeroBookBinding
import com.gitlab.mudlej.MjPdfReader.core.io.formatRelativeDate
import kotlinx.coroutines.CoroutineScope

class HeroCarouselAdapter(
    private val coverCache: CoverCache,
    private val scope: CoroutineScope,
    private val functions: HomeItemFunctions,
) : RecyclerView.Adapter<HeroCarouselAdapter.HeroViewHolder>() {

    private var items: List<HomeItem> = emptyList()

    val currentItems: List<HomeItem>
        get() = items

    fun replaceItems(newItems: List<HomeItem>) {
        if (items == newItems) {
            return
        }
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeroViewHolder {
        val binding = ItemHomeHeroBookBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HeroViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HeroViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HeroViewHolder(
        private val binding: ItemHomeHeroBookBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem) {
            binding.title.text = item.title

            if (item.length > 0) {
                binding.progressLabel.visibility = View.VISIBLE
                binding.progressLabel.text = binding.root.context.getString(
                    R.string.home_hero_progress_template,
                    item.progressPercent,
                    item.pageNumber + 1,
                    item.length,
                )
            } else {
                binding.progressLabel.visibility = View.GONE
            }
            binding.progress.progress = item.progressPercent

            if (item.hasBeenOpened) {
                binding.lastOpenedLabel.visibility = View.VISIBLE
                binding.lastOpenedLabel.text =
                    formatRelativeDate(binding.root.context, item.lastOpened)
            } else {
                binding.lastOpenedLabel.visibility = View.GONE
            }

            val coverWidthPx = (COVER_WIDTH_DP * binding.root.resources.displayMetrics.density).toInt()
            coverCache.bind(binding.cover, item.coverKey, item.uri, coverWidthPx, scope)

            binding.heroInnerCard.setOnClickListener { functions.onItemClicked(item) }
            binding.heroInnerCard.setOnLongClickListener { functions.onItemLongClicked(item) }
        }
    }

    companion object {
        private const val COVER_WIDTH_DP = 150
    }
}
