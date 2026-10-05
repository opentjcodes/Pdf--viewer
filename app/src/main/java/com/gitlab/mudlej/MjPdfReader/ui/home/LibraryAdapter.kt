// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeGridCellBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeListRowBinding
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.gitlab.mudlej.MjPdfReader.core.io.formatRelativeDate
import com.gitlab.mudlej.MjPdfReader.core.text.StringUtil.formatEnumToTitle
import kotlinx.coroutines.CoroutineScope

enum class ListMetaStyle { FOLDERS, LIBRARY, RECENT }

class LibraryAdapter(
    private val coverCache: CoverCache,
    private val scope: CoroutineScope,
    private val functions: HomeItemFunctions,
    private val pref: Preferences,
    private val selection: () -> Set<String> = { emptySet() },
) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(HomeItemComparator()) {

    var viewMode: HomeViewMode = HomeViewMode.GRID
    var coverWidthPx: Int = DEFAULT_COVER_WIDTH_PX
    var metaStyle: ListMetaStyle = ListMetaStyle.LIBRARY
    var progressStyle: HomeProgressStyle = HomeProgressStyle.RING

    private var appliedTitleSignature: String? = null

    fun applyTitleStyle() {
        val signature = titleSignature()
        val firstApply = appliedTitleSignature == null
        appliedTitleSignature = signature
        if (!firstApply) {
            notifyDataSetChanged()
        }
    }

    private fun titleSignature(): String {
        return "${pref.getHomeGridTitleLines()}|${pref.getHomeListTitleLines()}|${pref.getHomeTitleEllipsize().name}" +
            "|${pref.getHomeBadgePages()}|${pref.getHomeBadgeProgress()}|${pref.getHomeBadgeLastOpened()}" +
            "|${pref.getHomeBadgeFileSize()}|${pref.getHomeBadgeStatus()}"
    }

    private fun TextView.applyTitleLines(maxLines: Int) {
        this.maxLines = maxLines
        ellipsize = pref.getHomeTitleEllipsize().truncateAt
    }

    override fun getItemViewType(position: Int): Int {
        return if (viewMode == HomeViewMode.GRID) TYPE_GRID else TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GRID) {
            GridViewHolder(ItemHomeGridCellBinding.inflate(inflater, parent, false))
        } else {
            ListViewHolder(ItemHomeListRowBinding.inflate(inflater, parent, false))
        }
    }
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.itemAnimator = null
    }


    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is GridViewHolder -> holder.bind(item)
            is ListViewHolder -> holder.bind(item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isNotEmpty() && payloads.all { it == SELECTION_PAYLOAD }) {
            val item = getItem(position)
            when (holder) {
                is GridViewHolder -> holder.applySelection(item)
                is ListViewHolder -> holder.applySelection(item)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    fun notifySelectionChanged() {
        notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
    }

    private fun isSelected(item: HomeItem) = item.stableKey in selection()

    inner class GridViewHolder(
        private val binding: ItemHomeGridCellBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem) {
            binding.title.text = item.title
            binding.title.applyTitleLines(pref.getHomeGridTitleLines())

            if (item.hasBeenOpened) {
                binding.lastOpenedLabel.visibility = View.VISIBLE
                binding.lastOpenedLabel.text =
                    formatRelativeDate(binding.root.context, item.lastOpened)
            } else {
                binding.lastOpenedLabel.visibility = View.GONE
            }

            val showBar = item.progressPercent > 0 && progressStyle == HomeProgressStyle.BAR
            val showRing = item.progressPercent > 0 && progressStyle == HomeProgressStyle.RING
            binding.progress.visibility = if (showBar) View.VISIBLE else View.GONE
            binding.progressBadge.visibility = if (showRing) View.VISIBLE else View.GONE
            if (showBar) {
                binding.progress.progress = item.progressPercent
            }
            if (showRing) {
                binding.progressCircle.progress = item.progressPercent
                binding.progressPercent.text = item.progressPercent.toString()
            }

            applySelection(item)
            coverCache.bind(binding.cover, item.coverKey, item.uri, coverWidthPx, scope)

            binding.coverCard.setOnClickListener { functions.onItemClicked(item) }
            binding.coverCard.setOnLongClickListener { functions.onItemLongClicked(item) }
            binding.optionsButton.setOnClickListener { functions.onItemOptionsClicked(item) }
        }

        fun applySelection(item: HomeItem) {
            binding.root.alpha = if (item.available) 1f else UNAVAILABLE_ALPHA
            binding.coverCard.isChecked = isSelected(item)
        }
    }

    inner class ListViewHolder(
        private val binding: ItemHomeListRowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem) {
            binding.title.text = item.title
            binding.title.applyTitleLines(pref.getHomeListTitleLines())
            bindMetaBadges(item)

            applySelection(item)
            coverCache.bind(binding.cover, item.coverKey, item.uri, LIST_COVER_WIDTH_PX, scope)

            binding.listCard.setOnClickListener { functions.onItemClicked(item) }
            binding.listCard.setOnLongClickListener { functions.onItemLongClicked(item) }
            binding.optionsButton.setOnClickListener { functions.onItemOptionsClicked(item) }
        }

        fun applySelection(item: HomeItem) {
            binding.root.alpha = if (item.available) 1f else UNAVAILABLE_ALPHA
            binding.listCard.isChecked = isSelected(item)
        }

        private fun bindMetaBadges(item: HomeItem) {
            val container = binding.metaBadges
            container.removeAllViews()

            val parts = buildMetaParts(item)
            if (parts.isEmpty()) {
                container.visibility = View.GONE
                return
            }
            container.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(container.context)
            parts.forEach { (text, accent) ->
                val layout = if (accent) R.layout.home_meta_badge_accent else R.layout.home_meta_badge
                val badge = inflater.inflate(layout, container, false) as TextView
                badge.text = text
                container.addView(badge)
            }
        }

        private fun buildMetaParts(item: HomeItem): List<Pair<String, Boolean>> {
            val context = binding.root.context
            val parts = mutableListOf<Pair<String, Boolean>>()
            if (pref.getHomeBadgePages() && item.length > 0) {
                if (item.readingStarted) {
                    parts.add("${item.pageNumber + 1}/${item.length}" to false)
                } else {
                    parts.add(
                        context.resources.getQuantityString(
                            R.plurals.home_pages, item.length, item.length
                        ) to false
                    )
                }
            }
            if (metaStyle == ListMetaStyle.FOLDERS) {
                if (pref.getHomeBadgeFileSize() && item.sizeBytes > 0) {
                    parts.add(Formatter.formatShortFileSize(context, item.sizeBytes) to false)
                }
            } else {
                if (pref.getHomeBadgeProgress() && item.progressPercent > 0) {
                    parts.add("${item.progressPercent}%" to false)
                }
                if (pref.getHomeBadgeLastOpened() && item.hasBeenOpened) {
                    parts.add(formatRelativeDate(context, item.lastOpened) to false)
                }
            }
            if (pref.getHomeBadgeStatus() && metaStyle == ListMetaStyle.RECENT
                && item.readingStatus != ReadingStatus.UNSET
            ) {
                parts.add(item.readingStatus.name.formatEnumToTitle() to true)
            }
            return parts
        }
    }

    companion object {
        private const val TYPE_GRID = 0
        private const val TYPE_LIST = 1
        private const val DEFAULT_COVER_WIDTH_PX = 320
        private const val LIST_COVER_WIDTH_PX = 192
        private const val SELECTION_PAYLOAD = "selection"
        private const val UNAVAILABLE_ALPHA = 0.45f
    }
}

class HomeItemComparator : DiffUtil.ItemCallback<HomeItem>() {

    override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean {
        return oldItem.stableKey == newItem.stableKey
    }

    override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean {
        return oldItem == newItem
    }
}
