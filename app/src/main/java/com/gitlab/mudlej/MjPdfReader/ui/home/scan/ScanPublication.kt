// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import com.gitlab.mudlej.MjPdfReader.core.debug.ScanDebugSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ScanPublication(private val work: ScanWorkIndex) {
    private val state = MutableStateFlow(LibraryIndex())

    val index: StateFlow<LibraryIndex> = state.asStateFlow()

    fun publish(
        phase: ScanPhase,
        loaded: Boolean = state.value.loaded,
        showProgress: Boolean = state.value.showProgress,
    ) {
        val index = work.snapshot(phase, loaded, showProgress, state.value.revision + 1)
        state.value = index
        ScanDebugSignals.publish(index)
    }
}
