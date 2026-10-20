// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.DialogHomeMenuBinding
import com.gitlab.mudlej.MjPdfReader.ui.about.AboutActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsActivity
import com.gitlab.mudlej.MjPdfReader.core.ui.SegmentedButtonStyler
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HomeMenuDialog(
    private val activity: AppCompatActivity,
    private val pref: Preferences,
    private val currentTab: () -> HomeTab,
    private val onViewModeChanged: () -> Unit,
    private val onGridSizeChanged: () -> Unit,
    private val onSortChanged: () -> Unit,
    private val onFolderModeChanged: () -> Unit,
    private val onShowStats: () -> Unit,
    private val hasFullAccess: () -> Boolean,
    private val onScanLocations: () -> Unit,
    private val onFullRescan: () -> Unit,
) {

    fun show() {
        val binding = DialogHomeMenuBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .create()

        val tab = currentTab()
        binding.librarySection.visibility =
            if (tab == HomeTab.LIBRARY) View.VISIBLE else View.GONE
        binding.foldersSection.visibility =
            if (tab == HomeTab.FOLDERS) View.VISIBLE else View.GONE
        binding.menuDivider.visibility =
            if (tab == HomeTab.RECENT) View.GONE else View.VISIBLE

        bindViewMode(binding)
        bindGridSize(binding)
        bindSort(binding)
        bindFoldersMode(binding)
        updateGridSizeVisibility(binding)

        SegmentedButtonStyler.attach(binding.viewModeGroup)
        SegmentedButtonStyler.attach(binding.gridSizeGroup)
        SegmentedButtonStyler.attach(binding.sortGroup)
        SegmentedButtonStyler.attach(binding.foldersModeGroup)

        binding.settingsRow.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
        binding.aboutRow.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, AboutActivity::class.java))
        }
        binding.statsRow.setOnClickListener {
            dialog.dismiss()
            onShowStats()
        }
        binding.scanLocationsRow.visibility =
            if (hasFullAccess()) View.VISIBLE else View.GONE
        binding.scanLocationsRow.setOnClickListener {
            dialog.dismiss()
            onScanLocations()
        }
        binding.fullRescanRow.visibility =
            if (hasFullAccess() && pref.getScanMode() != ScanMode.NOT_CONFIGURED) View.VISIBLE else View.GONE
        binding.fullRescanRow.setOnClickListener {
            dialog.dismiss()
            onFullRescan()
        }
        binding.openOnlineRow.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(activity, MainActivity::class.java)
            intent.putExtra(HomeActivity.EXTRA_OPEN_ONLINE_DIALOG, true)
            activity.startActivity(intent)
        }

        dialog.show()
    }

    private fun bindViewMode(binding: DialogHomeMenuBinding) {
        binding.viewModeGroup.check(
            when (pref.getHomeViewMode()) {
                HomeViewMode.GRID -> R.id.viewGridButton
                HomeViewMode.LIST -> R.id.viewListButton
            }
        )
        binding.viewModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val newMode = if (checkedId == R.id.viewListButton) HomeViewMode.LIST else HomeViewMode.GRID
            if (newMode != pref.getHomeViewMode()) {
                pref.setHomeViewMode(newMode)
                updateGridSizeVisibility(binding)
                onViewModeChanged()
            }
        }
    }

    private fun updateGridSizeVisibility(binding: DialogHomeMenuBinding) {
        binding.gridSizeSection.visibility =
            if (pref.getHomeViewMode() == HomeViewMode.GRID) View.VISIBLE else View.GONE
    }

    private fun bindGridSize(binding: DialogHomeMenuBinding) {
        binding.gridSizeGroup.check(
            when (pref.getHomeGridSize()) {
                HomeGridSize.SMALL -> R.id.gridSizeSmallButton
                HomeGridSize.MEDIUM -> R.id.gridSizeMediumButton
                HomeGridSize.LARGE -> R.id.gridSizeLargeButton
            }
        )
        binding.gridSizeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val newSize = when (checkedId) {
                R.id.gridSizeSmallButton -> HomeGridSize.SMALL
                R.id.gridSizeLargeButton -> HomeGridSize.LARGE
                else -> HomeGridSize.MEDIUM
            }
            if (newSize != pref.getHomeGridSize()) {
                pref.setHomeGridSize(newSize)
                onGridSizeChanged()
            }
        }
    }

    private fun bindSort(binding: DialogHomeMenuBinding) {
        binding.sortGroup.check(
            when (pref.getHomeSort()) {
                HomeSortOrder.LAST_OPENED -> R.id.sortLastOpenedButton
                HomeSortOrder.NAME -> R.id.sortNameButton
            }
        )
        binding.sortGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val newSort = if (checkedId == R.id.sortNameButton) {
                HomeSortOrder.NAME
            } else {
                HomeSortOrder.LAST_OPENED
            }
            if (newSort != pref.getHomeSort()) {
                pref.setHomeSort(newSort)
                onSortChanged()
            }
        }
    }

    private fun bindFoldersMode(binding: DialogHomeMenuBinding) {
        binding.foldersModeGroup.check(
            if (pref.getHomeFolderFlat()) R.id.foldersFlatButton else R.id.foldersHierarchyButton
        )
        binding.foldersModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val flat = checkedId == R.id.foldersFlatButton
            if (flat != pref.getHomeFolderFlat()) {
                pref.setHomeFolderFlat(flat)
                onFolderModeChanged()
            }
        }
    }

}
