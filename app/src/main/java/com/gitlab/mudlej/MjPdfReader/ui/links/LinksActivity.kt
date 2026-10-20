// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.links

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.attachFilterSearchView
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoNightModeFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoOverlayFromIntent
import com.gitlab.mudlej.MjPdfReader.pdf.Link
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityLinkBinding
import com.gitlab.mudlej.MjPdfReader.pdf.ExtractorScreen
import com.gitlab.mudlej.MjPdfReader.pdf.PdfExtractor
import com.gitlab.mudlej.MjPdfReader.core.ui.configureSearchIcon
import com.gitlab.mudlej.MjPdfReader.core.ui.copyToClipboard
import com.gitlab.mudlej.MjPdfReader.core.ui.openDocumentLink
import com.gitlab.mudlej.MjPdfReader.core.ui.tintIconsForChrome
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class LinksActivity : AppCompatActivity(), LinkFunctions {
    private lateinit var binding: ActivityLinkBinding
    private lateinit var pdfExtractor: PdfExtractor
    private val extractorScreen = ExtractorScreen(this)
    private val linkAdapter = LinkAdapter(this, this)
    private var links: List<Link> = listOf()
    private var actionBarMenu: Menu? = null
    private val lastPageLiveData = MutableLiveData<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyIncognitoNightModeFromIntent()
        super.onCreate(savedInstanceState)
        applyIncognitoOverlayFromIntent()
        binding = ActivityLinkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        title = getString(R.string.loading)

        showProgressBar()

        extractorScreen.open("Failed to read links! (file move or deleted?)") { extractor ->
            pdfExtractor = extractor
            initLinks()
            initUi()
        }
    }

    private fun showProgressBar() {
        binding.progressBar.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        extractorScreen.close()
        super.onDestroy()
    }

    private fun initLinks() {
        val pageCount = pdfExtractor.getPageCount()
        binding.progressBar.visibility = View.GONE
        binding.linksProgressBar.max = pageCount
        binding.linksProgressBar.progress = 0
        binding.linksProgressBar.visibility = View.VISIBLE
        lastPageLiveData.observe(this) { pageNumber ->
            binding.linksProgressBar.progress = pageNumber
        }

        lifecycleScope.launch(Dispatchers.Default) {
            val collected = mutableListOf<Link>()
            var lastSubmittedCount = 0

            for (pageIndex in 0 until pageCount) {
                yield()
                for (pageLink in pdfExtractor.getPageLinks(pageIndex)) {
                    val url = pageLink.uri
                    if (url.isNullOrBlank()) {
                        continue
                    }
                    collected.add(Link(text = "", url = url, pageNumber = pageIndex + 1))
                }
                lastPageLiveData.postValue(pageIndex + 1)

                val isBatchBoundary = pageIndex % LINKS_BATCH_PAGES == 0 || pageIndex == pageCount - 1
                if (isBatchBoundary && collected.size > lastSubmittedCount) {
                    lastSubmittedCount = collected.size
                    val snapshot = collected.toList()
                    withContext(Dispatchers.Main) {
                        links = snapshot
                        linkAdapter.submitList(snapshot)
                        binding.message.visibility = View.GONE
                        actionBarMenu?.let { configureSearchIcon(it, true) }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                links = collected.toList()
                linkAdapter.submitList(links)
                binding.linksProgressBar.visibility = View.GONE
                postGettingLinks()
            }
        }
    }

    private fun postGettingLinks() {
        if (links.isNotEmpty()) {
            binding.message.visibility = View.GONE
        }
        else {
            binding.message.text = getString(R.string.no_links_put_in_pdf)
        }
        actionBarMenu?.let { configureSearchIcon(it, links.isNotEmpty()) }

        // set up the title in the App Bar
        title = "${"%,d".format(links.size)} ${getString(R.string.links_in_document)}"

        // show too many results message
        if (links.size > PDF.TOO_MANY_RESULTS) {
            AppSnackbar.make(binding.root,getString(R.string.too_many_results_may_be_slow), Snackbar.LENGTH_INDEFINITE).also {
                it.setAction(getText(R.string.ok)) { }
                it.show()
            }
        }
    }

    private fun initUi() {
        title = getString(R.string.links_activity_title)
        linkAdapter.submitList(links)
        linkAdapter.progressBar = binding.progressBar
        binding.linkRecyclerView.apply {
            adapter = linkAdapter
            layoutManager = LinearLayoutManager(this@LinksActivity)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.search_menu, menu)
        menu.tintIconsForChrome(this)
        actionBarMenu = menu
        configureSearchIcon(menu, links.isNotEmpty())
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.attachFilterSearchView(
            binding.root,
            onQueryChanged = { query ->
                linkAdapter.nestedQuery = query
                binding.progressBar.visibility = View.VISIBLE
                val filteredList = links.filter {
                    it.url.contains(query, true) || it.text.contains(query, true)
                }
                linkAdapter.submitList(filteredList)
                linkAdapter.notifyDataSetChanged() // because the comparator doesn't see the difference in text style
                filteredList.size
            },
            onClosed = {
                linkAdapter.submitList(links)
                true
            },
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            else -> super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onLinkClicked(link: Link) {
        openDocumentLink(binding.root, link.url)
    }

    override fun onPageNumberClicked(link: Link) {
        Intent().also { resultIntent ->
            resultIntent.putExtra(PDF.linkResultKey, link.pageNumber)
            setResult(PDF.LINK_RESULT_OK, resultIntent)
        }
        finish()
    }

    override fun onCopyLinkClicked(link: Link) {
        val copyLabel = "Link URL copy"
        copyToClipboard(this, copyLabel, link.url)
    }

    companion object {
        const val TAG = "LinksActivity"
        private const val LINKS_BATCH_PAGES = 50
    }

}
