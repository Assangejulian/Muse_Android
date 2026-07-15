package com.androidagent.app.accessibility

import com.androidagent.app.agent.RuntimeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

internal data class RunResultCoordinatorStats(
    val results: Int,
    val stopCauses: Int,
    val waiters: Int,
    val tombstones: Int,
)

/** Run-scoped result rendezvous with bounded late-result suppression. */
internal class RunResultCoordinator(
    private val maxRetainedResults: Int = 20,
    private val maxRetainedTombstones: Int = 40,
    private val maxRetainedWaiters: Int = 40,
) {
    private val results = linkedMapOf<String, RuntimeResult>()
    private val stopCauses = linkedMapOf<String, AgentStopCause>()
    private val waiters = linkedMapOf<String, CompletableDeferred<RuntimeResult>>()
    private val tombstones = linkedSetOf<String>()
    private val resultOrder = ArrayDeque<String>()
    private val tombstoneOrder = ArrayDeque<String>()
    private val waiterOrder = ArrayDeque<String>()

    @Synchronized
    fun registerRun(runId: String) {
        results.remove(runId)
        stopCauses.remove(runId)
        tombstones.remove(runId)
        resultOrder.remove(runId)
        tombstoneOrder.remove(runId)
        waiters.remove(runId)?.cancel(CancellationException("run id was re-registered"))
        waiterOrder.remove(runId)
    }

    /** First explicit terminal cause wins for the lifetime of one run. */
    @Synchronized
    fun recordStopCause(runId: String, cause: AgentStopCause): AgentStopCause {
        val existing = stopCauses[runId]
        if (existing != null) return existing
        if (runId !in tombstones) stopCauses[runId] = cause
        return cause
    }

    @Synchronized
    fun stopCauseFor(runId: String): AgentStopCause? = stopCauses[runId]

    @Synchronized
    fun resultForRun(runId: String): RuntimeResult? = results[runId]

    @Synchronized
    fun storeResult(runId: String, result: RuntimeResult): Boolean {
        if (runId in tombstones) {
            dropTombstonedRun(runId)
            return false
        }
        if (runId in results) return false
        results[runId] = result
        resultOrder.remove(runId)
        resultOrder.addLast(runId)
        waiters[runId]?.complete(result)
        trimResults()
        trimWaiters()
        return true
    }

    suspend fun awaitAndConsumeResult(runId: String, timeoutMillis: Long): RuntimeResult? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val waiter = synchronized(this) {
            if (runId in results) return consumeResult(runId)
            if (runId in tombstones) return null
            waiters.getOrPut(runId) {
                waiterOrder.addLast(runId)
                CompletableDeferred()
            }.also { trimWaiters() }
        }
        val completed = try {
            withTimeoutOrNull(timeoutMillis) { waiter.await() }
        } catch (cancelled: CancellationException) {
            // A tombstone cancels the internal waiter.  Do not leak that
            // implementation detail as a normal Worker failure, while still
            // propagating cancellation requested by the caller itself.
            if (!currentCoroutineContext().isActive) throw cancelled
            null
        }
        if (completed == null) return null
        return consumeResult(runId)
    }

    @Synchronized
    fun consumeResult(runId: String): RuntimeResult? {
        val result = results.remove(runId)
        resultOrder.remove(runId)
        stopCauses.remove(runId)
        waiters.remove(runId)
        waiterOrder.remove(runId)
        return result
    }

    @Synchronized
    fun removeResult(runId: String) {
        results.remove(runId)
        resultOrder.remove(runId)
        stopCauses.remove(runId)
        waiters.remove(runId)?.cancel(CancellationException("run result removed"))
        waiterOrder.remove(runId)
        tombstones.remove(runId)
        tombstoneOrder.remove(runId)
    }

    @Synchronized
    fun registerLateResultTombstone(runId: String) {
        results.remove(runId)
        resultOrder.remove(runId)
        waiters.remove(runId)?.cancel(CancellationException("late result tombstoned"))
        waiterOrder.remove(runId)
        if (tombstones.add(runId)) tombstoneOrder.addLast(runId)
        while (tombstoneOrder.size > maxRetainedTombstones) {
            val evicted = tombstoneOrder.removeFirst()
            tombstones.remove(evicted)
            stopCauses.remove(evicted)
        }
    }

    @Synchronized
    fun stats(): RunResultCoordinatorStats = RunResultCoordinatorStats(
        results = results.size,
        stopCauses = stopCauses.size,
        waiters = waiters.size,
        tombstones = tombstones.size,
    )

    @Synchronized
    private fun trimResults() {
        while (resultOrder.size > maxRetainedResults) {
            val evicted = resultOrder.removeFirst()
            results.remove(evicted)
            stopCauses.remove(evicted)
            waiters.remove(evicted)?.cancel(CancellationException("retained result limit reached"))
            waiterOrder.remove(evicted)
        }
    }

    @Synchronized
    private fun trimWaiters() {
        while (waiterOrder.size > maxRetainedWaiters) {
            val evicted = waiterOrder.removeFirst()
            if (evicted in results || evicted in tombstones) continue
            waiters.remove(evicted)?.cancel(CancellationException("retained waiter limit reached"))
            stopCauses.remove(evicted)
        }
    }

    @Synchronized
    private fun dropTombstonedRun(runId: String) {
        results.remove(runId)
        resultOrder.remove(runId)
        stopCauses.remove(runId)
        waiters.remove(runId)?.cancel(CancellationException("late result discarded"))
        waiterOrder.remove(runId)
    }
}
