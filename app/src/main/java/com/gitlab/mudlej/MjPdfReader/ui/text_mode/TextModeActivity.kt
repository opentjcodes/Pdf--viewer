// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.text_mode

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.toast
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.grantPdfReadAccess
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityTextModeBinding
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.ui.gotopage.GoToPageActivity
import com.gitlab.mudlej.MjPdfReader.ui.gotopage.showGoToPageDialog
import com.gitlab.mudlej.MjPdfReader.ui.tableofcontents.TableOfContentsActivity
import com.gitlab.mudlej.MjPdfReader.ui.tableofcontents.TableOfContentsState
import com.gitlab.mudlej.MjPdfReader.core.ui.ColorUtil
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoNightModeFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoOverlayFromIntent
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TextModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextModeBinding
    private lateinit var adapter: TextModePageAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var pdfRepository: PdfRepository
    private lateinit var historyPolicy: HistoryPolicy
    private lateinit var contentLoader: TextModeContentLoader
    private lateinit var controlsController: TextModeControlsController
    private lateinit var pdfUri: Uri

    private val typographyController = TextModeTypographyController(
        this,
        { settings },
        ::updateSettings,
        ReflowControls(
            getJoinParagraphs = { joinParagraphsEnabled },
            getDetectHeadings = { detectHeadingsEnabled },
            getCodeBlocks = { codeBlocksEnabled },
            onJoinParagraphsChanged = ::setJoinParagraphs,
            onDetectHeadingsChanged = ::setDetectHeadings,
            onCodeBlocksChanged = ::setCodeBlocks,
            onReset = ::resetReflowOverrides,
        ),
    )

    private var pdfPassword: String? = null
    private var fileHash: String? = null
    private var pageCount = 0
    private var currentPageIndex = 0
    private var sliderTracking = false
    private var sliderGestureCancelled = false
    private var resultPrepared = false
    private var settings = TextModeSettings()
    private var joinParagraphsOverride: Boolean? = null
    private var detectHeadingsOverride: Boolean? = null
    private var codeBlocksOverride: Boolean? = null
    private var tableOfContentsState = TableOfContentsState()
    private var savedPageIndex = -1
    private var setupJob: Job? = null
    private var requestedPageIndex = RecyclerView.NO_POSITION
    private var holdAttempts = 0

    private val tableOfContentsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.let { tableOfContentsState = TableOfContentsState.from(it) }
        if (result.resultCode == PDF.TABLE_OF_CONTENTS_RESULT_OK) {
            val pageIndex = result.data?.getIntExtra(PDF.chosenTableOfContentsEntryKey, currentPageIndex)
                ?: return@registerForActivityResult
            scrollToPage(pageIndex)
        }
    }

    private val goToPageGridLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PDF.GO_TO_PAGE_RESULT_OK) {
            val pageIndex = result.data?.getIntExtra(PDF.chosenPageIndexKey, -1) ?: -1
            if (pageIndex >= 0) {
                scrollToPage(pageIndex)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyIncognitoNightModeFromIntent()
        super.onCreate(savedInstanceState)
        applyIncognitoOverlayFromIntent()
        binding = ActivityTextModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowChrome()
        createCoreServices()
        restoreState(savedInstanceState)
        initPdfProperties()
        if (!::pdfUri.isInitialized) return
        startDocumentSetup()
    }

    private fun setupWindowChrome() {
        ColorUtil.colorize(this, window, supportActionBar)
        ColorUtil.enterFullscreen(window)
        ViewCompat.setOnApplyWindowInsetsListener(binding.readerControlsCard) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            val margin = (12 * resources.displayMetrics.density).toInt() + bottomInset
            if (params.bottomMargin != margin) {
                params.bottomMargin = margin
                view.layoutParams = params
            }
            insets
        }
    }

    private fun createCoreServices() {
        pdfRepository = PdfRepository(AppDatabase.getInstance(applicationContext))
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        settings = TextModeSettings.load(sharedPreferences)
        val preferences = Preferences(sharedPreferences)
        historyPolicy = HistoryPolicy(preferences) { intent.getBooleanExtra(PDF.incognitoKey, false) }
        val hideDelayMillis = preferences.getHideDelay().toLong() + CONTROLS_EXTRA_HIDE_DELAY_MS
        contentLoader = TextModeContentLoader(
            this,
            binding.textPagesRecyclerView,
            { joinParagraphsEnabled },
            { detectHeadingsEnabled },
            { codeBlocksEnabled },
        )
        controlsController = TextModeControlsController(binding, hideDelayMillis)
    }

    private fun startDocumentSetup() {
        setupJob = lifecycleScope.launch {
            showLoading()
            if (!contentLoader.open(pdfUri, pdfPassword)) {
                if (!contentLoader.closing) {
                    badFileExit()
                }
                return@launch
            }
            pageCount = contentLoader.pageCount
            if (pageCount <= 0) {
                badFileExit()
                return@launch
            }
            if (fileHash == null) {
                fileHash = pdfRepository.resolveIdentity(this@TextModeActivity, pdfUri)
            }
            fileHash?.let { hash ->
                pdfRepository.findTextModeReflow(hash)?.let { override ->
                    joinParagraphsOverride = override.textModeJoinParagraphs
                    detectHeadingsOverride = override.textModeDetectHeadings
                    codeBlocksOverride = override.textModeCodeBlocks
                }
            }
            currentPageIndex = currentPageIndex.coerceIn(0, pageCount - 1)
            initReader()
            hideLoading()
        }
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        tableOfContentsState = savedInstanceState?.let { TableOfContentsState.from(it) } ?: TableOfContentsState.from(intent)
        currentPageIndex = savedInstanceState?.getInt(CURRENT_PAGE_KEY)
            ?: intent.getIntExtra(PDF.pageNumberKey, 0)
        fileHash = savedInstanceState?.getString(PDF.fileHashKey)
            ?: intent.getStringExtra(PDF.fileHashKey)
        savedPageIndex = currentPageIndex
    }

    private fun initPdfProperties() {
        val pdfPath = intent.getStringExtra(PDF.filePathKey)
        if (pdfPath.isNullOrBlank()) {
            badFileExit()
            return
        }

        pdfUri = Uri.parse(pdfPath)
        pdfPassword = intent.getStringExtra(PDF.passwordKey)
    }

    private fun initReader() {
        val initialPageIndex = currentPageIndex
        adapter = TextModePageAdapter(contentLoader::retryPage)
        layoutManager = LinearLayoutManager(this)
        contentLoader.attach(adapter, layoutManager) { currentPageIndex }
        binding.textPagesRecyclerView.adapter = adapter
        binding.textPagesRecyclerView.layoutManager = layoutManager
        binding.textPagesRecyclerView.itemAnimator = null
        controlsController.attachTapListener()
        binding.textPagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                holdRequestedPage()
                updateCurrentPageFromScroll()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    requestedPageIndex = RecyclerView.NO_POSITION
                }
            }
        })
        binding.textPagesRecyclerView.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    contentLoader.scheduleLoadVisiblePages()
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            },
        )

        adapter.submitPageCount(pageCount)
        adapter.applySettings(settings)
        applyReaderTheme()
        initControls()
        binding.textPagesRecyclerView.post {
            scrollToPage(initialPageIndex)
            binding.textPagesRecyclerView.post { contentLoader.loadVisiblePages() }
        }
    }

    private fun initControls() {
        binding.previousPageButton.setOnClickListener { scrollToPage(currentPageIndex - 1) }
        binding.nextPageButton.setOnClickListener { scrollToPage(currentPageIndex + 1) }
        binding.pageButton.setOnClickListener {
            showGoToPageDialog(
                this,
                currentPageIndex,
                pageCount,
                pdfUri,
                pdfPassword,
                ::scrollToPage,
                showAllPages = ::showGoToPageGrid,
            )
        }
        binding.tableOfContentsButton.setOnClickListener { showTableOfContents() }
        binding.typographyButton.setOnClickListener { typographyController.showSheet() }
        binding.backToPdfButton.setOnClickListener { finish() }

        binding.pageSlider.valueFrom = if (pageCount > 1) 1f else 0f
        binding.pageSlider.valueTo = if (pageCount > 1) pageCount.toFloat() else 1f
        binding.pageSlider.stepSize = 1f
        binding.pageSlider.isEnabled = pageCount > 1
        binding.pageSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                sliderTracking = true
                sliderGestureCancelled = false
            }

            override fun onStopTrackingTouch(slider: Slider) {
                sliderTracking = false
                if (sliderGestureCancelled) {
                    sliderGestureCancelled = false
                    updatePageControls()
                    return
                }
                scrollToPage(currentPageIndex)
            }
        })
        binding.pageSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                seekToPage(value.toInt() - 1)
            }
        }
        updatePageControls()
        controlsController.setControlsTouchListeners { sliderGestureCancelled = true }
        controlsController.showTemporarily()
    }

    private fun updateSettings(newSettings: TextModeSettings) {
        settings = newSettings
        settings.save(PreferenceManager.getDefaultSharedPreferences(this))
        applySettingsToPages()
        applyReaderTheme()
    }

    private val joinParagraphsEnabled: Boolean
        get() = joinParagraphsOverride ?: DEFAULT_JOIN_PARAGRAPHS

    private val detectHeadingsEnabled: Boolean
        get() = detectHeadingsOverride ?: DEFAULT_DETECT_HEADINGS

    private val codeBlocksEnabled: Boolean
        get() = codeBlocksOverride ?: DEFAULT_CODE_BLOCKS

    private fun setJoinParagraphs(enabled: Boolean) {
        if (joinParagraphsEnabled == enabled) return
        joinParagraphsOverride = enabled
        persistReflowOverrides()
        contentLoader.invalidateAndReload()
    }

    private fun setDetectHeadings(enabled: Boolean) {
        if (detectHeadingsEnabled == enabled) return
        detectHeadingsOverride = enabled
        persistReflowOverrides()
        contentLoader.invalidateAndReload()
    }

    private fun setCodeBlocks(enabled: Boolean) {
        if (codeBlocksEnabled == enabled) return
        codeBlocksOverride = enabled
        persistReflowOverrides()
        contentLoader.invalidateAndReload()
    }

    private fun resetReflowOverrides() {
        val effectiveChanged = joinParagraphsEnabled != DEFAULT_JOIN_PARAGRAPHS ||
            detectHeadingsEnabled != DEFAULT_DETECT_HEADINGS ||
            codeBlocksEnabled != DEFAULT_CODE_BLOCKS
        val hadOverrides = joinParagraphsOverride != null || detectHeadingsOverride != null ||
            codeBlocksOverride != null
        joinParagraphsOverride = null
        detectHeadingsOverride = null
        codeBlocksOverride = null
        if (hadOverrides) {
            persistReflowOverrides()
        }
        if (effectiveChanged) {
            contentLoader.invalidateAndReload()
        }
    }

    private fun persistReflowOverrides() {
        if (!historyPolicy.canRecord()) return
        val hash = fileHash ?: return
        val joinParagraphs = joinParagraphsOverride
        val detectHeadings = detectHeadingsOverride
        val codeBlocks = codeBlocksOverride
        lifecycleScope.launch {
            pdfRepository.setTextModeReflow(hash, joinParagraphs, detectHeadings, codeBlocks)
        }
    }

    private fun applySettingsToPages() {
        if (binding.textPagesRecyclerView.isComputingLayout) {
            binding.textPagesRecyclerView.post { applySettingsToPages() }
            return
        }

        adapter.applySettings(settings)
        contentLoader.scheduleLoadVisiblePages()
    }

    private fun applyReaderTheme() {
        val colors = settings.theme.colors(binding.root)
        binding.textModeRoot.setBackgroundColor(colors.background)
        binding.textPagesRecyclerView.setBackgroundColor(colors.background)
        binding.message.setTextColor(colors.label)
    }

    private fun scrollToPage(pageIndex: Int) {
        if (pageCount <= 0) return

        currentPageIndex = pageIndex.coerceIn(0, pageCount - 1)
        binding.textPagesRecyclerView.stopScroll()
        val anchoredView = layoutManager.findViewByPosition(currentPageIndex)
        if (anchoredView != null &&
            layoutManager.findFirstVisibleItemPosition() == currentPageIndex &&
            anchoredView.top == binding.textPagesRecyclerView.paddingTop
        ) {
            requestedPageIndex = RecyclerView.NO_POSITION
            updatePageControls()
            saveCurrentPage()
            return
        }
        requestedPageIndex = currentPageIndex
        holdAttempts = 0
        anchorRequestedPage()
        contentLoader.loadTargetWindow(currentPageIndex)
        binding.textPagesRecyclerView.doOnNextLayout { contentLoader.loadVisiblePages() }
        updatePageControls()
        saveCurrentPage()
    }

    private fun anchorRequestedPage() {
        binding.textPagesRecyclerView.focusedChild?.clearFocus()
        layoutManager.scrollToPositionWithOffset(requestedPageIndex, 0)
    }

    private fun holdRequestedPage() {
        val requested = requestedPageIndex
        if (requested == RecyclerView.NO_POSITION) return
        if (layoutManager.findFirstVisibleItemPosition() == requested) return
        if (holdAttempts >= MAX_HOLD_ATTEMPTS) {
            requestedPageIndex = RecyclerView.NO_POSITION
            return
        }
        holdAttempts++
        anchorRequestedPage()
    }

    private fun seekToPage(pageIndex: Int) {
        if (pageCount <= 0) return

        currentPageIndex = pageIndex.coerceIn(0, pageCount - 1)
        updatePageControls()
    }

    private fun updateCurrentPageFromScroll() {
        val firstVisiblePage = layoutManager.findFirstVisibleItemPosition()
        if (firstVisiblePage == RecyclerView.NO_POSITION) return

        contentLoader.loadVisiblePages()

        if (binding.textPagesRecyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) return
        if (firstVisiblePage == currentPageIndex) return

        currentPageIndex = firstVisiblePage
        updatePageControls()
        saveCurrentPage()
    }

    private fun saveCurrentPage() {
        if (!historyPolicy.canRecord()) return
        val hash = fileHash ?: return
        if (savedPageIndex == currentPageIndex) return

        savedPageIndex = currentPageIndex
        lifecycleScope.launch {
            pdfRepository.setPageNumber(hash, currentPageIndex)
        }
    }

    private fun updatePageControls() {
        if (pageCount <= 0) return

        binding.pageButton.text = getString(R.string.text_mode_page_counter, currentPageIndex + 1, pageCount)
        if (!sliderTracking) {
            binding.pageSlider.value = (currentPageIndex + 1).toFloat().coerceIn(binding.pageSlider.valueFrom, binding.pageSlider.valueTo)
        }
        binding.previousPageButton.isEnabled = currentPageIndex > 0
        binding.nextPageButton.isEnabled = currentPageIndex < pageCount - 1
    }

    private fun showTableOfContents() {
        Intent(this, TableOfContentsActivity::class.java).also { bookmarkIntent ->
            bookmarkIntent.putExtra(PDF.filePathKey, pdfUri.toString())
            bookmarkIntent.putExtra(PDF.passwordKey, pdfPassword)
            bookmarkIntent.putExtra(PDF.pageNumberKey, currentPageIndex)
            bookmarkIntent.putExtra(PDF.incognitoKey, intent.getBooleanExtra(PDF.incognitoKey, false))
            tableOfContentsState.putInto(bookmarkIntent)
            bookmarkIntent.grantPdfReadAccess(pdfUri.toString())
            tableOfContentsLauncher.launch(bookmarkIntent)
        }
    }

    private fun showGoToPageGrid() {
        Intent(this, GoToPageActivity::class.java).also { gridIntent ->
            gridIntent.putExtra(PDF.filePathKey, pdfUri.toString())
            gridIntent.putExtra(PDF.passwordKey, pdfPassword)
            gridIntent.putExtra(PDF.pageNumberKey, currentPageIndex)
            gridIntent.putExtra(PDF.incognitoKey, intent.getBooleanExtra(PDF.incognitoKey, false))
            gridIntent.grantPdfReadAccess(pdfUri.toString())
            goToPageGridLauncher.launch(gridIntent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(CURRENT_PAGE_KEY, currentPageIndex)
        fileHash?.let { outState.putString(PDF.fileHashKey, it) }
        tableOfContentsState.putInto(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ColorUtil.enterFullscreen(window)
        }
    }

    override fun finish() {
        if (!resultPrepared) {
            setPageResult(Activity.RESULT_OK)
        }
        super.finish()
    }

    override fun onDestroy() {
        if (::controlsController.isInitialized) {
            controlsController.release()
        }
        if (::contentLoader.isInitialized) {
            contentLoader.close()
        }
        setupJob?.cancel()
        super.onDestroy()
    }

    private fun setPageResult(resultCode: Int) {
        resultPrepared = true
        setResult(
            resultCode,
            Intent().putExtra(PDF.pageNumberKey, currentPageIndex),
        )
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.message.visibility = View.GONE
    }

    private fun hideLoading() {
        binding.progressBar.visibility = View.GONE
    }

    private fun badFileExit() {
        toast(this, getString(R.string.failed_to_extract_text), Toast.LENGTH_LONG)
        setPageResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val CURRENT_PAGE_KEY = "CURRENT_TEXT_MODE_PAGE"
        private const val CONTROLS_EXTRA_HIDE_DELAY_MS = 1500L
        private const val DEFAULT_JOIN_PARAGRAPHS = true
        private const val DEFAULT_DETECT_HEADINGS = true
        private const val DEFAULT_CODE_BLOCKS = true
        private const val MAX_HOLD_ATTEMPTS = 4
    }
}
