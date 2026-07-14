package com.androidagent.app.agent

import java.security.MessageDigest

/** Keeps local diagnostics useful without persisting user payloads or screens. */
object TraceSanitizer {
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val phone = Regex("(?<!\\d)1\\d{10}(?!\\d)")
    private val card = Regex("(?<!\\d)\\d{13,19}(?!\\d)")
    private val idCard = Regex("(?<!\\d)\\d{17}[0-9Xx](?!\\d)")

    fun goal(value: String): String = "[goal length=${value.length} sha256=${digest(value)}]"

    fun action(action: AgentAction): String = when (action) {
        is AgentAction.InputText -> "input_text(target=${action.target ?: action.nodeId ?: "focused"}, chars=${action.text.length}, mode=${action.mode}, submit=${action.submit})"
        is AgentAction.SubmitInput -> "submit_input(target=${action.target ?: action.nodeId ?: "focused"})"
        is AgentAction.ClickText -> "click_text(chars=${action.text.length})"
        is AgentAction.Finish -> "finish(reason_present=${action.reason.isNotBlank()})"
        is AgentAction.Fail -> "fail(reason_present=${action.reason.isNotBlank()})"
        else -> action::class.simpleName.orEmpty()
    }

    fun payload(payload: Map<String, Any?>): Map<String, Any?> = payload.mapValues { (key, value) ->
        val normalized = key.lowercase()
        when {
            normalized.contains("api") && normalized.contains("key") -> "[redacted-api-key]"
            normalized == "goal" || normalized.contains("chat") -> goal(value?.toString().orEmpty())
            normalized in setOf("plan", "observation", "screen", "history", "compacttext", "screenshot") -> "[redacted-$normalized]"
            value is AgentAction -> action(value)
            else -> sanitizeString(value?.toString().orEmpty(), 800)
        }
    }

    fun reason(value: String): String = sanitizeString(value, 800)

    fun sanitizeString(value: String, maxLength: Int = 800): String {
        if (value.contains("InputText(", ignoreCase = true)) return "[redacted-input-action]"
        return value
            .replace(email, "[redacted-email]")
            .replace(phone, "[redacted-phone]")
            .replace(idCard, "[redacted-id]")
            .replace(card, "[redacted-number]")
            .take(maxLength)
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
