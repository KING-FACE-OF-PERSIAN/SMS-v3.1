package com.example.consentsms

data class RecipientReport(
    val number: String,
    val totalAttempts: Int,
    var successCount: Int = 0,
    var failedCount: Int = 0
) {
    val completedAttempts: Int
        get() = successCount + failedCount

    val remainingAttempts: Int
        get() = totalAttempts - completedAttempts
}

data class ScheduledMessage(
    val id: Long,
    val numbersText: String,
    val message: String,
    val repeatCount: Int,
    val triggerAtMillis: Long
)
