// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.HomeDebugState
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanPhase
import kotlinx.coroutines.CoroutineScope

class FoldersTabController(
    private val context: Context,
    private val pref: Preferences,
    coverCache: CoverCache,
    scope: CoroutineScope,
    functions: HomeItemFunctions,
    onGrantAccessClicked: () -> Unit,
    private val hasFullAccess: () -> Boolean,
    private val libraryController: HomeLibraryController,
    private val onNavigationChanged: () -> Unit,
    selection: () -> Set<String> = { emptySet() },
) {

    private val topSections = HomeSectionsAdapter(
        coverCache,
        scope,
        functions,
        onGrantAccessClicked = onGrantAccessClicked,
    )
    private val breadcrumbAdapter = BreadcrumbAdapter { path -> navigateTo(path) }
    private val folderAdapter = FolderAdapter { node -> navigateTo(node.path) }
    private val filesAdapter = LibraryAdapter(coverCache, scope, functions, pref, selection).apply {
        viewMode = HomeViewMode.LIST
        metaStyle = ListMetaStyle.FOLDERS
    }
    private val bottomSections = HomeSectionsAdapter(coverCache, scope, functions)

    private var index: FolderIndex? = null
    private var currentDir: String? = null
    private var lastItems: List<HomeItem> = emptyList()
    private var lastIndex = LibraryIndex()

    private data class Contents(
        val folders: List<FolderNode> = emptyList(),
        val files: List<ScannedPdfEntry> = emptyList(),
        val crumbs: List<Crumb> = emptyList(),
    )

    fun attach(recyclerView: RecyclerView) {
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        recyclerView.adapter = ConcatAdapter(
            topSections, breadcrumbAdapter, folderAdapter, filesAdapter, bottomSections
        )
    }

    fun render(allItems: List<HomeItem>, scanIndex: LibraryIndex) {
        index = FolderIndex(scanIndex.entries, context.getString(R.string.home_folder_storage))
        lastItems = allItems
        lastIndex = scanIndex
        renderCurrent()
    }

    fun onModeChanged() {
        currentDir = null
        renderCurrent()
    }

    fun canGoBack(): Boolean = currentDir != null

    fun currentItems(): List<HomeItem> = filesAdapter.currentList

    fun applyTitleStyle() = filesAdapter.applyTitleStyle()

    fun notifySelectionChanged() = filesAdapter.notifySelectionChanged()

    fun goBack(): Boolean {
        val dir = currentDir ?: return false
        currentDir = if (pref.getHomeFolderFlat()) {
            null
        } else {
            index?.parentOf(dir)
        }
        renderCurrent()
        return true
    }

    fun onCoversChanged() {
        filesAdapter.notifyDataSetChanged()
        topSections.rebindCovers()
    }

    private fun navigateTo(path: String?) {
        currentDir = path
        renderCurrent()
    }

    private fun renderCurrent() {
        val folderIndex = index ?: return
        val contents = contentsFor(folderIndex)

        breadcrumbAdapter.submit(contents.crumbs)
        folderAdapter.submitList(contents.folders)
        val fileItems = if (lastIndex.phase == ScanPhase.IDLE || lastIndex.phase == ScanPhase.INCOMPLETE) {
            libraryController.reconcileWithScan(lastItems, contents.files)
        } else {
            libraryController.mergeDuringDiscovery(lastItems, contents.files)
        }
        filesAdapter.submitList(fileItems)
        if (BuildConfig.DEBUG) {
            HomeDebugState.noteRendered(HomeTab.FOLDERS, contents.folders.size + fileItems.size)
        }
        topSections.submitList(buildTopSections())
        bottomSections.submitList(buildBottomSections(contents))
        onNavigationChanged()
    }

    private fun contentsFor(folderIndex: FolderIndex): Contents {
        val flat = pref.getHomeFolderFlat()
        val dir = currentDir
        return when {
            dir != null -> Contents(
                folders = if (flat) emptyList() else folderIndex.foldersIn(dir),
                files = folderIndex.filesIn(dir),
                crumbs = folderIndex.crumbsFor(dir),
            )
            flat -> Contents(folders = folderIndex.flatFolders())
            folderIndex.roots.size > 1 -> Contents(folders = folderIndex.rootFolders())
            else -> {
                val root = folderIndex.roots.firstOrNull()
                Contents(
                    folders = root?.let { folderIndex.foldersIn(it) }.orEmpty(),
                    files = root?.let { folderIndex.filesIn(it) }.orEmpty(),
                )
            }
        }
    }

    private fun buildTopSections(): List<HomeSection> = buildList {
        if (!hasFullAccess()) {
            add(HomeSection.PermissionCard())
        }
        LibraryPresentation.progressFor(this@FoldersTabController.lastIndex)?.let { add(HomeSection.ScanProgressRow(it)) }
    }

    private fun buildBottomSections(contents: Contents): List<HomeSection> {
        if (contents.folders.isNotEmpty() || contents.files.isNotEmpty() ||
            LibraryPresentation.progressFor(lastIndex) != null
        ) return emptyList()
        return listOf(HomeSection.EmptyState(R.string.home_empty_all_title, R.string.home_empty_all_message))
    }

}
