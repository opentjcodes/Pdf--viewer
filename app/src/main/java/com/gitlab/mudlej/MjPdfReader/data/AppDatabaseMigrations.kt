// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AppDatabaseMigrations {
    val MIGRATION_7_TO_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `PdfAnnotationSaveDestination` (
                    `sourceKey` TEXT NOT NULL,
                    `destinationUri` TEXT NOT NULL,
                    `lastSavedHash` TEXT,
                    `lastSavedAt` TEXT NOT NULL,
                    PRIMARY KEY(`sourceKey`)
                )
                """.trimIndent()
            )
            if (tableExists(db, "PdfSaveTarget")) {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `PdfAnnotationSaveDestination` (`sourceKey`, `destinationUri`, `lastSavedHash`, `lastSavedAt`)
                    SELECT `sourceKey`, `targetUri`, `lastSavedHash`, `lastSavedAt` FROM `PdfSaveTarget`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `PdfSaveTarget`")
            }
        }

        private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$tableName'").use { cursor ->
                return cursor.moveToFirst()
            }
        }
    }

    val MIGRATION_10_TO_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `UserBookmark` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("UPDATE `UserBookmark` SET `sortOrder` = `pageIndex` WHERE `sortOrder` < 0")
        }
    }

    val MIGRATION_14_TO_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addMetadataColumns(db)
            migrateCachedMetadata(db)
            createMetadataStateIndex(db)
        }

        private fun addMetadataColumns(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `ScannedPdfCache` ADD COLUMN `metadataState` TEXT NOT NULL DEFAULT 'PENDING'"
            )
            db.execSQL(
                "ALTER TABLE `ScannedPdfCache` ADD COLUMN `metadataFailure` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `ScannedPdfCache` ADD COLUMN `metadataWorkerDeaths` INTEGER NOT NULL DEFAULT 0"
            )
        }

        private fun migrateCachedMetadata(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE `ScannedPdfCache`
                SET `lastModified` = (`lastModified` / 1000) * 1000
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE `ScannedPdfCache`
                SET `metadataState` = 'COMPLETE'
                WHERE `pageCount` > 0 AND `hash` LIKE 'h2%'
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE `ScannedPdfCache`
                SET `pageCount` = 0
                WHERE `metadataState` = 'PENDING'
                """.trimIndent()
            )
        }

        private fun createMetadataStateIndex(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ScannedPdfCache_metadataState` " +
                    "ON `ScannedPdfCache` (`metadataState`)"
            )
        }
    }
}
