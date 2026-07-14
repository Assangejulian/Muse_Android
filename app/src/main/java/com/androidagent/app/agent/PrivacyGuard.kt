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
    private val blockedTerms = listOf(
        "支付", "付款", "收银台", "购买", "充值", "转账", "银行卡", "验证码", "动态口令",
        "登录密码", "修改密码", "实名认证", "身份证", "授权权限", "安装应用", "卸载应用",
        "payment", "checkout", "purchase", "bank card", "verification code", "one-time password",
        "password", "grant permission", "install app", "uninstall app",
    )
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val phone = Regex("(?<!\\d)1\\d{10}(?!\\d)")
    private val longNumber = Regex("(?<!\\d)\\d{13,19}(?!\\d)")
    private val idCard = Regex("(?<!\\d)\\d{17}[0-9Xx](?!\\d)")
    private val codeCandidate = Regex("(?i)(?:code|otp|verification|验证码)[^0-9]{0,12}(\\d{4,8})")

    fun prepare(observation: Observation): PrivacyDecision {
        val packageName = observation.packageName.lowercase()
        val packageBlocked = blockedPackages.any(packageName::startsWith)
        val passwordField = observation.nodes.any { it.visible && it.password }
        val visibleText = observation.visibleText()
        val matchedTerm = blockedTerms.firstOrNull { visibleText.contains(it, ignoreCase = true) }
        val reason = when {
            packageBlocked -> "system permission or installer surface"
            passwordField -> "password field is visible"
            matchedTerm != null -> "sensitive screen term detected: $matchedTerm"
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
        .replace(codeCandidate) { "[redacted-otp]" }
        .let { redacted ->
            if (codeCandidate.containsMatchIn("$context $value")) "[redacted-otp]" else redacted
        }
}
