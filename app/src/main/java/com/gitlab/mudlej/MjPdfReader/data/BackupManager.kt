// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.BackupDebugHooks
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationJournal
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.data.entity.UserBookmark
import com.gitlab.mudlej.MjPdfReader.data.signature.SignatureStore
import com.gitlab.mudlej.MjPdfReader.ui.home.CoverCache
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime

class BackupManager(
    private val context: Context,
    private val pdfRepository: PdfRepository,
) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun export(
        folder: DocumentFile,
        fileName: String,
        options: BackupExportOptions,
    ): ExportSummary = withContext(NonCancellable + Dispatchers.IO) {
        val settings = if (options.includeSettings) collectSettings() else null
        val records = if (options.includeHistory) {
            pdfRepository.findAllRecords().map { it.toBackup(options.includePasswords) }
        } else {
            null
        }
        val bookmarks = if (options.includeHistory) {
            pdfRepository.findAllUserBookmarks().map { it.toBackup() }
        } else {
            null
        }
        val data = BackupData(
            schemaVersion = SCHEMA_VERSION,
            appVersionCode = appVersionCode(),
            exportedAt = OffsetDateTime.now().toString(),
            settings = settings,
            pdfRecords = records,
            userBookmarks = bookmarks,
        )
        val json = gson.toJson(BackupCodec.encode(data))
        val tmpFile = folder.createFile(TMP_MIME_TYPE, "$fileName.tmp")
            ?: throw BackupException(R.string.backup_error_create_file)
        try {
            writeAndVerify(tmpFile, data, json)
        } catch (exception: Exception) {
            tmpFile.delete()
            throw exception
        }
        if (!tmpFile.renameTo(fileName)) {
            finalizeWithoutRename(folder, fileName, data, json, tmpFile)
        }
        ExportSummary(settings?.size ?: 0, records?.size ?: 0, bookmarks?.size ?: 0)
    }

    private fun finalizeWithoutRename(
        folder: DocumentFile,
        fileName: String,
        data: BackupData,
        json: String,
        tmpFile: DocumentFile,
    ) {
        val finalFile = folder.createFile("application/json", fileName)
        if (finalFile == null) {
            tmpFile.delete()
            throw BackupException(R.string.backup_error_finalize_failed)
        }
        try {
            writeAndVerify(finalFile, data, json)
        } catch (exception: Exception) {
            finalFile.delete()
            tmpFile.delete()
            throw exception
        }
        if (!tmpFile.delete()) {
            throw BackupException(R.string.backup_error_cleanup_failed)
        }
    }

    private fun writeAndVerify(file: DocumentFile, data: BackupData, json: String) {
        val output = runCatching { context.contentResolver.openOutputStream(file.uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(file.uri, "w")
            ?: throw BackupException(R.string.backup_error_open_write)
        output.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
            stream.flush()
        }
        val written = readAndParse(file.uri)
        val matches = written.schemaVersion == data.schemaVersion &&
            written.settings?.size == data.settings?.size &&
            written.pdfRecords?.size == data.pdfRecords?.size &&
            written.userBookmarks?.size == data.userBookmarks?.size
        if (!matches) {
            throw BackupException(R.string.backup_error_verification_failed)
        }
        val importable = try {
            val writtenPlan = prepareImport(written)
            val expectedPlan = prepareImport(data)
            writtenPlan.records.size == expectedPlan.records.size &&
                writtenPlan.bookmarks.size == expectedPlan.bookmarks.size &&
                writtenPlan.settingsPuts?.size == expectedPlan.settingsPuts?.size &&
                writtenPlan.skippedSettingsCount == expectedPlan.skippedSettingsCount
        } catch (exception: Exception) {
            false
        }
        if (!importable) {
            throw BackupException(R.string.backup_error_verification_failed)
        }
    }

    suspend fun parse(uri: Uri): BackupData = withContext(Dispatchers.IO) {
        val data = readAndParse(uri)
        if (data.settings == null && !data.includesHistory) {
            throw BackupException(R.string.backup_error_nothing_to_import)
        }
        data
    }

    private fun readAndParse(uri: Uri): BackupData {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BackupException(R.string.backup_error_open_read)
        val json = input.use { stream -> readBounded(stream) }.toString(Charsets.UTF_8)
        val data = BackupCodec.decode(json)
        if (data.schemaVersion < 1) {
            throw BackupException(R.string.backup_error_not_valid)
        }
        if (data.schemaVersion > SCHEMA_VERSION) {
            throw BackupException(R.string.backup_error_newer_version)
        }
        return data
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) {
                break
            }
            total += read
            if (total > MAX_BACKUP_BYTES) {
                throw BackupException(R.string.backup_error_too_large)
            }
            output.write(chunk, 0, read)
        }
        return output.toByteArray()
    }

    fun prepareImport(data: BackupData): ImportPlan {
        if (BuildConfig.DEBUG && BackupDebugHooks.consumePrepareImportFailure()) {
            throw BackupException(R.string.backup_error_not_valid)
        }
        val settings = data.settings?.let { validateSettings(it) }
        return ImportPlan(
            includesHistory = data.includesHistory,
            records = toRecords(data.pdfRecords.orEmpty()),
            bookmarks = toBookmarks(data.userBookmarks.orEmpty()),
            settingsPuts = settings?.first,
            skippedSettingsCount = settings?.second ?: 0,
        )
    }

    suspend fun applyImport(plan: ImportPlan): ImportSummary = withContext(NonCancellable + Dispatchers.IO) {
        if (plan.includesHistory) {
            CoverCache.getInstance(context).clearAllBlocking()
            AnnotationJournal(context).deleteAllBlocking()
            SignatureStore(context).delete()
            pdfRepository.replaceAllHistory(plan.records, plan.bookmarks)
        }
        plan.settingsPuts?.let { puts -> applySettings(puts) }
        ImportSummary(
            plan.settingsPuts?.size ?: 0,
            plan.records.size,
            plan.bookmarks.size,
            plan.skippedSettingsCount,
        )
    }

    private fun collectSettings(): List<BackupSetting> {
        val all = PreferenceManager.getDefaultSharedPreferences(context).all
        return all.mapNotNull { (key, value) ->
            if (key in excludedSettingKeys || value == null) {
                return@mapNotNull null
            }
            when (value) {
                is Boolean -> BackupSetting(key, Preferences.kindBoolean, value.toString())
                is Int -> BackupSetting(key, Preferences.kindInt, value.toString())
                is Long -> BackupSetting(key, Preferences.kindLong, value.toString())
                is Float -> BackupSetting(key, Preferences.kindFloat, value.toString())
                is String -> BackupSetting(key, Preferences.kindString, value)
                is Set<*> -> BackupSetting(key, Preferences.kindStringSet, null, value.filterIsInstance<String>())
                else -> null
            }
        }.sortedBy { it.key }
    }

    private fun applySettings(puts: List<(SharedPreferences.Editor) -> Unit>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val preserved = prefs.all.filterKeys { it in excludedSettingKeys }
        val editor = prefs.edit().clear()
        preserved.forEach { (key, value) -> putSetting(editor, key, value) }
        puts.forEach { put -> put(editor) }
        @Suppress("ApplySharedPref")
        if (!editor.commit()) {
            throw BackupException(R.string.backup_error_settings_write_failed)
        }
    }

    private fun putSetting(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun toRecords(records: List<BackupPdfRecord>): List<PdfRecord> {
        return records.mapNotNull { backup ->
            val hash = backup.hash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PdfRecord(
                hash = hash,
                pageNumber = backup.pageNumber,
                uri = Uri.parse(backup.uri.orEmpty()),
                length = backup.length,
                fileName = backup.fileName.orEmpty(),
                password = backup.password,
                lastOpened = parseDate(backup.lastOpened) ?: LocalDateTime.MIN,
                reading = parseReadingStatus(backup.reading),
                favorite = backup.favorite,
                cropMargins = backup.cropMargins,
                cropMarginsVersion = backup.cropMarginsVersion,
                autoScrollSpeed = backup.autoScrollSpeed,
                readingDirectionOverride = backup.readingDirectionOverride,
                detectedReadingDirection = backup.detectedReadingDirection,
                documentTitle = backup.documentTitle,
                textModeJoinParagraphs = backup.textModeJoinParagraphs,
                textModeDetectHeadings = backup.textModeDetectHeadings,
                textModeCodeBlocks = backup.textModeCodeBlocks,
                hidden = backup.hidden,
                sourceUri = backup.sourceUri,
            )
        }
    }

    private fun toBookmarks(bookmarks: List<BackupUserBookmark>): List<UserBookmark> {
        return bookmarks.mapNotNull { backup ->
            val hash = backup.fileHash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (backup.pageIndex < 0) {
                return@mapNotNull null
            }
            UserBookmark(
                fileHash = hash,
                pageIndex = backup.pageIndex,
                label = backup.label,
                createdAt = parseDate(backup.createdAt) ?: LocalDateTime.now(),
                sortOrder = backup.sortOrder.takeIf { it >= 0 } ?: backup.pageIndex,
            )
        }
    }

    private fun PdfRecord.toBackup(includePasswords: Boolean): BackupPdfRecord {
        return BackupPdfRecord(
            hash = hash,
            pageNumber = pageNumber,
            length = length,
            fileName = fileName,
            password = if (includePasswords) password else null,
            lastOpened = lastOpened.toString(),
            reading = reading.name,
            favorite = favorite,
            cropMargins = cropMargins,
            cropMarginsVersion = cropMarginsVersion,
            autoScrollSpeed = autoScrollSpeed,
            readingDirectionOverride = readingDirectionOverride,
            detectedReadingDirection = detectedReadingDirection,
            documentTitle = documentTitle,
            uri = uri.toString(),
            textModeJoinParagraphs = textModeJoinParagraphs,
            textModeDetectHeadings = textModeDetectHeadings,
            textModeCodeBlocks = textModeCodeBlocks,
            hidden = hidden,
            sourceUri = sourceUri,
        )
    }

    private fun UserBookmark.toBackup(): BackupUserBookmark {
        return BackupUserBookmark(
            fileHash = fileHash,
            pageIndex = pageIndex,
            label = label,
            createdAt = createdAt.toString(),
            sortOrder = sortOrder,
        )
    }

    private fun parseDate(value: String?): LocalDateTime? {
        if (value.isNullOrBlank()) {
            return null
        }
        return runCatching { LocalDateTime.parse(value) }.getOrNull()
    }

    private fun parseReadingStatus(value: String?, fallback: ReadingStatus = ReadingStatus.UNSET): ReadingStatus {
        return ReadingStatus.entries.firstOrNull { it.name == value } ?: fallback
    }

    private fun appVersionCode(): Long {
        return runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        }.getOrDefault(0L)
    }

    class ImportPlan internal constructor(
        internal val includesHistory: Boolean,
        internal val records: List<PdfRecord>,
        internal val bookmarks: List<UserBookmark>,
        internal val settingsPuts: List<(SharedPreferences.Editor) -> Unit>?,
        internal val skippedSettingsCount: Int,
    )

    companion object {
        const val SCHEMA_VERSION = 1

        private const val MAX_BACKUP_BYTES = 32L * 1024L * 1024L
        private const val TMP_MIME_TYPE = "application/octet-stream"

        private val excludedSettingKeys = setOf(
            Preferences.firstInstallKey,
            Preferences.showFeaturesDialogKey,
            Preferences.showExitFullscreenTipKey,
            Preferences.lastSeenVersionCodeKey,
            Preferences.backupFolderTreeUriKey,
            Preferences.autoBackupLastRunKey,
            Preferences.autoBackupLastErrorKey,
            Preferences.autoBackupErrorAcknowledgedRunKey,
            Preferences.autoBackupEnabledAtKey,
            Preferences.importResultPendingKey,
        )

        internal fun validateSettings(
            settings: List<BackupSetting>,
        ): Pair<List<(SharedPreferences.Editor) -> Unit>, Int> {
            val puts = mutableListOf<(SharedPreferences.Editor) -> Unit>()
            var skipped = 0
            settings.forEach { setting ->
                val key = setting.key ?: return@forEach
                if (key in excludedSettingKeys) {
                    return@forEach
                }
                val expectedKind = Preferences.backupSettingKinds[key]
                if (expectedKind == null || expectedKind != setting.type) {
                    skipped++
                    return@forEach
                }
                val enumDomain = Preferences.backupSettingEnumDomains[key]
                if (enumDomain != null && setting.value !in enumDomain) {
                    skipped++
                    return@forEach
                }
                val put: ((SharedPreferences.Editor) -> Unit)? = when (setting.type) {
                    Preferences.kindBoolean -> setting.value?.toBooleanStrictOrNull()
                        ?.let { parsed -> { editor -> editor.putBoolean(key, parsed) } }
                    Preferences.kindInt -> setting.value?.toIntOrNull()
                        ?.let { parsed -> { editor -> editor.putInt(key, parsed) } }
                    Preferences.kindLong -> setting.value?.toLongOrNull()
                        ?.let { parsed -> { editor -> editor.putLong(key, parsed) } }
                    Preferences.kindFloat -> setting.value?.toFloatOrNull()
                        ?.let { parsed -> { editor -> editor.putFloat(key, parsed) } }
                    Preferences.kindString -> setting.value
                        ?.let { parsed -> { editor -> editor.putString(key, parsed) } }
                    Preferences.kindStringSet -> setting.values
                        ?.let { parsed -> { editor -> editor.putStringSet(key, parsed.toSet()) } }
                    else -> null
                }
                if (put != null) {
                    puts.add(put)
                } else {
                    skipped++
                }
            }
            return puts to skipped
        }
    }
}
