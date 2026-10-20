// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.entity

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserBookmarkDao {

    @Query("SELECT * FROM UserBookmark WHERE fileHash = :fileHash ORDER BY sortOrder, pageIndex")
    fun findByHash(fileHash: String): List<UserBookmark>

    @Query("SELECT * FROM UserBookmark ORDER BY fileHash, sortOrder, pageIndex")
    fun findAll(): List<UserBookmark>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM UserBookmark WHERE fileHash = :fileHash")
    fun nextSortOrder(fileHash: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(bookmark: UserBookmark)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(bookmark: UserBookmark): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(bookmarks: List<UserBookmark>)

    @Query("UPDATE OR REPLACE UserBookmark SET fileHash = :newHash WHERE fileHash = :oldHash")
    fun rekey(oldHash: String, newHash: String): Int

    @Query("DELETE FROM UserBookmark WHERE fileHash = :fileHash AND pageIndex = :pageIndex")
    fun delete(fileHash: String, pageIndex: Int)

    @Query("DELETE FROM UserBookmark WHERE fileHash = :fileHash")
    fun deleteByFileHash(fileHash: String): Int

    @Query("DELETE FROM UserBookmark")
    fun deleteAll(): Int

    @Query("UPDATE UserBookmark SET label = :label WHERE fileHash = :fileHash AND pageIndex = :pageIndex")
    fun updateLabel(fileHash: String, pageIndex: Int, label: String?)

    @Query("UPDATE UserBookmark SET sortOrder = :sortOrder WHERE fileHash = :fileHash AND pageIndex = :pageIndex")
    fun updateSortOrder(fileHash: String, pageIndex: Int, sortOrder: Int)
}
