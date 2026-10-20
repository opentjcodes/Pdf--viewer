// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanPathProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "path-probe-${UUID.randomUUID()}")
    private val probe = ScanPathProbe(context)

    @Before fun createRoot() {
        check(root.mkdirs())
    }

    @After fun removeRoot() {
        root.deleteRecursively()
    }

    @Test fun presentFileUsesActualNormalizedFingerprint() {
        val file = File(root, "book.PDF").also { it.writeText("1234567") }
        check(file.setLastModified(1_700_000_000_789L))

        val state = probe.read(file.path)

        assertTrue(state is ScanPathState.Present)
        val entry = (state as ScanPathState.Present).entry
        assertEquals(file.path, entry.path)
        assertEquals(7L, entry.size)
        assertEquals(1_700_000_000_000L, entry.lastModified)
    }

    @Test fun absentLeafInAccessibleDirectoryIsMissing() {
        assertEquals(ScanPathState.Missing, probe.read(File(root, "gone.pdf").path))
    }

    @Test fun removedIntermediateDirectoriesAreVerifiedFromAccessibleAncestor() {
        val directory = File(root, "removed/nested").also { check(it.mkdirs()) }
        val file = File(directory, "gone.pdf").also { it.writeText("pdf") }
        check(File(root, "removed").deleteRecursively())

        assertEquals(ScanPathState.Missing, probe.read(file.path))
    }

    @Test fun deniedAncestorDoesNotTurnAbsenceIntoMissing() {
        val denied = File(root, "denied").also { check(it.mkdir()) }
        Os.chmod(denied.path, 0)
        try {
            assertEquals(ScanPathState.Unavailable, probe.read(File(denied, "gone.pdf").path))
        } finally {
            Os.chmod(denied.path, 448)
        }
    }

    @Test fun unreadableExistingFileIsUnavailable() {
        val denied = File(root, "denied.pdf").also { it.writeText("pdf") }
        Os.chmod(denied.path, 0)
        try {
            assertEquals(ScanPathState.Unavailable, probe.read(denied.path))
        } finally {
            Os.chmod(denied.path, 384)
        }
    }

    @Test fun absentUnrecognizedRemovableVolumeIsUnavailable() {
        val path = "/storage/scan-unmounted-${UUID.randomUUID()}/removed/book.pdf"

        assertEquals(ScanPathState.Unavailable, probe.read(path))
    }

    @Test fun relativePathsAreNotEvidenceOfAbsence() {
        assertEquals(ScanPathState.Unavailable, probe.read("missing.pdf"))
    }

    @Test fun absenceOutsideIdentifiedStorageIsUnavailable() {
        assertEquals(ScanPathState.Unavailable, probe.read("/scan-unknown-${UUID.randomUUID()}/book.pdf"))
    }
}
