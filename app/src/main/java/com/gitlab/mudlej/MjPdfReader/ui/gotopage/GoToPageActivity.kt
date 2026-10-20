// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.gotopage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoNightModeFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoOverlayFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.toast
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityGoToPageBinding
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.PageThumbnailCache
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.PdfThemeController
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch

class GoToPageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGoToPageBinding
    private lateinit var pref: Preferences
    private lateinit var cache: PageThumbnailCache
    private var gridLayoutManager: GridLayoutManager? = null
    private var adapter: PageThumbnailGridAdapter? = null
    private var pageCount = 0
    private var currentPageIndex = 0
    private var restoredScrollPosition = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        applyIncognitoNightModeFromIntent()
        super.onCreate(savedInstanceState)
        applyIncognitoOverlayFromIntent()
        binding = ActivityGoToPageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        title = getString(R.string.all_pages)
        pref = Preferences(PreferenceManager.getDefaultSharedPreferences(this))

        val pdfPath = intent.getStringExtra(PDF.filePathKey)
        if (pdfPath.isNullOrBlank()) {
            finish()
            return
        }
        currentPageIndex = intent.getIntExtra(PDF.pageNumberKey, 0)
        restoredScrollPosition = savedInstanceState?.getInt(scrollPositionKey, -1) ?: -1
        cache = PageThumbnailCache(this, Uri.parse(pdfPath), intent.getStringExtra(PDF.passwordKey))

        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            pageCount = cache.pageCount()
            binding.progressBar.visibility = View.GONE
            if (pageCount <= 0) {
                toast(this@GoToPageActivity, getString(R.string.failed_to_load_pages), Toast.LENGTH_LONG)
                finish()
                return@launch
            }
            initGrid()
            initSlider()
        }
    }

    private fun initGrid() {
        val spanCount = pref.getGoToPageGridColumns().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val layoutManager = GridLayoutManager(this, spanCount)
        gridLayoutManager = layoutManager
        val gridAdapter = PageThumbnailGridAdapter(
            pageCount,
            currentPageIndex.coerceIn(0, pageCount - 1),
            cache,
            PdfThemeController.effectivePdfDarkTheme(this, pref),
            cellWidthFor(spanCount),
        ) { pageIndex -> onPageChosen(pageIndex) }
        adapter = gridAdapter
        binding.pageGrid.layoutManager = layoutManager
        binding.pageGrid.adapter = gridAdapter
        GridScrollHandle(binding.pageGrid, binding.scrollHandleThumb)

        val targetPosition = if (restoredScrollPosition >= 0) restoredScrollPosition else currentPageIndex
        binding.pageGrid.post {
            layoutManager.scrollToPositionWithOffset(
                targetPosition.coerceIn(0, pageCount - 1),
                binding.pageGrid.height / 3,
            )
        }
    }

    private fun initSlider() {
        val slider = binding.sizeSlider
        val columns = pref.getGoToPageGridColumns().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        slider.value = sliderValueFor(columns)
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                applySpanCount(columnsFor(value))
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}

            override fun onStopTrackingTouch(slider: Slider) {
                pref.setGoToPageGridColumns(columnsFor(slider.value))
            }
        })
    }

    private fun applySpanCount(newSpanCount: Int) {
        val layoutManager = gridLayoutManager ?: return
        val gridAdapter = adapter ?: return
        if (newSpanCount == layoutManager.spanCount) {
            return
        }
        layoutManager.spanCount = newSpanCount
        gridAdapter.cellWidthPx = cellWidthFor(newSpanCount)
        gridAdapter.notifyDataSetChanged()
    }

    private fun columnsFor(sliderValue: Float): Int {
        return sliderValue.toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    private fun sliderValueFor(columns: Int): Float {
        return columns.toFloat()
    }

    private fun cellWidthFor(spanCount: Int): Int {
        return resources.displayMetrics.widthPixels / spanCount
    }

    private fun onPageChosen(pageIndex: Int) {
        val resultIntent = Intent().putExtra(PDF.chosenPageIndexKey, pageIndex)
        setResult(PDF.GO_TO_PAGE_RESULT_OK, resultIntent)
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        gridLayoutManager?.findFirstVisibleItemPosition()?.takeIf { it >= 0 }?.let {
            outState.putInt(scrollPositionKey, it)
        }
    }

    override fun onDestroy() {
        if (::cache.isInitialized) {
            cache.close()
        }
        super.onDestroy()
    }

    companion object {
        private const val scrollPositionKey = "scrollPositionKey"
        private const val MIN_COLUMNS = 2
        private const val MAX_COLUMNS = 7
    }
}
