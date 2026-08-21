package io.github.lamppkk.xanhbrowser.sync

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes native runtime use and close as one state transition.
 *
 * An operation that entered while open is allowed to finish, including nested
 * runtime calls. Every close caller waits for that operation and returns only
 * after cleanup has completed. A cleanup failure permanently fails closed so
 * a second Application Services profile cannot register over uncertain state.
 */
internal class RuntimeLifecycleGate {
    private enum class State { OPEN, CLOSING, CLOSED, FAILED }

    private val lock = ReentrantLock()
    private var state = State.OPEN
    private var closeFailure: Throwable? = null

    fun <T> withOpen(block: () -> T): T = lock.withLock {
        check(state == State.OPEN) { "Mozilla Sync runtime is closed" }
        block()
    }

    fun close(cleanup: () -> Throwable?) = lock.withLock {
        when (state) {
            State.CLOSED -> return
            State.FAILED -> throw checkNotNull(closeFailure)
            State.CLOSING -> error("Mozilla Sync runtime close is re-entrant")
            State.OPEN -> state = State.CLOSING
        }
        val failure = try {
            cleanup()
        } catch (error: Throwable) {
            error
        }
        if (failure == null) {
            state = State.CLOSED
        } else {
            closeFailure = failure
            state = State.FAILED
            throw failure
        }
    }
}
