package com.autodoomscroller.data

import android.os.Build
import kotlinx.coroutines.flow.Flow

class ReelRepository(private val dao: ReelDao) {

    suspend fun startSession(nowMs: Long = System.currentTimeMillis()): Long =
        dao.insertSession(SessionEntity(startedAtMs = nowMs, deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"))

    suspend fun endSession(id: Long, nowMs: Long = System.currentTimeMillis()) = dao.endSession(id, nowMs)

    suspend fun saveReel(reel: ReelEntity): Long = dao.insertReel(reel)

    fun observeLabeledCount(): Flow<Int> = dao.observeLabeledCount()

    suspend fun allSessions(): List<SessionEntity> = dao.allSessions()

    suspend fun allReels(): List<ReelEntity> = dao.allReels()

    suspend fun deleteAll() = dao.deleteAll()
}
