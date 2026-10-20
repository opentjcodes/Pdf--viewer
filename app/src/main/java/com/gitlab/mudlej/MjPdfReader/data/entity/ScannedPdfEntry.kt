// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ScannedPdfCache",
    indices = [Index(value = ["metadataState"])],
)
data class ScannedPdfEntry(
    @PrimaryKey val path: String,
    val size: Long,
    val lastModified: Long,
    val hash: String?,
    @ColumnInfo(defaultValue = "0")
    val pageCount: Int = 0,
    @ColumnInfo(defaultValue = "PENDING")
    val metadataState: ScannedPdfMetadataState = ScannedPdfMetadataState.PENDING,
    val metadataFailure: ScannedPdfMetadataFailure? = null,
    @ColumnInfo(defaultValue = "0")
    val metadataWorkerDeaths: Int = 0,
)
