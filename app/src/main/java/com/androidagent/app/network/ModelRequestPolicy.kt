package com.androidagent.app.network

import org.json.JSONObject
import java.net.URI
import kotlin.random.Random

internal object ModelRetryPolicy {
    fun shouldRetryStatus(status: Int): Boolean = status == 429 || status in 500..504

    fun delayMillis(attempt: Int, random: Random = Random.Default): Long =
        (500L * (1L shl attempt.coerceIn(0, 4)) + random.nextLong(0L, 250L)).coerceAtMost(8_000L)
}

internal object ProviderRequestPolicy {
    fun configure(body: JSONObject, baseUrl: String, provider: String) {
        when {
            provider.equals("qwen", true) -> body.put("enable_thinking", false)
            provider.equals("deepseek", true) -> body.put("thinking", JSONObject().put("type", "disabled"))
            provider.isBlank() && baseUrl.contains("aliyuncs.com", true) -> body.put("enable_thinking", false)
            provider.isBlank() && baseUrl.contains("deepseek.com", true) -> body.put("thinking", JSONObject().put("type", "disabled"))
        }
    }
}

internal object BaseUrlPolicy {
    fun validate(baseUrl: String, allowInsecureLocalDevelopment: Boolean = false): String {
        val normalized = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrElse { error("Invalid model service Base URL") }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase().orEmpty()
        require(scheme == "https" || scheme == "http") { "Base URL must use https://" }
        if (scheme == "http") {
            val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
            require(loopback || allowInsecureLocalDevelopment) {
                "Insecure HTTP is only allowed for localhost or explicit local development mode"
            }
        }
        require(host.isNotBlank()) { "Base URL must include a host" }
        return normalized
    }
}
