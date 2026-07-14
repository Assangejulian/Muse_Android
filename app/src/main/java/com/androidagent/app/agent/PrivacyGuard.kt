package com.androidagent.app.agent

data class PrivacyDecision(
    val allowed: Boolean,
    val reason: String? = null,
    val observation: Observation,
)

/** Blocks sensitive screens before any node text or screenshot can reach a model provider. */
object PrivacyGuard {
    private val blockedPackages = listOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val phone = Regex("(?<!\\d)1\\d{10}(?!\\d)")
    private val longNumber = Regex("(?<!\\d)\\d{13,19}(?!\\d)")
    private val idCard = Regex("(?<!\\d)\\d{17}[0-9Xx](?!\\d)")

    fun prepare(observation: Observation): PrivacyDecision {
        val packageName = observation.packageName.lowercase()
        val packageBlocked = blockedPackages.any(packageName::startsWith)
        val passwordField = observation.nodes.any { it.visible && it.password }
        val matchedTerm = SensitiveOperationPolicy.matchObservation(observation)
        val reason = when {
            packageBlocked -> "system permission or installer surface"
            passwordField -> "password field is visible"
            matchedTerm != null -> "sensitive screen term detected: ${matchedTerm.term}"
            else -> null
        }
        return PrivacyDecision(
            allowed = reason == null,
            reason = reason,
            observation = sanitize(observation),
        )
    }

    fun sanitize(observation: Observation): Observation = observation.copy(
        nodes = observation.nodes.map { node ->
            if (node.password) {
                node.copy(text = "", description = "[redacted-password]")
            } else {
                node.copy(
                    text = redact(node.text, node.description),
                    description = redact(node.description, node.text),
                )
            }
        },
    )

    private fun redact(value: String, context: String = ""): String = value
        .replace(email, "[redacted-email]")
        .replace(phone, "[redacted-phone]")
        .replace(idCard, "[redacted-id]")
        .replace(longNumber, "[redacted-number]")
        .replace(
            Regex("(?i)(?:code|otp|verification|one[- ]time|\\u9a8c\\u8bc1\\u7801|\\u52a8\\u6001\\u53e3\\u4ee4)[^0-9]{0,16}\\d{4,8}"),
            "[redacted-otp]",
        )
        .let { redacted ->
            val contextHasCodeLabel = context.contains("code", true) || context.contains("otp", true) ||
                context.contains("verification", true) || context.contains("\u9a8c\u8bc1\u7801") || context.contains("\u52a8\u6001\u53e3\u4ee4")
            if (contextHasCodeLabel && value.trim().matches(Regex("\\d{4,8}"))) "[redacted-otp]" else redacted
        }
}
