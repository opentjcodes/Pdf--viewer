// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import android.view.MotionEvent
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class LibraryViewport(
    private val context: Context,
    private val pref: Preferences,
    private val libraryAdapter: LibraryAdapter,
    private val sectionsAdapter: HomeSectionsAdapter,
    private val onInteractionChanged: (Boolean) -> Unit,
    private val onPresentationInvalidated: () -> Unit,
) {
    private var spanCount = 2
    private var gridLayoutManager: GridLayoutManager? = null
    private var recyclerView: RecyclerView? = null
    private var attachmentRevision = 0L
    private var hostStopped = false
    private var cancelCanonicalSubmission: (() -> Unit)? = null
    private var scrolledBy = 0L
    private var touching = false
    private var scrolling = false

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
            updateInteraction(scrolling = newState != RecyclerView.SCROLL_STATE_IDLE)
        }

        override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
            scrolledBy += dy
        }
    }

    private val touchListener = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(view: RecyclerView, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> updateInteraction(touching = true)
                MotionEvent.ACTION_POINTER_UP -> updateInteraction(touching = event.pointerCount > 1)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> updateInteraction(touching = false)
            }
            return false
        }

        override fun onTouchEvent(view: RecyclerView, event: MotionEvent) = Unit

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    }

    private val attachListener = object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: android.view.View) {
            attachmentRevision++
            onPresentationInvalidated()
        }

        override fun onViewDetachedFromWindow(view: android.view.View) {
            attachmentRevision++
            updateInteraction(touching = false, scrolling = false)
        }
    }

    fun attach(recyclerView: RecyclerView) {
        this.recyclerView?.let { previous ->
            previous.removeOnScrollListener(scrollListener)
            previous.removeOnItemTouchListener(touchListener)
            previous.removeOnAttachStateChangeListener(attachListener)
        }
        attachmentRevision++
        updateInteraction(touching = false, scrolling = false)
        spanCount = computeSpanCount()
        libraryAdapter.viewMode = pref.getHomeViewMode()
        libraryAdapter.progressStyle = pref.getHomeProgressStyle()
        libraryAdapter.coverWidthPx = context.resources.displayMetrics.widthPixels / spanCount
        val layoutManager = GridLayoutManager(context, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (position < sectionsAdapter.itemCount || libraryAdapter.viewMode == HomeViewMode.LIST) {
                    spanCount
                } else 1
        }
        gridLayoutManager = layoutManager
        this.recyclerView = recyclerView
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = ConcatAdapter(sectionsAdapter, libraryAdapter)
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnItemTouchListener(touchListener)
        recyclerView.addOnAttachStateChangeListener(attachListener)
    }

    fun onHostStopped() {
        hostStopped = true
        attachmentRevision++
        updateInteraction(touching = false, scrolling = false)
    }

    fun onHostStarted() {
        hostStopped = false
        updateInteraction(scrolling = recyclerView?.scrollState?.let { it != RecyclerView.SCROLL_STATE_IDLE } == true)
    }

    private fun updateInteraction(touching: Boolean = this.touching, scrolling: Boolean = this.scrolling) {
        val wasActive = this.touching || this.scrolling
        this.touching = touching
        this.scrolling = scrolling
        val active = touching || scrolling
        if (wasActive != active) onInteractionChanged(active)
    }

    fun applyGridSize() {
        spanCount = computeSpanCount()
        libraryAdapter.coverWidthPx = context.resources.displayMetrics.widthPixels / spanCount
        gridLayoutManager?.spanCount = spanCount
        libraryAdapter.notifyDataSetChanged()
    }

    fun onInteractionBlocked(): Boolean {
        val cancel = cancelCanonicalSubmission ?: return false
        cancel()
        return true
    }

    suspend fun submit(items: List<HomeItem>, sections: List<HomeSection>, canonical: Boolean): Boolean {
        if (hostStopped || (canonical && (touching || scrolling))) return false
        val revision = attachmentRevision
        val view = recyclerView
        val manager = gridLayoutManager
        val presented = libraryAdapter.currentList
        val needsAnchor = sections != sectionsAdapter.currentList ||
            items.take(presented.size).map { it.stableKey } != presented.map { it.stableKey }
        val anchor = if (needsAnchor) captureAnchor(manager, view, presented) else null
        val scrollAtCapture = scrolledBy
        return suspendCoroutine { continuation ->
            var finished = false
            fun complete(accepted: Boolean) {
                if (finished) return
                finished = true
                cancelCanonicalSubmission = null
                continuation.resume(accepted)
            }
            if (canonical) {
                cancelCanonicalSubmission = {
                    libraryAdapter.submitList(libraryAdapter.currentList)
                    sectionsAdapter.submitList(sectionsAdapter.currentList)
                    complete(false)
                }
            }
            libraryAdapter.submitList(items) rowsCommitted@{
                if (finished) return@rowsCommitted
                if (revision != attachmentRevision) {
                    complete(false)
                    return@rowsCommitted
                }
                sectionsAdapter.submitList(sections) sectionsCommitted@{
                    if (finished) return@sectionsCommitted
                    if (revision != attachmentRevision) {
                        complete(false)
                        return@sectionsCommitted
                    }
                    restoreAnchor(
                        anchor?.copy(top = anchor.top - (scrolledBy - scrollAtCapture).toInt()),
                        libraryAdapter.currentList, manager, view,
                    )
                    complete(true)
                }
            }
        }
    }

    private data class Anchor(val items: List<HomeItem>, val index: Int, val top: Int)

    private fun captureAnchor(
        manager: GridLayoutManager?,
        view: RecyclerView?,
        presented: List<HomeItem>,
    ): Anchor? {
        if (manager == null || view == null) return null
        for (childIndex in 0 until view.childCount) {
            val child = view.getChildAt(childIndex)
            if (manager.getDecoratedBottom(child) <= view.paddingTop ||
                manager.getDecoratedTop(child) >= view.height - view.paddingBottom
            ) continue
            val holder = view.getChildViewHolder(child)
            if (holder.bindingAdapter !== libraryAdapter) continue
            val index = holder.bindingAdapterPosition
            if (index !in presented.indices) continue
            val margin = (child.layoutParams as RecyclerView.LayoutParams).topMargin
            return Anchor(presented, index, manager.getDecoratedTop(child) - margin - view.paddingTop)
        }
        return null
    }

    private fun restoreAnchor(
        anchor: Anchor?,
        canonical: List<HomeItem>,
        manager: GridLayoutManager?,
        view: RecyclerView?,
    ) {
        if (anchor == null || manager == null || view?.isAttachedToWindow != true) return
        val retainedIndex = anchorIndex(anchor.items, anchor.index, canonical) ?: return
        manager.scrollToPositionWithOffset(
            sectionsAdapter.itemCount + retainedIndex,
            anchor.top,
        )
    }

    private fun computeSpanCount(): Int =
        (context.resources.configuration.screenWidthDp / pref.getHomeGridSize().targetCellDp).coerceAtLeast(2)

    companion object {
        fun anchorIndex(presented: List<HomeItem>, index: Int, items: List<HomeItem>): Int? {
            val anchor = presented.getOrNull(index) ?: return null
            val positions = items.withIndex().associate { it.value.stableKey to it.index }
            positions[anchor.stableKey]?.let { return it }
            if (presented.count { it.hash == anchor.hash } == 1) {
                items.withIndex().singleOrNull { it.value.hash == anchor.hash }?.let { return it.index }
            }
            for (distance in 1 until presented.size) {
                presented.getOrNull(index + distance)?.let { positions[it.stableKey] }?.let { return it }
                presented.getOrNull(index - distance)?.let { positions[it.stableKey] }?.let { return it }
            }
            return null
        }
    }
}
