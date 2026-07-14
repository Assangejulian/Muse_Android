package com.androidagent.app.agent

data class ActionRecord(
    val step: Int,
    val action: AgentAction,
    val success: Boolean,
    val beforeFingerprint: String? = null,
    val afterFingerprint: String? = null,
    val result: String,
    val timestamp: Long = System.currentTimeMillis(),
)

class ExecutionHistory {
    private val records = mutableListOf<ActionRecord>()

    fun record(record: ActionRecord) {
        records += record
        while (records.size > MAX_RECORDS) records.removeAt(0)
    }

    fun recent(limit: Int = 12): List<ActionRecord> = records.takeLast(limit.coerceAtLeast(0))

    fun promptLines(limit: Int = 12): List<String> = recent(limit).map { record ->
        "step=${record.step} action=${record.action::class.simpleName} success=${record.success} " +
            "before=${record.beforeFingerprint.orEmpty()} after=${record.afterFingerprint.orEmpty()} result=${record.result.take(500)}"
    }

    fun all(): List<ActionRecord> = records.toList()

    private companion object { const val MAX_RECORDS = 64 }
}
