// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.entity

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScannedPdfEntryDao {

    @Query("SELECT * FROM ScannedPdfCache ORDER BY path")
    fun findAll(): List<ScannedPdfEntry>

    @Query("SELECT * FROM ScannedPdfCache WHERE path = :path LIMIT 1")
    fun findByPath(path: String): ScannedPdfEntry?

    @Query("SELECT * FROM ScannedPdfCache WHERE metadataState = 'PENDING' ORDER BY path")
    fun findPending(): List<ScannedPdfEntry>

    @Query("SELECT * FROM ScannedPdfCache WHERE hash = :hash ORDER BY path")
    fun findByHash(hash: String): List<ScannedPdfEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(entries: List<ScannedPdfEntry>)

    @Query(
        """
        UPDATE ScannedPdfCache
        SET hash = :hash,
            pageCount = :pageCount,
            metadataState = 'COMPLETE',
            metadataFailure = NULL
        WHERE path = :path
          AND size = :size
          AND lastModified = :lastModified
          AND metadataState = 'PENDING'
        """
    )
    fun completeMetadata(
        path: String,
        size: Long,
        lastModified: Long,
        hash: String?,
        pageCount: Int,
    ): Int

    @Query(
        """
        UPDATE ScannedPdfCache
        SET hash = COALESCE(:hash, hash),
            pageCount = 0,
            metadataState = 'FAILED',
            metadataFailure = :failure
        WHERE path = :path
          AND size = :size
          AND lastModified = :lastModified
          AND metadataState = 'PENDING'
        """
    )
    fun failMetadata(
        path: String,
        size: Long,
        lastModified: Long,
        hash: String?,
        failure: ScannedPdfMetadataFailure,
    ): Int

    @Query(
        """
        UPDATE ScannedPdfCache
        SET metadataWorkerDeaths = metadataWorkerDeaths + 1,
            pageCount = 0,
            metadataState = CASE
                WHEN metadataWorkerDeaths + 1 >= 2 THEN 'FAILED'
                ELSE 'PENDING'
            END,
            metadataFailure = CASE
                WHEN metadataWorkerDeaths + 1 >= 2 THEN 'WORKER_CRASH'
                ELSE NULL
            END
        WHERE path = :path
          AND size = :size
          AND lastModified = :lastModified
          AND metadataState = 'PENDING'
        """
    )
    fun recordWorkerDeath(path: String, size: Long, lastModified: Long): Int

    @Query(
        """
        UPDATE ScannedPdfCache
        SET hash = :hash
        WHERE path = :path
          AND size = :size
          AND lastModified = :lastModified
        """
    )
    fun updateHash(path: String, size: Long, lastModified: Long, hash: String): Int

    @Query("DELETE FROM ScannedPdfCache WHERE path IN (:paths)")
    fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM ScannedPdfCache WHERE path = :path")
    fun deleteByPath(path: String): Int

    @Query("DELETE FROM ScannedPdfCache WHERE path = :path AND size = :size AND lastModified = :lastModified")
    fun deleteByFingerprint(path: String, size: Long, lastModified: Long): Int

    @Query("UPDATE ScannedPdfCache SET path = :newPath WHERE path = :oldPath")
    fun updatePath(oldPath: String, newPath: String): Int
}
