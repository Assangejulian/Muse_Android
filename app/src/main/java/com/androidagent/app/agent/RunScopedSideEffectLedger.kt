package com.androidagent.app.agent

import java.util.ArrayDeque

/**
 * The result of a mutating dispatch is independent from the plan that happened
 * to request it.  This state intentionally lives outside RunLedger so a
 * replan, predicate-id change, or window recreation cannot unlock an unknown
 * side effect.
 */
enum class SideEffectResultState {
    UNKNOWN_SIDE_EFFECT,
    CONFIRMED,
}

data class SideEffectIdentity(
    val actionType: String,
    val targetPackage: String?,
    val targetCrossWindowStructureKey: String?,
    val irreversiblePayloadDigest: String? = null,
    val desiredState: String? = null,
)

data class SideEffectRecord(
    val identity: SideEffectIdentity,
    val state: SideEffectResultState,
    val dispatchSequence: Long,
    val dispatchObservationId: String,
    val preDispatchSnapshotId: String? = null,
)

data class SideEffectCheck(
    val allowed: Boolean,
    val reason: String? = null,
    val existing: SideEffectRecord? = null,
)

/** In-memory, run-scoped idempotence ledger. It is cleared when the run ends. */
class RunScopedSideEffectLedger(val runId: String? = null) {
    private val records = linkedMapOf<SideEffectIdentity, SideEffectRecord>()
    private var sequence = 0L

    @Synchronized
    fun nextDispatchSequence(): Long = ++sequence

    @Synchronized
    fun check(identity: SideEffectIdentity): SideEffectCheck {
        val existing = records[identity]
        return if (existing == null) {
            SideEffectCheck(true)
        } else {
            SideEffectCheck(
                allowed = false,
                reason = "side effect identity was already dispatched in this run",
                existing = existing,
            )
        }
    }

    /** Records that Android accepted a dispatch, while its outcome is unknown. */
    @Synchronized
    fun markUnknown(
        identity: SideEffectIdentity,
        dispatchObservationId: String,
        dispatchSequence: Long = nextDispatchSequence(),
        preDispatchSnapshotId: String? = null,
    ): Boolean {
        if (records.containsKey(identity)) return false
        records[identity] = SideEffectRecord(
            identity = identity,
            state = SideEffectResultState.UNKNOWN_SIDE_EFFECT,
            dispatchSequence = dispatchSequence,
            dispatchObservationId = dispatchObservationId,
            preDispatchSnapshotId = preDispatchSnapshotId,
        )
        return true
    }

    /** A confirmed settle or deterministic local proof closes the identity. */
    @Synchronized
    fun markConfirmed(
        identity: SideEffectIdentity,
        dispatchObservationId: String = "",
        dispatchSequence: Long = nextDispatchSequence(),
        preDispatchSnapshotId: String? = null,
    ): Boolean {
        val existing = records[identity]
        if (existing != null) {
            records[identity] = existing.copy(state = SideEffectResultState.CONFIRMED)
            return true
        }
        records[identity] = SideEffectRecord(
            identity = identity,
            state = SideEffectResultState.CONFIRMED,
            dispatchSequence = dispatchSequence,
            dispatchObservationId = dispatchObservationId,
            preDispatchSnapshotId = preDispatchSnapshotId,
        )
        return true
    }

    /** Failed-before-dispatch actions intentionally do not create a record. */
    @Synchronized
    fun markFailed(@Suppress("UNUSED_PARAMETER") identity: SideEffectIdentity): Unit = Unit

    @Synchronized
    fun recordFor(identity: SideEffectIdentity): SideEffectRecord? = records[identity]

    @Synchronized
    fun records(): List<SideEffectRecord> = records.values.toList()

    @Synchronized
    fun clear() {
        records.clear()
        sequence = 0L
    }
}

/**
 * Safe, in-memory evidence captured immediately before a mutating dispatch.
 * It is never sent to a model or persisted in trace storage.  Only the last
 * few snapshots are retained so a replan can prove a real false-to-true
 * transition without retaining a full screen history.
 */
data class PreDispatchNodeSnapshot(
    val id: Int,
    val packageName: String,
    val viewIdResourceName: String?,
    val className: String,
    val treePath: List<Int>?,
    val bounds: String?,
    val withinWindowStableKey: String?,
    val crossWindowStructureKey: String?,
    val visible: Boolean,
    val enabled: Boolean,
    val checked: Boolean?,
    val selected: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val password: Boolean,
    val safeText: String?,
    val safeDescription: String?,
    val textHash: String,
    val descriptionHash: String,
    val windowId: Int?,
)

data class PreDispatchEvidenceSnapshot(
    val snapshotId: String,
    val packageName: String,
    val imeVisible: Boolean,
    val windowIds: Set<Int>,
    val windowPackages: Map<Int, String>,
    val nodes: List<PreDispatchNodeSnapshot>,
    val sideEffectIdentity: SideEffectIdentity,
    val dispatchObservationId: String,
    val dispatchSequence: Long,
) {
    fun toObservation(): Observation = Observation(
        packageName = packageName,
        nodes = nodes.map { node ->
            UiNodeSnapshot(
                id = node.id,
                text = node.safeText.orEmpty(),
                description = node.safeDescription.orEmpty(),
                className = node.className,
                clickable = false,
                editable = node.editable,
                bounds = node.bounds.orEmpty(),
                withinWindowStableKey = node.withinWindowStableKey.orEmpty(),
                crossWindowStructureKey = node.crossWindowStructureKey.orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                treePath = node.treePath,
                enabled = node.enabled,
                focused = node.focused,
                checked = node.checked,
                selected = node.selected,
                packageName = node.packageName,
                visible = node.visible,
                password = node.password,
                windowId = node.windowId,
            )
        },
        imeVisible = imeVisible,
        windowIds = windowIds,
        windowPackages = windowPackages,
    )
}

class PreDispatchEvidenceStore(private val maxSnapshots: Int = 8) {
    private val snapshots = linkedMapOf<String, PreDispatchEvidenceSnapshot>()
    private val order = ArrayDeque<String>()

    @Synchronized
    fun capture(
        observation: Observation,
        identity: SideEffectIdentity,
        dispatchSequence: Long,
    ): PreDispatchEvidenceSnapshot {
        val safeObservation = PrivacyGuard.sanitize(observation)
        val snapshotId = TraceSanitizer.digest("$identity|$dispatchSequence|${safeObservation.observationId}")
        val safeNodes = safeObservation.nodes.map { node ->
            val password = node.password
            val safeText = if (password) null else node.text
            val safeDescription = if (password) null else node.description
            PreDispatchNodeSnapshot(
                id = node.id,
                packageName = node.packageName,
                viewIdResourceName = node.viewId.takeIf(String::isNotBlank),
                className = node.className,
                treePath = node.treePath,
                bounds = node.bounds.takeIf(String::isNotBlank),
                withinWindowStableKey = node.withinWindowStableKey.takeIf(String::isNotBlank),
                crossWindowStructureKey = node.crossWindowStructureKey.takeIf(String::isNotBlank),
                visible = node.visible,
                enabled = node.enabled,
                checked = node.checked,
                selected = node.selected,
                editable = node.editable,
                focused = node.focused,
                password = password,
                safeText = safeText,
                safeDescription = safeDescription,
                textHash = TraceSanitizer.digest(safeText.orEmpty()),
                descriptionHash = TraceSanitizer.digest(safeDescription.orEmpty()),
                windowId = node.windowId,
            )
        }
        val snapshot = PreDispatchEvidenceSnapshot(
            snapshotId = snapshotId,
            packageName = safeObservation.packageName,
            imeVisible = safeObservation.imeVisible,
            windowIds = safeObservation.windowIds,
            windowPackages = safeObservation.windowPackages,
            nodes = safeNodes,
            sideEffectIdentity = identity,
            dispatchObservationId = observation.observationId,
            dispatchSequence = dispatchSequence,
        )
        snapshots[snapshotId] = snapshot
        order.remove(snapshotId)
        order.addLast(snapshotId)
        while (order.size > maxSnapshots) snapshots.remove(order.removeFirst())
        return snapshot
    }

    @Synchronized
    fun get(snapshotId: String?): PreDispatchEvidenceSnapshot? = snapshotId?.let(snapshots::get)

    @Synchronized
    fun latestFor(identity: SideEffectIdentity): PreDispatchEvidenceSnapshot? =
        order.toList().asReversed().firstNotNullOfOrNull { id -> snapshots[id]?.takeIf { it.sideEffectIdentity == identity } }

    @Synchronized
    fun remove(snapshotId: String?) {
        if (snapshotId == null) return
        snapshots.remove(snapshotId)
        order.remove(snapshotId)
    }

    @Synchronized
    fun all(): List<PreDispatchEvidenceSnapshot> = order.mapNotNull(snapshots::get)

    @Synchronized
    fun clear() {
        snapshots.clear()
        order.clear()
    }
}

object SideEffectIdentityFactory {
    fun create(
        action: AgentAction,
        observation: Observation,
        screenshotFingerprint: String? = null,
    ): SideEffectIdentity? {
        val target = targetFor(action, observation)
        val targetPackage = target?.packageName?.takeIf(String::isNotBlank) ?: observation.packageName.takeIf(String::isNotBlank)
        val targetKey = target?.let(::crossWindowKey)
        return when (action) {
            is AgentAction.ClickNode -> SideEffectIdentity("CLICK_NODE", targetPackage, targetKey)
            is AgentAction.ClickText -> SideEffectIdentity("CLICK_TEXT", targetPackage, targetKey)
            is AgentAction.EnsureToggle -> SideEffectIdentity(
                actionType = "ENSURE_TOGGLE",
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = targetKey,
                desiredState = action.desired.toString(),
            )
            is AgentAction.InputText -> SideEffectIdentity(
                actionType = "INPUT_TEXT",
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = targetKey,
                irreversiblePayloadDigest = TraceSanitizer.digest("${action.mode}|${action.submit}|${action.text.length}|${TraceSanitizer.digest(action.text)}"),
                desiredState = action.mode.name,
            )
            is AgentAction.SubmitInput -> SideEffectIdentity("SUBMIT_INPUT", targetPackage, targetKey)
            is AgentAction.LaunchApp -> SideEffectIdentity("LAUNCH_APP", action.packageName, null)
            is AgentAction.TapPoint -> SideEffectIdentity(
                actionType = "TAP_POINT",
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = null,
                irreversiblePayloadDigest = TraceSanitizer.digest(
                    "${screenshotFingerprint.orEmpty()}|${action.x / 24}|${action.y / 24}",
                ),
            )
            is AgentAction.Swipe -> SideEffectIdentity(
                "SWIPE",
                targetPackage,
                null,
                irreversiblePayloadDigest = TraceSanitizer.digest(action.direction.trim().lowercase()),
            )
            AgentAction.Back -> SideEffectIdentity("BACK", targetPackage, null)
            AgentAction.Home -> SideEffectIdentity("HOME", targetPackage, null)
            is AgentAction.BindPredicate,
            is AgentAction.Wait,
            is AgentAction.Finish,
            is AgentAction.Fail,
            -> null
        }
    }

    private fun targetFor(action: AgentAction, observation: Observation): UiNodeSnapshot? = when (action) {
        is AgentAction.ClickNode -> NodeSelector.resolve(observation, action.nodeId, action.selector)
        is AgentAction.ClickText -> observation.nodes.filter { node ->
            node.visible && node.enabled && !node.editable &&
                (node.text.equals(action.text, true) || node.description.equals(action.text, true))
        }.singleOrNull()
        is AgentAction.EnsureToggle -> NodeSelector.resolve(observation, action.nodeId, action.selector)
        is AgentAction.InputText -> NodeSelector.resolve(observation, action.nodeId, action.target)
        is AgentAction.SubmitInput -> NodeSelector.resolve(observation, action.nodeId, action.target)
        else -> null
    }

    private fun crossWindowKey(node: UiNodeSnapshot): String = node.crossWindowStructureKey.ifBlank {
        NodeIdentityKeys.crossWindowStructureKey(
            node.packageName,
            node.viewId,
            node.className,
            node.treePath.orEmpty(),
        )
    }
}
