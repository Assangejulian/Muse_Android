package com.androidagent.app.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestPolicyTest {
    @Test
    fun retriesOnlyTransientStatuses() {
        assertTrue(ModelRetryPolicy.shouldRetryStatus(429))
        assertTrue(ModelRetryPolicy.shouldRetryStatus(500))
        assertTrue(ModelRetryPolicy.shouldRetryStatus(503))
        assertFalse(ModelRetryPolicy.shouldRetryStatus(401))
        assertFalse(ModelRetryPolicy.shouldRetryStatus(403))
    }

    @Test
    fun explicitProviderOverridesUrlInference() {
        val body = JSONObject()
        ProviderRequestPolicy.configure(body, "https://api.deepseek.com", "qwen")
        assertEquals(false, body.getBoolean("enable_thinking"))
        assertFalse(body.has("thinking"))
    }

    @Test
    fun deepseekProManagerMayKeepThinkingEnabled() {
        val body = JSONObject()
        ProviderRequestPolicy.configure(
            body = body,
            baseUrl = "https://api.deepseek.com",
            provider = "deepseek",
            model = "deepseek-v4-pro",
            allowThinking = true,
        )
        assertFalse(body.has("thinking"))
    }

    @Test
    fun deepseekActorStillDisablesThinkingByDefault() {
        val body = JSONObject()
        ProviderRequestPolicy.configure(
            body = body,
            baseUrl = "https://api.deepseek.com",
            provider = "deepseek",
            model = "deepseek-v4-pro",
            allowThinking = false,
        )
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }
}
