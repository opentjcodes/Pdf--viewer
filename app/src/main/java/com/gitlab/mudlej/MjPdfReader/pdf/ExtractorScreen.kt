// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.pdf

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.core.ui.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ExtractorScreen(private val activity: AppCompatActivity) {

    var extractor: PdfExtractor? = null
        private set

    @Volatile
    private var closing = false
    private val mutex = Mutex()

    fun open(failureMessage: String, onReady: suspend (PdfExtractor) -> Unit) {
        activity.lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) {
                mutex.withLock {
                    val created = activity.openPdfExtractorFromIntent()
                    if (created != null && closing) {
                        created.close()
                        null
                    } else {
                        extractor = created
                        created
                    }
                }
            }
            if (opened == null) {
                toast(activity, failureMessage, Toast.LENGTH_SHORT)
                activity.finish()
                return@launch
            }
            onReady(opened)
        }
    }

    fun close() {
        closing = true
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            mutex.withLock {
                extractor?.close()
                extractor = null
            }
        }
    }
}
