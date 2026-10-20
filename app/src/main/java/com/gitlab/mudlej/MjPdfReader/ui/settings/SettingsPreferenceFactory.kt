// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.settings

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.BackupFolder
import com.gitlab.mudlej.MjPdfReader.data.PageFitPolicy
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.ReadingMode
import com.gitlab.mudlej.MjPdfReader.data.SharedCopyMode
import com.gitlab.mudlej.MjPdfReader.data.TapToTurnZones
import com.gitlab.mudlej.MjPdfReader.data.getReadingMode
import com.gitlab.mudlej.MjPdfReader.data.readingModePreferenceKey
import com.gitlab.mudlej.MjPdfReader.data.setReadingMode
import com.gitlab.mudlej.MjPdfReader.data.translation.DictionaryInstaller
import com.gitlab.mudlej.MjPdfReader.data.translation.DictionaryStore
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationEngine
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationLanguages
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationUrlBuilder
import com.gitlab.mudlej.MjPdfReader.ui.history.ReadingHistoryActivity
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeProgressStyle
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTitleEllipsize
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ConfigurableAction
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.PdfThemeController
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

internal class SettingsPreferenceFactory(
    private val fragment: PreferenceFragmentCompat,
    private val appPreferences: Preferences,
) {
    private val context get() = fragment.requireContext()
    private val allEntries by lazy {
        SettingsEntryProvider(appPreferences).entries()
    }

    fun entries(): List<SettingEntry> = allEntries

    fun entriesFor(page: SettingsPage): List<SettingEntry> {
        return allEntries.filter { it.page == page }
    }

    fun navigationPreference(page: SettingsPage, onSelected: (SettingsPage) -> Unit): Preference {
        return Preference(context).apply {
            title = getString(page.titleRes)
            summary = getString(page.summaryRes)
            setIcon(page.iconRes)
            widgetLayoutResource = R.layout.preference_widget_chevron
            isIconSpaceReserved = true
            setOnPreferenceClickListener {
                onSelected(page)
                true
            }
        }
    }

    fun noSearchResultsPreference(): Preference {
        return Preference(context).apply {
            title = getString(R.string.settings_search_no_results)
            isSelectable = false
            isIconSpaceReserved = false
        }
    }

    fun interfaceThemePreference(breadcrumb: String?): Preference {
        return ThemeChoicePreference(
            context = context,
            titleText = formatSummary(breadcrumb, getString(R.string.dark_theme_for_app)) ?: getString(R.string.dark_theme_for_app),
            initialSelectedMode = appPreferences.getInterfaceTheme(),
        ) { mode ->
            appPreferences.setInterfaceTheme(mode)
            setDefaultNightMode(PdfThemeController.interfaceNightMode(appPreferences))
        }
    }

    fun pdfPagesThemePreference(breadcrumb: String?): Preference {
        return ThemeChoicePreference(
            context = context,
            titleText = formatSummary(breadcrumb, getString(R.string.dark_theme_for_pdf)) ?: getString(R.string.dark_theme_for_pdf),
            initialSelectedMode = appPreferences.getPdfPagesTheme(),
        ) { mode ->
            appPreferences.setPdfPagesTheme(mode)
        }
    }

    private val titleLineOptions = listOf(1, 2, 3, 4, 5)
    private val hideDelayOptions = listOf(2000, 3000, 5000, 10000)

    private fun progressStyleLabel(style: HomeProgressStyle): String {
        return when (style) {
            HomeProgressStyle.RING -> getString(R.string.home_progress_style_ring)
            HomeProgressStyle.BAR -> getString(R.string.home_progress_style_bar)
        }
    }

    fun homeProgressStylePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val styles = HomeProgressStyle.entries
        return Preference(context).apply {
            title = getString(R.string.home_progress_style_title)
            key = Preferences.homeProgressStyleKey
            summary = formatSummary(breadcrumb, progressStyleLabel(appPreferences.getHomeProgressStyle()))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.home_progress_style_title),
                    items = styles.map { progressStyleLabel(it) },
                    checkedIndex = styles.indexOf(appPreferences.getHomeProgressStyle()),
                    onReset = {
                        appPreferences.setHomeProgressStyle(HomeProgressStyle.valueOf(Preferences.homeProgressStyleDefault))
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setHomeProgressStyle(styles[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun homeTitleEllipsizePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val options = HomeTitleEllipsize.entries
        return Preference(context).apply {
            title = getString(R.string.home_title_ellipsize_title)
            key = Preferences.homeTitleEllipsizeKey
            summary = formatSummary(breadcrumb, getString(appPreferences.getHomeTitleEllipsize().labelRes))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.home_title_ellipsize_title),
                    items = options.map { getString(it.labelRes) },
                    checkedIndex = options.indexOf(appPreferences.getHomeTitleEllipsize()),
                    onReset = {
                        appPreferences.setHomeTitleEllipsize(HomeTitleEllipsize.valueOf(Preferences.homeTitleEllipsizeDefault))
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setHomeTitleEllipsize(options[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun homeGridTitleLinesPreference(breadcrumb: String?): Preference {
        return titleLinesPreference(
            titleRes = R.string.home_grid_title_lines_title,
            key = Preferences.homeGridTitleLinesKey,
            currentValue = appPreferences.getHomeGridTitleLines(),
            defaultValue = Preferences.homeGridTitleLinesDefault,
            breadcrumb = breadcrumb,
            onSelected = appPreferences::setHomeGridTitleLines,
        )
    }

    fun homeListTitleLinesPreference(breadcrumb: String?): Preference {
        return titleLinesPreference(
            titleRes = R.string.home_list_title_lines_title,
            key = Preferences.homeListTitleLinesKey,
            currentValue = appPreferences.getHomeListTitleLines(),
            defaultValue = Preferences.homeListTitleLinesDefault,
            breadcrumb = breadcrumb,
            onSelected = appPreferences::setHomeListTitleLines,
        )
    }

    fun hideDelayPreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val labels = hideDelayOptions.map {
            context.resources.getQuantityString(R.plurals.hide_delay_seconds, it / 1000, it / 1000)
        }
        val currentValue = appPreferences.getHideDelay()
        return Preference(context).apply {
            title = getString(R.string.hide_delay_title)
            this.key = Preferences.hideDelayKey
            summary = formatSummary(breadcrumb, labels.getOrNull(hideDelayOptions.indexOf(currentValue)))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.hide_delay_title),
                    items = labels,
                    checkedIndex = hideDelayOptions.indexOf(currentValue),
                    onReset = {
                        appPreferences.setHideDelay(Preferences.hideDelayDefault)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setHideDelay(hideDelayOptions[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    private fun titleLinesPreference(
        @StringRes titleRes: Int,
        key: String,
        currentValue: Int,
        defaultValue: Int,
        breadcrumb: String?,
        onSelected: (Int) -> Unit,
    ): Preference {
        val host = fragment as? SettingsFragment
        val labels = titleLineOptions.map {
            context.resources.getQuantityString(R.plurals.home_title_lines, it, it)
        }
        return Preference(context).apply {
            title = getString(titleRes)
            this.key = key
            summary = formatSummary(breadcrumb, labels.getOrNull(titleLineOptions.indexOf(currentValue)))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(titleRes),
                    items = labels,
                    checkedIndex = titleLineOptions.indexOf(currentValue),
                    onReset = {
                        onSelected(defaultValue)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    onSelected(titleLineOptions[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun readingModePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val options = ReadingMode.entries
        return Preference(context).apply {
            title = getString(R.string.reading_mode_title)
            key = readingModePreferenceKey
            summary = formatSummary(breadcrumb, appPreferences.getReadingMode().let {
                "${getString(it.labelRes)}\n${getString(it.summaryRes)}"
            })
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.reading_mode_title),
                    items = options.map { getString(it.labelRes) },
                    checkedIndex = options.indexOf(appPreferences.getReadingMode()),
                    onReset = {
                        appPreferences.setReadingMode(ReadingMode.CONTINUOUS)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setReadingMode(options[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun pageFitPreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val options = PageFitPolicy.entries
        return Preference(context).apply {
            title = getString(R.string.page_fit_title)
            key = Preferences.pageFitPolicyKey
            summary = formatSummary(breadcrumb, getString(appPreferences.getPageFitPolicy().labelRes))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.page_fit_title),
                    items = options.map { getString(it.labelRes) },
                    checkedIndex = options.indexOf(appPreferences.getPageFitPolicy()),
                    onReset = {
                        appPreferences.setPageFitPolicy(PageFitPolicy.valueOf(Preferences.pageFitPolicyDefault))
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setPageFitPolicy(options[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun tapToTurnPreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val options = TapToTurnZones.entries
        return Preference(context).apply {
            title = getString(R.string.tap_to_turn_title)
            key = Preferences.tapToTurnKey
            summary = formatSummary(breadcrumb, getString(appPreferences.getTapToTurnZones().labelRes))
            isEnabled = appPreferences.getSinglePageMode()
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.tap_to_turn_title),
                    items = options.map { getString(it.labelRes) },
                    checkedIndex = options.indexOf(appPreferences.getTapToTurnZones()),
                    onReset = {
                        appPreferences.setTapToTurnZones(TapToTurnZones.valueOf(Preferences.tapToTurnDefault))
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setTapToTurnZones(options[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun switchPreference(
        @StringRes titleRes: Int,
        key: String,
        defaultValue: Boolean,
        @StringRes summaryRes: Int?,
        breadcrumb: String?,
    ): SwitchPreferenceCompat {
        return SwitchPreferenceCompat(context).apply {
            title = getString(titleRes)
            this.key = key
            setDefaultValue(defaultValue)
            summary = formatSummary(breadcrumb, summaryRes?.let(::getString))
            isIconSpaceReserved = false
        }
    }

    fun refreshingSwitchPreference(
        @StringRes titleRes: Int,
        key: String,
        defaultValue: Boolean,
        @StringRes summaryRes: Int?,
        breadcrumb: String?,
    ): SwitchPreferenceCompat {
        val host = fragment as? SettingsFragment
        return switchPreference(
            titleRes = titleRes,
            key = key,
            defaultValue = defaultValue,
            summaryRes = summaryRes,
            breadcrumb = breadcrumb,
        ).apply {
            setOnPreferenceChangeListener { _, _ ->
                host?.refreshPreferencesAfterChange()
                true
            }
        }
    }

    fun actionPreference(
        @StringRes titleRes: Int,
        key: String,
        defaultValue: String,
        currentValue: String,
        actions: List<ConfigurableAction>,
        breadcrumb: String?,
        onActionSelected: (String) -> Unit,
    ): Preference {
        val resetActionId = defaultValue.takeIf { value -> actions.any { it.id == value } }
            ?: actions.first().id
        var selectedActionId = currentValue.takeIf { value -> actions.any { it.id == value } }
            ?: resetActionId

        return Preference(context).apply {
            title = getString(titleRes)
            this.key = key
            updateActionSummary(selectedActionId, breadcrumb)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showActionPreferenceDialog(
                    title = getString(titleRes),
                    actions = actions,
                    currentValue = selectedActionId,
                    resetValue = resetActionId,
                ) { actionId ->
                    selectedActionId = actionId
                    onActionSelected(actionId)
                    updateActionSummary(actionId, breadcrumb)
                }
                true
            }
        }
    }

    fun fullScreenButtonsPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.fullscreen_buttons)
            key = Preferences.fullScreenOverlayActionsKey
            summary = formatSummary(breadcrumb, getString(R.string.fullscreen_buttons_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showFullScreenButtonsPreferenceDialog(context, appPreferences) {}
                true
            }
        }
    }

    fun listCardBadgesPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.home_list_badges_title)
            key = Preferences.homeBadgePagesKey
            summary = formatSummary(breadcrumb, getString(R.string.home_list_badges_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showListCardBadgesPreferenceDialog(context, appPreferences)
                true
            }
        }
    }

    fun scrollingInfoCardPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.scrolling_info_card)
            key = Preferences.fullScreenInfoShowPageNumberKey
            summary = formatSummary(breadcrumb, getString(R.string.scrolling_info_card_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showScrollingInfoCardPreferenceDialog(context, appPreferences)
                true
            }
        }
    }

    fun shortcutBarButtonsPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.shortcut_bar_buttons)
            key = Preferences.shortcutBarActionsKey
            summary = formatSummary(breadcrumb, getString(R.string.shortcut_bar_buttons_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showShortcutBarButtonsPreferenceDialog(context, appPreferences) {}
                true
            }
        }
    }

    fun sharedCopyModePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val options = SharedCopyMode.entries
        return Preference(context).apply {
            title = getString(R.string.keep_shared_copies_title)
            key = Preferences.sharedCopyModeKey
            summary = formatSummary(breadcrumb, appPreferences.getSharedCopyMode().let {
                "${getString(it.labelRes)}\n${getString(it.summaryRes)}"
            })
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.keep_shared_copies_title),
                    items = options.map { getString(it.labelRes) },
                    checkedIndex = options.indexOf(appPreferences.getSharedCopyMode()),
                    onReset = {
                        appPreferences.setSharedCopyMode(SharedCopyMode.NEVER_COPY)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setSharedCopyMode(options[index])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun readingHistoryPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.reading_history)
            key = "readingHistory"
            summary = formatSummary(breadcrumb, getString(R.string.reading_history_row_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                context.startActivity(Intent(context, ReadingHistoryActivity::class.java))
                true
            }
        }
    }

    fun clearReadingHistoryPreference(breadcrumb: String?): Preference {
        return clearActionPreference(
            "clearReadingHistory",
            R.string.clear_reading_history_title,
            R.string.clear_reading_history_summary,
            breadcrumb,
        ) { it.startClearReadingHistory() }
    }

    fun clearSavedPasswordsPreference(breadcrumb: String?): Preference {
        return clearActionPreference(
            "clearSavedPasswords",
            R.string.clear_saved_passwords_title,
            R.string.clear_saved_passwords_summary,
            breadcrumb,
        ) { it.startClearSavedPasswords() }
    }

    fun clearBookmarksPreference(breadcrumb: String?): Preference {
        return clearActionPreference(
            "clearBookmarks",
            R.string.clear_bookmarks_title,
            R.string.clear_bookmarks_summary,
            breadcrumb,
        ) { it.startClearBookmarks() }
    }

    fun clearAnnotationJournalsPreference(breadcrumb: String?): Preference {
        return clearActionPreference(
            "clearAnnotationJournals",
            R.string.clear_annotation_journals_title,
            R.string.clear_annotation_journals_summary,
            breadcrumb,
        ) { it.startClearAnnotationJournals() }
    }

    private fun clearActionPreference(
        preferenceKey: String,
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int,
        breadcrumb: String?,
        onClicked: (SettingsFragment) -> Unit,
    ): Preference {
        val host = fragment as? SettingsFragment
        return Preference(context).apply {
            title = getString(titleRes)
            key = preferenceKey
            summary = formatSummary(breadcrumb, getString(summaryRes))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                host?.let(onClicked)
                true
            }
        }
    }

    fun backupFolderPreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val detail = BackupFolder.describe(appPreferences.getBackupFolderTreeUri())
            ?.let { fragment.getString(R.string.backup_folder_summary_set, it) }
            ?: getString(R.string.backup_folder_summary_unset)
        return Preference(context).apply {
            title = getString(R.string.backup_folder_title)
            key = "backupFolder"
            summary = formatSummary(breadcrumb, detail)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                host?.startPickBackupFolder()
                true
            }
        }
    }

    fun autoBackupSwitchPreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        return SwitchPreferenceCompat(context).apply {
            title = getString(R.string.auto_backup_title)
            key = Preferences.autoBackupEnabledKey
            setDefaultValue(Preferences.autoBackupEnabledDefault)
            summary = formatSummary(breadcrumb, getString(R.string.auto_backup_summary))
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, newValue ->
                host?.onAutoBackupToggled(newValue == true)
                true
            }
        }
    }

    fun autoBackupTimePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val time = LocalTime.of(appPreferences.getAutoBackupHour(), appPreferences.getAutoBackupMinute())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        val detail = buildList {
            add(fragment.getString(R.string.auto_backup_time_summary, time))
            val lastRun = appPreferences.getAutoBackupLastRun()
            if (lastRun > 0L) {
                val lastRunText = Instant.ofEpochMilli(lastRun)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
                val error = appPreferences.getAutoBackupLastError()
                add(
                    if (error == null) {
                        fragment.getString(R.string.auto_backup_last_success, lastRunText)
                    } else {
                        fragment.getString(R.string.auto_backup_last_failed, error)
                    }
                )
            }
        }.joinToString("\n")
        return Preference(context).apply {
            title = getString(R.string.auto_backup_time_title)
            key = "autoBackupTime"
            summary = formatSummary(breadcrumb, detail)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                host?.startPickAutoBackupTime()
                true
            }
        }
    }

    fun backupExportPreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        return Preference(context).apply {
            title = getString(R.string.backup_export_title)
            key = "backupExport"
            summary = formatSummary(breadcrumb, getString(R.string.backup_export_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                host?.startBackupExport()
                true
            }
        }
    }

    fun backupImportPreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        return Preference(context).apply {
            title = getString(R.string.backup_import_title)
            key = "backupImport"
            summary = formatSummary(breadcrumb, getString(R.string.backup_import_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                host?.startBackupImport()
                true
            }
        }
    }

    fun backupRestoreSnapshotPreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val newest = BackupFolder.listSafetySnapshots(context).firstOrNull()
        val detail = newest?.let { snapshot ->
            fragment.getString(
                R.string.backup_restore_snapshot_summary,
                Instant.ofEpochMilli(snapshot.lastModified())
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)),
            )
        }
        return Preference(context).apply {
            title = getString(R.string.backup_restore_snapshot_title)
            key = "backupRestoreSnapshot"
            summary = formatSummary(breadcrumb, detail)
            isVisible = newest != null
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                host?.startSafetyRestore()
                true
            }
        }
    }

    fun highlightColorsPreference(breadcrumb: String?): Preference {
        return Preference(context).apply {
            title = getString(R.string.highlight_colors)
            key = Preferences.highlightColorsKey
            summary = formatSummary(breadcrumb, getString(R.string.highlight_colors_summary))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showHighlightColorsPreferenceDialog(context, appPreferences) {}
                true
            }
        }
    }

    fun translationModePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val modes = listOf(
            Preferences.translationModeApps to R.string.translation_mode_installed_apps,
            Preferences.translationModeWeb to R.string.translation_mode_web_translator,
        )
        val currentMode = appPreferences.getTranslationMode()
        val detailRes = modes.firstOrNull { it.first == currentMode }?.second
            ?: R.string.translation_mode_installed_apps
        return Preference(context).apply {
            title = getString(R.string.translate_with_title)
            key = Preferences.translationModeKey
            summary = formatSummary(breadcrumb, getString(detailRes))
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.translate_with_title),
                    items = modes.map { getString(it.second) },
                    checkedIndex = modes.indexOfFirst { it.first == appPreferences.getTranslationMode() },
                    onReset = {
                        appPreferences.setTranslationMode(Preferences.translationModeDefault)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setTranslationMode(modes[index].first)
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    private fun engineLabel(engine: TranslationEngine): String {
        return if (engine.unstable) {
            fragment.getString(R.string.translation_engine_unstable, getString(engine.titleRes))
        } else {
            getString(engine.titleRes)
        }
    }

    fun translationEnginePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val engines = TranslationEngine.entries
        val currentEngine = TranslationEngine.fromId(appPreferences.getTranslationEngine())
        val detail = buildString {
            append(engineLabel(currentEngine))
            if (currentEngine == TranslationEngine.CUSTOM) {
                val templateHost = Uri.parse(appPreferences.getTranslationCustomUrl()).host
                if (!templateHost.isNullOrBlank()) {
                    append(" (").append(templateHost).append(")")
                }
            }
        }
        return Preference(context).apply {
            title = getString(R.string.translation_engine_title)
            key = Preferences.translationEngineKey
            summary = formatSummary(breadcrumb, detail)
            isEnabled = appPreferences.getTranslationMode() == Preferences.translationModeWeb
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showSingleChoiceDialog(
                    title = getString(R.string.translation_engine_title),
                    items = engines.map { engineLabel(it) },
                    checkedIndex = engines.indexOf(TranslationEngine.fromId(appPreferences.getTranslationEngine())),
                    onReset = {
                        appPreferences.setTranslationEngine(Preferences.translationEngineDefault)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    val engine = engines[index]
                    if (engine == TranslationEngine.CUSTOM) {
                        showCustomTranslationUrlDialog(host)
                    } else {
                        appPreferences.setTranslationEngine(engine.id)
                        host?.refreshPreferences()
                    }
                }
                true
            }
        }
    }

    fun translationTargetLanguagePreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val codes = TranslationLanguages.codes.sortedBy { TranslationLanguages.displayName(it) }
        val stored = appPreferences.getTranslationTargetLanguage()
        val deviceLabel = fragment.getString(
            R.string.translation_device_language,
            TranslationLanguages.displayName(TranslationLanguages.deviceLanguage()),
        )
        val detail = if (stored.isBlank()) deviceLabel else TranslationLanguages.displayName(stored)
        return Preference(context).apply {
            title = getString(R.string.translation_target_language_title)
            key = Preferences.translationTargetLanguageKey
            summary = formatSummary(breadcrumb, detail)
            isEnabled = appPreferences.getTranslationMode() == Preferences.translationModeWeb
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                val current = appPreferences.getTranslationTargetLanguage()
                showSingleChoiceDialog(
                    title = getString(R.string.translation_target_language_title),
                    items = listOf(deviceLabel) + codes.map { TranslationLanguages.displayName(it) },
                    checkedIndex = if (current.isBlank()) 0 else codes.indexOf(current) + 1,
                    onReset = {
                        appPreferences.setTranslationTargetLanguage(Preferences.translationTargetLanguageDefault)
                        host?.refreshPreferences()
                    },
                ) { index ->
                    appPreferences.setTranslationTargetLanguage(if (index == 0) "" else codes[index - 1])
                    host?.refreshPreferences()
                }
                true
            }
        }
    }

    fun dictionaryPreference(breadcrumb: String?): Preference {
        val host = fragment as? SettingsFragment
        val installed = DictionaryStore.isInstalled(context)
        val detail = if (installed) {
            fragment.getString(
                R.string.dictionary_summary_installed,
                Formatter.formatShortFileSize(context, DictionaryStore.installedSize(context)),
            )
        } else {
            fragment.getString(
                R.string.dictionary_summary_not_installed,
                Formatter.formatShortFileSize(context, DictionaryInstaller.downloadSizeBytes),
            )
        }
        return Preference(context).apply {
            title = getString(R.string.dictionary_title)
            key = "offlineDictionary"
            summary = formatSummary(breadcrumb, detail)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (DictionaryStore.isInstalled(context)) {
                    host?.startDictionaryDelete()
                } else {
                    host?.startDictionaryInstall()
                }
                true
            }
        }
    }

    fun dictionaryDefineWordsPreference(breadcrumb: String?): Preference {
        return SwitchPreferenceCompat(context).apply {
            title = getString(R.string.dictionary_define_words_title)
            key = Preferences.dictionaryDefineWordsKey
            setDefaultValue(Preferences.dictionaryDefineWordsDefault)
            summary = formatSummary(breadcrumb, getString(R.string.dictionary_define_words_summary))
            isEnabled = DictionaryStore.isInstalled(context)
            isIconSpaceReserved = false
        }
    }

    private fun showSingleChoiceDialog(
        title: String,
        items: List<String>,
        checkedIndex: Int,
        onReset: () -> Unit,
        onSelected: (Int) -> Unit,
    ) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), checkedIndex) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                onReset()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showCustomTranslationUrlDialog(host: SettingsFragment?) {
        val inputLayout = fragment.layoutInflater.inflate(R.layout.input_layout, null) as TextInputLayout
        inputLayout.hint = getString(R.string.translation_custom_url_hint)
        inputLayout.setStartIconDrawable(R.drawable.ic_link)
        inputLayout.editText?.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(appPreferences.getTranslationCustomUrl())
            setSelection(text?.length ?: 0)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.translation_engine_custom)
            .setView(inputLayout)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = inputLayout.editText?.text?.toString()?.trim().orEmpty()
                val hasScheme = value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true)
                if (!hasScheme || !value.contains(TranslationUrlBuilder.textPlaceholder)) {
                    inputLayout.error = getString(R.string.translation_custom_url_invalid)
                    return@setOnClickListener
                }
                appPreferences.setTranslationCustomUrl(value)
                appPreferences.setTranslationEngine(TranslationEngine.CUSTOM.id)
                dialog.dismiss()
                host?.refreshPreferences()
            }
        }
        dialog.show()
    }

    fun floatPreference(
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int,
        key: String,
        currentValue: Float,
        defaultValue: Float,
        minValue: Float,
        maxValue: Float,
        breadcrumb: String?,
        onValueSelected: (Float) -> Unit,
    ): Preference {
        return Preference(context).apply {
            title = getString(titleRes)
            this.key = key
            updateFloatPreferenceSummary(
                breadcrumb = breadcrumb,
                summaryRes = summaryRes,
                value = currentValue,
            )
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                showFloatPreferenceDialog(
                    titleRes = titleRes,
                    currentValue = currentValue,
                    defaultValue = defaultValue,
                    minValue = minValue,
                    maxValue = maxValue,
                ) { value ->
                    onValueSelected(value)
                    updateFloatPreferenceSummary(
                        breadcrumb = breadcrumb,
                        summaryRes = summaryRes,
                        value = value,
                    )
                }
                true
            }
        }
    }

    private fun showActionPreferenceDialog(
        title: String,
        actions: List<ConfigurableAction>,
        currentValue: String,
        resetValue: String,
        onActionSelected: (String) -> Unit,
    ) {
        val checkedIndex = actions.indexOfFirst { it.id == currentValue }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(actions.toEntryTitles(), checkedIndex) { dialog, which ->
                onActionSelected(actions[which].id)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                onActionSelected(resetValue)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showFloatPreferenceDialog(
        @StringRes titleRes: Int,
        currentValue: Float,
        defaultValue: Float,
        minValue: Float,
        maxValue: Float,
        onValueSelected: (Float) -> Unit,
    ) {
        val min = minValue.roundToInt()
        val max = maxValue.roundToInt()
        val valueText = TextView(context).apply {
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        val seekBar = SeekBar(context).apply {
            this.max = max - min
            progress = currentValue.roundToInt().coerceIn(min, max) - min
        }

        fun selectedValue() = min + seekBar.progress

        fun updateValueText() {
            valueText.text = selectedValue().toString()
        }

        updateValueText()
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateValueText()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(
                valueText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                seekBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(layout)
            .setPositiveButton(R.string.apply, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                onValueSelected(selectedValue().toFloat())
                dialog.dismiss()
            }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                onValueSelected(defaultValue)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun getString(@StringRes stringRes: Int): String {
        return fragment.getString(stringRes)
    }

    private fun List<ConfigurableAction>.toEntryTitles(): Array<String> {
        return map { getString(it.titleRes) }.toTypedArray()
    }

    private fun Preference.updateActionSummary(actionId: String, breadcrumb: String?) {
        summary = formatSummary(
            breadcrumb = breadcrumb,
            detail = getString(ConfigurableAction.fromId(actionId).titleRes),
        )
    }

    private fun Preference.updateFloatPreferenceSummary(
        breadcrumb: String?,
        @StringRes summaryRes: Int,
        value: Float,
    ) {
        val currentValue = context.getString(
            R.string.settings_current_value,
            value.roundToInt(),
        )
        val detail = "${getString(summaryRes)}\n$currentValue"
        summary = formatSummary(breadcrumb = breadcrumb, detail = detail)
    }

    private fun formatSummary(breadcrumb: String?, detail: String?): String? {
        return when {
            breadcrumb.isNullOrBlank() -> detail
            detail.isNullOrBlank() -> breadcrumb
            else -> "$breadcrumb: $detail"
        }
    }

    private fun dp(value: Int): Int {
        return (value * fragment.resources.displayMetrics.density).roundToInt()
    }

    private class ThemeChoicePreference(
        context: android.content.Context,
        private val titleText: String,
        initialSelectedMode: String,
        private val onModeSelected: (String) -> Unit,
    ) : Preference(context) {
        private var selectedMode = initialSelectedMode

        init {
            layoutResource = R.layout.preference_theme_choice
            title = titleText
            isSelectable = false
            isIconSpaceReserved = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            (holder.findViewById(R.id.theme_choice_title) as TextView).text = titleText

            val group = holder.findViewById(R.id.theme_choice_group) as MaterialButtonToggleGroup
            ThemeChoiceStrip.bind(group, selectedMode) { mode ->
                selectedMode = mode
                onModeSelected(mode)
            }
        }
    }
}
