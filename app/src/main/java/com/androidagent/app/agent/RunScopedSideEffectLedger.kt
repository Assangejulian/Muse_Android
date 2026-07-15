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

enum class SideEffectFamily {
    ACTIVATE_CONTROL,
    SET_BOOLEAN_CONTROL,
    INPUT_VALUE,
    SUBMIT_VALUE,
    LAUNCH_APP,
    NAVIGATE_BACK,
    NAVIGATE_HOME,
    SCROLL,
    POINT_ACTIVATION,
}

enum class SideEffectTargetKind { OTHER, BOOLEAN_CONTROL }

data class SideEffectIdentity(
    /** Canonical family name retained under the historical actionType field. */
    val actionType: String,
    val targetPackage: String?,
    val targetCrossWindowStructureKey: String?,
    val irreversiblePayloadDigest: String? = null,
    val desiredState: String? = null,
    val family: SideEffectFamily = familyFor(actionType),
    val targetKind: SideEffectTargetKind = SideEffectTargetKind.OTHER,
    val submitRequested: Boolean = false,
    /** Local observation only; used to decide whether a different toggle target is already satisfied. */
    val observedChecked: Boolean? = null,
    /** Explicit once-only policy; false for ordinary repeatable actions. */
    val onceOnly: Boolean = false,
) {
    companion object {
        fun familyFor(actionType: String): SideEffectFamily = when (actionType.trim().uppercase()) {
            "CLICK_NODE", "CLICK_TEXT", "ACTIVATE_CONTROL" -> SideEffectFamily.ACTIVATE_CONTROL
            "ENSURE_TOGGLE", "SET_BOOLEAN_CONTROL" -> SideEffectFamily.SET_BOOLEAN_CONTROL
            "INPUT_TEXT", "INPUT_VALUE" -> SideEffectFamily.INPUT_VALUE
            "SUBMIT_INPUT", "SUBMIT_VALUE" -> SideEffectFamily.SUBMIT_VALUE
            "LAUNCH_APP" -> SideEffectFamily.LAUNCH_APP
            "BACK", "NAVIGATE_BACK" -> SideEffectFamily.NAVIGATE_BACK
            "HOME", "NAVIGATE_HOME" -> SideEffectFamily.NAVIGATE_HOME
            "SWIPE", "SCROLL" -> SideEffectFamily.SCROLL
            "TAP_POINT", "POINT_ACTIVATION" -> SideEffectFamily.POINT_ACTIVATION
            else -> SideEffectFamily.ACTIVATE_CONTROL
        }
    }
}

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
        if (identity.onceOnly && existing?.state == SideEffectResultState.CONFIRMED) {
            return SideEffectCheck(
                allowed = false,
                reason = "once-only side effect was already confirmed",
                existing = existing,
            )
        }
        val confirmedSubmit = if (identity.family == SideEffectFamily.SUBMIT_VALUE) {
            records.values.firstOrNull { record ->
                record.state == SideEffectResultState.CONFIRMED &&
                    record.identity.family == SideEffectFamily.INPUT_VALUE &&
                    record.identity.submitRequested &&
                    record.identity.onceOnly &&
                    sameTarget(record.identity, identity)
            }
        } else null
        if (confirmedSubmit != null) {
            return SideEffectCheck(
                allowed = false,
                reason = "a confirmed submit-on-input already covers this target",
                existing = confirmedSubmit,
            )
        }
        val unknown = records.values.firstOrNull { record ->
            if (record.state != SideEffectResultState.UNKNOWN_SIDE_EFFECT) return@firstOrNull false
            val recorded = record.identity
            when {
                recorded == identity -> true
                identity.family == SideEffectFamily.SET_BOOLEAN_CONTROL &&
                    recorded.family == SideEffectFamily.SET_BOOLEAN_CONTROL &&
                    sameTarget(recorded, identity) -> {
                    val differentDesiredState = recorded.desiredState != identity.desiredState
                    if (!differentDesiredState) {
                        true
                    } else {
                        // A different toggle transition is safe only when the
                        // current local snapshot proves the requested state is
                        // already present, or proves the unknown transition's
                        // desired state before starting a new transition.
                        val requested = identity.desiredState?.toBooleanStrictOrNull()
                        val recordedDesired = recorded.desiredState?.toBooleanStrictOrNull()
                        !(identity.observedChecked == requested || identity.observedChecked == recordedDesired)
                    }
                }
                identity.family == SideEffectFamily.ACTIVATE_CONTROL &&
                    recorded.family == SideEffectFamily.ACTIVATE_CONTROL &&
                    sameTarget(recorded, identity) -> true
                identity.family == SideEffectFamily.ACTIVATE_CONTROL &&
                    identity.targetKind == SideEffectTargetKind.BOOLEAN_CONTROL &&
                    recorded.family == SideEffectFamily.SET_BOOLEAN_CONTROL -> sameTarget(recorded, identity)
                identity.family == SideEffectFamily.SET_BOOLEAN_CONTROL &&
                    identity.targetKind == SideEffectTargetKind.BOOLEAN_CONTROL &&
                    recorded.family == SideEffectFamily.ACTIVATE_CONTROL &&
                    recorded.targetKind == SideEffectTargetKind.BOOLEAN_CONTROL -> sameTarget(recorded, identity)
                identity.family == SideEffectFamily.SUBMIT_VALUE &&
                    recorded.family == SideEffectFamily.INPUT_VALUE &&
                    recorded.submitRequested -> sameTarget(recorded, identity)
                else -> false
            }
        }
        return if (unknown != null) {
            SideEffectCheck(
                allowed = false,
                reason = "unknown side effect blocks the same semantic target until locally resolved",
                existing = unknown,
            )
        } else {
            // Confirmed records remain available for diagnostics but are not a
            // run-wide hard lock. RunLedger and milestone evidence own normal
            // repeat detection for confirmed actions.
            SideEffectCheck(allowed = true, existing = existing)
        }
    }

    private fun sameTarget(first: SideEffectIdentity, second: SideEffectIdentity): Boolean =
        first.targetPackage == second.targetPackage &&
            first.targetCrossWindowStructureKey == second.targetCrossWindowStructureKey

    /** Records that Android accepted a dispatch, while its outcome is unknown. */
    @Synchronized
    fun markUnknown(
        identity: SideEffectIdentity,
        dispatchObservationId: String,
        dispatchSequence: Long = nextDispatchSequence(),
        preDispatchSnapshotId: String? = null,
    ): Boolean {
        // A confirmed identity is diagnostically retained but must not prevent a
        // later legitimate dispatch.  Only an existing UNKNOWN record is a
        // concurrent/ambiguous dispatch conflict; the pre-dispatch check already
        // rejects that case before Android is touched.
        if (records[identity]?.state == SideEffectResultState.UNKNOWN_SIDE_EFFECT) return false
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
    val isComplete: Boolean = true,
    val privacyFiltered: Boolean = false,
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
        isComplete = isComplete,
        privacyFiltered = privacyFiltered,
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
            isComplete = safeObservation.isComplete,
            privacyFiltered = safeObservation.privacyFiltered,
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
        if (action is AgentAction.ClickNode || action is AgentAction.ClickText ||
            action is AgentAction.EnsureToggle || action is AgentAction.InputText ||
            action is AgentAction.SubmitInput
        ) {
            // Never fall back to a package-only identity for an action whose
            // meaning depends on a concrete control or input target.
            if (target == null) return null
        }
        val targetPackage = target?.packageName?.takeIf(String::isNotBlank) ?: observation.packageName.takeIf(String::isNotBlank)
        val targetKey = target?.let(::crossWindowKey)
        val targetKind = target?.let { node ->
            if (TargetResolver.isBooleanControl(node)) SideEffectTargetKind.BOOLEAN_CONTROL else SideEffectTargetKind.OTHER
        } ?: SideEffectTargetKind.OTHER
        return when (action) {
            is AgentAction.ClickNode -> SideEffectIdentity(
                actionType = SideEffectFamily.ACTIVATE_CONTROL.name,
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = targetKey,
                family = SideEffectFamily.ACTIVATE_CONTROL,
                targetKind = targetKind,
                observedChecked = target?.checked,
            )
            is AgentAction.ClickText -> SideEffectIdentity(
                actionType = SideEffectFamily.ACTIVATE_CONTROL.name,
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = targetKey,
                family = SideEffectFamily.ACTIVATE_CONTROL,
                targetKind = targetKind,
                observedChecked = target?.checked,
            )
            is AgentAction.EnsureToggle -> SideEffectIdentity(
                actionType = SideEffectFamily.SET_BOOLEAN_CONTROL.name,
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = targetKey,
                desiredState = action.desired.toString(),
                family = SideEffectFamily.SET_BOOLEAN_CONTROL,
                targetKind = SideEffectTargetKind.BOOLEAN_CONTROL,
                observedChecked = target?.checked,
            )
            is AgentAction.InputText -> SideEffectIdentity(
                actionType = SideEffectFamily.INPUT_VALUE.name,
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = targetKey,
                irreversiblePayloadDigest = TraceSanitizer.digest("${action.mode}|${action.submit}|${action.text.length}|${TraceSanitizer.digest(action.text)}"),
                desiredState = "${action.mode.name}|submit=${action.submit}",
                family = SideEffectFamily.INPUT_VALUE,
                submitRequested = action.submit,
                onceOnly = action.submit,
            )
            is AgentAction.SubmitInput -> SideEffectIdentity(
                actionType = SideEffectFamily.SUBMIT_VALUE.name,
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = targetKey,
                family = SideEffectFamily.SUBMIT_VALUE,
            )
            is AgentAction.LaunchApp -> SideEffectIdentity(
                actionType = SideEffectFamily.LAUNCH_APP.name,
                targetPackage = action.packageName,
                targetCrossWindowStructureKey = null,
                family = SideEffectFamily.LAUNCH_APP,
            )
            is AgentAction.TapPoint -> SideEffectIdentity(
                actionType = SideEffectFamily.POINT_ACTIVATION.name,
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = null,
                irreversiblePayloadDigest = TraceSanitizer.digest(
                    "${screenshotFingerprint.orEmpty()}|${action.x / 24}|${action.y / 24}",
                ),
                family = SideEffectFamily.POINT_ACTIVATION,
            )
            is AgentAction.Swipe -> SideEffectIdentity(
                actionType = SideEffectFamily.SCROLL.name,
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = null,
                irreversiblePayloadDigest = TraceSanitizer.digest(action.direction.trim().lowercase()),
                family = SideEffectFamily.SCROLL,
            )
            AgentAction.Back -> SideEffectIdentity(
                actionType = SideEffectFamily.NAVIGATE_BACK.name,
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = null,
                family = SideEffectFamily.NAVIGATE_BACK,
            )
            AgentAction.Home -> SideEffectIdentity(
                actionType = SideEffectFamily.NAVIGATE_HOME.name,
                targetPackage = targetPackage,
                targetCrossWindowStructureKey = null,
                family = SideEffectFamily.NAVIGATE_HOME,
            )
            is AgentAction.BindPredicate,
            is AgentAction.Wait,
            is AgentAction.Finish,
            is AgentAction.Fail,
            -> null
        }
    }

    private fun targetFor(action: AgentAction, observation: Observation): UiNodeSnapshot? = TargetResolver.resolve(action, observation)

    private fun crossWindowKey(node: UiNodeSnapshot): String = node.crossWindowStructureKey.ifBlank {
        NodeIdentityKeys.crossWindowStructureKey(
            node.packageName,
            node.viewId,
            node.className,
            node.treePath.orEmpty(),
        )
    }
}
