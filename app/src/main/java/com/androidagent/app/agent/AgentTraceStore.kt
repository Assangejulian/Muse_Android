package com.androidagent.app.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

class AgentTraceStore(context: Context) {
    private val database = TraceDatabase(context.applicationContext)

    fun startRun(goal: String, model: String): String {
        val runId = UUID.randomUUID().toString()
        database.writableDatabase.insertOrThrow("runs", null, ContentValues().apply {
            put("id", runId)
            put("goal", goal)
            put("model", model)
            put("status", "RUNNING")
            put("started_at", System.currentTimeMillis())
        })
        return runId
    }

    fun event(runId: String, type: String, payload: Map<String, Any?>) {
        database.writableDatabase.insertOrThrow("events", null, ContentValues().apply {
            put("run_id", runId)
            put("event_type", type)
            put("payload", JSONObject(payload).toString())
            put("created_at", System.currentTimeMillis())
        })
    }

    fun finish(runId: String, status: String, reason: String) {
        database.writableDatabase.update("runs", ContentValues().apply {
            put("status", status)
            put("reason", reason)
            put("finished_at", System.currentTimeMillis())
        }, "id=?", arrayOf(runId))
    }

    fun latestRunSummary(): String {
        val db = database.readableDatabase
        val run = db.query("runs", arrayOf("id", "model", "status", "reason", "started_at"), null, null, null, null, "started_at DESC", "1")
            .use { cursor ->
                if (!cursor.moveToFirst()) return "还没有 Agent 运行轨迹。"
                listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3).orEmpty())
            }
        val events = buildList {
            db.query("events", arrayOf("event_type", "payload"), "run_id=?", arrayOf(run[0]), null, null, "id DESC", "20")
                .use { cursor ->
                    while (cursor.moveToNext()) add("${cursor.getString(0)}: ${cursor.getString(1)}")
                }
        }.reversed()
        return buildString {
            appendLine("Run: ${run[2]}")
            appendLine("Model: ${run[1]}")
            if (run[3].isNotBlank()) appendLine("Reason: ${run[3]}")
            appendLine("Recent events:")
            events.forEach { appendLine(it) }
        }.take(12_000)
    }
}

private class TraceDatabase(context: Context) : SQLiteOpenHelper(context, "muse_agent_traces.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE runs (
                id TEXT PRIMARY KEY NOT NULL,
                goal TEXT NOT NULL,
                model TEXT NOT NULL,
                status TEXT NOT NULL,
                reason TEXT,
                started_at INTEGER NOT NULL,
                finished_at INTEGER
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(run_id) REFERENCES runs(id) ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX trace_events_run_idx ON events(run_id, id)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
