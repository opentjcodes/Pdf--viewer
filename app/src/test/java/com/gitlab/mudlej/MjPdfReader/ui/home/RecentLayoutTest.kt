// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RecentLayoutTest {
    private val layout = RecentLayout()

    @Test fun renderWithoutViewValidatesBeforePublishing() = runBlocking {
        val calls = mutableListOf<String>()
        val result = layout.render(
            validate = { calls += "validate:$it"; true },
            block = { calls += "submit"; 42 },
            onCommitted = { calls += "publish:$it" },
        )

        assertEquals(42, result)
        assertEquals(listOf("submit", "validate:42", "publish:42"), calls)
    }

    @Test fun rejectedRenderCanRetryWithoutPublishing() = runBlocking {
        val committed = mutableListOf<Int>()
        assertNull(layout.render(validate = { false }, block = { 1 }, onCommitted = { committed += it }))
        assertEquals(2, layout.render(validate = { true }, block = { 2 }, onCommitted = { committed += it }))
        assertEquals(listOf(2), committed)
    }

    @Test fun submissionFailurePropagatesAndAllowsAnotherRender() = runBlocking {
        val failure = IllegalStateException("submission failed")
        try {
            layout.render<Int>(
                validate = { fail("Validation must not run"); true },
                block = { throw failure },
                onCommitted = { fail("Failed submission must not publish") },
            )
            fail("Submission failure must propagate")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertEquals(3, layout.render(validate = { true }, block = { 3 }, onCommitted = {}))
    }

    @Test fun publicationFailurePropagatesAndAllowsAnotherRender() = runBlocking {
        val failure = IllegalStateException("publication failed")
        try {
            layout.render(validate = { true }, block = { 1 }, onCommitted = { throw failure })
            fail("Publication failure must propagate")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertEquals(4, layout.render(validate = { true }, block = { 4 }, onCommitted = {}))
    }

    @Test fun cancelledSubmissionDoesNotPublish() = runBlocking {
        val blocked = CompletableDeferred<Int>()
        val committed = mutableListOf<Int>()
        val rendering = async(start = CoroutineStart.UNDISPATCHED) {
            layout.render(validate = { true }, block = { blocked.await() }, onCommitted = { committed += it })
        }
        rendering.cancelAndJoin()

        assertEquals(5, layout.render(validate = { true }, block = { 5 }, onCommitted = { committed += it }))
        assertEquals(listOf(5), committed)
    }

    @Test fun successfulNullResultStillPublishes() = runBlocking {
        val committed = mutableListOf<String?>()
        val result = layout.render<String?>(
            validate = { it == null },
            block = { null },
            onCommitted = { committed += it },
        )

        assertNull(result)
        assertEquals(listOf<String?>(null), committed)
    }
}
