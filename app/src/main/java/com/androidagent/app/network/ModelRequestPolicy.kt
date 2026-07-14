package com.androidagent.app.network

import org.json.JSONObject
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
