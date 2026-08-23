// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityHomeBinding
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.gitlab.mudlej.MjPdfReader.data.BackupNotices
import com.gitlab.mudlej.MjPdfReader.data.HistoryCleaner
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationJournal
import com.gitlab.mudlej.MjPdfReader.data.signature.SignatureStore
import com.gitlab.mudlej.MjPdfReader.core.PermissionManager
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.ColorUtil
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.ui.about.WhatsNewActivity
import com.gitlab.mudlej.MjPdfReader.ui.about.WhatsNewRelease
import com.gitlab.mudlej.MjPdfReader.ui.about.whatsNewReleaseForUpgrade
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.intro.MainIntroActivity
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsActivity
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsPage
import com.gitlab.mudlej.MjPdfReader.core.io.DocumentRemover
import com.gitlab.mudlej.MjPdfReader.core.io.PersistedGrantKeeper
import com.gitlab.mudlej.MjPdfReader.core.text.StringUtil.formatEnumToTitle
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.search.SearchView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity(), HomeItemFunctions {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var pref: Preferences
    private lateinit var pdfRepository: PdfRepository
    private lateinit var historyPolicy: HistoryPolicy
    private lateinit var historyCleaner: HistoryCleaner
    private lateinit var permissionManager: PermissionManager
    private lateinit var coverCache: CoverCache
    private lateinit var libraryController: HomeLibraryController
    private lateinit var libraryScanner: LibraryScanner
    private lateinit var selectionController: HomeSelectionController
    private lateinit var relocateController: RelocateController
    private lateinit var menuDialog: HomeMenuDialog
    private lateinit var scanSetupDialog: ScanSetupDialog
    private lateinit var recordOptionsDialog: RecordOptionsDialog
    private lateinit var searchResultsAdapter: LibraryAdapter
    private lateinit var recentTab: RecentTabController
    private lateinit var libraryTab: LibraryTabController
    private lateinit var foldersTab: FoldersTabController

    private val homeViewModel: HomeViewModel by viewModels()

    private var allItems: List<HomeItem> = emptyList()
    private var allRecordItems: List<HomeItem> = emptyList()
    private var relinkRunning = false
    private val renderMutex = Mutex()

    private val homeBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                selectionController.wantsBackButton -> selectionController.finish()
                foldersCanGoBack() -> foldersTab.goBack()
            }
        }
    }

    private val scanLocationsPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                libraryScanner.refresh(force = true)
                refresh()
            }
        }

    private val introLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            showWhatsNewOnFirstRun()
        }

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        openPickedDocument(uri, incognito = false)
    }

    private val pdfPickerIncognito = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        openPickedDocument(uri, incognito = true)
    }

    private fun openPickedDocument(uri: Uri?, incognito: Boolean) {
        if (uri == null) {
            return
        }
        if (!incognito) {
            PersistedGrantKeeper.takeReadGrant(this, uri)
        }
        openInReader(uri, incognito = incognito)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pref = Preferences(PreferenceManager.getDefaultSharedPreferences(this))
        val launchIntro = launchIntroOnFirstInstall()
        if (redirectToReaderIfHomeDisabled()) {
            return
        }
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowChrome()
        createCoreServices()
        createControllers()
        setupPager()
        setupSearch()
        setupMenuAndNavigation()
        setupOpenFab()
        observeLibraryIndex()
        maybeShowWhatsNew(launchIntro)
        handleRelocateIntent(intent)
    }

    private fun launchIntroOnFirstInstall(): Boolean {
        val launchIntro = pref.getFirstInstall()
        if (launchIntro) {
            pref.setFirstInstall(false)
            pref.setShowFeaturesDialog(true)
            pref.setLastSeenVersionCode(BuildConfig.VERSION_CODE)
            introLauncher.launch(Intent(this, MainIntroActivity::class.java))
        }
        return launchIntro
    }

    private fun redirectToReaderIfHomeDisabled(): Boolean {
        if (pref.getHomeDisabled()) {
            val release = consumeWhatsNewDue()
            startActivity(Intent(this, MainActivity::class.java))
            if (release != null) {
                startActivity(WhatsNewActivity.createIntent(this, release))
            }
            overridePendingTransition(0, 0)
            finish()
            return true
        }
        return false
    }

    private fun consumeWhatsNewDue(): WhatsNewRelease? {
        val release = whatsNewReleaseForUpgrade(
            lastSeenVersionCode = pref.getLastSeenVersionCode(),
            currentVersionCode = BuildConfig.VERSION_CODE,
        ) ?: return null
        pref.setLastSeenVersionCode(BuildConfig.VERSION_CODE)
        return release
    }

    private fun setupWindowChrome() {
        ColorUtil.applySystemBarIconColors(this, window)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (view.paddingBottom != bottomInset) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomInset)
            }
            insets
        }
    }

    private fun createCoreServices() {
        pdfRepository = PdfRepository(AppDatabase.getInstance(applicationContext))
        coverCache = CoverCache.getInstance(applicationContext)
        historyPolicy = HistoryPolicy(pref)
        historyCleaner = HistoryCleaner(
            pdfRepository,
            AnnotationJournal(applicationContext),
            SignatureStore(applicationContext),
            coverCache,
        )
        permissionManager = PermissionManager(this) { onStorageAccessChanged() }
        libraryController = HomeLibraryController(pdfRepository, pref)
        libraryScanner = LibraryScanner.getInstance(applicationContext)
        scanSetupDialog = ScanSetupDialog(
            this,
            pref,
            onWholeDeviceChosen = {
                pref.setScanMode(ScanMode.WHOLE_DEVICE)
                libraryScanner.refresh(force = true)
                refresh()
            },
            onPickLocationsChosen = {
                scanLocationsPicker.launch(Intent(this, ScanLocationsActivity::class.java))
            },
        )
    }

    private fun createControllers() {
        recordOptionsDialog = RecordOptionsDialog(
            this,
            pdfRepository,
            coverCache,
            libraryScanner,
            historyPolicy,
            historyCleaner,
            lifecycleScope,
            onOpenIncognito = { item ->
                openInReader(item.uri, item.hash.takeUnless { item.isScanOnly }, incognito = true)
            },
            onChanged = ::refresh,
        )
        recentTab = RecentTabController(
            coverCache,
            lifecycleScope,
            this,
            pref,
            libraryController,
            hasFullAccess = { permissionManager.hasFullAccess() },
            onGrantAccessClicked = { permissionManager.requestFullAccess() },
            selection = { selectionController.selectedHashes },
        )
        libraryTab = LibraryTabController(
            this,
            pref,
            coverCache,
            lifecycleScope,
            this,
            selection = { selectionController.selectedHashes },
            libraryController = libraryController,
            libraryScanner = libraryScanner,
            hasFullAccess = { permissionManager.hasFullAccess() },
            onGrantAccessClicked = { permissionManager.requestFullAccess() },
            showScanSetup = ::shouldShowScanSetup,
            onScanSetupClicked = { scanSetupDialog.show() },
            onFilterChanged = ::refresh,
        )
        selectionController = HomeSelectionController(
            this,
            binding,
            currentItems = ::selectableItems,
            currentContext = ::selectionContext,
            onSelectionChanged = ::notifySelectionChanged,
            onStatusBatch = ::statusBatch,
            onRemoveRecentBatch = ::removeRecentBatch,
            onHideBatch = ::hideBatch,
            onDeleteBatch = ::deleteBatch,
        )

        foldersTab = FoldersTabController(
            this,
            pref,
            coverCache,
            lifecycleScope,
            this,
            onGrantAccessClicked = { permissionManager.requestFullAccess() },
            hasFullAccess = { permissionManager.hasFullAccess() },
            libraryController = libraryController,
            onNavigationChanged = ::updateBackState,
            selection = { selectionController.selectedHashes },
        )
        relocateController = RelocateController(
            this,
            pdfRepository,
            libraryScanner,
            homeViewModel,
            lifecycleScope,
            onOpen = ::openInReader,
            onHealed = ::refresh,
        )
    }

    private fun setupMenuAndNavigation() {
        menuDialog = HomeMenuDialog(
            this,
            pref,
            currentTab = { currentTab() },
            onViewModeChanged = { libraryTab.applyViewMode() },
            onGridSizeChanged = { libraryTab.applyGridSize() },
            onSortChanged = ::refresh,
            onFolderModeChanged = { foldersTab.onModeChanged() },
            onShowStats = {
                showLibraryStatsDialog(this, allItems, libraryScanner.libraryEntries())
            },
            hasFullAccess = { permissionManager.hasFullAccess() },
            onScanLocations = { scanSetupDialog.show() },
        )
        onBackPressedDispatcher.addCallback(this, homeBackCallback)
        binding.searchBar.inflateMenu(R.menu.home_search_bar)
        binding.searchBar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.homeMenuOption) {
                menuDialog.show()
                true
            } else {
                false
            }
        }
    }

    private fun setupOpenFab() {
        binding.openPdfFab.setOnClickListener { pdfPicker.launch(arrayOf(PDF.FILE_TYPE)) }
        binding.openPdfFab.setOnLongClickListener {
            Toast.makeText(this, R.string.open_in_incognito_hint, Toast.LENGTH_SHORT).show()
            pdfPickerIncognito.launch(arrayOf(PDF.FILE_TYPE))
            true
        }
    }

    private fun observeLibraryIndex() {
        lifecycleScope.launch {
            libraryScanner.index.collect { refresh() }
        }
    }

    private fun maybeShowWhatsNew(launchIntro: Boolean) {
        if (!launchIntro) {
            showWhatsNewOnFirstRun(consumeWhatsNewDue())
        }
    }

    private fun setupPager() {
        binding.homePager.adapter = HomeTabsAdapter { tab, recyclerView, swipeRefresh ->
            when (tab) {
                HomeTab.RECENT -> recentTab.attach(recyclerView)
                HomeTab.LIBRARY -> libraryTab.attach(recyclerView)
                HomeTab.FOLDERS -> foldersTab.attach(recyclerView)
            }
            setupPullToRefresh(swipeRefresh)
        }
        binding.homePager.offscreenPageLimit = HomeTab.entries.size - 1

        TabLayoutMediator(binding.homeTabs, binding.homePager) { tab, position ->
            tab.setText(
                when (HomeTab.entries[position]) {
                    HomeTab.RECENT -> R.string.home_tab_recent
                    HomeTab.LIBRARY -> R.string.home_tab_library
                    HomeTab.FOLDERS -> R.string.home_tab_folders
                }
            )
        }.attach()

        binding.homePager.setCurrentItem(pref.getHomeTab().ordinal, false)
        binding.homePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pref.setHomeTab(HomeTab.entries[position])
                selectionController.finish()
                updateBackState()
            }
        })
    }

    private fun updateBackState() {
        homeBackCallback.isEnabled = selectionController.wantsBackButton || foldersCanGoBack()
    }

    private fun foldersCanGoBack(): Boolean {
        return currentTab() == HomeTab.FOLDERS && foldersTab.canGoBack()
    }

    private fun currentTab(): HomeTab = HomeTab.entries[binding.homePager.currentItem]

    private fun showWhatsNewOnFirstRun(release: WhatsNewRelease? = null) {
        val requestedRelease = release ?: if (pref.getShowFeaturesDialog()) {
            WhatsNewRelease.CURRENT
        } else {
            return
        }
        pref.setShowFeaturesDialog(false)
        lifecycleScope.launch {
            delay(500)
            if (!isFinishing) {
                startActivity(WhatsNewActivity.createIntent(this@HomeActivity, requestedRelease))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        libraryScanner.startObserving()
    }

    override fun onStop() {
        libraryScanner.stopObserving()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRelocateIntent(intent)
    }

    private fun handleRelocateIntent(intent: Intent) {
        val relocateHash = intent.getStringExtra(EXTRA_RELOCATE_HASH) ?: return
        intent.removeExtra(EXTRA_RELOCATE_HASH)
        relocateController.handleMissingFile(relocateHash)
    }

    override fun onResume() {
        super.onResume()
        permissionManager.recheck()
        libraryTab.applyProgressStyle()
        libraryTab.applyTitleStyle()
        recentTab.applyTitleStyle()
        foldersTab.applyTitleStyle()
        searchResultsAdapter.applyTitleStyle()
        libraryScanner.refresh()
        refresh()
        showBackupNoticesIfNeeded()
    }

    private fun showBackupNoticesIfNeeded() {
        pref.getImportResultPending()?.let { message ->
            pref.setImportResultPending(null)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_import_title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        val failure = BackupNotices.shouldShowFailureNotice(pref)
        if (!failure && !BackupNotices.shouldShowStaleNotice(pref)) {
            return
        }
        val messageRes = if (failure) R.string.auto_backup_failure_notice else R.string.auto_backup_stale_notice
        val snackbar = AppSnackbar.make(binding.root, messageRes, Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction(R.string.auto_backup_notice_action) {
            BackupNotices.acknowledge(pref)
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_PAGE, SettingsPage.BACKUP.name)
            )
        }
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (event == DISMISS_EVENT_SWIPE) {
                    BackupNotices.acknowledge(pref)
                }
            }
        })
        snackbar.show()
    }

    private fun setupSearch() {
        binding.searchView.setupWithSearchBar(binding.searchBar)

        searchResultsAdapter =
            LibraryAdapter(coverCache, lifecycleScope, this, pref) { selectionController.selectedHashes }
        searchResultsAdapter.viewMode = HomeViewMode.LIST
        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.searchResultsRecyclerView.adapter = searchResultsAdapter

        binding.searchView.addTransitionListener { _, _, newState ->
            if (newState == SearchView.TransitionState.HIDING) {
                selectionController.finish()
            }
        }

        binding.searchView.editText.doOnTextChanged { _, _, _, _ ->
            submitSearchResults()
        }
    }

    private fun submitSearchResults() {
        val query = binding.searchView.editText.text?.toString().orEmpty()
        searchResultsAdapter.submitList(
            libraryController.searchAll(allItems, libraryScanner.libraryEntries(), query)
        )
    }

    private fun shouldShowScanSetup(): Boolean {
        return permissionManager.hasFullAccess()
            && pref.getScanMode() == ScanMode.NOT_CONFIGURED
            && libraryScanner.index.value.loaded
    }

    private fun onStorageAccessChanged() {
        recentTab.onCoversChanged()
        libraryTab.onCoversChanged()
        foldersTab.onCoversChanged()
        searchResultsAdapter.notifyDataSetChanged()
        libraryScanner.refresh(force = true)
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch { renderTabs() }
    }

    private suspend fun renderTabs() {
        renderMutex.withLock {
            val probe = AvailabilityProbe(applicationContext, permissionManager.hasFullAccess())
            allRecordItems = libraryController.loadLibrary(probe, getString(R.string.home_title_annotated))
            allItems = allRecordItems.filter { it.availability != Availability.MISSING }
            val scanIndex = libraryScanner.index.value
            recentTab.render(allItems)
            libraryTab.render(allItems)
            foldersTab.render(allItems, scanIndex.entries, scanIndex.scanning)
            if (binding.searchView.isShowing) {
                submitSearchResults()
            }
            maybeRelinkRecords()
        }
    }

    private fun maybeRelinkRecords() {
        val scanIndex = libraryScanner.index.value
        if (relinkRunning || !permissionManager.hasFullAccess() || !scanIndex.loaded || scanIndex.scanning) {
            return
        }
        if (!historyPolicy.canRecord()) {
            return
        }
        val unavailableHashes = allRecordItems
            .filter { !it.available && !it.isScanOnly }
            .map { it.hash }
        if (unavailableHashes.isEmpty()) {
            return
        }
        relinkRunning = true
        lifecycleScope.launch {
            try {
                var healed = false
                for (hash in unavailableHashes) {
                    val record = pdfRepository.findRecord(hash) ?: continue
                    val healedPath = libraryScanner.findPathByHash(hash) ?: continue
                    val file = File(healedPath)
                    pdfRepository.updateRecordIdentity(
                        hash, Uri.fromFile(file), file.nameWithoutExtension, record.lastOpened
                    )
                    healed = true
                }
                if (healed) {
                    refresh()
                }
            } finally {
                relinkRunning = false
            }
        }
    }

    private fun setupPullToRefresh(swipeRefresh: SwipeRefreshLayout) {
        swipeRefresh.setColorSchemeColors(
            MaterialColors.getColor(swipeRefresh, androidx.appcompat.R.attr.colorPrimary, 0)
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            MaterialColors.getColor(swipeRefresh, com.google.android.material.R.attr.colorSurface, 0)
        )
        swipeRefresh.setOnRefreshListener {
            libraryScanner.refresh(force = true)
            lifecycleScope.launch {
                renderTabs()
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun openInReader(uri: Uri, hash: String? = null, incognito: Boolean = false) {
        Intent(this, MainActivity::class.java).also { intent ->
            intent.data = uri
            intent.putExtra(EXTRA_FROM_HOME, true)
            hash?.let { intent.putExtra(EXTRA_RECORD_HASH, it) }
            if (incognito) {
                intent.putExtra(PDF.incognitoKey, true)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            startActivity(intent)
        }
    }

    override fun onItemClicked(item: HomeItem) {
        if (selectionController.active) {
            selectionController.toggle(item)
            return
        }
        if (binding.searchView.isShowing) {
            binding.searchView.hide()
        }
        if (!item.available) {
            if (item.availability == Availability.LOCKED) {
                permissionManager.requestFullAccess()
            } else {
                relocateController.handleMissingFile(item.hash)
            }
            return
        }
        if (isMissingFile(item)) {
            relocateController.handleMissingFile(item.hash)
            return
        }
        openInReader(item.uri, item.hash.takeUnless { item.isScanOnly })
    }

    private fun isMissingFile(item: HomeItem): Boolean {
        if (item.isScanOnly || item.uri.scheme != "file") {
            return false
        }
        val path = item.uri.path ?: return false
        return !File(path).canRead()
    }

    override fun onItemLongClicked(item: HomeItem): Boolean {
        return selectionController.begin(item)
    }

    private fun selectableItems(): List<HomeItem> {
        if (binding.searchView.isShowing) {
            return searchResultsAdapter.currentList
        }
        return when (currentTab()) {
            HomeTab.RECENT -> recentTab.currentItems()
            HomeTab.LIBRARY -> libraryTab.currentGridItems()
            HomeTab.FOLDERS -> foldersTab.currentItems()
        }
    }

    private fun selectionContext(): SelectionContext {
        if (binding.searchView.isShowing) {
            return SelectionContext.SEARCH
        }
        return when (currentTab()) {
            HomeTab.RECENT -> SelectionContext.RECENT
            HomeTab.LIBRARY -> SelectionContext.LIBRARY
            HomeTab.FOLDERS -> SelectionContext.FOLDERS
        }
    }

    private fun notifySelectionChanged() {
        updateBackState()
        recentTab.notifySelectionChanged()
        libraryTab.notifySelectionChanged()
        foldersTab.notifySelectionChanged()
        searchResultsAdapter.notifySelectionChanged()
    }

    override fun onItemOptionsClicked(item: HomeItem) {
        recordOptionsDialog.show(item)
    }

    private fun statusBatch(items: List<HomeItem>) {
        val labels = ReadingStatus.entries
            .map { it.name.formatEnumToTitle() }
            .toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_set_status)
            .setItems(labels) { _, index ->
                lifecycleScope.launch {
                    val hashes = items.mapNotNull { recordOptionsDialog.ensureRecordHash(it) }
                    if (hashes.size < items.size && !historyPolicy.canRecord()) {
                        Toast.makeText(this@HomeActivity, R.string.history_action_blocked, Toast.LENGTH_SHORT).show()
                    }
                    pdfRepository.setReadingBatch(hashes, ReadingStatus.entries[index])
                    selectionController.finish()
                    refresh()
                }
            }
            .show()
    }

    private fun removeRecentBatch(items: List<HomeItem>) {
        lifecycleScope.launch {
            val unset = LocalDateTime.parse(PdfRecord.UNSET_DATE)
            items.filter { !it.isScanOnly && it.hasBeenOpened }.forEach {
                pdfRepository.setLastOpened(it.hash, unset)
            }
            selectionController.finish()
            refresh()
        }
    }

    private fun hideBatch(items: List<HomeItem>) {
        lifecycleScope.launch {
            val hashes = items.mapNotNull { recordOptionsDialog.ensureRecordHash(it) }
            if (hashes.size < items.size && !historyPolicy.canRecord()) {
                Toast.makeText(this@HomeActivity, R.string.history_action_blocked, Toast.LENGTH_SHORT).show()
            }
            hashes.forEach { pdfRepository.setHidden(it, true) }
            selectionController.finish()
            refresh()
        }
    }

    private fun deleteBatch(items: List<HomeItem>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_dialog_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.home_delete_batch_confirm_message, items.size, items.size
                )
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val removals = withContext(Dispatchers.IO) {
                        items.map { item -> item to DocumentRemover.remove(this@HomeActivity, item.uri) }
                    }
                    val deleted = removals.filter { it.second.deleted }
                    deleted.forEach { (item, removal) ->
                        historyCleaner.deleteDocument(item.hash)
                        coverCache.invalidate(item.coverKey)
                        removal.path?.let { libraryScanner.onFileRemoved(it) }
                    }
                    if (deleted.size < items.size) {
                        Toast.makeText(this@HomeActivity, R.string.home_delete_failed, Toast.LENGTH_SHORT).show()
                    }
                    selectionController.finish()
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_FROM_HOME = "fromHome"
        const val EXTRA_RECORD_HASH = "recordHash"
        const val EXTRA_RELOCATE_HASH = "relocateHash"
        const val EXTRA_OPEN_ONLINE_DIALOG = "openOnlineDialog"
    }
}
