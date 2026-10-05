// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderViewModel
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentLoader
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.LoadState
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderHistoryManager

class ReaderDebugStateSource(
    private val vm: ReaderViewModel,
    private val documentLoader: DocumentLoader,
    private val history: ReaderHistoryManager,
) : PDFView.DebugStateSource {

    override fun loadState(): String = when (documentLoader.state) {
        LoadState.Idle -> "Idle"
        LoadState.Loading -> "Loading"
        LoadState.PasswordRequired -> "PasswordRequired"
        is LoadState.Loaded -> "Loaded"
        is LoadState.Failed -> "Failed"
    }

    override fun fileHash(): String = vm.doc.fileHash.orEmpty()

    override fun docUri(): String = vm.doc.uri?.toString().orEmpty()

    override fun autoScrolling(): Boolean = vm.isAutoScrolling

    override fun saving(): Boolean = vm.isSavingAnnotations

    override fun dirty(): Boolean = vm.hasUnsavedAnnotations

    override fun dwellMs(): Long = history.elapsedDwellMs()
}
