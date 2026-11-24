package com.autodoomscroller.data

import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ReelRepository(private val dao: ReelDao) {

    suspend fun startSession(nowMs: Long = System.currentTimeMillis()): Long =
        dao.insertSession(SessionEntity(startedAtMs = nowMs, deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"))

    suspend fun endSession(id: Long, nowMs: Long = System.currentTimeMillis()) = dao.endSession(id, nowMs)

    suspend fun saveReel(reel: ReelEntity): Long = dao.insertReel(reel)

    /** Labeled reels per source app, e.g. {"com.instagram.android": 120}. */
    fun observeLabeledCounts(): Flow<Map<String, Int>> =
        dao.observeLabeledCounts().map { counts -> counts.associate { it.packageName to it.count } }

    suspend fun allSessions(): List<SessionEntity> = dao.allSessions()

    suspend fun allReels(): List<ReelEntity> = dao.allReels()

    suspend fun saveEmbedding(reelId: Long, embedding: FloatArray) {
        val bytes = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(embedding)
        dao.insertEmbedding(FrameEmbeddingEntity(reelId, bytes.array()))
    }

    /** Frame embeddings by reel id. */
    suspend fun allEmbeddings(): Map<Long, FloatArray> = dao.allEmbeddings().associate { row ->
        val floats = ByteBuffer.wrap(row.vector).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        row.reelId to FloatArray(floats.remaining()).also { floats.get(it) }
    }

    suspend fun embeddedReelIds(): Set<Long> = dao.embeddedReelIds().toSet()

    suspend fun deleteAll() = dao.deleteAll()
}
