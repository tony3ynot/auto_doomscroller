package com.autodoomscroller.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ReelDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAtMs = :endedAtMs WHERE id = :id")
    suspend fun endSession(id: Long, endedAtMs: Long)

    @Insert
    suspend fun insertReel(reel: ReelEntity): Long

    @Query("SELECT COUNT(*) FROM reels WHERE label IS NOT NULL")
    fun observeLabeledCount(): Flow<Int>

    @Query("SELECT * FROM sessions ORDER BY startedAtMs")
    suspend fun allSessions(): List<SessionEntity>

    @Query("SELECT * FROM reels ORDER BY startedAtMs")
    suspend fun allReels(): List<ReelEntity>

    @Query("DELETE FROM reels")
    suspend fun deleteAllReels()

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Transaction
    suspend fun deleteAll() {
        deleteAllReels()
        deleteAllSessions()
    }
}
