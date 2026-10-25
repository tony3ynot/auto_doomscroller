package com.autodoomscroller.adapter

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.autodoomscroller.BuildConfig
import android.view.accessibility.AccessibilityNodeInfo
import com.autodoomscroller.tracking.MediaType
import com.autodoomscroller.tracking.ReelSnapshot

/**
 * Reads the YouTube Shorts player. Only the Shorts pager (`reel_recycler`) counts: long-form
 * videos, inline previews on Home and the mini player use other views and are ignored.
 *
 * Shorts pages are rendered without view IDs, so reel details come from one walk over the
 * page; the like/save buttons found there are refreshed for state. Based on node dumps of the
 * Korean UI (October 2026).
 */
class YouTubeShortsAdapter(private val clock: () -> Long = SystemClock::uptimeMillis) : TargetAppAdapter {
    override val packageName = PACKAGE

    /**
     * [page] identifies the page across reads. Node objects are snapshots, so its children are
     * only as fresh as the read that fetched it; walks always start from the current read's node.
     */
    private class PageCache(
        val page: AccessibilityNodeInfo,
        val recycler: AccessibilityNodeInfo,
        val index: Int?,
        val firstSeenAtMs: Long,
    ) {
        var creator: String? = null
        var isLive = false
        var title: String? = null
        var audio: String? = null
        var isAd = false
        var paidPromotion = false
        var likeCount: Long? = null
        var commentCount: Long? = null
        var likeButton: AccessibilityNodeInfo? = null
        var saveButton: AccessibilityNodeInfo? = null
        var detailReads = 0
        var liked: Boolean? = null
        var saved: Boolean? = null
        var positionMs: Long? = null
        var durationMs: Long? = null
        var commentsOpen = false
        var lastStateAtMs = 0L
    }

    private var cache: PageCache? = null
    private var lastPanelSeen = false

    override fun read(root: AccessibilityNodeInfo): FeedState {
        val recycler = root.findById(RECYCLER).firstOrNull { it.isVisibleToUser }
        if (recycler == null) {
            cache = null
            return FeedState.NotOnFeed
        }
        // While a swipe is in progress two pages share the screen; wait until one settles.
        val page = (0 until recycler.childCount).mapNotNull(recycler::getChild)
            .filter { it.isVisibleToUser }
            .maxByOrNull { it.bounds().height() } ?: return FeedState.Unknown
        if (page.bounds().height() < recycler.bounds().height() * SETTLED_SHARE) return FeedState.Unknown

        val now = clock()
        val index = page.collectionItemInfo?.rowIndex
        val entry = cache?.takeIf { it.page == page && it.index == index }
            ?: PageCache(page, recycler, index, now).also { cache = it }

        val sinceFirstMs = now - entry.firstSeenAtMs
        val scheduledRead = entry.detailReads < DETAIL_READ_TIMES_MS.size &&
            sinceFirstMs >= DETAIL_READ_TIMES_MS[entry.detailReads]
        // Until the channel shows up, look on every read so the reel starts on time.
        if (scheduledRead || (entry.creator == null && sinceFirstMs < DETAIL_READ_TIMES_MS.last())) {
            readDetails(entry, page)
            if (scheduledRead) entry.detailReads++
        }
        // Live streams show up in the Shorts feed but aren't shorts.
        if (entry.isLive) return FeedState.OtherItem(isAd = false)

        val creator = entry.creator
            ?: return if (sinceFirstMs < OTHER_ITEM_MIN_MS) FeedState.Unknown else FeedState.OtherItem(entry.isAd)
        if (now - entry.lastStateAtMs >= STATE_INTERVAL_MS) {
            readState(root, entry, sinceFirstMs)
            entry.lastStateAtMs = now
        }
        return FeedState.OnReel(
            ReelSnapshot(
                packageName = PACKAGE,
                creator = creator,
                caption = entry.title,
                audio = entry.audio,
                mediaType = MediaType.VIDEO,
                isAd = entry.isAd,
                liked = entry.liked,
                saved = entry.saved,
                likeCount = entry.likeCount,
                commentCount = entry.commentCount,
                positionMs = entry.positionMs,
                durationMs = entry.durationMs,
                commentsOpen = entry.commentsOpen,
                feedIndex = index,
                paidPromotion = entry.paidPromotion,
            ),
        )
    }

    override fun isCommentsWindow(root: AccessibilityNodeInfo): Boolean =
        root.findById(ENGAGEMENT_PANEL).any { it.isVisibleToUser } &&
            root.findById(PANEL_TITLE).any { it.text?.toString()?.trim() in COMMENT_TITLES }

    /** [read] accepts a page covering most of the pager; for screenshots it has to fill it. */
    override fun settledMediaBounds(root: AccessibilityNodeInfo): Rect? {
        val entry = cache?.takeIf { it.creator != null } ?: return null
        if (!entry.page.refresh() || !entry.recycler.refresh()) return null
        return entry.page.bounds().takeIf { it == entry.recycler.bounds() }
    }

    /** One walk over the page; later reads only fill in what earlier ones missed. */
    private fun readDetails(entry: PageCache, page: AccessibilityNodeInfo) {
        if (page.findById(LIVE_PLAYER).isNotEmpty()) {
            entry.isLive = true
            return
        }
        var channel: String? = null
        var title: String? = null
        var audio: String? = null
        var adTitle: String? = null
        visit(page) { node ->
            val text = node.text?.toString()
            val description = node.contentDescription?.toString()
            val className = node.className?.toString().orEmpty()
            if (channel == null) channel = description?.let(::channelFromDescription)
            // Language-independent fallback: the handle itself is a button labelled "@name".
            if (channel == null && className.endsWith("Button") && text?.trim()?.startsWith("@") == true) {
                channel = text.trim().removePrefix("@").ifEmpty { null }
            }
            if (listOfNotNull(text, description).any(::isAdLabel)) {
                entry.isAd = true
                if (adTitle == null) adTitle = description?.lines()?.firstOrNull { it.isNotBlank() && !isAdLabel(it) }
            }
            if (listOfNotNull(text, description).any(::isPaidPromotionLabel)) entry.paidPromotion = true
            if (title == null && node.isClickable && className.endsWith("ViewGroup") &&
                !text.isNullOrBlank() && !text.startsWith("@")
            ) {
                title = text.trim()
            }
            // The sound row reads like "Helios · Jicmus".
            if (audio == null && text != null && " · " in text && !text.startsWith("@")) audio = text.trim()
            if (className.endsWith("RadioButton") && description != null) {
                when {
                    LIKE_MARKER in description -> {
                        entry.likeButton = node
                        entry.likeCount = CountParser.parse(description) ?: entry.likeCount
                    }
                    description == SAVE_LABEL -> entry.saveButton = node
                }
            }
            if (className.endsWith("Button") && description?.startsWith(COMMENTS_PREFIX) == true) {
                entry.commentCount = CountParser.parse(description) ?: entry.commentCount
            }
        }
        entry.title = entry.title ?: title
        entry.audio = entry.audio ?: audio
        // Ads often have no channel row; name them after the advertiser's title instead.
        entry.creator = entry.creator ?: channel ?: if (entry.isAd) "ad:${adTitle ?: title ?: "unknown"}" else null
    }

    private fun readState(root: AccessibilityNodeInfo, entry: PageCache, sinceFirstMs: Long) {
        entry.likeButton?.takeIf { it.refresh() }?.let { entry.liked = it.isCheckedCompat }
        entry.saveButton?.takeIf { it.refresh() }?.let { entry.saved = it.isCheckedCompat }
        // Comments open in an engagement panel over the shrunken player; the same panel also
        // shows descriptions and remixes, so check its title.
        val panels = root.findById(ENGAGEMENT_PANEL)
        val titles = root.findById(PANEL_TITLE).map { it.text?.toString()?.trim() }
        entry.commentsOpen = panels.any { it.isVisibleToUser } && titles.any { it in COMMENT_TITLES }
        if (BuildConfig.DEBUG && (panels.isNotEmpty() || titles.isNotEmpty()) != lastPanelSeen) {
            lastPanelSeen = panels.isNotEmpty() || titles.isNotEmpty()
            Log.d(TAG, "engagement panels=${panels.size} visible=${panels.count { it.isVisibleToUser }} titles=$titles")
        }
        // The time bar only appears now and then. Right after a swipe it can still show the
        // previous short, so a position further than this page could have played is ignored.
        val timeBar = root.findById(TIME_BAR).firstOrNull() ?: return
        val seekBar = (0 until timeBar.childCount).mapNotNull(timeBar::getChild)
            .firstOrNull { it.className?.toString()?.endsWith("SeekBar") == true } ?: return
        val (positionMs, durationMs) = seekBar.contentDescription?.toString()?.let(::parseTimeBar) ?: return
        if (positionMs <= sinceFirstMs + TIME_BAR_SLACK_MS) {
            entry.positionMs = positionMs
            entry.durationMs = durationMs
        }
    }

    private fun visit(node: AccessibilityNodeInfo, depth: Int = 0, action: (AccessibilityNodeInfo) -> Unit) {
        action(node)
        if (depth >= MAX_DEPTH) return
        for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1, action) }
    }

    private fun AccessibilityNodeInfo.findById(id: String): List<AccessibilityNodeInfo> =
        findAccessibilityNodeInfosByViewId("$PACKAGE:id/$id")

    private fun AccessibilityNodeInfo.bounds(): Rect = Rect().also(::getBoundsInScreen)

    private companion object {
        const val TAG = "YouTubeShorts"
        const val PACKAGE = "com.google.android.youtube"
        const val RECYCLER = "reel_recycler"
        const val TIME_BAR = "reel_time_bar"
        const val LIVE_PLAYER = "immersive_live_player"
        const val ENGAGEMENT_PANEL = "app_engagement_panel"
        const val PANEL_TITLE = "modern_title"
        val COMMENT_TITLES = setOf("댓글", "Comments")
        const val LIKE_MARKER = "좋아요 표시"
        const val SAVE_LABEL = "저장"
        const val COMMENTS_PREFIX = "댓글"
        const val SETTLED_SHARE = 0.75
        const val MAX_DEPTH = 25
        const val STATE_INTERVAL_MS = 1_000L
        const val OTHER_ITEM_MIN_MS = 700L
        const val TIME_BAR_SLACK_MS = 1_500L

        /** When details are read, measured from the moment the page settled. */
        val DETAIL_READ_TIMES_MS = longArrayOf(0, 700, 2_000, 4_000)
    }
}

private val AD_LABELS = setOf("광고", "스폰서", "Sponsored", "Ad")
private val HOURS = Regex("""(\d+)\s*시간""")
private val MINUTES = Regex("""(\d+)\s*분""")
private val SECONDS = Regex("""(\d+)\s*초""")

/** "@duktem 채널로 이동" -> "duktem". */
internal fun channelFromDescription(description: String): String? {
    val trimmed = description.trim()
    if (!trimmed.endsWith(" 채널로 이동")) return null
    return trimmed.removeSuffix(" 채널로 이동").trim().removePrefix("@").ifEmpty { null }
}

/**
 * An ad slot labels itself "광고" / "스폰서", on its own or as one line of a card
 * ("아쿠아랜드…\n광고\n별점 4.6"). Sponsored content by creators ("유료 광고 포함",
 * "수수료 지급") is not an ad slot and doesn't match.
 */
internal fun isAdLabel(text: CharSequence): Boolean = text.toString().lines().any { it.trim() in AD_LABELS }

/** Creator content with a sponsorship or affiliate disclosure. Recorded, not skipped. */
internal fun isPaidPromotionLabel(text: CharSequence): Boolean {
    val value = text.toString().trim()
    return value == "수수료 지급" || "유료 광고 포함" in value || "Includes paid promotion" in value
}

/** "0분 58초 중 0분 8초" -> (positionMs = 8000, durationMs = 58000). */
internal fun parseTimeBar(description: String): Pair<Long, Long>? {
    val parts = description.split(" 중 ")
    if (parts.size != 2) return null
    val durationMs = parseKoreanDuration(parts[0]) ?: return null
    val positionMs = parseKoreanDuration(parts[1]) ?: return null
    return positionMs to durationMs
}

internal fun parseKoreanDuration(text: String): Long? {
    val units = listOf(HOURS to 3_600_000L, MINUTES to 60_000L, SECONDS to 1_000L)
    val matches = units.mapNotNull { (regex, ms) -> regex.find(text)?.groupValues?.get(1)?.toLongOrNull()?.times(ms) }
    return if (matches.isEmpty()) null else matches.sum()
}
