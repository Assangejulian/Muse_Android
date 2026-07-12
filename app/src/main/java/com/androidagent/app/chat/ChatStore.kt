package com.androidagent.app.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(val role: String, val content: String, val timestamp: Long = System.currentTimeMillis())

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val pinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
)

class ChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("agent_chats", Context.MODE_PRIVATE)

    fun load(): List<Conversation> = runCatching {
        val array = JSONArray(prefs.getString("conversations", "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val messagesJson = item.optJSONArray("messages") ?: JSONArray()
                val messages = buildList {
                    for (messageIndex in 0 until messagesJson.length()) {
                        val message = messagesJson.getJSONObject(messageIndex)
                        add(ChatMessage(message.getString("role"), message.getString("content"), message.optLong("timestamp")))
                    }
                }
                add(Conversation(item.getString("id"), item.getString("title"), item.optBoolean("pinned"), item.optLong("updatedAt"), messages))
            }
        }
    }.getOrDefault(emptyList())

    fun save(conversations: List<Conversation>) {
        val array = JSONArray()
        conversations.forEach { conversation ->
            array.put(JSONObject()
                .put("id", conversation.id)
                .put("title", conversation.title)
                .put("pinned", conversation.pinned)
                .put("updatedAt", conversation.updatedAt)
                .put("messages", JSONArray().apply {
                    conversation.messages.forEach { message ->
                        put(JSONObject().put("role", message.role).put("content", message.content).put("timestamp", message.timestamp))
                    }
                }))
        }
        prefs.edit().putString("conversations", array.toString()).apply()
    }
}
