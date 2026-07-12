package com.androidagent.app.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.Executors

data class ChatMessage(val role: String, val content: String, val timestamp: Long = System.currentTimeMillis())

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val pinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
)

/** Stores conversation memory in the app-private SQLite database. */
class ChatStore(context: Context) {
    private val appContext = context.applicationContext
    private val database = ChatDatabase(appContext)
    private val writer = Executors.newSingleThreadExecutor()

    fun load(): List<Conversation> {
        val stored = database.readAll()
        if (stored.isNotEmpty()) return stored

        val legacy = readLegacyPreferences()
        if (legacy.isNotEmpty()) {
            database.replaceAll(legacy)
            appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(LEGACY_KEY)
                .apply()
        }
        return legacy
    }

    fun save(conversations: List<Conversation>) {
        val snapshot = conversations.map { it.copy(messages = it.messages.toList()) }
        writer.execute { database.replaceAll(snapshot) }
    }

    private fun readLegacyPreferences(): List<Conversation> = runCatching {
        val raw = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_KEY, "[]")
        val array = JSONArray(raw)
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

    private companion object {
        const val LEGACY_PREFS = "agent_chats"
        const val LEGACY_KEY = "conversations"
    }
}

private class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "muse_memory.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE conversations (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX messages_conversation_idx ON messages(conversation_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun readAll(): List<Conversation> {
        val db = readableDatabase
        val messagesByConversation = mutableMapOf<String, MutableList<ChatMessage>>()
        db.query(
            "messages",
            arrayOf("conversation_id", "role", "content", "created_at"),
            null,
            null,
            null,
            null,
            "id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val conversationId = cursor.getString(0)
                messagesByConversation.getOrPut(conversationId, ::mutableListOf).add(
                    ChatMessage(cursor.getString(1), cursor.getString(2), cursor.getLong(3)),
                )
            }
        }
        return buildList {
            db.query(
                "conversations",
                arrayOf("id", "title", "pinned", "updated_at"),
                null,
                null,
                null,
                null,
                "pinned DESC, updated_at DESC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    add(
                        Conversation(
                            id = id,
                            title = cursor.getString(1),
                            pinned = cursor.getInt(2) != 0,
                            updatedAt = cursor.getLong(3),
                            messages = messagesByConversation[id].orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    fun replaceAll(conversations: List<Conversation>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", null, null)
            db.delete("conversations", null, null)
            conversations.forEach { conversation ->
                db.insertOrThrow("conversations", null, ContentValues().apply {
                    put("id", conversation.id)
                    put("title", conversation.title)
                    put("pinned", if (conversation.pinned) 1 else 0)
                    put("updated_at", conversation.updatedAt)
                })
                conversation.messages.forEach { message ->
                    db.insertOrThrow("messages", null, ContentValues().apply {
                        put("conversation_id", conversation.id)
                        put("role", message.role)
                        put("content", message.content)
                        put("created_at", message.timestamp)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
