// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.userhighlights

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.DebugRootDescription
import com.gitlab.mudlej.MjPdfReader.core.io.appDateTimeFormatter
import com.gitlab.mudlej.MjPdfReader.core.io.convertDateString
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.attachFilterSearchView
import com.gitlab.mudlej.MjPdfReader.core.ui.configureSearchIcon
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoNightModeFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoOverlayFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.tintIconsForChrome
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityUserHighlightsBinding
import com.gitlab.mudlej.MjPdfReader.pdf.ExtractorScreen
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.PdfExtractor
import com.gitlab.mudlej.MjPdfReader.pdf.SweptHighlight
import com.gitlab.mudlej.MjPdfReader.pdf.sweepPageHighlights
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.HighlightPalette
import com.gitlab.mudlej.MjPdfReader.ui.tableofcontents.TableOfContentsPathResolver
import com.gitlab.mudlej.MjPdfReader.ui.usernotes.UserNoteAdapter
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.shockwave.pdfium.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.LocalDateTime
import java.util.Locale

class UserHighlightsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserHighlightsBinding
    private lateinit var pdfExtractor: PdfExtractor
    private val extractorScreen = ExtractorScreen(this)
    private val highlightAdapter = UserNoteAdapter(::onHighlightClicked, notesMode = false)
    private var highlights: List<SweptHighlight> = listOf()
    private var query: String = ""
    private var sortByDate: Boolean = false
    private var selectedColor: Int? = null
    private var documentMeta: PdfDocument.Meta? = null
    private var actionBarMenu: Menu? = null
    private val scannedPageLiveData = MutableLiveData<Int>()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { destinationUri ->
        destinationUri?.let(::exportHighlights)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyIncognitoNightModeFromIntent()
        super.onCreate(savedInstanceState)
        applyIncognitoOverlayFromIntent()
        binding = ActivityUserHighlightsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DebugRootDescription.attachLayoutDirection(binding.root)
        setupScreenChrome()
        title = getString(R.string.user_highlights_title)

        binding.userHighlightsRecyclerView.apply {
            adapter = highlightAdapter
            layoutManager = LinearLayoutManager(this@UserHighlightsActivity)
        }
        binding.progressBar.visibility = View.VISIBLE

        extractorScreen.open(getString(R.string.file_opening_error)) { extractor ->
            pdfExtractor = extractor
            documentMeta = withContext(Dispatchers.IO) { extractor.getMeta() }
            loadTableOfContentsPaths()
            scanHighlights()
        }
    }

    override fun onDestroy() {
        extractorScreen.close()
        super.onDestroy()
    }

    private fun loadTableOfContentsPaths() {
        lifecycleScope.launch {
            val resolver = TableOfContentsPathResolver.load(
                this@UserHighlightsActivity,
                intent.getStringExtra(PDF.filePathKey),
                intent.getStringExtra(PDF.passwordKey),
            )
            if (resolver !== TableOfContentsPathResolver.EMPTY) {
                highlightAdapter.tableOfContentsPathResolver = resolver
            }
        }
    }

    private fun scanHighlights() {
        val pageCount = pdfExtractor.getPageCount()
        binding.progressBar.visibility = View.GONE
        binding.scanProgressBar.max = pageCount
        binding.scanProgressBar.progress = 0
        binding.scanProgressBar.visibility = View.VISIBLE
        scannedPageLiveData.observe(this) { pageNumber ->
            binding.scanProgressBar.progress = pageNumber
        }

        lifecycleScope.launch(Dispatchers.Default) {
            val collected = mutableListOf<SweptHighlight>()
            var lastSubmittedCount = 0

            for (pageIndex in 0 until pageCount) {
                yield()
                collected.addAll(sweepPageHighlights(pdfExtractor, pageIndex))
                scannedPageLiveData.postValue(pageIndex + 1)

                val isBatchBoundary = pageIndex % SCAN_BATCH_PAGES == 0 || pageIndex == pageCount - 1
                if (isBatchBoundary && collected.size > lastSubmittedCount) {
                    lastSubmittedCount = collected.size
                    val snapshot = collected.toList()
                    withContext(Dispatchers.Main) {
                        highlights = snapshot
                        binding.message.visibility = View.GONE
                        refreshDisplayedHighlights()
                        refreshMenu()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                highlights = collected.toList()
                binding.scanProgressBar.visibility = View.GONE
                refreshDisplayedHighlights()
                refreshMenu()
                buildColorFilterChips()
                binding.message.text = getString(R.string.no_user_highlights)
                binding.message.visibility = if (highlights.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun buildColorFilterChips() {
        val colors = highlights.map { it.color }.distinct()
        if (colors.size < 2) {
            binding.colorFilterScroll.visibility = View.GONE
            return
        }
        val chipGroup = binding.colorFilterChips
        chipGroup.removeAllViews()

        val allChip = Chip(this).apply {
            id = View.generateViewId()
            text = getString(R.string.all)
            isCheckable = true
            isChecked = true
            setOnClickListener {
                selectedColor = null
                refreshDisplayedHighlights()
            }
        }
        chipGroup.addView(allChip)

        val density = resources.displayMetrics.density
        for (color in colors) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = colorLabel(color)
                isCheckable = true
                chipIcon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setSize((18 * density).toInt(), (18 * density).toInt())
                }
                isChipIconVisible = true
                setOnClickListener {
                    selectedColor = color
                    refreshDisplayedHighlights()
                }
            }
            chipGroup.addView(chip)
        }
        binding.colorFilterScroll.visibility = View.VISIBLE
    }

    private fun colorLabel(color: Int): String {
        val palette = HighlightPalette.fromColor(color)
        return if (palette != null) {
            getString(palette.nameRes)
        } else {
            String.format(Locale.US, "#%06X", color and 0xFFFFFF)
        }
    }

    private fun refreshDisplayedHighlights(): Int {
        var displayed = highlights
        selectedColor?.let { color ->
            displayed = displayed.filter { it.color == color }
        }
        if (query.isNotBlank()) {
            displayed = displayed.filter {
                it.quotedText.contains(query, true) || it.note.contains(query, true)
            }
        }
        if (sortByDate) {
            displayed = displayed.sortedWith(
                compareBy<SweptHighlight> { it.creationDate == null }
                    .thenByDescending { it.creationDate ?: "" }
            )
        }
        highlightAdapter.submitList(displayed)
        return displayed.size
    }

    private fun refreshMenu() {
        val menu = actionBarMenu ?: return
        configureSearchIcon(menu, highlights.isNotEmpty())
        menu.findItem(R.id.export_list)?.isVisible = highlights.isNotEmpty()
        menu.findItem(R.id.sort_by_creation_date)?.let { sortItem ->
            sortItem.isVisible = highlights.any { it.creationDate != null }
            sortItem.isChecked = sortByDate
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.annotation_list_menu, menu)
        menu.tintIconsForChrome(this)
        actionBarMenu = menu
        refreshMenu()
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.attachFilterSearchView(
            binding.root,
            onQueryChanged = { newQuery ->
                query = newQuery
                refreshDisplayedHighlights()
            },
            onClosed = {
                query = ""
                refreshDisplayedHighlights()
                true
            },
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.sort_by_creation_date -> {
                sortByDate = !sortByDate
                item.isChecked = sortByDate
                refreshDisplayedHighlights()
            }
            R.id.export_list -> exportLauncher.launch(exportFileName())
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun onHighlightClicked(item: SweptHighlight) {
        val resultIntent = Intent()
        resultIntent.putExtra(PDF.chosenTableOfContentsEntryKey, item.pageIndex)
        resultIntent.putExtra(PDF.chosenHighlightGroupKey, item.groupKey)
        resultIntent.putExtra(PDF.chosenHighlightAnnotationIndexKey, item.annotationIndex)
        item.bounds?.let { resultIntent.putExtra(PDF.chosenHighlightBoundsKey, it) }
        setResult(PDF.TABLE_OF_CONTENTS_RESULT_OK, resultIntent)
        finish()
    }

    private fun documentTitle(): String {
        val metaTitle = documentMeta?.title?.takeIf { it.isNotBlank() }
        val fileName = intent.getStringExtra(PDF.nameKey)?.takeIf { it.isNotBlank() }
        return metaTitle ?: fileName ?: getString(R.string.user_highlights_title)
    }

    private fun exportFileName(): String {
        return "${documentTitle()} - ${getString(R.string.user_highlights_title)}.txt"
    }

    private fun buildExportText(): String {
        val builder = StringBuilder()
        builder.appendLine(documentTitle())
        documentMeta?.author?.takeIf { it.isNotBlank() }?.let {
            builder.appendLine(getString(R.string.export_by_author, it))
        }
        builder.appendLine(
            getString(
                R.string.export_highlights_line,
                highlights.size,
                LocalDateTime.now().format(appDateTimeFormatter),
            )
        )

        val exported = if (sortByDate) {
            highlights.sortedWith(
                compareBy<SweptHighlight> { it.creationDate == null }
                    .thenByDescending { it.creationDate ?: "" }
            )
        } else {
            highlights
        }
        for (item in exported) {
            builder.appendLine()
            builder.appendLine(EXPORT_SEPARATOR)
            val infoParts = mutableListOf(getString(R.string.bookmark_page_label, item.pageIndex + 1))
            highlightAdapter.tableOfContentsPathResolver.resolve(item.pageIndex)?.let(infoParts::add)
            convertDateString(item.creationDate)?.let(infoParts::add)
            infoParts.add(colorLabel(item.color))
            builder.appendLine(infoParts.joinToString(" | "))
            if (item.quotedText.isNotBlank()) {
                builder.appendLine("${getString(R.string.export_quote_label)}: “${item.quotedText.trim()}”")
            }
            if (item.note.isNotBlank()) {
                builder.appendLine("${getString(R.string.note)}: ${item.note}")
            }
        }
        return builder.toString()
    }

    private fun exportHighlights(destinationUri: Uri) {
        lifecycleScope.launch {
            val text = buildExportText()
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = contentResolver.openOutputStream(destinationUri, "wt")
                        ?: contentResolver.openOutputStream(destinationUri, "w")
                        ?: return@runCatching false
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                }.getOrDefault(false)
            }
            val messageRes = if (written) R.string.export_done else R.string.export_failed
            AppSnackbar.make(binding.root, getString(messageRes), Snackbar.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val SCAN_BATCH_PAGES = 50
        private const val EXPORT_SEPARATOR = "----------------------------------------"
    }
}
