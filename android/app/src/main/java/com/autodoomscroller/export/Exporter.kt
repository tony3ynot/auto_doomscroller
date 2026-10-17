package com.autodoomscroller.export

import android.content.Context
import com.autodoomscroller.AppGraph
import com.autodoomscroller.data.ReelEntity
import com.autodoomscroller.data.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes all local data in the format described in docs/data-format.md, to app-specific
 * external storage so it can be pulled with
 * `adb pull /sdcard/Android/data/com.autodoomscroller/files/exports`.
 */
object Exporter {

    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        val root = context.getExternalFilesDir("exports") ?: File(context.filesDir, "exports")
        val dir = File(root, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()))
        dir.mkdirs()

        val repository = AppGraph.reelRepository
        File(dir, "sessions.jsonl").bufferedWriter().use { out ->
            repository.allSessions().forEach { out.appendLine(it.toJson().toString()) }
        }
        File(dir, "reels.jsonl").bufferedWriter().use { out ->
            repository.allReels().forEach { out.appendLine(it.toJson().toString()) }
        }
        val thumbnailDir = File(dir, "thumbnails")
        AppGraph.thumbnailStore.all().forEach { it.copyTo(File(thumbnailDir, it.name), overwrite = true) }
        dir
    }

    private fun SessionEntity.toJson() = JSONObject()
        .put("id", id)
        .put("started_at_ms", startedAtMs)
        .put("ended_at_ms", endedAtMs ?: JSONObject.NULL)
        .put("device_model", deviceModel)

    private fun ReelEntity.toJson() = JSONObject()
        .put("id", id)
        .put("session_id", sessionId)
        .put("package", packageName)
        .put("reel_key", reelKey)
        .put("creator", creator ?: JSONObject.NULL)
        .put("caption", caption ?: JSONObject.NULL)
        .put("audio", audio ?: JSONObject.NULL)
        .put("mode", mode.name)
        .put("started_at_ms", startedAtMs)
        .put("ended_at_ms", endedAtMs)
        .put("moved_to_next", movedToNext)
        .put("looped", looped)
        .put("liked", liked)
        .put("auto_skipped", autoSkipped)
        .put("undone", undone)
        .put("label", label?.name ?: JSONObject.NULL)
        .put("frame_count", frameCount)
}
