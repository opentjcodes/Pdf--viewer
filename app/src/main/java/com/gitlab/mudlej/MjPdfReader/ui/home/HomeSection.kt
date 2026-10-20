// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import androidx.annotation.StringRes
import com.gitlab.mudlej.MjPdfReader.R

sealed class HomeSection {

    data class PermissionCard(
        @StringRes val messageRes: Int = R.string.home_permission_card_message,
    ) : HomeSection()

    data object ScanSetupCard : HomeSection()

    data class Hero(val items: List<HomeItem>) : HomeSection()

    data object Chips : HomeSection()

    data class EmptyState(
        @StringRes val titleRes: Int,
        @StringRes val messageRes: Int,
    ) : HomeSection()

    data class ScanProgressRow(val progress: ScanProgress) : HomeSection()
}

sealed interface ScanProgress {
    data class Finding(val found: Int) : ScanProgress
    data class ReadingDetails(val completed: Int, val total: Int) : ScanProgress
    data class Incomplete(val found: Int) : ScanProgress
}
