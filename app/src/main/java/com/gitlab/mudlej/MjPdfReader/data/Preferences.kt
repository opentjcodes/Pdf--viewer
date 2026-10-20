// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import android.content.SharedPreferences
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.HighlightPalette
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ConfigurableAction
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeGridSize
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeProgressStyle
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeSortOrder
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTab
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTitleEllipsize
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeViewMode
import com.gitlab.mudlej.MjPdfReader.ui.home.ListFilter
import com.gitlab.mudlej.MjPdfReader.ui.home.ScanMode
import com.gitlab.mudlej.MjPdfReader.ui.text_mode.ReaderFontFamily
import com.gitlab.mudlej.MjPdfReader.ui.text_mode.ReaderTheme

class Preferences(private val prefMan: SharedPreferences) {

    init {
        migrateLegacyKey("spaceBetweenPagesKey", spaceBetweenPagesKey)
        migrateLegacyKey("alwaysHorizontalKey", alwaysHorizontalKey)
    }

    private fun migrateLegacyKey(legacyKey: String, key: String) {
        if (!prefMan.contains(legacyKey)) {
            return
        }
        val editor = prefMan.edit()
        if (!prefMan.contains(key)) {
            editor.putBoolean(key, safeGetBoolean(legacyKey, false))
        }
        editor.remove(legacyKey).apply()
    }

    private fun safeGetBoolean(key: String, default: Boolean): Boolean {
        return try {
            prefMan.getBoolean(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private fun safeGetInt(key: String, default: Int): Int {
        return try {
            prefMan.getInt(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private fun safeGetLong(key: String, default: Long): Long {
        return try {
            prefMan.getLong(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private fun safeGetFloat(key: String, default: Float): Float {
        return try {
            prefMan.getFloat(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private fun safeGetString(key: String, default: String?): String? {
        return try {
            prefMan.getString(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private fun safeGetStringSet(key: String, default: Set<String>?): Set<String>? {
        return try {
            prefMan.getStringSet(key, default)
        } catch (exception: ClassCastException) {
            prefMan.edit().remove(key).apply()
            default
        }
    }

    private inline fun <reified T : Enum<T>> safeGetEnum(key: String, defaultName: String): T {
        val stored = safeGetString(key, defaultName) ?: defaultName
        return runCatching { enumValueOf<T>(stored) }.getOrElse {
            prefMan.edit().remove(key).apply()
            enumValueOf(defaultName)
        }
    }

    companion object {
        // Preferences keys
        const val firstInstallKey = "firstInstall"
        const val showFeaturesDialogKey = "showFeaturesDialog"
        const val showExitFullscreenTipKey = "showExitFullscreenTip"
        const val lastSeenVersionCodeKey = "lastSeenVersionCode"
        const val highQualityKey = "highQuality"
        const val antiAliasingKey = "antiAliasing"
        const val horizontalScrollKey = "horizontalScroll"
        const val dualPageModeKey = "dualPageMode"
        const val dualPageFirstPageAloneKey = "dualPageFirstPageAlone"
        const val pageFitPolicyKey = "pageFitPolicy"
        const val pageSnapKey = "pageSnap"
        const val pageFlingKey = "pageFling"
        const val browserScrollModeKey = "browserScrollMode"
        const val singlePageModeKey = "singlePageMode"
        const val tapToTurnKey = "tapToTurnPages"
        const val turnPageByMouseButtonsKey = "turnPageByMouseButtons"
        const val pdfDarkThemeKey = "pdfDarkTheme"
        const val appFollowSystemThemeKey = "appFollowSystemTheme"
        const val pdfFollowSystemThemeKey = "pdfFollowSystemTheme"
        const val interfaceThemeKey = "interfaceTheme"
        const val pdfPagesThemeKey = "pdfPagesTheme"
        const val enableReloadButtonKey = "enableReloadButton"
        const val primaryButtonActionKey = "primaryButtonAction"
        const val secondaryButtonActionKey = "secondaryButtonAction"
        const val fullScreenOverlayActionsKey = "fullScreenOverlayActions"
        const val fullScreenOverlayActionOrderKey = "fullScreenOverlayActionOrder"
        const val shortcutBarActionsKey = "shortcutBarActions"
        const val shortcutBarActionOrderKey = "shortcutBarActionOrder"
        const val screenOnKey = "screenOn"
        const val spaceBetweenPagesKey = "spaceBetweenPages"
        const val hideDelayKey = "hideDelay"
        const val partSizeKey = "partSize"
        const val maxZoomKey = "maxZoom"
        const val inlineTextSelectionKey = "inlineTextSelection"
        const val detectExistingHighlightsKey = "detectExistingHighlights"
        const val highlightColorsKey = "highlightColors"
        const val searchIgnoreAccentsKey = "searchIgnoreAccents"
        const val searchZoomToResultKey = "searchZoomToResult"
        const val defaultTextModeKey = "defaultTextMode"
        const val turnPageByVolumeButtonsKey = "turnPageByVolumeButtons"
        const val showScrollHandlePageCountKey = "showScrollHandlePageCount"
        const val showAppBarPageCountKey = "showAppBarPageCount"
        const val alwaysHideMarginsKey = "alwaysHideMargins"
        const val secondBarEnabledKey = "secondBarEnabled"
        const val hideButtonsLabelsKey = "hideButtonsLabels"
        const val fullScreenInfoShowTimeKey = "fullScreenInfoShowTime"
        const val fullScreenInfoShowPdfNameKey = "fullScreenInfoShowPdfName"
        const val fullScreenInfoShowPageNumberKey = "fullScreenInfoShowPageNumber"
        const val fullScreenInfoShowReadingPercentageKey = "fullScreenInfoShowReadingPercentage"
        const val doubleTapToExitEnabledKey = "doubleTapToExitEnabled"
        const val doubleTapThreeStepZoomKey = "doubleTapThreeStepZoom"
        const val openPdfsInSeparateWindowsKey = "openPdfsInSeparateWindows"
        const val alwaysOpenAtFirstPageKey = "alwaysOpenAtFirstPage"
        const val autoFullScreenKey = "autoFullScreenSwitch"
        const val alwaysHorizontalKey = "alwaysHorizontal"
        const val scrollSpeedKey = "scrollSpeed"
        const val listFilterKey = "listFilter"
        const val homeDisabledKey = "homeDisabled"
        const val homeShowPdfTitleKey = "homeShowPdfTitle"
        const val homeTabKey = "homeTab"
        const val homeFolderFlatKey = "homeFolderFlat"
        const val homeViewModeKey = "homeViewMode"
        const val homeProgressStyleKey = "homeProgressStyle"
        const val homeGridSizeKey = "homeGridSize"
        const val homeListTitleLinesKey = "homeListTitleLines"
        const val homeGridTitleLinesKey = "homeGridTitleLines"
        const val homeTitleEllipsizeKey = "homeTitleEllipsize"
        const val homeBadgePagesKey = "homeBadgePages"
        const val homeBadgeProgressKey = "homeBadgeProgress"
        const val homeBadgeLastOpenedKey = "homeBadgeLastOpened"
        const val homeBadgeFileSizeKey = "homeBadgeFileSize"
        const val homeBadgeStatusKey = "homeBadgeStatus"
        const val goToPageGridColumnsKey = "goToPageGridColumns"
        const val backupFolderTreeUriKey = "backupFolderTreeUri"
        const val autoBackupEnabledKey = "autoBackupEnabled"
        const val autoBackupHourKey = "autoBackupHour"
        const val autoBackupMinuteKey = "autoBackupMinute"
        const val autoBackupLastRunKey = "autoBackupLastRun"
        const val autoBackupLastErrorKey = "autoBackupLastError"
        const val autoBackupErrorAcknowledgedRunKey = "autoBackupErrorAcknowledgedRun"
        const val autoBackupEnabledAtKey = "autoBackupEnabledAt"
        const val importResultPendingKey = "importResultPending"
        const val homeSortKey = "homeSort"
        const val historyEnabledKey = "historyEnabled"
        const val keepSharedCopiesKey = "keepSharedCopies"
        const val sharedCopyModeKey = "sharedCopyMode"
        const val scanModeKey = "scanMode"
        const val scanLocationsKey = "scanLocations"
        const val scanBaselineScopeKey = "scanBaselineScope"
        const val translationModeKey = "translationMode"
        const val translationEngineKey = "translationEngine"
        const val translationTargetLanguageKey = "translationTargetLanguage"
        const val translationCustomUrlKey = "translationCustomUrl"
        const val dictionaryDefineWordsKey = "dictionaryDefineWords"
        const val textModeFontSizeKey = "textModeFontSize"
        const val textModeLineSpacingKey = "textModeLineSpacing"
        const val textModeHorizontalMarginKey = "textModeHorizontalMargin"
        const val textModeThemeKey = "textModeTheme"
        const val textModeFontFamilyKey = "textModeFontFamily"
        const val textModeReadableLineLengthKey = "textModeReadableLineLength"

        // Default values
        const val firstInstallDefault = true
        const val showFeaturesDialogDefault = true
        const val showExitFullscreenTipDefault = true
        const val highQualityDefault = false
        const val antiAliasingDefault = true
        const val horizontalScrollDefault = false
        const val dualPageModeDefault = false
        const val dualPageFirstPageAloneDefault = false
        const val pageFitPolicyDefault = "WIDTH"
        const val pageSnapDefault = false
        const val pageFlingDefault = false
        const val browserScrollModeDefault = false
        const val singlePageModeDefault = false
        const val tapToTurnDefault = "LEFT_RIGHT"
        const val turnPageByMouseButtonsDefault = true
        const val pdfDarkThemeDefault = false
        const val appFollowSystemThemeDefault = true    // NEW: for version v2.1 M3 Theme
        const val pdfFollowSystemThemeDefault = false
        const val enableReloadButtonDefault = false
        const val primaryButtonActionDefault = "fullscreen"
        const val secondaryButtonActionDefault = "none"
        const val annotationRenderingDefault = true
        const val screenOnDefault = false
        const val spaceBetweenPagesDefault = true
        const val hideDelayDefault = 3000
        const val spacingDefault = 10           // in dp
        const val minZoomDefault = 0.5f         //0.5f
        const val midZoomDefault = 2.0f
        const val maxZoomDefault = 10.0f
        const val partSizeDefault = 512f
        const val goToPageGridColumnsDefault = 3
        const val autoBackupEnabledDefault = false
        const val autoBackupHourDefault = 2
        const val autoBackupMinuteDefault = 0
        const val inlineTextSelectionDefault = true
        const val detectExistingHighlightsDefault = true
        const val searchIgnoreAccentsDefault = false
        const val searchZoomToResultDefault = false
        const val defaultTextModeDefault = false
        const val turnPageByVolumeButtonsDefault = false
        const val showScrollHandlePageCountDefault = false
        const val showAppBarPageCountDefault = false
        const val alwaysHideMarginsDefault = false
        const val secondBarEnabledDefault = false
        const val hideButtonsLabelsDefault = false
        const val fullScreenInfoShowTimeDefault = false
        const val fullScreenInfoShowPdfNameDefault = false
        const val fullScreenInfoShowPageNumberDefault = true
        const val fullScreenInfoShowReadingPercentageDefault = true
        const val doubleTapToExitEnabledDefault = false
        const val doubleTapThreeStepZoomDefault = false
        const val openPdfsInSeparateWindowsDefault = true
        const val alwaysOpenAtFirstPageDefault = false
        const val autoFullScreenDefault = false
        const val alwaysHorizontalDefault = false
        const val scrollSpeedDefault = 3
        const val listFilterDefault = "RECENT"  // ListFilter.RECENT.name
        const val homeDisabledDefault = false
        const val homeShowPdfTitleDefault = true
        const val homeTabDefault = "LIBRARY"
        const val homeFolderFlatDefault = false
        const val homeViewModeDefault = "GRID"
        const val homeProgressStyleDefault = "RING"
        const val homeGridSizeDefault = "MEDIUM"
        const val homeListTitleLinesDefault = 2
        const val homeGridTitleLinesDefault = 2
        const val homeTitleEllipsizeDefault = "END"
        const val homeBadgeDefault = true
        const val homeSortDefault = "NAME"
        const val historyEnabledDefault = true
        const val keepSharedCopiesDefault = true
        const val sharedCopyModeDefault = "ALWAYS_COPY"
        const val scanModeDefault = "NOT_CONFIGURED"
        val scanLocationsDefault: Set<String> = emptySet()
        const val themeSystem = "system"
        const val themeLight = "light"
        const val themeDark = "dark"
        const val translationModeApps = "apps"
        const val translationModeWeb = "web"
        const val translationModeDefault = translationModeApps
        const val translationEngineDefault = "google"
        const val translationTargetLanguageDefault = ""
        const val translationCustomUrlDefault = ""
        const val dictionaryDefineWordsDefault = true
        val fullScreenOverlayActionsDefault = ConfigurableAction.defaultFullScreenOverlayActionIds
        val shortcutBarActionsDefault = ConfigurableAction.defaultShortcutBarActionIds

        // Colors
        const val pdfDarkBackgroundColor = -0x313132          // -0x313132 = 0xffcecece
        const val pdfLightBackgroundColor = -0xcdcdce         // 0xff323232 = -0xcdcdce

        // Constants
        const val highlightColorsCount = 6
        const val minMaxZoom = 1f
        const val maxMaxZoom = 100f
        const val minPartSize = 5f
        const val maxPartSize = 1000f
        const val AUTO_SCROLL_UNIT = 0.1

        const val kindBoolean = "boolean"
        const val kindInt = "int"
        const val kindLong = "long"
        const val kindFloat = "float"
        const val kindString = "string"
        const val kindStringSet = "stringSet"

        val backupSettingKinds: Map<String, String> = buildMap {
            listOf(
                firstInstallKey, showFeaturesDialogKey, showExitFullscreenTipKey, highQualityKey, antiAliasingKey,
                horizontalScrollKey, dualPageModeKey, dualPageFirstPageAloneKey, pageSnapKey,
                pageFlingKey, browserScrollModeKey, singlePageModeKey, turnPageByMouseButtonsKey, pdfDarkThemeKey,
                appFollowSystemThemeKey, pdfFollowSystemThemeKey, enableReloadButtonKey, screenOnKey,
                spaceBetweenPagesKey, inlineTextSelectionKey, detectExistingHighlightsKey,
                searchIgnoreAccentsKey, searchZoomToResultKey, defaultTextModeKey,
                turnPageByVolumeButtonsKey, showScrollHandlePageCountKey, showAppBarPageCountKey,
                alwaysHideMarginsKey, secondBarEnabledKey, hideButtonsLabelsKey,
                fullScreenInfoShowTimeKey, fullScreenInfoShowPdfNameKey,
                fullScreenInfoShowPageNumberKey, fullScreenInfoShowReadingPercentageKey,
                doubleTapToExitEnabledKey, doubleTapThreeStepZoomKey, alwaysOpenAtFirstPageKey,
                autoFullScreenKey, alwaysHorizontalKey, homeDisabledKey, homeShowPdfTitleKey,
                homeFolderFlatKey, homeBadgePagesKey, homeBadgeProgressKey, homeBadgeLastOpenedKey,
                homeBadgeFileSizeKey, homeBadgeStatusKey, historyEnabledKey, keepSharedCopiesKey,
                dictionaryDefineWordsKey, autoBackupEnabledKey, openPdfsInSeparateWindowsKey,
                textModeReadableLineLengthKey,
            ).forEach { put(it, kindBoolean) }
            listOf(
                hideDelayKey, goToPageGridColumnsKey, scrollSpeedKey, homeListTitleLinesKey,
                homeGridTitleLinesKey, autoBackupHourKey, autoBackupMinuteKey, lastSeenVersionCodeKey,
                textModeHorizontalMarginKey,
            ).forEach { put(it, kindInt) }
            listOf(
                autoBackupLastRunKey, autoBackupErrorAcknowledgedRunKey, autoBackupEnabledAtKey,
            ).forEach { put(it, kindLong) }
            listOf(
                partSizeKey, maxZoomKey, textModeFontSizeKey, textModeLineSpacingKey,
            ).forEach { put(it, kindFloat) }
            listOf(
                interfaceThemeKey, pdfPagesThemeKey, primaryButtonActionKey,
                secondaryButtonActionKey, fullScreenOverlayActionOrderKey, shortcutBarActionOrderKey,
                highlightColorsKey, listFilterKey, homeTabKey, homeViewModeKey, homeProgressStyleKey,
                homeGridSizeKey, homeTitleEllipsizeKey, homeSortKey, scanModeKey, pageFitPolicyKey,
                tapToTurnKey, backupFolderTreeUriKey, autoBackupLastErrorKey, translationModeKey,
                translationEngineKey, translationTargetLanguageKey, translationCustomUrlKey,
                importResultPendingKey, textModeThemeKey, textModeFontFamilyKey,
                sharedCopyModeKey,
            ).forEach { put(it, kindString) }
            listOf(
                fullScreenOverlayActionsKey, shortcutBarActionsKey, scanLocationsKey,
            ).forEach { put(it, kindStringSet) }
        }

        val backupSettingEnumDomains: Map<String, Set<String>> = mapOf(
            listFilterKey to ListFilter.entries.map { it.name }.toSet(),
            homeTabKey to HomeTab.entries.map { it.name }.toSet(),
            homeViewModeKey to HomeViewMode.entries.map { it.name }.toSet(),
            homeProgressStyleKey to HomeProgressStyle.entries.map { it.name }.toSet(),
            homeGridSizeKey to HomeGridSize.entries.map { it.name }.toSet(),
            homeTitleEllipsizeKey to HomeTitleEllipsize.entries.map { it.name }.toSet(),
            homeSortKey to HomeSortOrder.entries.map { it.name }.toSet(),
            scanModeKey to ScanMode.entries.map { it.name }.toSet(),
            pageFitPolicyKey to PageFitPolicy.entries.map { it.name }.toSet(),
            tapToTurnKey to TapToTurnZones.entries.map { it.name }.toSet(),
            textModeThemeKey to ReaderTheme.entries.map { it.name }.toSet(),
            textModeFontFamilyKey to ReaderFontFamily.entries.map { it.name }.toSet(),
            sharedCopyModeKey to SharedCopyMode.entries.map { it.name }.toSet(),
        )
    }

    // get values saved in Shared Preferences or return the default values
    fun getFirstInstall() = safeGetBoolean(firstInstallKey, firstInstallDefault)
    fun getShowFeaturesDialog() = safeGetBoolean(showFeaturesDialogKey, showFeaturesDialogDefault)

    fun getShowExitFullscreenTip() = safeGetBoolean(showExitFullscreenTipKey, showExitFullscreenTipDefault)
    fun getLastSeenVersionCode() = safeGetInt(lastSeenVersionCodeKey, 0)
    fun getHighQuality() = safeGetBoolean(highQualityKey, highQualityDefault)
    fun getAntiAliasing() = safeGetBoolean(antiAliasingKey, antiAliasingDefault)
    fun getHorizontalScroll() = safeGetBoolean(horizontalScrollKey, horizontalScrollDefault)
    fun getDualPageMode() = safeGetBoolean(dualPageModeKey, dualPageModeDefault)
    fun getDualPageFirstPageAlone() = safeGetBoolean(dualPageFirstPageAloneKey, dualPageFirstPageAloneDefault)
    fun getPageFitPolicy() = safeGetEnum<PageFitPolicy>(pageFitPolicyKey, pageFitPolicyDefault)
    fun getPageSnap() = safeGetBoolean(pageSnapKey, pageSnapDefault)
    fun getPageFling() = safeGetBoolean(pageFlingKey, pageFlingDefault)
    fun getBrowserScrollMode() = safeGetBoolean(browserScrollModeKey, browserScrollModeDefault)
    fun getSinglePageMode() = safeGetBoolean(singlePageModeKey, singlePageModeDefault)
    fun getTapToTurnZones() = safeGetEnum<TapToTurnZones>(tapToTurnKey, tapToTurnDefault)
    fun getTurnPageByMouseButtons() = safeGetBoolean(turnPageByMouseButtonsKey, turnPageByMouseButtonsDefault)
    fun getPdfDarkTheme() = safeGetBoolean(pdfDarkThemeKey, pdfDarkThemeDefault)
    fun getAppFollowSystemTheme() = safeGetBoolean(appFollowSystemThemeKey, appFollowSystemThemeDefault)
    fun getPdfFollowSystemTheme() = safeGetBoolean(pdfFollowSystemThemeKey, pdfFollowSystemThemeDefault)
    fun getInterfaceTheme(): String {
        return safeGetString(interfaceThemeKey, null)
            ?: if (getAppFollowSystemTheme()) themeSystem else themeLight
    }
    fun getPdfPagesTheme(): String {
        return safeGetString(pdfPagesThemeKey, null)
            ?: if (getPdfFollowSystemTheme()) themeSystem else if (getPdfDarkTheme()) themeDark else themeLight
    }
    fun getScreenOn() = safeGetBoolean(screenOnKey, screenOnDefault)
    fun getSpaceBetweenPages() = safeGetBoolean(spaceBetweenPagesKey, spaceBetweenPagesDefault)
    fun getHideDelay() = safeGetInt(hideDelayKey, hideDelayDefault)
    fun getPartSize() = safeGetFloat(partSizeKey, partSizeDefault)

    fun getGoToPageGridColumns() = safeGetInt(goToPageGridColumnsKey, goToPageGridColumnsDefault)

    fun getBackupFolderTreeUri(): String? = safeGetString(backupFolderTreeUriKey, null)

    fun getAutoBackupEnabled() = safeGetBoolean(autoBackupEnabledKey, autoBackupEnabledDefault)

    fun getAutoBackupHour() = safeGetInt(autoBackupHourKey, autoBackupHourDefault)

    fun getAutoBackupMinute() = safeGetInt(autoBackupMinuteKey, autoBackupMinuteDefault)

    fun getAutoBackupLastRun() = safeGetLong(autoBackupLastRunKey, 0L)

    fun getAutoBackupLastError(): String? = safeGetString(autoBackupLastErrorKey, null)

    fun getAutoBackupErrorAcknowledgedRun() = safeGetLong(autoBackupErrorAcknowledgedRunKey, 0L)

    fun getAutoBackupEnabledAt() = safeGetLong(autoBackupEnabledAtKey, 0L)

    fun ensureAutoBackupEnabledAt(): Long {
        val stored = getAutoBackupEnabledAt()
        if (stored > 0L || !getAutoBackupEnabled()) {
            return stored
        }
        val now = System.currentTimeMillis()
        setAutoBackupEnabledAt(now)
        return now
    }

    fun getImportResultPending(): String? = safeGetString(importResultPendingKey, null)

    fun getTranslationMode() = safeGetString(translationModeKey, translationModeDefault) ?: translationModeDefault

    fun getTranslationEngine() = safeGetString(translationEngineKey, translationEngineDefault) ?: translationEngineDefault

    fun getTranslationTargetLanguage() = safeGetString(translationTargetLanguageKey, translationTargetLanguageDefault)
        ?: translationTargetLanguageDefault

    fun getTranslationCustomUrl() = safeGetString(translationCustomUrlKey, translationCustomUrlDefault)
        ?: translationCustomUrlDefault

    fun getDictionaryDefineWords() = safeGetBoolean(dictionaryDefineWordsKey, dictionaryDefineWordsDefault)
    fun getMaxZoom() = safeGetFloat(maxZoomKey, maxZoomDefault)
    fun getInlineTextSelection() = safeGetBoolean(inlineTextSelectionKey, inlineTextSelectionDefault)
    fun getDetectExistingHighlights() = safeGetBoolean(detectExistingHighlightsKey, detectExistingHighlightsDefault)
    fun getHighlightColors(): List<Int> {
        val stored = safeGetString(highlightColorsKey, null)
            ?.split(",")
            ?.mapNotNull(HighlightPalette::fromName)
            ?: HighlightPalette.defaultSelection
        return (stored + HighlightPalette.defaultSelection)
            .filter { it in HighlightPalette.selectable }
            .distinct()
            .take(highlightColorsCount)
            .map { it.colorValue }
    }
    fun getSearchIgnoreAccents() = safeGetBoolean(searchIgnoreAccentsKey, searchIgnoreAccentsDefault)
    fun getSearchZoomToResult() = safeGetBoolean(searchZoomToResultKey, searchZoomToResultDefault)
    fun getDefaultTextMode() = safeGetBoolean(defaultTextModeKey, defaultTextModeDefault)
    fun getTurnPageByVolumeButtons() = safeGetBoolean(turnPageByVolumeButtonsKey, turnPageByVolumeButtonsDefault)
    fun getShowScrollHandlePageCount() = safeGetBoolean(showScrollHandlePageCountKey, showScrollHandlePageCountDefault)
    fun getShowAppBarPageCount() = safeGetBoolean(showAppBarPageCountKey, showAppBarPageCountDefault)
    fun getAlwaysHideMargins() = safeGetBoolean(alwaysHideMarginsKey, alwaysHideMarginsDefault)
    fun getSecondBarEnabled() = safeGetBoolean(secondBarEnabledKey, secondBarEnabledDefault)
    fun getHideButtonsLabels() = safeGetBoolean(hideButtonsLabelsKey, hideButtonsLabelsDefault)
    fun getFullScreenInfoShowTime() = safeGetBoolean(fullScreenInfoShowTimeKey, fullScreenInfoShowTimeDefault)
    fun getFullScreenInfoShowPdfName() = safeGetBoolean(fullScreenInfoShowPdfNameKey, fullScreenInfoShowPdfNameDefault)
    fun getFullScreenInfoShowPageNumber() = safeGetBoolean(fullScreenInfoShowPageNumberKey, fullScreenInfoShowPageNumberDefault)
    fun getFullScreenInfoShowReadingPercentage() = safeGetBoolean(fullScreenInfoShowReadingPercentageKey, fullScreenInfoShowReadingPercentageDefault)
    fun getDoubleTapToExitEnabled() = safeGetBoolean(doubleTapToExitEnabledKey, doubleTapToExitEnabledDefault)
    fun getDoubleTapThreeStepZoom() = safeGetBoolean(doubleTapThreeStepZoomKey, doubleTapThreeStepZoomDefault)
    fun getOpenPdfsInSeparateWindows() = safeGetBoolean(openPdfsInSeparateWindowsKey, openPdfsInSeparateWindowsDefault)

    fun getAlwaysOpenAtFirstPage() = safeGetBoolean(alwaysOpenAtFirstPageKey, alwaysOpenAtFirstPageDefault)
    fun getAutoFullScreen() = safeGetBoolean(autoFullScreenKey, autoFullScreenDefault)
    fun getAlwaysHorizontal() = safeGetBoolean(alwaysHorizontalKey, alwaysHorizontalDefault)
    fun getEnableReloadButton() = safeGetBoolean(enableReloadButtonKey, enableReloadButtonDefault)
    fun getPrimaryButtonAction() = safeGetString(
        primaryButtonActionKey,
        primaryButtonActionDefault,
    ) ?: primaryButtonActionDefault
    fun getSecondaryButtonAction(): String {
        if (prefMan.contains(secondaryButtonActionKey)) {
            return safeGetString(secondaryButtonActionKey, secondaryButtonActionDefault) ?: secondaryButtonActionDefault
        }
        return if (getEnableReloadButton()) ConfigurableAction.RELOAD.id else secondaryButtonActionDefault
    }
    fun getFullScreenOverlayActions(): Set<String> {
        return safeGetStringSet(fullScreenOverlayActionsKey, fullScreenOverlayActionsDefault)?.toSet()
            ?: fullScreenOverlayActionsDefault
    }
    fun getFullScreenOverlayActionOrder(): List<String> {
        return safeGetString(fullScreenOverlayActionOrderKey, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: ConfigurableAction.defaultFullScreenOverlayOrder.map { it.id }
    }
    fun getShortcutBarActions(): Set<String> {
        return safeGetStringSet(shortcutBarActionsKey, shortcutBarActionsDefault)?.toSet()
            ?: shortcutBarActionsDefault
    }
    fun getShortcutBarActionOrder(): List<String> {
        return safeGetString(shortcutBarActionOrderKey, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: ConfigurableAction.defaultShortcutBarOrder.map { it.id }
    }
    fun getScrollSpeed() = safeGetInt(scrollSpeedKey, scrollSpeedDefault)
    fun getListFilter() = safeGetEnum<ListFilter>(listFilterKey, listFilterDefault)
    fun getHomeDisabled() = safeGetBoolean(homeDisabledKey, homeDisabledDefault)
    fun getHomeShowPdfTitle() = safeGetBoolean(homeShowPdfTitleKey, homeShowPdfTitleDefault)
    fun getHomeTab() = safeGetEnum<HomeTab>(homeTabKey, homeTabDefault)
    fun getHomeFolderFlat() = safeGetBoolean(homeFolderFlatKey, homeFolderFlatDefault)
    fun getHomeViewMode() = safeGetEnum<HomeViewMode>(homeViewModeKey, homeViewModeDefault)
    fun getHomeProgressStyle() = safeGetEnum<HomeProgressStyle>(homeProgressStyleKey, homeProgressStyleDefault)
    fun getHomeGridSize() = safeGetEnum<HomeGridSize>(homeGridSizeKey, homeGridSizeDefault)
    fun getHomeListTitleLines() = safeGetInt(homeListTitleLinesKey, homeListTitleLinesDefault)
    fun getHomeGridTitleLines() = safeGetInt(homeGridTitleLinesKey, homeGridTitleLinesDefault)
    fun getHomeTitleEllipsize() = safeGetEnum<HomeTitleEllipsize>(homeTitleEllipsizeKey, homeTitleEllipsizeDefault)
    fun getHomeBadgePages() = safeGetBoolean(homeBadgePagesKey, homeBadgeDefault)
    fun getHomeBadgeProgress() = safeGetBoolean(homeBadgeProgressKey, homeBadgeDefault)
    fun getHomeBadgeLastOpened() = safeGetBoolean(homeBadgeLastOpenedKey, homeBadgeDefault)
    fun getHomeBadgeFileSize() = safeGetBoolean(homeBadgeFileSizeKey, homeBadgeDefault)
    fun getHomeBadgeStatus() = safeGetBoolean(homeBadgeStatusKey, homeBadgeDefault)
    fun getHomeSort() = safeGetEnum<HomeSortOrder>(homeSortKey, homeSortDefault)
    fun getHistoryEnabled() = safeGetBoolean(historyEnabledKey, historyEnabledDefault)

    fun getKeepSharedCopies() = safeGetBoolean(keepSharedCopiesKey, keepSharedCopiesDefault)
    fun getSharedCopyMode(): SharedCopyMode {
        val fallback = if (getKeepSharedCopies()) sharedCopyModeDefault else SharedCopyMode.ASK.name
        return safeGetEnum(sharedCopyModeKey, fallback)
    }
    fun getScanMode() = safeGetEnum<ScanMode>(scanModeKey, scanModeDefault)
    fun getScanBaselineScope(): String? = safeGetString(scanBaselineScopeKey, null)
    fun getScanLocations(): Set<String> {
        return safeGetStringSet(scanLocationsKey, scanLocationsDefault)?.toSet()
            ?: scanLocationsDefault
    }

    // put values in Shared Preferences
    fun setFirstInstall(value: Boolean) = prefMan.edit().putBoolean(firstInstallKey, value).apply()
    fun setShowFeaturesDialog(value: Boolean) = prefMan.edit().putBoolean(showFeaturesDialogKey, value).apply()

    fun setShowExitFullscreenTip(value: Boolean) = prefMan.edit().putBoolean(showExitFullscreenTipKey, value).apply()
    fun setLastSeenVersionCode(value: Int) = prefMan.edit().putInt(lastSeenVersionCodeKey, value).apply()
    fun setHighQuality(value: Boolean) = prefMan.edit().putBoolean(highQualityKey, value).apply()
    fun setAntiAliasing(value: Boolean) = prefMan.edit().putBoolean(antiAliasingKey, value).apply()
    fun setHorizontalScroll(value: Boolean) = prefMan.edit().putBoolean(horizontalScrollKey, value).apply()
    fun setDualPageMode(value: Boolean) = prefMan.edit().putBoolean(dualPageModeKey, value).apply()
    fun setDualPageFirstPageAlone(value: Boolean) = prefMan.edit().putBoolean(dualPageFirstPageAloneKey, value).apply()
    fun setPageFitPolicy(value: PageFitPolicy) = prefMan.edit().putString(pageFitPolicyKey, value.name).apply()
    fun setPageSnap(value: Boolean) = prefMan.edit().putBoolean(pageSnapKey, value).apply()
    fun setPageFling(value: Boolean) = prefMan.edit().putBoolean(pageFlingKey, value).apply()
    fun setBrowserScrollMode(value: Boolean) = prefMan.edit().putBoolean(browserScrollModeKey, value).apply()
    fun setSinglePageMode(value: Boolean) = prefMan.edit().putBoolean(singlePageModeKey, value).apply()
    fun setTapToTurnZones(value: TapToTurnZones) = prefMan.edit().putString(tapToTurnKey, value.name).apply()
    fun setTurnPageByMouseButtons(value: Boolean) = prefMan.edit().putBoolean(turnPageByMouseButtonsKey, value).apply()
    fun setPdfDarkTheme(value: Boolean) = prefMan.edit().putBoolean(pdfDarkThemeKey, value).apply()
    fun setAppFollowSystemTheme(value: Boolean) = prefMan.edit().putBoolean(appFollowSystemThemeKey, value).apply()
    fun setPdfFollowSystemTheme(value: Boolean) = prefMan.edit().putBoolean(pdfFollowSystemThemeKey, value).apply()
    fun setInterfaceTheme(value: String) = prefMan.edit()
        .putString(interfaceThemeKey, value)
        .putBoolean(appFollowSystemThemeKey, value == themeSystem)
        .apply()
    fun setPdfPagesTheme(value: String) = prefMan.edit()
        .putString(pdfPagesThemeKey, value)
        .putBoolean(pdfFollowSystemThemeKey, value == themeSystem)
        .putBoolean(pdfDarkThemeKey, value == themeDark)
        .apply()
    fun setScreenOn(value: Boolean) = prefMan.edit().putBoolean(screenOnKey, value).apply()
    fun setSpaceBetweenPages(value: Boolean) = prefMan.edit().putBoolean(spaceBetweenPagesKey, value).apply()
    fun setHideDelay(value: Int) = prefMan.edit().putInt(hideDelayKey, value).apply()
    fun setPartSize(value: Float) = prefMan.edit().putFloat(partSizeKey, value).apply()

    fun setGoToPageGridColumns(value: Int) = prefMan.edit().putInt(goToPageGridColumnsKey, value).apply()

    fun setBackupFolderTreeUri(value: String?) = prefMan.edit().putString(backupFolderTreeUriKey, value).apply()

    fun setAutoBackupEnabled(value: Boolean) = prefMan.edit().putBoolean(autoBackupEnabledKey, value).apply()

    fun setAutoBackupTime(hour: Int, minute: Int) = prefMan.edit()
        .putInt(autoBackupHourKey, hour)
        .putInt(autoBackupMinuteKey, minute)
        .apply()

    fun setAutoBackupLastResult(runAt: Long, error: String?) = prefMan.edit()
        .putLong(autoBackupLastRunKey, runAt)
        .putString(autoBackupLastErrorKey, error)
        .apply()

    fun setAutoBackupErrorAcknowledgedRun(value: Long) = prefMan.edit()
        .putLong(autoBackupErrorAcknowledgedRunKey, value)
        .apply()

    fun setAutoBackupEnabledAt(value: Long) = prefMan.edit()
        .putLong(autoBackupEnabledAtKey, value)
        .apply()

    @Suppress("ApplySharedPref")
    fun setImportResultPending(value: String?) {
        prefMan.edit().putString(importResultPendingKey, value).commit()
    }

    fun setTranslationMode(value: String) = prefMan.edit().putString(translationModeKey, value).apply()

    fun setTranslationEngine(value: String) = prefMan.edit().putString(translationEngineKey, value).apply()

    fun setTranslationTargetLanguage(value: String) = prefMan.edit().putString(translationTargetLanguageKey, value).apply()

    fun setTranslationCustomUrl(value: String) = prefMan.edit().putString(translationCustomUrlKey, value).apply()

    fun setMaxZoom(value: Float) = prefMan.edit().putFloat(maxZoomKey, value).apply()
    fun setInlineTextSelection(value: Boolean) = prefMan.edit().putBoolean(inlineTextSelectionKey, value).apply()
    fun setDetectExistingHighlights(value: Boolean) = prefMan.edit().putBoolean(detectExistingHighlightsKey, value).apply()
    fun setHighlightColors(value: List<HighlightPalette>) = prefMan.edit()
        .putString(highlightColorsKey, value.joinToString(",") { it.name })
        .apply()
    fun setSearchIgnoreAccents(value: Boolean) = prefMan.edit().putBoolean(searchIgnoreAccentsKey, value).apply()
    fun setDefaultTextMode(value: Boolean) = prefMan.edit().putBoolean(defaultTextModeKey, value).apply()
    fun setTurnPageByVolumeButtons(value: Boolean) = prefMan.edit().putBoolean(turnPageByVolumeButtonsKey, value).apply()
    fun setShowScrollHandlePageCount(value: Boolean) = prefMan.edit().putBoolean(showScrollHandlePageCountKey, value).apply()
    fun setShowAppBarPageCount(value: Boolean) = prefMan.edit().putBoolean(showAppBarPageCountKey, value).apply()
    fun setAlwaysHideMargins(value: Boolean) = prefMan.edit().putBoolean(alwaysHideMarginsKey, value).apply()
    fun setSecondBarEnabled(value: Boolean) = prefMan.edit().putBoolean(secondBarEnabledKey, value).apply()
    fun setDoubleTapToExitEnabled(value: Boolean) = prefMan.edit().putBoolean(doubleTapToExitEnabledKey, value).apply()
    fun setDoubleTapThreeStepZoom(value: Boolean) = prefMan.edit().putBoolean(doubleTapThreeStepZoomKey, value).apply()
    fun setOpenPdfsInSeparateWindows(value: Boolean) = prefMan.edit().putBoolean(openPdfsInSeparateWindowsKey, value).apply()
    fun setAutoFullScreen(value: Boolean) = prefMan.edit().putBoolean(autoFullScreenKey, value).apply()
    fun setAlwaysHorizontal(value: Boolean) = prefMan.edit().putBoolean(alwaysHorizontalKey, value).apply()
    fun setHideButtonsLabels(value: Boolean) = prefMan.edit().putBoolean(hideButtonsLabelsKey, value).apply()
    fun setFullScreenInfoShowTime(value: Boolean) = prefMan.edit().putBoolean(fullScreenInfoShowTimeKey, value).apply()
    fun setFullScreenInfoShowPdfName(value: Boolean) = prefMan.edit().putBoolean(fullScreenInfoShowPdfNameKey, value).apply()
    fun setFullScreenInfoShowPageNumber(value: Boolean) = prefMan.edit().putBoolean(fullScreenInfoShowPageNumberKey, value).apply()
    fun setFullScreenInfoShowReadingPercentage(value: Boolean) = prefMan.edit().putBoolean(fullScreenInfoShowReadingPercentageKey, value).apply()
    fun setEnableReloadButton(value: Boolean) = prefMan.edit().putBoolean(enableReloadButtonKey, value).apply()
    fun setPrimaryButtonAction(value: String) = prefMan.edit().putString(primaryButtonActionKey, value).apply()
    fun setSecondaryButtonAction(value: String) = prefMan.edit().putString(secondaryButtonActionKey, value).apply()
    fun setFullScreenOverlayActions(value: Set<String>) = prefMan.edit().putStringSet(fullScreenOverlayActionsKey, value).apply()
    fun setFullScreenOverlayActionOrder(value: List<String>) = prefMan.edit()
        .putString(fullScreenOverlayActionOrderKey, value.joinToString(","))
        .apply()
    fun setShortcutBarActions(value: Set<String>) = prefMan.edit().putStringSet(shortcutBarActionsKey, value).apply()
    fun setShortcutBarActionOrder(value: List<String>) = prefMan.edit()
        .putString(shortcutBarActionOrderKey, value.joinToString(","))
        .apply()
    fun setScrollSpeed(value: Int) = prefMan.edit().putInt(scrollSpeedKey, value).apply()
    fun setListFilter(value: ListFilter) = prefMan.edit().putString(listFilterKey, value.name).apply()
    fun setHomeDisabled(value: Boolean) = prefMan.edit().putBoolean(homeDisabledKey, value).apply()
    fun setHomeShowPdfTitle(value: Boolean) = prefMan.edit().putBoolean(homeShowPdfTitleKey, value).apply()
    fun setHomeTab(value: HomeTab) = prefMan.edit().putString(homeTabKey, value.name).apply()
    fun setHomeFolderFlat(value: Boolean) = prefMan.edit().putBoolean(homeFolderFlatKey, value).apply()
    fun setHomeViewMode(value: HomeViewMode) = prefMan.edit().putString(homeViewModeKey, value.name).apply()
    fun setHomeProgressStyle(value: HomeProgressStyle) = prefMan.edit().putString(homeProgressStyleKey, value.name).apply()
    fun setHomeGridSize(value: HomeGridSize) = prefMan.edit().putString(homeGridSizeKey, value.name).apply()
    fun setHomeListTitleLines(value: Int) = prefMan.edit().putInt(homeListTitleLinesKey, value).apply()
    fun setHomeGridTitleLines(value: Int) = prefMan.edit().putInt(homeGridTitleLinesKey, value).apply()
    fun setHomeTitleEllipsize(value: HomeTitleEllipsize) = prefMan.edit().putString(homeTitleEllipsizeKey, value.name).apply()
    fun setHomeBadgePages(value: Boolean) = prefMan.edit().putBoolean(homeBadgePagesKey, value).apply()
    fun setHomeBadgeProgress(value: Boolean) = prefMan.edit().putBoolean(homeBadgeProgressKey, value).apply()
    fun setHomeBadgeLastOpened(value: Boolean) = prefMan.edit().putBoolean(homeBadgeLastOpenedKey, value).apply()
    fun setHomeBadgeFileSize(value: Boolean) = prefMan.edit().putBoolean(homeBadgeFileSizeKey, value).apply()
    fun setHomeBadgeStatus(value: Boolean) = prefMan.edit().putBoolean(homeBadgeStatusKey, value).apply()
    fun setHomeSort(value: HomeSortOrder) = prefMan.edit().putString(homeSortKey, value.name).apply()
    fun setHistoryEnabled(value: Boolean) = prefMan.edit().putBoolean(historyEnabledKey, value).apply()

    fun setKeepSharedCopies(value: Boolean) = prefMan.edit().putBoolean(keepSharedCopiesKey, value).apply()
    fun setSharedCopyMode(value: SharedCopyMode) = prefMan.edit().putString(sharedCopyModeKey, value.name).apply()
    fun setScanMode(value: ScanMode) = prefMan.edit().putString(scanModeKey, value.name).apply()
    fun setScanLocations(value: Set<String>) = prefMan.edit().putStringSet(scanLocationsKey, value).apply()

    fun setScanScope(mode: ScanMode, locations: Set<String>) = prefMan.edit()
        .putString(scanModeKey, mode.name)
        .putStringSet(scanLocationsKey, locations.toSet())
        .apply()

    fun setScanBaselineScope(value: String) = prefMan.edit().putString(scanBaselineScopeKey, value).apply()

}
