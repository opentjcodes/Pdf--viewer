// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

internal class RecentLayout {
    private var recyclerView: RecyclerView? = null
    private var active = false
    private var accessInvalidated = false
    private var pendingLayout: LayoutWait? = null

    fun attach(view: RecyclerView) {
        recyclerView = view
    }

    suspend fun <T> render(
        validate: (T) -> Boolean,
        block: suspend () -> T,
        onCommitted: (T) -> Unit,
    ): T? {
        val attempt = Attempt(recyclerView)
        active = true
        accessInvalidated = false
        attempt.suppress()
        try {
            val result = block()
            if (!validateAfterLayout(attempt) { validate(result) }) {
                attempt.holdForRetry()
                return null
            }
            onCommitted(result)
            attempt.complete()
            return result
        } finally {
            active = false
            pendingLayout = null
            attempt.finish()
        }
    }

    fun invalidateAccess() {
        if (!active) return
        accessInvalidated = true
        recyclerView?.takeIf { it.isAttachedToWindow }?.suppressLayout(true)
        pendingLayout?.complete()
    }

    private suspend fun validateAfterLayout(attempt: Attempt, validate: () -> Boolean): Boolean {
        accessInvalidated = false
        attempt.releaseForLayout()
        attempt.view?.let { awaitLayout(it) }
        return !accessInvalidated && validate()
    }

    private suspend fun awaitLayout(view: RecyclerView) {
        val owner = view.findViewTreeLifecycleOwner()
        if (!view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE ||
            owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == false
        ) return

        suspendCancellableCoroutine { continuation ->
            val wait = LayoutWait(view, owner, continuation)
            wait.subscribe()
            pendingLayout = wait
            continuation.invokeOnCancellation { wait.unsubscribe() }

            if (!view.isAttachedToWindow || view.windowVisibility != View.VISIBLE ||
                owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == false
            ) {
                wait.complete()
            } else {
                view.requestLayout()
            }
        }
    }

    private class Attempt(val view: RecyclerView?) {
        private val canSuppress = view?.isAttachedToWindow == true
        private var keepSuppressed = false
        private var completedNormally = false

        fun suppress() {
            if (canSuppress) view?.suppressLayout(true)
        }

        fun releaseForLayout() {
            if (canSuppress) view?.suppressLayout(false)
        }

        fun holdForRetry() {
            keepSuppressed = view?.isAttachedToWindow == true
            if (keepSuppressed) view?.suppressLayout(true)
            completedNormally = true
        }

        fun complete() {
            completedNormally = true
        }

        fun finish() {
            if (!completedNormally || !keepSuppressed) view?.suppressLayout(false)
        }
    }

    private inner class LayoutWait(
        private val view: RecyclerView,
        private val owner: LifecycleOwner?,
        private val continuation: CancellableContinuation<Unit>,
    ) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener, LifecycleEventObserver {
        fun subscribe() {
            view.addOnAttachStateChangeListener(this)
            view.addOnLayoutChangeListener(this)
            owner?.lifecycle?.addObserver(this)
        }

        fun unsubscribe() {
            view.removeOnAttachStateChangeListener(this)
            view.removeOnLayoutChangeListener(this)
            owner?.lifecycle?.removeObserver(this)
            if (pendingLayout === this) pendingLayout = null
        }

        fun complete() {
            unsubscribe()
            if (continuation.isActive) continuation.resume(Unit)
        }

        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) = complete()

        override fun onLayoutChange(
            view: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
        ) = complete()

        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) complete()
        }
    }
}
