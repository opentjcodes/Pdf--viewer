// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPresentationTest {
    private val emptyAll = HomeSection.EmptyState(
        R.string.home_empty_all_title, R.string.home_empty_all_message,
    )
    private val emptyStatus = HomeSection.EmptyState(
        R.string.home_empty_status_title, R.string.home_empty_status_message,
    )

    @Test fun progressPreservesCountsAndPhaseMapping() {
        val expected = mapOf(
            ScanPhase.IDLE to null,
            ScanPhase.FINDING to ScanProgress.Finding(17),
            ScanPhase.READING_DETAILS to ScanProgress.ReadingDetails(23, 19),
            ScanPhase.PAUSED to null,
            ScanPhase.INCOMPLETE to ScanProgress.Incomplete(17),
        )

        assertEquals(ScanPhase.entries.toSet(), expected.keys)
        expected.forEach { (phase, progress) ->
            assertEquals(progress, LibraryPresentation.progressFor(phase, found = 17, completed = 23, total = 19))
        }
    }

    @Test fun indexVisibilityHidesAllRoutineProgress() {
        for (phase in ScanPhase.entries) {
            val index = LibraryIndex(
                phase = phase, foundCount = 17, detailsCompleted = 23, detailsTotal = 19,
            )
            assertEquals(null, LibraryPresentation.progressFor(index))
            assertEquals(
                LibraryPresentation.progressFor(phase, 17, 23, 19),
                LibraryPresentation.progressFor(index.copy(showProgress = true)),
            )
        }
    }

    @Test fun onlyAllFilesShowsProgressAndSuppressesTheEmptyState() {
        val progressStates = listOf(
            null,
            ScanProgress.Finding(0),
            ScanProgress.ReadingDetails(3, 7),
            ScanProgress.Incomplete(0),
        )
        for (filter in ListFilter.entries) {
            for (progress in progressStates) {
                val sections = LibraryPresentation.buildSections(
                    filter, progress, itemsEmpty = true,
                    hasFullAccess = { true }, showScanSetup = { false },
                )
                val expected = when {
                    filter != ListFilter.ALL -> listOf(HomeSection.Chips, emptyStatus)
                    progress != null -> listOf(HomeSection.Chips, HomeSection.ScanProgressRow(progress))
                    else -> listOf(HomeSection.Chips, emptyAll)
                }
                assertEquals("filter=$filter progress=$progress", expected, sections)
            }
        }
    }

    @Test fun permissionAndSetupCardsPrecedeChipsAndProgress() {
        val progress = ScanProgress.Finding(5)
        for (fullAccess in listOf(false, true)) {
            val reads = mutableListOf<String>()
            val sections = LibraryPresentation.buildSections(
                ListFilter.ALL, progress, itemsEmpty = true,
                hasFullAccess = { reads += "access"; fullAccess },
                showScanSetup = { reads += "setup"; true },
            )

            assertEquals(
                listOf(
                    if (fullAccess) HomeSection.ScanSetupCard else HomeSection.PermissionCard(),
                    HomeSection.Chips,
                    HomeSection.ScanProgressRow(progress),
                ),
                sections,
            )
            assertEquals(if (fullAccess) listOf("access", "setup") else listOf("access"), reads)
        }
    }

    @Test fun emptyStateRereadsSetupInsteadOfReusingTheCardDecision() {
        for (firstSetup in listOf(false, true)) {
            val reads = mutableListOf<String>()
            var setupReads = 0
            val sections = LibraryPresentation.buildSections(
                ListFilter.ALL, progress = null, itemsEmpty = true,
                hasFullAccess = { reads += "access"; true },
                showScanSetup = {
                    reads += "setup"
                    if (setupReads++ == 0) firstSetup else !firstSetup
                },
            )

            assertEquals(
                if (firstSetup) listOf(HomeSection.ScanSetupCard, HomeSection.Chips, emptyAll)
                else listOf(HomeSection.Chips),
                sections,
            )
            assertEquals(listOf("access", "setup", "setup"), reads)
        }
    }

    @Test fun permissionDoesNotPreventTheEmptyStateSetupCheck() {
        for (setup in listOf(false, true)) {
            val reads = mutableListOf<String>()
            val sections = LibraryPresentation.buildSections(
                ListFilter.READING, ScanProgress.ReadingDetails(1, 2), itemsEmpty = true,
                hasFullAccess = { reads += "access"; false },
                showScanSetup = { reads += "setup"; setup },
            )

            val expected = listOf(HomeSection.PermissionCard(), HomeSection.Chips)
            assertEquals(if (setup) expected else expected + emptyStatus, sections)
            assertEquals(listOf("access", "setup"), reads)
        }
    }

    @Test fun nonemptyRowsSkipTheEmptyStateSetupRead() {
        for (fullAccess in listOf(false, true)) {
            for (filter in listOf(ListFilter.ALL, ListFilter.READING)) {
                val reads = mutableListOf<String>()
                val sections = LibraryPresentation.buildSections(
                    filter, progress = null, itemsEmpty = false,
                    hasFullAccess = { reads += "access"; fullAccess },
                    showScanSetup = { reads += "setup"; false },
                )

                assertEquals(
                    if (fullAccess) listOf(HomeSection.Chips)
                    else listOf(HomeSection.PermissionCard(), HomeSection.Chips),
                    sections,
                )
                assertEquals(if (fullAccess) listOf("access", "setup") else listOf("access"), reads)
            }
        }
    }
}
