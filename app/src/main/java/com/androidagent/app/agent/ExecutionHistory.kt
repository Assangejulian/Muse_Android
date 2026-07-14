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

data class RecoveryRecord(
    val step: Int,
    val reason: RecoveryReason,
    val action: RecoveryAction,
    val success: Boolean,
    val result: String,
    val timestamp: Long = System.currentTimeMillis(),
)

class ExecutionHistory {
    private val records = mutableListOf<ActionRecord>()
    private val recoveryRecords = mutableListOf<RecoveryRecord>()

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

    fun recordRecovery(record: RecoveryRecord) {
        recoveryRecords += record
        while (recoveryRecords.size > MAX_RECOVERY_RECORDS) recoveryRecords.removeAt(0)
    }

    fun recoveries(): List<RecoveryRecord> = recoveryRecords.toList()

    private companion object {
        const val MAX_RECORDS = 64
        const val MAX_RECOVERY_RECORDS = 32
    }
}
