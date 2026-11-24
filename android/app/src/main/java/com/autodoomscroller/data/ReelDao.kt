package com.autodoomscroller.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class AppCount(val packageName: String, val count: Int)

@Dao
interface ReelDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAtMs = :endedAtMs WHERE id = :id")
    suspend fun endSession(id: Long, endedAtMs: Long)

    @Insert
    suspend fun insertReel(reel: ReelEntity): Long

    @Query("SELECT packageName, COUNT(*) AS count FROM reels WHERE label IS NOT NULL GROUP BY packageName")
    fun observeLabeledCounts(): Flow<List<AppCount>>

    @Query("SELECT * FROM sessions ORDER BY startedAtMs")
    suspend fun allSessions(): List<SessionEntity>

    @Query("SELECT * FROM reels ORDER BY startedAtMs")
    suspend fun allReels(): List<ReelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(embedding: FrameEmbeddingEntity)

    @Query("SELECT * FROM frame_embeddings")
    suspend fun allEmbeddings(): List<FrameEmbeddingEntity>

    @Query("SELECT reelId FROM frame_embeddings")
    suspend fun embeddedReelIds(): List<Long>

    @Query("DELETE FROM frame_embeddings")
    suspend fun deleteAllEmbeddings()

    @Query("DELETE FROM reels")
    suspend fun deleteAllReels()

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Transaction
    suspend fun deleteAll() {
        deleteAllEmbeddings()
        deleteAllReels()
        deleteAllSessions()
    }
}
