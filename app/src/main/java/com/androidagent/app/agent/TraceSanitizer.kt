package com.androidagent.app.agent

import java.security.MessageDigest

/** Diagnostic metadata only. User payloads and live UI text never enter a trace. */
object TraceSanitizer {
    private val redactedPayloadKeys = setOf(
        "plan", "observation", "screen", "history", "compacttext", "screenshot",
        "chat", "chattext", "rawgoal", "evidencepayload", "observationdelta",
    )
    private val safeScalarKeys = setOf(
        "step", "id", "event", "eventtype", "status", "package", "milestone",
        "before", "after", "basedon", "judgement", "source",
        "observationid", "fingerprint", "reasoncode", "addedcount", "removedcount",
        "packagechanged", "frompackage", "topackage", "success", "ok",
    )

    fun goal(value: String): String = "[goal length=${value.length} sha256=${digest(value)}]"

    fun selectorMetadata(selector: ElementSelector?): String {
        if (selector == null) return "selector_present=false"
        return listOf(
            "selector_present=true",
            "packageName=${selector.packageName != null}",
            "viewIdResourceName=${selector.viewIdResourceName != null}",
            "text=${selector.text != null}",
            "description=${selector.description != null}",
            "className=${selector.className != null}",
            "treePath=${selector.treePath != null}",
            "bounds=${selector.bounds != null}",
        ).joinToString(",")
    }

    fun action(action: AgentAction): String = when (action) {
        is AgentAction.InputText -> "input_text(nodeId_present=${action.nodeId != null}, ${selectorMetadata(action.target)}, chars=${action.text.length}, mode=${action.mode}, submit=${action.submit})"
        is AgentAction.SubmitInput -> "submit_input(nodeId_present=${action.nodeId != null}, ${selectorMetadata(action.target)})"
        is AgentAction.ClickText -> "click_text(text_present=${action.text.isNotBlank()}, chars=${action.text.length})"
        is AgentAction.ClickNode -> "click_node(nodeId_present=true, ${selectorMetadata(action.selector)})"
        is AgentAction.EnsureToggle -> "ensure_toggle(nodeId_present=true, ${selectorMetadata(action.selector)}, desired=${action.desired})"
        is AgentAction.LaunchApp -> "launch_app(package_present=${action.packageName.isNotBlank()})"
        is AgentAction.TapPoint -> "tap_point(normalized=true)"
        is AgentAction.Swipe -> "swipe(direction=${action.direction})"
        is AgentAction.Wait -> "wait(milliseconds=${action.milliseconds})"
        is AgentAction.Finish -> "finish(reason_present=${action.reason.isNotBlank()})"
        is AgentAction.Fail -> "fail(reason_present=${action.reason.isNotBlank()})"
        AgentAction.Back -> "back"
        AgentAction.Home -> "home"
    }

    fun actionTarget(action: AgentAction, @Suppress("UNUSED_PARAMETER") observation: Observation? = null): String = when (action) {
        is AgentAction.InputText -> "input_target(nodeId_present=${action.nodeId != null}, ${selectorMetadata(action.target)}, type=editable)"
        is AgentAction.SubmitInput -> "submit_target(nodeId_present=${action.nodeId != null}, ${selectorMetadata(action.target)}, type=editable)"
        is AgentAction.ClickNode -> "click_target(nodeId_present=true, ${selectorMetadata(action.selector)}, type=node)"
        is AgentAction.ClickText -> "click_target(text_present=${action.text.isNotBlank()}, type=text)"
        is AgentAction.EnsureToggle -> "toggle_target(nodeId_present=true, ${selectorMetadata(action.selector)}, type=toggle)"
        is AgentAction.TapPoint -> "tap_target(normalized=true, type=point)"
        else -> "target_present=false"
    }

    fun observationDelta(before: Observation, after: Observation): String {
        val oldKeys = before.nodes.filter { it.visible }.map { it.stableKey.ifBlank { "${it.className}:${it.bounds}" } }.toSet()
        val newKeys = after.nodes.filter { it.visible }.map { it.stableKey.ifBlank { "${it.className}:${it.bounds}" } }.toSet()
        return "observation_delta(addedCount=${(newKeys - oldKeys).size}, removedCount=${(oldKeys - newKeys).size}, packageChanged=${before.packageName != after.packageName}, fingerprint=${after.observationId})"
    }

    fun payload(payload: Map<String, Any?>): Map<String, Any?> = payload.mapValues { (key, value) ->
        val normalized = key.lowercase().replace("_", "")
        when {
            normalized.contains("apikey") || normalized.contains("token") || normalized.contains("secret") ->
                "[redacted-secret]"
            normalized == "goal" || normalized == "rawgoal" -> goal(value?.toString().orEmpty())
            normalized in redactedPayloadKeys -> "[redacted-${normalized}]"
            value is AgentAction -> action(value)
            normalized == "action" -> textMetadata(value?.toString().orEmpty())
            value == null || value is Number || value is Boolean -> value
            normalized in safeScalarKeys -> value.toString().take(120)
            else -> textMetadata(value.toString())
        }
    }

    fun reason(value: String): String = textMetadata(value)

    fun sanitizeString(value: String, @Suppress("UNUSED_PARAMETER") maxLength: Int = 800): String = textMetadata(value)

    fun eventType(value: String): String = value.trim()
        .takeIf { it.matches(Regex("[A-Za-z0-9_]{1,64}")) }
        ?: textMetadata(value)

    private fun textMetadata(value: String): String = "[text length=${value.length} sha256=${digest(value)}]"

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
