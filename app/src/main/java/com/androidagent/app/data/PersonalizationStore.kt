package com.androidagent.app.data

import android.content.Context
import java.io.File

class PersonalizationStore(context: Context) {
    private val memoryFile = File(context.filesDir, "personalization/memory.md")

    fun loadMemory(): String = runCatching {
        if (memoryFile.exists()) memoryFile.readText(Charsets.UTF_8) else DEFAULT_MEMORY
    }.getOrDefault(DEFAULT_MEMORY)

    fun saveMemory(markdown: String) {
        memoryFile.parentFile?.mkdirs()
        memoryFile.writeText(markdown.take(MAX_MEMORY_CHARS), Charsets.UTF_8)
    }

    fun absolutePath(): String = memoryFile.absolutePath

    companion object {
        const val MAX_MEMORY_CHARS = 32_000
        private val DEFAULT_MEMORY = """
            # User memory

            - Preferred language: Chinese
            - Keep answers concise and action-oriented.
        """.trimIndent()
    }
}
