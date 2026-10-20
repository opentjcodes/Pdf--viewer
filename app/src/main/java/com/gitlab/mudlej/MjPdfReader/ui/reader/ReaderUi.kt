// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.net.Uri

interface ReaderUi {
    fun updateTitle()
    fun updateActionBar()
    fun updateDirtyUi()
    fun updateDirtyUiPosition()
    fun showProgress()
    fun hideProgress()
    fun checkHasFile(): Boolean
    fun runAfterDirtyAnnotationPrompt(action: PostSaveAction, uri: Uri? = null)
}
