// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.entity

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow

data class TextModeReflowOverride(
    val textModeJoinParagraphs: Boolean?,
    val textModeDetectHeadings: Boolean?,
    val textModeCodeBlocks: Boolean?,
)

@Dao
interface PdfRecordDao {

    @Query("SELECT * FROM PdfRecord")
    fun findAll(): List<PdfRecord>

    @Query("SELECT * FROM PdfRecord")
    fun observeAll(): Flow<List<PdfRecord>>

    @Query("SELECT * FROM PdfRecord WHERE hash = :fileHash LIMIT 1")
    fun findByHash(fileHash: String): PdfRecord?

    @Query("SELECT * FROM PdfRecord WHERE uri = :uri OR sourceUri = :uri LIMIT 1")
    fun findByUri(uri: String): PdfRecord?

    @Query("UPDATE PdfRecord SET sourceUri = :sourceUri WHERE hash = :fileHash")
    fun updateSourceUri(fileHash: String, sourceUri: String?): Int

    @Query("SELECT pageNumber FROM PdfRecord WHERE hash = :fileHash")
    fun findSavedPage(fileHash: String?): Int?

    @Query("SELECT password FROM PdfRecord WHERE hash = :fileHash")
    fun findPdfPassword(fileHash: String?): String?

    @Query("SELECT cropMargins FROM PdfRecord WHERE hash = :fileHash AND cropMarginsVersion = :version")
    fun findCropMargins(fileHash: String, version: Int): String?

    @Query("SELECT autoScrollSpeed FROM PdfRecord WHERE hash = :fileHash")
    fun findAutoScrollSpeed(fileHash: String): Int?

    @Query("SELECT readingDirectionOverride FROM PdfRecord WHERE hash = :fileHash")
    fun findReadingDirectionOverride(fileHash: String): String?

    @Query("SELECT detectedReadingDirection FROM PdfRecord WHERE hash = :fileHash")
    fun findDetectedReadingDirection(fileHash: String): String?

    @Query("UPDATE PdfRecord SET pageNumber = :page WHERE hash = :fileHash")
    fun updatePageNumber(fileHash: String?, page: Int): Int?

    @Query("UPDATE PdfRecord SET lastOpened = :lastOpened WHERE hash = :fileHash")
    fun updateLastOpened(fileHash: String?, lastOpened: LocalDateTime)

    @Query("UPDATE PdfRecord SET length = :length WHERE hash = :fileHash AND length != :length")
    fun updateLength(fileHash: String, length: Int): Int

    @Query("UPDATE PdfRecord SET uri = :uri, fileName = :fileName, lastOpened = :lastOpened WHERE hash = :fileHash")
    fun updateIdentity(fileHash: String, uri: android.net.Uri, fileName: String, lastOpened: LocalDateTime): Int

    @Query("""
        UPDATE PdfRecord SET uri = :uri, fileName = :fileName
        WHERE hash = :fileHash AND uri = :previousUri AND sourceUri IS :previousSourceUri
    """)
    fun repairIdentity(
        fileHash: String,
        previousUri: android.net.Uri,
        previousSourceUri: String?,
        uri: android.net.Uri,
        fileName: String,
    ): Int

    @Query("UPDATE OR REPLACE PdfRecord SET hash = :newHash WHERE hash = :oldHash")
    fun rekey(oldHash: String, newHash: String): Int

    @Query("UPDATE PdfRecord SET favorite = :favorite WHERE hash = :fileHash")
    fun updateFavorite(fileHash: String?, favorite: Boolean)

    @Query("UPDATE PdfRecord SET favorite = :favorite WHERE hash IN (:fileHashes)")
    fun updateFavoriteBatch(fileHashes: List<String>, favorite: Boolean)

    @Query("UPDATE PdfRecord SET hidden = :hidden WHERE hash = :fileHash")
    fun updateHidden(fileHash: String, hidden: Boolean)

    @Query("UPDATE PdfRecord SET reading = :readingStatus WHERE hash = :fileHash")
    fun updateReading(fileHash: String, readingStatus: ReadingStatus)

    @Query("UPDATE PdfRecord SET reading = :readingStatus WHERE hash IN (:fileHashes)")
    fun updateReadingBatch(fileHashes: List<String>, readingStatus: ReadingStatus)

    @Query("UPDATE PdfRecord SET password = :password WHERE hash = :fileHash")
    fun updatePassword(fileHash: String, password: String)

    @Query("UPDATE PdfRecord SET documentTitle = :title WHERE hash = :fileHash")
    fun updateDocumentTitle(fileHash: String, title: String?)

    @Query("UPDATE PdfRecord SET cropMargins = :cropMargins, cropMarginsVersion = :version WHERE hash = :fileHash")
    fun updateCropMargins(fileHash: String, cropMargins: String, version: Int): Int

    @Query("UPDATE PdfRecord SET autoScrollSpeed = :speed WHERE hash = :fileHash")
    fun updateAutoScrollSpeed(fileHash: String, speed: Int): Int

    @Query("UPDATE PdfRecord SET readingDirectionOverride = :direction WHERE hash = :fileHash")
    fun updateReadingDirectionOverride(fileHash: String, direction: String?): Int

    @Query("UPDATE PdfRecord SET detectedReadingDirection = :direction WHERE hash = :fileHash")
    fun updateDetectedReadingDirection(fileHash: String, direction: String): Int

    @Query("SELECT textModeJoinParagraphs, textModeDetectHeadings, textModeCodeBlocks FROM PdfRecord WHERE hash = :fileHash LIMIT 1")
    fun findTextModeReflow(fileHash: String): TextModeReflowOverride?

    @Query("UPDATE PdfRecord SET textModeJoinParagraphs = :joinParagraphs, textModeDetectHeadings = :detectHeadings, textModeCodeBlocks = :codeBlocks WHERE hash = :fileHash")
    fun updateTextModeReflow(fileHash: String, joinParagraphs: Boolean?, detectHeadings: Boolean?, codeBlocks: Boolean?): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(saveLocations: PdfRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(records: List<PdfRecord>)

    @Query("SELECT EXISTS(SELECT * FROM PdfRecord WHERE hash = :fileHash)")
    fun hasRecord(fileHash: String): Boolean

    @Delete()
    fun delete(record: PdfRecord)

    @Query("DELETE FROM PdfRecord WHERE hash = :fileHash")
    fun deleteByHash(fileHash: String): Int

    @Query("DELETE FROM PdfRecord WHERE hash IN (:fileHashes)")
    fun deleteByHashes(fileHashes: List<String>): Int

    @Query("DELETE FROM PdfRecord")
    fun deleteAll(): Int

    @Query("UPDATE PdfRecord SET password = NULL WHERE password IS NOT NULL")
    fun clearPasswords(): Int
}
