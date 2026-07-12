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
}
