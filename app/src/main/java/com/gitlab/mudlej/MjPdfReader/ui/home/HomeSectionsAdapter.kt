// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeChipRowBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeEmptyStateBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeHeroSectionBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomePermissionCardBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeScanProgressBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemHomeScanSetupCardBinding
import kotlinx.coroutines.CoroutineScope

class HomeSectionsAdapter(
    private val coverCache: CoverCache,
    private val scope: CoroutineScope,
    private val functions: HomeItemFunctions,
    private val onGrantAccessClicked: () -> Unit = {},
    private val onScanSetupClicked: () -> Unit = {},
    private val selectedFilter: () -> ListFilter = { ListFilter.ALL },
    private val onChipSelected: (ListFilter) -> Unit = {},
) : ListAdapter<HomeSection, RecyclerView.ViewHolder>(SectionComparator()) {

    private var coverEpoch = 0

    fun rebindCovers() {
        coverEpoch++
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is HomeSection.PermissionCard -> TYPE_PERMISSION_CARD
            is HomeSection.ScanSetupCard -> TYPE_SCAN_SETUP
            is HomeSection.Hero -> TYPE_HERO
            is HomeSection.Chips -> TYPE_CHIPS
            is HomeSection.EmptyState -> TYPE_EMPTY_STATE
            is HomeSection.ScanProgressRow -> TYPE_SCAN_PROGRESS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PERMISSION_CARD -> PermissionCardViewHolder(
                ItemHomePermissionCardBinding.inflate(inflater, parent, false)
            )
            TYPE_SCAN_SETUP -> ScanSetupCardViewHolder(
                ItemHomeScanSetupCardBinding.inflate(inflater, parent, false)
            )
            TYPE_HERO -> HeroSectionViewHolder(
                ItemHomeHeroSectionBinding.inflate(inflater, parent, false)
            )
            TYPE_CHIPS -> ChipRowViewHolder(
                ItemHomeChipRowBinding.inflate(inflater, parent, false)
            )
            TYPE_SCAN_PROGRESS -> ScanProgressViewHolder(
                ItemHomeScanProgressBinding.inflate(inflater, parent, false)
            )
            else -> EmptyStateViewHolder(
                ItemHomeEmptyStateBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val section = getItem(position)) {
            is HomeSection.PermissionCard -> (holder as PermissionCardViewHolder).bind(section)
            is HomeSection.ScanSetupCard -> (holder as ScanSetupCardViewHolder).bind()
            is HomeSection.Hero -> (holder as HeroSectionViewHolder).bind(section)
            is HomeSection.Chips -> (holder as ChipRowViewHolder).bind()
            is HomeSection.ScanProgressRow -> (holder as ScanProgressViewHolder).bind(section)
            is HomeSection.EmptyState -> (holder as EmptyStateViewHolder).bind(section)
        }
    }

    inner class PermissionCardViewHolder(
        private val binding: ItemHomePermissionCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(section: HomeSection.PermissionCard) {
            binding.permissionMessage.setText(section.messageRes)
            binding.grantAccessButton.setOnClickListener { onGrantAccessClicked() }
        }
    }

    inner class ScanSetupCardViewHolder(
        private val binding: ItemHomeScanSetupCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            binding.scanSetupButton.setOnClickListener { onScanSetupClicked() }
        }
    }

    inner class HeroSectionViewHolder(
        binding: ItemHomeHeroSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val heroAdapter = HeroCarouselAdapter(coverCache, scope, functions)
        private val heroRecyclerView = binding.heroRecyclerView
        private var boundCoverEpoch = -1

        init {
            heroRecyclerView.layoutManager = LinearLayoutManager(
                binding.root.context, LinearLayoutManager.HORIZONTAL, false
            )
            heroRecyclerView.adapter = heroAdapter
            PagerSnapHelper().attachToRecyclerView(heroRecyclerView)
        }

        fun bind(section: HomeSection.Hero) {
            val previousFirstHash = heroAdapter.currentItems.firstOrNull()?.hash
            heroAdapter.replaceItems(section.items)
            if (section.items.firstOrNull()?.hash != previousFirstHash) {
                heroRecyclerView.scrollToPosition(0)
            }
            if (boundCoverEpoch != coverEpoch) {
                boundCoverEpoch = coverEpoch
                heroAdapter.notifyDataSetChanged()
            }
        }
    }

    inner class ChipRowViewHolder(
        private val binding: ItemHomeChipRowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val chips = listOf(
            binding.chipAllFiles to ListFilter.ALL,
            binding.chipToRead to ListFilter.TO_READ,
            binding.chipReading to ListFilter.READING,
            binding.chipOnHold to ListFilter.ON_HOLD,
            binding.chipCompleted to ListFilter.COMPLETED,
            binding.chipAbandoned to ListFilter.ABANDONED,
        )

        fun bind() {
            val selected = selectedFilter()
            chips.forEach { (chip, filter) ->
                chip.isChecked = filter == selected
                chip.setOnClickListener {
                    chips.forEach { (other, _) -> other.isChecked = other == chip }
                    onChipSelected(filter)
                }
            }
        }
    }

    class ScanProgressViewHolder(
        private val binding: ItemHomeScanProgressBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(section: HomeSection.ScanProgressRow) {
            val context = binding.root.context
            when (val progress = section.progress) {
                is ScanProgress.Finding -> {
                    binding.scanIndicator.visibility = View.VISIBLE
                    binding.scanIndicator.isIndeterminate = true
                    binding.scanLabel.text = context.getString(R.string.home_scan_finding, progress.found)
                }
                is ScanProgress.ReadingDetails -> {
                    binding.scanIndicator.visibility = View.VISIBLE
                    binding.scanIndicator.isIndeterminate = false
                    binding.scanIndicator.max = progress.total.coerceAtLeast(1)
                    binding.scanIndicator.setProgressCompat(progress.completed, true)
                    binding.scanLabel.text = context.getString(
                        R.string.home_scan_reading_details, progress.completed, progress.total
                    )
                }
                is ScanProgress.Incomplete -> {
                    binding.scanIndicator.visibility = View.GONE
                    binding.scanLabel.text = context.getString(
                        R.string.home_scan_incomplete, progress.found
                    )
                }
            }
        }
    }

    class EmptyStateViewHolder(
        private val binding: ItemHomeEmptyStateBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(section: HomeSection.EmptyState) {
            binding.emptyTitle.setText(section.titleRes)
            binding.emptyMessage.setText(section.messageRes)
        }
    }

    class SectionComparator : DiffUtil.ItemCallback<HomeSection>() {

        override fun areItemsTheSame(oldItem: HomeSection, newItem: HomeSection): Boolean {
            return oldItem::class == newItem::class
        }

        override fun areContentsTheSame(oldItem: HomeSection, newItem: HomeSection): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val TYPE_PERMISSION_CARD = 0
        private const val TYPE_HERO = 1
        private const val TYPE_CHIPS = 2
        private const val TYPE_EMPTY_STATE = 3
        private const val TYPE_SCAN_PROGRESS = 4
        private const val TYPE_SCAN_SETUP = 5
    }
}
