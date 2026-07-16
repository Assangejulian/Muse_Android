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
    /**
     * @param allowThinking When true and the provider is DeepSeek V4 Pro, leave
     * thinking enabled for stronger multi-step planning. Other paths stay non-thinking
     * for latency and cost.
     */
    fun configure(
        body: JSONObject,
        baseUrl: String,
        provider: String,
        model: String = "",
        allowThinking: Boolean = false,
    ) {
        val deepseek = provider.equals("deepseek", true) ||
            (provider.isBlank() && baseUrl.contains("deepseek.com", true))
        val qwen = provider.equals("qwen", true) ||
            (provider.isBlank() && baseUrl.contains("aliyuncs.com", true))
        when {
            qwen -> body.put("enable_thinking", false)
            deepseek -> {
                val proThinking = allowThinking && model.contains("deepseek-v4-pro", ignoreCase = true)
                if (!proThinking) {
                    body.put("thinking", JSONObject().put("type", "disabled"))
                }
            }
        }
    }
}

internal object BaseUrlPolicy {
    /** Release endpoints are TLS-only; debug may opt into loopback HTTP only. */
    fun validate(baseUrl: String, allowInsecureLocalDevelopment: Boolean = false): String {
        val normalized = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrElse { error("Invalid model service Base URL") }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        require(host.isNotBlank()) { "Base URL must include a host" }
        require(uri.userInfo == null) { "Base URL must not include user information" }
        require(uri.query == null) { "Base URL must not include a query" }
        require(uri.fragment == null) { "Base URL must not include a fragment" }
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        require(scheme == "https" || (scheme == "http" && allowInsecureLocalDevelopment && loopback)) {
            "Public model service Base URL must use https://"
        }
        return normalized
    }
}
