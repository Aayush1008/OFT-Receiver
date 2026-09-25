package com.oftreceiver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TransferDao {

    @Insert
    suspend fun insert(record: TransferRecord): Long

    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    suspend fun getAll(): List<TransferRecord>

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM transfer_history")
    suspend fun count(): Int
}
