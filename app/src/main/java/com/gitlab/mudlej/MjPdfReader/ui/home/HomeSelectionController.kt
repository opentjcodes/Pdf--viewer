// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityHomeBinding

enum class SelectionContext { RECENT, LIBRARY, FOLDERS, SEARCH }

class HomeSelectionController(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val currentItems: () -> List<HomeItem>,
    private val currentContext: () -> SelectionContext,
    private val onSelectionChanged: () -> Unit,
    private val onStatusBatch: (List<HomeItem>) -> Unit,
    private val onRemoveRecentBatch: (List<HomeItem>) -> Unit,
    private val onHideBatch: (List<HomeItem>) -> Unit,
    private val onDeleteBatch: (List<HomeItem>) -> Unit,
) {
    private var actionMode: ActionMode? = null
    private var toolbarActive = false
    private var context = SelectionContext.LIBRARY

    private val keys = mutableSetOf<String>()

    val selectedKeys: Set<String>
        get() = keys.toSet()

    val active: Boolean
        get() = actionMode != null || toolbarActive

    val wantsBackButton: Boolean
        get() = toolbarActive

    init {
        binding.selectionToolbar.inflateMenu(R.menu.home_action_mode)
        binding.selectionToolbar.setNavigationOnClickListener { finish() }
        binding.selectionToolbar.setOnMenuItemClickListener { item -> handleAction(item) }
        ViewCompat.setOnApplyWindowInsetsListener(binding.selectionToolbar) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            if (params.topMargin != topInset) {
                params.topMargin = topInset
                view.layoutParams = params
            }
            insets
        }
    }

    fun begin(item: HomeItem): Boolean {
        if (active) {
            toggle(item)
            return true
        }
        keys.add(item.stableKey)
        context = currentContext()
        if (context == SelectionContext.SEARCH) {
            actionMode = activity.startSupportActionMode(actionModeCallback)
        } else {
            showSelectionToolbar()
        }
        updateTitle()
        onSelectionChanged()
        return true
    }

    fun toggle(item: HomeItem) {
        if (!keys.remove(item.stableKey)) {
            keys.add(item.stableKey)
        }
        if (keys.isEmpty()) {
            finish()
            return
        }
        updateTitle()
        onSelectionChanged()
    }

    fun finish() {
        actionMode?.finish()
        if (toolbarActive) {
            hideSelectionToolbar()
            endSelection()
        }
    }

    private fun showSelectionToolbar() {
        applyContextVisibility(binding.selectionToolbar.menu)
        toolbarActive = true
        binding.searchBar.visibility = View.GONE
        binding.selectionToolbar.visibility = View.VISIBLE
    }

    private fun hideSelectionToolbar() {
        toolbarActive = false
        binding.selectionToolbar.visibility = View.GONE
        binding.searchBar.visibility = View.VISIBLE
    }

    private fun endSelection() {
        keys.clear()
        onSelectionChanged()
    }

    private fun applyContextVisibility(menu: Menu) {
        menu.findItem(R.id.removeRecentBatchOption).isVisible = context == SelectionContext.RECENT
        menu.findItem(R.id.hideBatchOption).isVisible = context == SelectionContext.LIBRARY
        menu.findItem(R.id.deleteBatchOption).isVisible =
            context == SelectionContext.FOLDERS || context == SelectionContext.SEARCH
    }

    private fun updateTitle() {
        val title = activity.getString(R.string.home_selected_count, keys.size)
        actionMode?.title = title
        if (toolbarActive) {
            binding.selectionToolbar.title = title
        }
    }

    private fun selectedItems(): List<HomeItem> {
        return currentItems().filter { it.stableKey in keys }
    }

    private fun handleAction(item: MenuItem): Boolean {
        val items = selectedItems()
        if (items.isEmpty()) {
            return true
        }
        when (item.itemId) {
            R.id.statusBatchOption -> onStatusBatch(items)
            R.id.removeRecentBatchOption -> onRemoveRecentBatch(items)
            R.id.hideBatchOption -> onHideBatch(items)
            R.id.deleteBatchOption -> onDeleteBatch(items)
            else -> return false
        }
        return true
    }

    private val actionModeCallback = object : ActionMode.Callback {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.home_action_mode, menu)
            applyContextVisibility(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return handleAction(item)
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            endSelection()
        }
    }
}
