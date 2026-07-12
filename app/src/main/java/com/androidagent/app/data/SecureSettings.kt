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

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var targetPackage: String
        get() = prefs.getString("target_package", "").orEmpty()
        set(value) = prefs.edit().putString("target_package", value.trim()).apply()

    var taskGoal: String
        get() = prefs.getString("task_goal", "").orEmpty()
        set(value) = prefs.edit().putString("task_goal", value.trim()).apply()

    var githubRepository: String
        get() = prefs.getString("github_repository", BuildConfig.GITHUB_REPOSITORY).orEmpty()
        set(value) = prefs.edit().putString("github_repository", value.trim()).apply()

    var modelBaseUrl: String
        get() = prefs.getString("model_base_url", "https://api.deepseek.com").orEmpty()
        set(value) = prefs.edit().putString("model_base_url", value.trim().trimEnd('/')).apply()

    var modelName: String
        get() = prefs.getString("model_name", "deepseek-v4-flash").orEmpty()
        set(value) = prefs.edit().putString("model_name", value.trim()).apply()

    var visionEnabled: Boolean
        get() = prefs.getBoolean("vision_enabled", false)
        set(value) = prefs.edit().putBoolean("vision_enabled", value).apply()

    var visionModelName: String
        get() = prefs.getString("vision_model_name", "qwen3-vl-flash").orEmpty()
        set(value) = prefs.edit().putString("vision_model_name", value.trim()).apply()

    var visionBaseUrl: String
        get() = prefs.getString("vision_base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1").orEmpty()
        set(value) = prefs.edit().putString("vision_base_url", value.trim().trimEnd('/')).apply()

    var visionApiKey: String
        get() = prefs.getString("vision_api_key", "").orEmpty()
        set(value) = prefs.edit().putString("vision_api_key", value.trim()).apply()

    var scheduledGoal: String
        get() = prefs.getString("scheduled_goal", "").orEmpty()
        set(value) = prefs.edit().putString("scheduled_goal", value.trim()).apply()

    var nextRunAt: Long
        get() = prefs.getLong("next_run_at", 0L)
        set(value) = prefs.edit().putLong("next_run_at", value).apply()
}
