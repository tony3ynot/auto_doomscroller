package com.autodoomscroller.features

import com.autodoomscroller.tracking.MediaType
import com.autodoomscroller.tracking.ReelLabel
import com.autodoomscroller.tracking.ReelSnapshot
import java.io.File
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same reels and expected values as ml/tests/test_fixtures.py checks on the Python side. */
class ViewingHistoryTest {
    private val fixture = JSONObject(File("../../ml/tests/fixtures/decision_features.json").readText())

    @Test
    fun namesAreThePythonFeatureNames() {
        val names = fixture.getJSONArray("features")
        assertEquals(ViewingHistory.NAMES, List(names.length()) { names.getString(it) })
    }

    @Test
    fun featuresMatchThePythonOnes() {
        val history = ViewingHistory(ZoneId.of(fixture.getString("timezone")))
        val reels = fixture.getJSONArray("reels")
        val expected = fixture.getJSONArray("expected")
        for (i in 0 until reels.length()) {
            val reel = reels.getJSONObject(i)
            val snapshot = snapshot(reel)
            val startedAtMs = reel.getLong("started_at_ms")
            val features = history.features(snapshot, startedAtMs)
            // Asking again before the reel ends must not change anything.
            assertTrue(features.contentEquals(history.features(snapshot, startedAtMs)))
            ViewingHistory.NAMES.forEachIndexed { j, name ->
                val want = expected.getJSONObject(i)
                if (want.isNull(name)) {
                    assertTrue("reel $i $name should be missing", features[j].isNaN())
                } else {
                    assertEquals("reel $i $name", want.getDouble(name), features[j].toDouble(), 1e-5)
                }
            }
            history.add(
                packageName = snapshot.packageName,
                creator = snapshot.creator,
                startedAtMs = startedAtMs,
                endedAtMs = reel.getLong("ended_at_ms"),
                dwellMs = reel.getLong("dwell_ms"),
                isAd = snapshot.isAd,
                label = if (reel.isNull("label")) null else ReelLabel.valueOf(reel.getString("label")),
            )
        }
    }

    private fun snapshot(reel: JSONObject) = ReelSnapshot(
        packageName = reel.getString("package"),
        creator = reel.getString("creator"),
        caption = if (reel.isNull("caption")) null else reel.getString("caption"),
        audio = if (reel.isNull("audio")) null else reel.getString("audio"),
        mediaType = MediaType.valueOf(reel.getString("media_type")),
        isAd = reel.getBoolean("is_ad"),
        liked = null,
        saved = null,
        likeCount = if (reel.isNull("like_count")) null else reel.getLong("like_count"),
        commentCount = if (reel.isNull("comment_count")) null else reel.getLong("comment_count"),
        positionMs = null,
        durationMs = null,
        commentsOpen = false,
        paidPromotion = reel.getBoolean("paid_promotion"),
    )
}
