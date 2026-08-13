package com.androidagent.app.accessibility

import com.androidagent.app.agent.UiNodeSnapshot
import kotlin.math.abs

/** When several live nodes share identity, pick the closest bounds — do not fail the click. */
object LiveNodeMatchPolicy {
    fun <T> choose(candidates: List<T>, snapshot: UiNodeSnapshot, boundsOf: (T) -> String): T? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.single()
        val target = parseBounds(snapshot.bounds) ?: return candidates.first()
        return candidates.minBy { candidate ->
            val live = parseBounds(boundsOf(candidate))
            if (live == null) Int.MAX_VALUE else distance(target, live)
        }
    }

    private fun parseBounds(raw: String): IntArray? {
        val parts = raw.split(',')
        if (parts.size != 4) return null
        return runCatching { IntArray(4) { parts[it].trim().toInt() } }.getOrNull()
    }

    private fun distance(a: IntArray, b: IntArray): Int {
        val acx = (a[0] + a[2]) / 2
        val acy = (a[1] + a[3]) / 2
        val bcx = (b[0] + b[2]) / 2
        val bcy = (b[1] + b[3]) / 2
        return abs(acx - bcx) + abs(acy - bcy)
    }
}
