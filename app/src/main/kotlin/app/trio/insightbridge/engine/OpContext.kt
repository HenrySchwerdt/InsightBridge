package app.trio.insightbridge.engine

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Carries the dispatcher's time budget down into the backend so connect / mode check / command timeouts
 * are shared instead of nested (a dosing op must finish inside what the phone waits for).
 *
 * [late] is invoked by a backend when a command that already timed out here still gets its real reply later;
 * the dispatcher uses it to replace the uncertain outcome in the journal.
 */
class OpContext(
    val deadlineMs: Long,
    private val clock: () -> Long,
    val late: (Result<Any?>) -> Unit = {}
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<OpContext>

    fun remainingMs(): Long = deadlineMs - clock()
}

/** Remaining budget of the current operation, or [default] if there is none (e.g. background refresh). */
suspend fun opRemainingMs(default: Long): Long {
    val ctx = coroutineContext[OpContext] ?: return default
    return minOf(default, ctx.remainingMs()).coerceAtLeast(0)
}

suspend fun currentOpContext(): OpContext? = coroutineContext[OpContext]
