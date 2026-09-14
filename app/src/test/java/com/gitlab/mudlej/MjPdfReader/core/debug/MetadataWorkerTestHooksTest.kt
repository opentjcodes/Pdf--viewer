// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataMemorySample
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataProtocol.DEBUG_KILL
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataProtocol.DEBUG_NONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MetadataWorkerTestHooksTest {
    private val hooks = MetadataWorkerTestHooks
    private val fingerprint = SourceFingerprint("/hooks/book.pdf", 10, 1_999)
    private val different = listOf(
        fingerprint.copy(path = "/hooks/other.pdf"), fingerprint.copy(size = 11), fingerprint.copy(lastModified = 1_998),
    )
    private val real = MetadataMemorySample(1_000, 100, 2_000, false, 10, 20)

    @Before @After fun resetHooks() = hooks.reset()

    @Test fun pauseCountMustBePositiveAndTriggersOnlyOnTheExactCommit() {
        hooks.pauseAfterCommittedResults(3)
        for (invalid in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { hooks.pauseAfterCommittedResults(invalid) }
        }
        repeat(2) {
            hooks.noteCommitted()
            assertFalse(hooks.dispatchPaused())
        }
        hooks.noteCommitted()
        assertTrue(hooks.dispatchPaused())
        hooks.resumeDispatch()
        hooks.noteCommitted()
        assertFalse(hooks.dispatchPaused())
    }

    @Test fun oneShotKillMatchesEveryRawFingerprintFieldAndIsConsumedOnce() {
        hooks.killOnce(fingerprint)
        different.forEach { assertEquals(DEBUG_NONE, hooks.debugBehavior(it)) }
        assertEquals(DEBUG_KILL, hooks.debugBehavior(fingerprint.copy()))
        assertEquals(DEBUG_NONE, hooks.debugBehavior(fingerprint))
        hooks.killOnce(fingerprint)
        hooks.clearKills()
        assertEquals(DEBUG_NONE, hooks.debugBehavior(fingerprint))
    }

    @Test fun permanentKillMatchesEveryRawFingerprintFieldUntilCleared() {
        hooks.killAlways(fingerprint)
        hooks.killOnce(fingerprint)
        different.forEach { assertEquals(DEBUG_NONE, hooks.debugBehavior(it)) }
        repeat(3) { assertEquals(DEBUG_KILL, hooks.debugBehavior(fingerprint.copy())) }
        hooks.clearKills()
        assertEquals(DEBUG_NONE, hooks.debugBehavior(fingerprint))
    }

    @Test fun latestInjectedMemoryIsReturnedOnceThenTheRealSampleIsUsed() {
        val injected = real.copy(lowMemory = true)
        assertSame(real, hooks.effectiveMemorySample(real))
        hooks.injectMemorySample(real.copy(availableBytes = 0))
        hooks.injectMemorySample(injected)
        assertSame(injected, hooks.effectiveMemorySample(real))
        assertSame(real, hooks.effectiveMemorySample(real))
    }

    @Test fun pauseAndResumeToggleDispatchAndResetClearsAllPendingHooks() {
        assertFalse(hooks.dispatchPaused())
        hooks.pauseDispatch()
        assertTrue(hooks.dispatchPaused())
        hooks.resumeDispatch()
        assertFalse(hooks.dispatchPaused())
        hooks.pauseDispatch()
        hooks.pauseAfterCommittedResults(1)
        hooks.killOnce(fingerprint)
        hooks.killAlways(different.first())
        hooks.injectMemorySample(real.copy(lowMemory = true))
        hooks.reset()
        assertFalse(hooks.dispatchPaused())
        hooks.noteCommitted()
        assertFalse(hooks.dispatchPaused())
        assertEquals(DEBUG_NONE, hooks.debugBehavior(fingerprint))
        assertEquals(DEBUG_NONE, hooks.debugBehavior(different.first()))
        assertSame(real, hooks.effectiveMemorySample(real))
    }
}
