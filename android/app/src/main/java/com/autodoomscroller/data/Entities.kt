package com.autodoomscroller.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.autodoomscroller.session.SessionMode
import com.autodoomscroller.tracking.DurationSource
import com.autodoomscroller.tracking.MediaType
import com.autodoomscroller.tracking.ReelLabel

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val deviceModel: String,
)

/** One reel as the user experienced it. Field meanings are documented in docs/data-format.md. */
@Entity(tableName = "reels", indices = [Index("sessionId")])
data class ReelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val packageName: String,
    val reelKey: String,
    val creator: String?,
    val caption: String?,
    val audio: String?,
    /** Null for rows recorded before media types were detected (schema v2). */
    val mediaType: MediaType?,
    @ColumnInfo(defaultValue = "0") val isAd: Boolean,
    @ColumnInfo(defaultValue = "0") val paidPromotion: Boolean,
    val mode: SessionMode,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val movedToNext: Boolean,
    val looped: Boolean,
    val durationMs: Long?,
    val durationSource: DurationSource?,
    /** Furthest playback position read from the scrubber. */
    val maxPositionMs: Long?,
    /** Length estimated by frame-based loop detection, recorded even when a scrubber exists. */
    val loopEstimateMs: Long?,
    val liked: Boolean,
    @ColumnInfo(defaultValue = "0") val saved: Boolean,
    @ColumnInfo(defaultValue = "0") val openedComments: Boolean,
    @ColumnInfo(defaultValue = "0") val carouselSwipes: Int,
    val likeCount: Long?,
    val commentCount: Long?,
    val autoSkipped: Boolean,
    val undone: Boolean,
    @ColumnInfo(defaultValue = "0") val revisit: Boolean,
    val feedIndex: Int?,
    val label: ReelLabel?,
    val frameCount: Int,
)
