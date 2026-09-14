// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityScanLocationsBinding
import com.google.android.material.checkbox.MaterialCheckBox
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanLocationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanLocationsBinding
    private val pref by lazy { Preferences(PreferenceManager.getDefaultSharedPreferences(this)) }
    private val breadcrumbAdapter = BreadcrumbAdapter { path -> navigateTo(path?.let(::File)) }
    private val locationsAdapter = ScanLocationsAdapter(
        onRowClicked = { row -> if (row.isUp) goBack() else navigateTo(File(row.path)) },
        onCheckToggled = { row -> toggle(row.path) },
    )

    private lateinit var volumes: List<File>
    private var currentDir: File? = null
    private val selected = linkedSetOf<String>()
    private lateinit var previousMode: ScanMode
    private lateinit var previousLocations: Set<String>
    private var currentChildren: List<Pair<String, String>> = emptyList()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanLocationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        title = getString(R.string.home_scan_locations_title)

        volumes = StorageManager().volumeRoots(this)

        previousMode = (savedInstanceState?.getString(EXTRA_PREVIOUS_MODE)
            ?: intent.getStringExtra(EXTRA_PREVIOUS_MODE))?.let(ScanMode::valueOf) ?: pref.getScanMode()
        previousLocations = (savedInstanceState?.getStringArrayList(EXTRA_PREVIOUS_LOCATIONS)
            ?: intent.getStringArrayListExtra(EXTRA_PREVIOUS_LOCATIONS))?.toSet() ?: pref.getScanLocations()

        val savedSelection = savedInstanceState?.getStringArrayList(STATE_SELECTED)
        selected.addAll(savedSelection ?: previousLocations)
        currentDir = savedInstanceState?.getString(STATE_CURRENT_DIR)?.let(::File)
            ?: volumes.singleOrNull()

        binding.scanLocationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ScanLocationsActivity)
            adapter = ConcatAdapter(breadcrumbAdapter, locationsAdapter)
        }
        binding.saveLocationsButton.setOnClickListener { save() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
        outState.putString(STATE_CURRENT_DIR, currentDir?.absolutePath)
        outState.putString(EXTRA_PREVIOUS_MODE, previousMode.name)
        outState.putStringArrayList(EXTRA_PREVIOUS_LOCATIONS, ArrayList(previousLocations))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun navigateTo(dir: File?) {
        currentDir = dir
        render()
    }

    private fun goBack() {
        val dir = currentDir ?: return
        if (isVolumeRoot(dir)) {
            navigateTo(null)
        } else {
            navigateTo(dir.parentFile)
        }
    }

    private fun render() {
        backCallback.isEnabled = canGoUp()
        binding.saveLocationsButton.isEnabled = selected.isNotEmpty()

        val dir = currentDir
        if (dir == null) {
            breadcrumbAdapter.submit(emptyList())
            currentChildren = volumes.map { it.absolutePath to volumeLabel(it) }
            renderRows()
            return
        }

        breadcrumbAdapter.submit(crumbsFor(dir))
        lifecycleScope.launch {
            val children = withContext(Dispatchers.IO) { listChildren(dir) }
            if (currentDir?.absolutePath != dir.absolutePath) {
                return@launch
            }
            currentChildren = children.map { it.absolutePath to it.name }
            renderRows()
        }
    }

    private fun renderRows() {
        val rows = mutableListOf<ScanLocationRow>()
        if (canGoUp()) {
            rows.add(
                ScanLocationRow(
                    path = UP_ROW_PATH,
                    name = getString(R.string.home_scan_locations_up),
                    checkedState = MaterialCheckBox.STATE_UNCHECKED,
                    checkboxEnabled = false,
                    isUp = true,
                )
            )
        }
        currentChildren.mapTo(rows) { (path, name) ->
            ScanLocationRow(
                path = path,
                name = name,
                checkedState = stateOf(path),
                checkboxEnabled = selected.none { it != path && path.startsWith("$it${File.separatorChar}") },
            )
        }
        locationsAdapter.submitList(rows)
    }

    private fun listChildren(dir: File): List<File> {
        val atVolumeRoot = isVolumeRoot(dir)
        return dir.listFiles()
            .orEmpty()
            .filter { file ->
                file.isDirectory
                    && !file.isHidden
                    && !(atVolumeRoot && (file.name == "Android" || file.name == "data"))
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun stateOf(path: String): Int {
        return when {
            selected.any { path == it || path.startsWith("$it${File.separatorChar}") } ->
                MaterialCheckBox.STATE_CHECKED
            selected.any { it.startsWith("$path${File.separatorChar}") } ->
                MaterialCheckBox.STATE_INDETERMINATE
            else -> MaterialCheckBox.STATE_UNCHECKED
        }
    }

    private fun toggle(path: String) {
        if (!selected.remove(path)) {
            selected.removeAll { it.startsWith("$path${File.separatorChar}") }
            selected.add(path)
        }
        binding.saveLocationsButton.isEnabled = selected.isNotEmpty()
        renderRows()
    }

    private fun save() {
        val locations = ScanScope.normalize(selected).toSet()
        if (locations.isEmpty()) return
        if (previousMode != ScanMode.SELECTED_LOCATIONS ||
            locations != ScanScope.normalize(previousLocations).toSet()
        ) {
            pref.setScanScope(ScanMode.SELECTED_LOCATIONS, locations)
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_PREVIOUS_MODE, previousMode.name)
                    .putStringArrayListExtra(EXTRA_PREVIOUS_LOCATIONS, ArrayList(previousLocations)),
            )
        }
        finish()
    }

    private fun canGoUp(): Boolean {
        val dir = currentDir ?: return false
        return if (isVolumeRoot(dir)) volumes.size > 1 else true
    }

    private fun isVolumeRoot(dir: File): Boolean {
        return volumes.any { it.absolutePath == dir.absolutePath }
    }

    private fun volumeLabel(volume: File): String {
        return if (volume.absolutePath == StorageManager.ROOT_DIR) {
            getString(R.string.home_folder_storage)
        } else {
            volume.name
        }
    }

    private fun crumbsFor(dir: File): List<Crumb> {
        val volume = volumes.firstOrNull { volume ->
            dir.absolutePath == volume.absolutePath
                || dir.absolutePath.startsWith("${volume.absolutePath}${File.separatorChar}")
        } ?: return emptyList()

        val crumbs = mutableListOf(Crumb(volumeLabel(volume), volume.absolutePath))
        if (dir.absolutePath == volume.absolutePath) {
            return crumbs
        }
        val relative = dir.absolutePath.removePrefix(volume.absolutePath).trim(File.separatorChar)
        var current = volume.absolutePath
        for (segment in relative.split(File.separatorChar)) {
            current = "$current${File.separatorChar}$segment"
            crumbs.add(Crumb(segment, current))
        }
        return crumbs
    }

    companion object {
        const val EXTRA_PREVIOUS_MODE = "scanLocationsPreviousMode"
        const val EXTRA_PREVIOUS_LOCATIONS = "scanLocationsPreviousLocations"
        private const val STATE_SELECTED = "scanLocationsSelected"
        private const val STATE_CURRENT_DIR = "scanLocationsCurrentDir"
        private const val UP_ROW_PATH = ".."
    }
}
