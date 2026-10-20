// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import android.content.Context
import androidx.room.AutoMigration
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfAnnotationSaveDestination
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfAnnotationSaveDestinationDao
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecordDao
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntryDao
import com.gitlab.mudlej.MjPdfReader.data.entity.UserBookmark
import com.gitlab.mudlej.MjPdfReader.data.entity.UserBookmarkDao
import androidx.room.Database
import androidx.room.RenameTable
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import java.io.File

@Database(
    entities = [PdfRecord::class, PdfAnnotationSaveDestination::class, ScannedPdfEntry::class, UserBookmark::class],
    version = 15,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.MyAutoMigration::class),
        AutoMigration(from = 1, to = 3, spec = AppDatabase.MyAutoMigration::class),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
    ]
)
@TypeConverters(DataConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pdfRecordDao(): PdfRecordDao
    abstract fun pdfAnnotationSaveDestinationDao(): PdfAnnotationSaveDestinationDao
    abstract fun scannedPdfEntryDao(): ScannedPdfEntryDao
    abstract fun userBookmarkDao(): UserBookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private const val DATABASE_NAME = "app-db.db"

        fun getInstance(context: Context): AppDatabase {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val moved = moveDatabaseOutOfCacheDir(context)
                val databaseName = if (moved) {
                    DATABASE_NAME
                } else {
                    File(context.cacheDir, DATABASE_NAME).absolutePath
                }
                val created = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_7_TO_8)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_10_TO_11)
                    .addMigrations(AppDatabaseMigrations.MIGRATION_14_TO_15)
                    .build()
                INSTANCE = created
                return created
            }
        }

        private fun moveDatabaseOutOfCacheDir(context: Context): Boolean {
            val newDbFile = context.getDatabasePath(DATABASE_NAME)
            val newDbDir = newDbFile.parentFile ?: return true
            val oldDbFile = File(context.cacheDir, DATABASE_NAME)
            if (!oldDbFile.exists() || newDbFile.exists()) {
                return true
            }

            val copied = runCatching {
                newDbDir.mkdirs()
                for (suffix in listOf("-wal", "-shm", "")) {
                    val oldFile = File(context.cacheDir, DATABASE_NAME + suffix)
                    if (oldFile.exists()) {
                        oldFile.copyTo(File(newDbDir, DATABASE_NAME + suffix), overwrite = true)
                    }
                }
            }.isSuccess

            for (suffix in listOf("", "-wal", "-shm")) {
                if (copied) {
                    File(context.cacheDir, DATABASE_NAME + suffix).delete()
                } else {
                    File(newDbDir, DATABASE_NAME + suffix).delete()
                }
            }
            return copied
        }
    }

    @RenameTable(fromTableName = "SavedLocation", toTableName = "PdfRecord")
    class MyAutoMigration : AutoMigrationSpec

}
