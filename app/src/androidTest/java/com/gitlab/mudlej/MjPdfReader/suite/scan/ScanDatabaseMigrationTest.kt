package com.gitlab.mudlej.MjPdfReader.suite.scan

import com.gitlab.mudlej.MjPdfReader.suite.core.DurationCategory
import com.gitlab.mudlej.MjPdfReader.suite.core.TestDuration
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.AppDatabaseMigrations
import com.gitlab.mudlej.MjPdfReader.suite.core.TestId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@TestDuration(DurationCategory.SHORT)
class ScanDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    @TestId("SCAN-47")
    fun migration14To15CanonicalizesFingerprintsAndReconstructsOnlyUnambiguousMetadata() {
        helper.createDatabase(NAME, 14).apply {
            execSQL("INSERT INTO ScannedPdfCache VALUES ('/complete.pdf', 1, 1234, 'h2-current', 20)")
            execSQL("INSERT INTO ScannedPdfCache VALUES ('/zero.pdf', 2, 2345, 'h2-zero', 0)")
            execSQL("INSERT INTO ScannedPdfCache VALUES ('/legacy.pdf', 3, 3456, 'legacy', 8)")
            execSQL("INSERT INTO ScannedPdfCache VALUES ('/null.pdf', 4, 4567, NULL, 9)")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            NAME,
            15,
            true,
            AppDatabaseMigrations.MIGRATION_14_TO_15,
        )
        data class Migrated(val state: String, val failure: String?, val deaths: Int, val modified: Long, val pages: Int)
        val rows = linkedMapOf<String, Migrated>()
        db.query(
            "SELECT path, metadataState, metadataFailure, metadataWorkerDeaths, lastModified, pageCount " +
                "FROM ScannedPdfCache ORDER BY path"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows[cursor.getString(0)] = Migrated(
                    state = cursor.getString(1),
                    failure = if (cursor.isNull(2)) null else cursor.getString(2),
                    deaths = cursor.getInt(3),
                    modified = cursor.getLong(4),
                    pages = cursor.getInt(5),
                )
            }
        }
        assertEquals("COMPLETE", rows.getValue("/complete.pdf").state)
        assertEquals(20, rows.getValue("/complete.pdf").pages)
        assertEquals("PENDING", rows.getValue("/zero.pdf").state)
        assertEquals("PENDING", rows.getValue("/legacy.pdf").state)
        assertEquals("PENDING", rows.getValue("/null.pdf").state)
        assertTrue(rows.values.all { it.failure == null && it.deaths == 0 })
        assertTrue("Every migrated fingerprint is aligned to MediaStore precision", rows.values.all {
            it.modified % 1_000L == 0L
        })
        assertEquals("Ambiguous legacy metadata is cleared while pending", 0, rows.getValue("/legacy.pdf").pages)
        assertEquals("Ambiguous null-hash metadata is cleared while pending", 0, rows.getValue("/null.pdf").pages)

        var indexFound = false
        db.query("PRAGMA index_list(`ScannedPdfCache`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == "index_ScannedPdfCache_metadataState") indexFound = true
            }
        }
        assertTrue("metadata queue reconstruction index exists", indexFound)
        db.close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .addMigrations(AppDatabaseMigrations.MIGRATION_14_TO_15)
            .build()
        try {
            val changed = room.scannedPdfEntryDao().completeMetadata(
                "/zero.pdf",
                2,
                2_000,
                "h2-completed-after-migration",
                20,
            )
            assertEquals("A canonical migrated pending fingerprint accepts its worker result", 1, changed)
            val completed = room.scannedPdfEntryDao().findAll().single { it.path == "/zero.pdf" }
            assertEquals("COMPLETE", completed.metadataState.name)
            assertEquals(20, completed.pageCount)
        } finally {
            room.close()
        }
    }

    @Test
    @TestId("SCAN-53")
    fun exportedVersion15OpensWithTheCurrentRoomImplementation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val schema = instrumentation.context.assets.open("${AppDatabase::class.java.name}/15.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        helper.createDatabase(NAME, 15).use { database ->
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) database.execSQL(setup.getString(index))
            database.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(schema.getString("identityHash"), cursor.getString(0))
            }
        }
        val context = instrumentation.targetContext
        val room = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .build()
        try {
            assertTrue(room.scannedPdfEntryDao().findAll().isEmpty())
        } finally {
            room.close()
        }
    }

    companion object {
        private const val NAME = "scan-migration-test"
    }
}
