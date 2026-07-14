package com.androidagent.app.agent

sealed interface WaitResult<out T> {
    data class Satisfied<T>(val value: T, val elapsedMillis: Long) : WaitResult<T>
    data class TimedOut(val elapsedMillis: Long, val reason: String) : WaitResult<Nothing>
}

object WaitEngine {
    /** A plain Wait action is a bounded delay followed by one observation. */
    suspend fun waitForDuration(
        milliseconds: Long,
        observe: suspend () -> Observation,
    ): WaitResult<Observation> {
        require(milliseconds >= 0)
        kotlinx.coroutines.delay(milliseconds)
        return WaitResult.Satisfied(observe(), milliseconds)
    }

    suspend fun waitForScreenChange(
        before: Observation,
        timeoutMillis: Long = 3_000L,
        pollMillis: Long = 120L,
        observe: suspend () -> Observation,
    ): WaitResult<Observation> = waitUntil(timeoutMillis, pollMillis, "screen did not change", observe) {
        it.observationId != before.observationId
    }

    suspend fun waitForPackage(
        packageName: String,
        timeoutMillis: Long = 12_000L,
        pollMillis: Long = 150L,
        observe: suspend () -> Observation,
    ): WaitResult<Observation> = waitUntil(timeoutMillis, pollMillis, "package did not become foreground", observe) {
        it.packageName == packageName
    }

    suspend fun waitForText(
        text: String,
        timeoutMillis: Long = 3_000L,
        pollMillis: Long = 120L,
        observe: suspend () -> Observation,
    ): WaitResult<Observation> = waitUntil(timeoutMillis, pollMillis, "text was not observed", observe) {
        it.nodes.any { node -> node.visible && (node.text == text || node.description == text) }
    }

    suspend fun waitForElement(
        selector: ElementSelector,
        timeoutMillis: Long = 3_000L,
        pollMillis: Long = 120L,
        observe: suspend () -> Observation,
    ): WaitResult<Observation> = waitUntil(timeoutMillis, pollMillis, "element was not observed", observe) {
        NodeSelector.matchingNodes(it, selector).size == 1
    }

    suspend fun waitForScreenStable(
        timeoutMillis: Long = 3_000L,
        pollMillis: Long = 120L,
        requiredSamples: Int = 2,
        observe: suspend () -> Observation,
    ): WaitResult<Observation> {
        val started = System.currentTimeMillis()
        var latest = observe()
        var stable = 0
        while (System.currentTimeMillis() - started <= timeoutMillis) {
            kotlinx.coroutines.delay(pollMillis)
            val next = observe()
            if (next.observationId == latest.observationId) stable++ else stable = 0
            latest = next
            if (stable >= requiredSamples) return WaitResult.Satisfied(next, System.currentTimeMillis() - started)
        }
        return WaitResult.TimedOut(System.currentTimeMillis() - started, "screen did not stabilize")
    }

    private suspend fun <T> waitUntil(
        timeoutMillis: Long,
        pollMillis: Long,
        reason: String,
        observe: suspend () -> T,
        predicate: (T) -> Boolean,
    ): WaitResult<T> {
        require(timeoutMillis > 0 && pollMillis > 0)
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started <= timeoutMillis) {
            val value = observe()
            if (predicate(value)) return WaitResult.Satisfied(value, System.currentTimeMillis() - started)
            kotlinx.coroutines.delay(pollMillis)
        }
        return WaitResult.TimedOut(System.currentTimeMillis() - started, reason)
    }
}
