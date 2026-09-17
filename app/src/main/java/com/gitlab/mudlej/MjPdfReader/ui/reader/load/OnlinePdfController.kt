// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.load

import android.content.DialogInterface
import android.net.Uri
import android.os.Environment
import android.text.InputType
import android.util.Log
import android.view.View
import androidx.core.net.toUri
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.io.canWriteToDownloadFolder
import com.gitlab.mudlej.MjPdfReader.core.io.urlForLog
import com.gitlab.mudlej.MjPdfReader.core.net.contentLengthCompat
import com.gitlab.mudlej.MjPdfReader.core.io.copyFileToDirectory
import com.gitlab.mudlej.MjPdfReader.core.io.ensurePdfFileName
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnlinePdfController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val pdf: DocumentState,
    private val scope: CoroutineScope,
    private val isIncognito: () -> Boolean,
    private val requestSaveToDownloadPermission: () -> Unit,
    private val loadFromFile: (File) -> Unit,
    private val openConfirmedLink: (Uri) -> Unit,
) {

    private sealed class DownloadResult {
        class Success(val file: File?) : DownloadResult()
        class InsecureRedirect(val target: String) : DownloadResult()
        data object TooLarge : DownloadResult()
        data object NotPdf : DownloadResult()
        data object HttpError : DownloadResult()
        data object SslError : DownloadResult()
        data object GenericError : DownloadResult()
    }

    fun showOpenOnlinePdfDialog(
        initialUri: Uri? = null,
        onCancel: (() -> Unit)? = null,
        onConfirmed: (() -> Unit)? = null,
    ) {
        val inputLayout = activity.layoutInflater.inflate(R.layout.input_layout, null) as TextInputLayout
        inputLayout.hint = activity.getString(R.string.online_pdf_link)
        inputLayout.setStartIconDrawable(R.drawable.ic_link)
        inputLayout.editText?.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(initialUri?.toString().orEmpty())
        }

        var confirmedHttpLink = initialUri
            ?.takeIf { it.scheme.equals("http", ignoreCase = true) }
            ?.toString()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.open_online_pdf)
            .setView(inputLayout)
            .setPositiveButton(R.string.open_online_pdf, null)
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                onCancel?.invoke()
                dialog.dismiss()
            }
            .setOnCancelListener { onCancel?.invoke() }
            .create()

        dialog.setOnShowListener {
            val openButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            if (confirmedHttpLink != null) {
                openButton.setText(R.string.proceed_anyway)
                inputLayout.error = activity.getString(R.string.http_online_pdf_warning)
            }
            openButton.setOnClickListener {
                val link = inputLayout.editText?.text?.toString()?.trim().orEmpty()
                val uri = link.toUri()
                inputLayout.error = null

                if (!uri.scheme.isOnlinePdfScheme() || uri.host.isNullOrBlank()) {
                    confirmedHttpLink = null
                    openButton.setText(R.string.open_online_pdf)
                    inputLayout.error = activity.getString(R.string.invalid_online_pdf_link)
                    return@setOnClickListener
                }

                if (uri.scheme.equals("http", ignoreCase = true) && confirmedHttpLink != link) {
                    confirmedHttpLink = link
                    openButton.setText(R.string.proceed_anyway)
                    inputLayout.error = activity.getString(R.string.http_online_pdf_warning)
                    return@setOnClickListener
                }

                onConfirmed?.invoke()
                dialog.dismiss()
                openConfirmedLink(uri)
            }
        }
        dialog.show()
    }

    private fun String?.isOnlinePdfScheme(): Boolean {
        return equals("http", ignoreCase = true) || equals("https", ignoreCase = true)
    }

    fun downloadOrShowDownloadedFile(uri: Uri) {
        val file = OnlineDocumentStore.fileFor(activity, uri.toString())
        if (file != null) {
            loadFromFile(file)
        }
        else {
            startDownload(uri.toString())
        }
    }

    private fun startDownload(url: String, allowInsecureRedirect: Boolean = false) {
        binding.progressBar.isIndeterminate = true
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
        binding.pickFileButton.visibility = View.GONE
        scope.launch {
            val result = withContext(Dispatchers.IO) { download(url, allowInsecureRedirect) }
            if (!activity.isDisplayingUri(url)) {
                return@launch
            }
            when (result) {
                is DownloadResult.Success -> {
                    binding.progressBar.isIndeterminate = true
                    saveToFileAndDisplay(result.file)
                }
                is DownloadResult.InsecureRedirect -> askToProceedOverCleartext(url, result.target)
                is DownloadResult.TooLarge -> showDownloadError(R.string.toast_download_too_large)
                is DownloadResult.NotPdf -> showDownloadError(R.string.toast_download_not_a_pdf)
                is DownloadResult.HttpError -> showDownloadError(R.string.toast_http_code_error)
                is DownloadResult.SslError -> showDownloadError(R.string.toast_ssl_error)
                is DownloadResult.GenericError -> showDownloadError(R.string.toast_generic_download_error)
            }
        }
    }

    private fun askToProceedOverCleartext(url: String, target: String) {
        activity.hideProgress()
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.insecure_redirect_title)
            .setMessage(activity.getString(R.string.insecure_redirect_message, target.hostForDisplay()))
            .setPositiveButton(R.string.proceed_anyway) { dialog, _ ->
                dialog.dismiss()
                startDownload(url, allowInsecureRedirect = true)
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                showDownloadError(R.string.toast_insecure_redirect_refused)
            }
            .setOnCancelListener { showDownloadError(R.string.toast_insecure_redirect_refused) }
            .show()
    }

    private fun String.hostForDisplay(): String = runCatching { URL(this).host }.getOrNull().orEmpty()

    private fun downloadByteCeiling(): Long {
        val usable = activity.cacheDir.usableSpace - FREE_SPACE_RESERVE_BYTES
        return minOf(MAX_DOWNLOAD_BYTES, usable)
    }

    private fun download(url: String, allowInsecureRedirect: Boolean): DownloadResult {
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = URL(url)
            val startedSecure = currentUrl.protocol.equals("https", ignoreCase = true)
            var redirects = 0
            while (true) {
                val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                connection = conn
                conn.connect()
                when (val responseCode = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val contentLength = conn.contentLengthCompat()
                        val ceiling = downloadByteCeiling()
                        if (ceiling <= 0L || contentLength > ceiling) {
                            Log.e(TAG, "Refusing a download of $contentLength bytes against a ceiling of $ceiling")
                            return DownloadResult.TooLarge
                        }
                        if (contentLength > 0) {
                            binding.progressBar.post {
                                binding.progressBar.isIndeterminate = false
                                binding.progressBar.max = 100
                            }
                        }
                        var lastPercent = -1
                        val written = OnlineDocumentStore.write(
                            activity,
                            url,
                            isIncognito(),
                            conn.inputStream,
                            ceiling,
                        ) { totalBytes ->
                            if (contentLength > 0) {
                                val percent = ((totalBytes * 100) / contentLength).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    binding.progressBar.post { binding.progressBar.progress = percent }
                                }
                            }
                        }
                        return when (written) {
                            is OnlineDocumentStore.WriteResult.Stored -> DownloadResult.Success(written.file)
                            is OnlineDocumentStore.WriteResult.TooLarge -> DownloadResult.TooLarge
                            is OnlineDocumentStore.WriteResult.NotPdf -> DownloadResult.NotPdf
                            is OnlineDocumentStore.WriteResult.Failed -> DownloadResult.GenericError
                        }
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    HTTP_TEMPORARY_REDIRECT,
                    HTTP_PERMANENT_REDIRECT -> {
                        val location = conn.getHeaderField("Location")
                        if (location == null || redirects >= MAX_REDIRECTS) {
                            Log.e(TAG, "Redirect could not be followed for URL : ${url.urlForLog()}")
                            return DownloadResult.HttpError
                        }
                        val target = runCatching { URL(currentUrl, location) }.getOrNull()
                        if (target == null || !target.protocol.isOnlinePdfScheme()) {
                            Log.e(TAG, "Redirect target is not an http or https address for URL : ${url.urlForLog()}")
                            return DownloadResult.HttpError
                        }
                        val downgrades = startedSecure && target.protocol.equals("http", ignoreCase = true)
                        if (downgrades && !allowInsecureRedirect) {
                            Log.e(TAG, "Redirect downgrades to cleartext for URL : ${url.urlForLog()}")
                            return DownloadResult.InsecureRedirect(target.toString())
                        }
                        redirects++
                        currentUrl = target
                        conn.disconnect()
                    }
                    else -> {
                        Log.e(TAG, "Error during http request, response code : $responseCode")
                        return DownloadResult.HttpError
                    }
                }
            }
        } catch (e: SSLException) {
            Log.e(TAG, "Error cannot get file at URL : ${url.urlForLog()}", e)
            return DownloadResult.SslError
        } catch (e: IOException) {
            Log.e(TAG, "Error cannot get file at URL : ${url.urlForLog()}", e)
            return DownloadResult.GenericError
        } catch (e: Exception) {
            Log.e(TAG, "Error cannot get file at URL : ${url.urlForLog()}", e)
            return DownloadResult.GenericError
        } finally {
            connection?.disconnect()
        }
    }

    private fun showDownloadError(messageRes: Int) {
        activity.hideProgress()
        if (binding.pdfView.pageCount == 0) {
            binding.pickFileButton.visibility = View.VISIBLE
        }
        AppSnackbar.make(binding.root, messageRes, Snackbar.LENGTH_LONG).show()
    }

    fun saveToFileAndDisplay(file: File?) {
        if (file == null) {
            showDownloadError(R.string.toast_generic_download_error)
            return
        }
        loadFromFile(file)
        if (!isIncognito()) {
            saveToDownloadFolderIfAllowed(file)
        }
    }

    fun saveDownloadedFileAfterPermissionRequest(isPermissionGranted: Boolean) {
        if (isIncognito()) {
            return
        }
        val file = OnlineDocumentStore.fileFor(activity, pdf.uri?.toString())
        if (isPermissionGranted && file != null) {
            trySaveToDownloads(file, true)
        }
        else {
            AppSnackbar.make(binding.root, R.string.save_to_download_failed, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun saveToDownloadFolderIfAllowed(file: File) {
        if (canWriteToDownloadFolder(activity)) {
            trySaveToDownloads(file, false)
        }
        else {
            requestSaveToDownloadPermission()
        }
    }

    private fun trySaveToDownloads(file: File, showSuccessMessage: Boolean) {
        val finalName = ensurePdfFileName(pdf.name)
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val downloadDirectory =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val target = File(downloadDirectory, finalName)
                val part = File(downloadDirectory, "$finalName.${System.currentTimeMillis()}.part")
                try {
                    copyFileToDirectory(file, downloadDirectory, part.name)
                    if (part.renameTo(target)) {
                        true
                    } else {
                        target.delete()
                        part.renameTo(target)
                    }
                }
                catch (e: IOException) {
                    Log.e(TAG, activity.getString(R.string.save_to_download_failed), e)
                    false
                }
                finally {
                    part.delete()
                }
            }
            if (saved) {
                if (showSuccessMessage) {
                    AppSnackbar.make(binding.root, R.string.saved_to_download, Snackbar.LENGTH_SHORT).show()
                }
            }
            else {
                AppSnackbar.make(binding.root, R.string.save_to_download_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val TAG = "OnlinePdfController"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_DOWNLOAD_BYTES = 4L * 1024 * 1024 * 1024
        const val FREE_SPACE_RESERVE_BYTES = 250L * 1024 * 1024
        const val HTTP_TEMPORARY_REDIRECT = 307
        const val HTTP_PERMANENT_REDIRECT = 308
    }
}
