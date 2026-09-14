// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.userbookmarks

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.DebugRootDescription
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityUserBookmarksBinding
import com.gitlab.mudlej.MjPdfReader.core.ui.confirmDialog
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoNightModeFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoOverlayFromIntent
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.entity.UserBookmark
import com.gitlab.mudlej.MjPdfReader.ui.tableofcontents.TableOfContentsPathResolver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class UserBookmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserBookmarksBinding
    private val pdfRepository by lazy { PdfRepository(AppDatabase.getInstance(applicationContext)) }
    private val bookmarkAdapter = UserBookmarkAdapter(
        ::onBookmarkClicked,
        ::confirmDeleteBookmark,
        ::showRenameDialog,
    )
    private var bookmarks: List<UserBookmark> = listOf()
    private var fileHash: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyIncognitoNightModeFromIntent()
        super.onCreate(savedInstanceState)
        applyIncognitoOverlayFromIntent()
        binding = ActivityUserBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DebugRootDescription.attachLayoutDirection(binding.root)
        setupScreenChrome()
        title = getString(R.string.bookmarks)

        fileHash = intent.getStringExtra(PDF.fileHashKey)
        binding.userBookmarksRecyclerView.apply {
            adapter = bookmarkAdapter
            layoutManager = LinearLayoutManager(this@UserBookmarksActivity)
        }
        val touchHelper = ItemTouchHelper(UserBookmarkTouchCallback(bookmarkAdapter, ::saveBookmarkOrder))
        bookmarkAdapter.onDragRequested = touchHelper::startDrag
        touchHelper.attachToRecyclerView(binding.userBookmarksRecyclerView)
        loadBookmarks()
        loadTableOfContentsPaths()
    }

    private fun loadTableOfContentsPaths() {
        lifecycleScope.launch {
            val resolver = TableOfContentsPathResolver.load(
                this@UserBookmarksActivity,
                intent.getStringExtra(PDF.filePathKey),
                intent.getStringExtra(PDF.passwordKey),
            )
            if (resolver !== TableOfContentsPathResolver.EMPTY) {
                bookmarkAdapter.tableOfContentsPathResolver = resolver
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadBookmarks() {
        val hash = fileHash
        if (hash == null) {
            showBookmarks(emptyList())
            return
        }
        lifecycleScope.launch {
            showBookmarks(pdfRepository.findUserBookmarks(hash))
        }
    }

    private fun showBookmarks(loadedBookmarks: List<UserBookmark>) {
        bookmarks = loadedBookmarks
        bookmarkAdapter.submitList(bookmarks)
        binding.message.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onBookmarkClicked(bookmark: UserBookmark) {
        val resultIntent = Intent()
        resultIntent.putExtra(PDF.chosenTableOfContentsEntryKey, bookmark.pageIndex)
        setResult(PDF.TABLE_OF_CONTENTS_RESULT_OK, resultIntent)
        finish()
    }

    private fun confirmDeleteBookmark(bookmark: UserBookmark) {
        val label = bookmark.label?.takeIf { it.isNotBlank() }
            ?: getString(R.string.bookmark_page_label, bookmark.pageIndex + 1)
        confirmDialog(
            this,
            R.string.remove_bookmark,
            getString(R.string.delete_bookmark_confirm_message, label),
            R.string.delete,
        ) { deleteBookmark(bookmark) }
    }

    private fun deleteBookmark(bookmark: UserBookmark) {
        lifecycleScope.launch {
            pdfRepository.removeUserBookmark(bookmark.fileHash, bookmark.pageIndex)
            showBookmarks(bookmarks.filterNot { it.pageIndex == bookmark.pageIndex })
        }
    }

    private fun saveBookmarkOrder(reorderedBookmarks: List<UserBookmark>) {
        bookmarks = reorderedBookmarks.mapIndexed { index, bookmark -> bookmark.copy(sortOrder = index) }
        lifecycleScope.launch {
            pdfRepository.setUserBookmarkOrder(bookmarks)
        }
    }

    private fun showRenameDialog(bookmark: UserBookmark) {
        val editText = EditText(this).apply {
            setText(bookmark.label.orEmpty())
            hint = getString(R.string.bookmark_page_label, bookmark.pageIndex + 1)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val horizontalPadding = (20 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(editText)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_bookmark)
            .setView(container)
            .setPositiveButton(R.string.apply) { _, _ ->
                val label = editText.text.toString().trim().takeUnless { it.isBlank() }
                lifecycleScope.launch {
                    pdfRepository.setUserBookmarkLabel(bookmark.fileHash, bookmark.pageIndex, label)
                    showBookmarks(
                        bookmarks.map {
                            if (it.pageIndex == bookmark.pageIndex) it.copy(label = label) else it
                        }
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class UserBookmarkTouchCallback(
        private val adapter: UserBookmarkAdapter,
        private val onOrderChanged: (List<UserBookmark>) -> Unit,
    ) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

        private var moved = false

        override fun isLongPressDragEnabled() = false

        override fun isItemViewSwipeEnabled() = false

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val changed = adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            moved = moved || changed
            return changed
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            if (moved) {
                moved = false
                onOrderChanged(adapter.currentBookmarks())
            }
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    }
}
