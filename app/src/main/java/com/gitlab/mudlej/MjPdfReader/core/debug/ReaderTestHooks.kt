// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import kotlin.math.abs

object ReaderTestHooks {

    const val CROP_IDLE = ""
    const val CROP_RUNNING = "running"
    const val CROP_SKIPPED = "skipped"
    const val CROP_CANCELLED = "cancelled"
    const val CROP_ABANDONED = "abandoned"
    const val CROP_FULL_PAGE = "fullPage"
    const val CROP_APPLIED = "applied"

    @Volatile
    var cropDetectionRunning = false
        private set

    @Volatile
    var cropDetectionRuns = 0
        private set

    @Volatile
    var cropDetectionOutcome = CROP_IDLE
        private set

    @Volatile
    var cropDetectionRunOutcome = CROP_IDLE
        private set

    @Volatile
    var fullScreenPanelTranslationX = 0f
        private set

    @Volatile
    var fullScreenPanelHidePeakTranslationX = 0f
        private set

    @Volatile
    var fullScreenPanelHideTargetTranslationX = 0f
        private set

    fun cropDetectionStarted() {
        if (!BuildConfig.DEBUG) {
            return
        }
        cropDetectionOutcome = CROP_RUNNING
        cropDetectionRunning = true
    }

    fun cropDetectionFinished(outcome: String) {
        if (!BuildConfig.DEBUG || !cropDetectionRunning) {
            return
        }
        cropDetectionRunning = false
        cropDetectionOutcome = outcome
        cropDetectionRunOutcome = outcome
        cropDetectionRuns++
    }

    fun cropDetectionSkipped() {
        if (!BuildConfig.DEBUG || cropDetectionRunning) {
            return
        }
        cropDetectionOutcome = CROP_SKIPPED
    }

    fun fullScreenPanelHideStarted(targetTranslationX: Float) {
        if (!BuildConfig.DEBUG) {
            return
        }
        fullScreenPanelHidePeakTranslationX = 0f
        fullScreenPanelHideTargetTranslationX = targetTranslationX
        fullScreenPanelTranslationX = 0f
    }

    fun fullScreenPanelHideTranslation(translationX: Float) {
        if (!BuildConfig.DEBUG) {
            return
        }
        fullScreenPanelTranslationX = translationX
        if (abs(translationX) > abs(fullScreenPanelHidePeakTranslationX)) {
            fullScreenPanelHidePeakTranslationX = translationX
        }
    }

    fun reset() {
        if (!BuildConfig.DEBUG) {
            return
        }
        cropDetectionRunning = false
        cropDetectionRuns = 0
        cropDetectionOutcome = CROP_IDLE
        cropDetectionRunOutcome = CROP_IDLE
        fullScreenPanelTranslationX = 0f
        fullScreenPanelHidePeakTranslationX = 0f
        fullScreenPanelHideTargetTranslationX = 0f
    }
}
