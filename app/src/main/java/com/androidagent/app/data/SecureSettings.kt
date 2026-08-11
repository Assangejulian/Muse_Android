package com.androidagent.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.androidagent.app.BuildConfig

class SecureSettings(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "agent_secure_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        if (!prefs.getBoolean("provider_keys_migrated", false)) {
            val legacyKey = prefs.getString("api_key", "").orEmpty()
            if (legacyKey.isNotBlank()) {
                val provider = inferProvider(prefs.getString("model_base_url", "").orEmpty())
                prefs.edit().putString("api_key_$provider", legacyKey).apply()
            }
            prefs.edit().putBoolean("provider_keys_migrated", true).apply()
        }
    }

    var currentProvider: String
        get() = prefs.getString("model_provider", "").orEmpty().ifBlank {
            inferProvider(prefs.getString("model_base_url", "").orEmpty())
        }
        set(value) = prefs.edit().putString("model_provider", value).apply()

    var apiKey: String
        get() = prefs.getString("api_key_${currentProvider}", "").orEmpty()
        set(value) = prefs.edit().putString("api_key_${currentProvider}", value.trim()).apply()

    fun apiKeyFor(provider: String): String = prefs.getString("api_key_$provider", "").orEmpty()

    var targetPackage: String
        get() = prefs.getString("target_package", "").orEmpty()
        set(value) = prefs.edit().putString("target_package", value.trim()).apply()

    var taskGoal: String
        get() = prefs.getString("task_goal", "").orEmpty()
        set(value) = prefs.edit().putString("task_goal", value.trim()).apply()

    var githubRepository: String
        get() = prefs.getString("github_repository", BuildConfig.GITHUB_REPOSITORY).orEmpty()
        set(value) = prefs.edit().putString("github_repository", value.trim()).apply()

    var autoUpdateEnabled: Boolean
        get() = prefs.getBoolean("auto_update_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_update_enabled", value).apply()

    var modelBaseUrl: String
        get() = prefs.getString("model_base_url", "https://api.deepseek.com").orEmpty()
        set(value) = prefs.edit().putString("model_base_url", value.trim().trimEnd('/')).apply()

    var modelName: String
        get() = normalizeModelName(prefs.getString("model_name", DEFAULT_MODEL).orEmpty())
        set(value) = prefs.edit().putString("model_name", value.trim()).apply()

    var contextLength: Int
        get() = prefs.getInt("context_length", DEFAULT_CONTEXT_LENGTH)
            .coerceIn(MIN_CONTEXT_LENGTH, MAX_CONTEXT_LENGTH)
        set(value) = prefs.edit()
            .putInt("context_length", value.coerceIn(MIN_CONTEXT_LENGTH, MAX_CONTEXT_LENGTH))
            .apply()

    var maxOutputTokens: Int
        get() = prefs.getInt("max_output_tokens", DEFAULT_MAX_OUTPUT_TOKENS)
            .coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
        set(value) = prefs.edit()
            .putInt("max_output_tokens", value.coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS))
            .apply()

    var terminalWorkingDirectory: String
        get() = prefs.getString("terminal_working_directory", DEFAULT_WORKING_DIRECTORY).orEmpty()
            .ifBlank { DEFAULT_WORKING_DIRECTORY }
        set(value) = prefs.edit().putString("terminal_working_directory", value.trim()).apply()

    var terminalPathPrefix: String
        get() = prefs.getString("terminal_path_prefix", "").orEmpty()
        set(value) = prefs.edit().putString("terminal_path_prefix", value.trim()).apply()

    var environmentMirrorId: String
        get() = prefs.getString("environment_mirror_id", "tuna").orEmpty().ifBlank { "tuna" }
        set(value) = prefs.edit().putString("environment_mirror_id", value.trim()).apply()

    var enabledTerminalTools: Set<String>
        get() = prefs.getStringSet("enabled_terminal_tools", DEFAULT_TERMINAL_TOOLS)?.toSet()
            ?: DEFAULT_TERMINAL_TOOLS
        set(value) = prefs.edit().putStringSet("enabled_terminal_tools", value).apply()

    fun terminalToolCommand(toolId: String, defaultValue: String): String =
        prefs.getString("terminal_tool_$toolId", defaultValue).orEmpty().ifBlank { defaultValue }

    fun setTerminalToolCommand(toolId: String, value: String) {
        prefs.edit().putString("terminal_tool_$toolId", value.trim()).apply()
    }

    companion object {
        /** Preferred default: DeepSeek V4 Flash (fast Actor loop). */
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_PROVIDER = "deepseek"
        const val DEFAULT_CONTEXT_LENGTH = 32_000
        const val MIN_CONTEXT_LENGTH = 4_096
        const val MAX_CONTEXT_LENGTH = 128_000
        const val DEFAULT_MAX_OUTPUT_TOKENS = 4_096
        const val MIN_OUTPUT_TOKENS = 256
        const val MAX_OUTPUT_TOKENS = 16_384
        const val DEFAULT_WORKING_DIRECTORY = "/sdcard"
        val DEFAULT_TERMINAL_TOOLS = setOf("shell", "ssh", "python", "node", "java")
    }

    var visionEnabled: Boolean
        get() = prefs.getBoolean("vision_enabled", false)
        set(value) = prefs.edit().putBoolean("vision_enabled", value).apply()

    var visionModelName: String
        get() = prefs.getString("vision_model_name", "qwen3-vl-flash").orEmpty().let {
            if (it == "qwen3.5-omni-plus") "qwen3-vl-flash" else it
        }
        set(value) = prefs.edit().putString("vision_model_name", value.trim()).apply()

    var visionBaseUrl: String
        get() = prefs.getString("vision_base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1").orEmpty()
        set(value) = prefs.edit().putString("vision_base_url", value.trim().trimEnd('/')).apply()

    var visionApiKey: String
        get() = prefs.getString("vision_api_key", "").orEmpty()
        set(value) = prefs.edit().putString("vision_api_key", value.trim()).apply()

    var privilegedBackendEnabled: Boolean
        get() = prefs.getBoolean("privileged_backend_enabled", true)
        set(value) = prefs.edit().putBoolean("privileged_backend_enabled", value).apply()

    var scheduledTaskId: String
        get() = prefs.getString("scheduled_task_id", "").orEmpty()
        set(value) = prefs.edit().putString("scheduled_task_id", value.trim()).apply()

    var scheduledTaskGoal: String
        get() = prefs.getString("scheduled_task_goal", prefs.getString("scheduled_goal", "")).orEmpty()
        set(value) = prefs.edit().putString("scheduled_task_goal", value.trim()).apply()

    var nextRunAt: Long
        get() = prefs.getLong("next_run_at", 0L)
        set(value) = prefs.edit().putLong("next_run_at", value).apply()

    private fun inferProvider(baseUrl: String): String = when {
        baseUrl.contains("aliyuncs.com", true) -> "qwen"
        baseUrl.contains("xiaomi", true) || baseUrl.contains("mimo", true) -> "mimo"
        else -> "deepseek"
    }
}

internal fun normalizeModelName(modelName: String): String = when (modelName) {
    // Legacy Qwen Omni needs unsupported streaming tools.
    "qwen3.5-omni-plus" -> "qwen3.6-flash"
    // Keep existing installations on the provider's rolling Flash alias.
    "deepseek-v4-flash-0731" -> SecureSettings.DEFAULT_MODEL
    else -> modelName
}
