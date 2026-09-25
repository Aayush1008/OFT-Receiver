package com.oftreceiver.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_history")
data class TransferRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filename: String,
    val fileSize: Long,
    val sha256: String,
    val savedUri: String?,       // content:// URI where file was saved, null if not saved
    val timestamp: Long,         // epoch millis
    val sessionId: String,
    val totalChunks: Int,
    val durationMs: Long         // how long the transfer took
)
