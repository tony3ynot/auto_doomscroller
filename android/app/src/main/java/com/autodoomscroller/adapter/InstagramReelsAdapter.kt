package com.autodoomscroller.adapter

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.autodoomscroller.tracking.MediaType
import com.autodoomscroller.tracking.ReelSnapshot

/**
 * Reads the Instagram Reels viewer. View IDs come from node dumps of the Korean UI
 * (October 2026) and will need updating when Instagram changes its layout.
 *
 * Every node lookup is an IPC call into Instagram, and the scrubber makes Instagram send
 * events continuously, so reads are tiered: [read] only works out which reel is on screen;
 * playback and button state are refreshed every [STATE_INTERVAL_MS]; details that don't
 * change during a reel are read a few times right after it appears.
 */
class InstagramReelsAdapter(private val clock: () -> Long = SystemClock::uptimeMillis) : TargetAppAdapter {
    override val packageName = PACKAGE

    private data class Details(
        val caption: String?,
        val audio: String?,
        val mediaType: MediaType,
        val isAd: Boolean,
        val likeCount: Long?,
        val commentCount: Long?,
    )

    private data class State(
        val liked: Boolean?,
        val saved: Boolean?,
        val positionMs: Long?,
        val durationMs: Long?,
        val commentsOpen: Boolean,
    )

    private class PageCache(val page: AccessibilityNodeInfo, val creator: String, val firstSeenAtMs: Long) {
        var details: Details? = null
        var detailReads = 0
        var state: State? = null
        var lastStateAtMs = 0L
    }

    private var cache: PageCache? = null

    /** A page without an author row, and since when; see [otherItem]. */
    private var otherPage: AccessibilityNodeInfo? = null
    private var otherSinceMs = 0L
    private var otherIsAd: Boolean? = null

    override fun read(root: AccessibilityNodeInfo): FeedState {
        // Other tabs (DMs, profile) keep the Reels pager alive off screen, so visibility matters.
        val pager = root.findById(PAGER).firstOrNull { it.isVisibleToUser }
        if (pager == null) {
            cache = null
            return FeedState.NotOnFeed
        }
        // One visible page per reel; two while a swipe is in progress.
        val page = (0 until pager.childCount).mapNotNull(pager::getChild).filter { it.isVisibleToUser }.singleOrNull()
            ?: return FeedState.Unknown
        // The author row hides while the user holds the screen; the video's description still names them.
        val creator = (page.findById(USERNAME).firstOrNull()?.text?.toString() ?: creatorFromMedia(page))
            ?.let(::leadCreator).orEmpty()
        if (creator.isEmpty()) return otherItem(page)
        otherPage = null

        val now = clock()
        val entry = cache?.takeIf { it.creator == creator && it.page == page }
            ?: PageCache(page, creator, now).also { cache = it }
        if (entry.detailReads < DETAIL_READ_TIMES_MS.size &&
            now - entry.firstSeenAtMs >= DETAIL_READ_TIMES_MS[entry.detailReads]
        ) {
            entry.details = readDetails(page, creator, entry.details)
            entry.detailReads++
        }
        if (entry.state == null || now - entry.lastStateAtMs >= STATE_INTERVAL_MS) {
            entry.state = readState(root, page)
            entry.lastStateAtMs = now
        }
        return FeedState.OnReel(snapshotOf(entry))
    }

    /**
     * Regular reels show their author row within a few hundred milliseconds. A page that still
     * has none after [OTHER_ITEM_MIN_MS] is something else, such as a multi-ad collage.
     */
    private fun otherItem(page: AccessibilityNodeInfo): FeedState {
        val now = clock()
        if (otherPage != page) {
            otherPage = page
            otherSinceMs = now
            otherIsAd = null
        }
        if (now - otherSinceMs < OTHER_ITEM_MIN_MS) return FeedState.Unknown
        cache = null
        val isAd = otherIsAd ?: looksLikeAd(page).also { otherIsAd = it }
        return FeedState.OtherItem(isAd)
    }

    private fun creatorFromMedia(page: AccessibilityNodeInfo): String? =
        listOf(VIDEO_CONTAINER, MEDIA_COMPONENT).firstNotNullOfOrNull { id ->
            page.findById(id).firstOrNull()?.contentDescription?.toString()?.let(::creatorFromMediaDescription)
        }

    /** Layout-agnostic check for unfamiliar items: an ad label or an ad-specific view ID anywhere. */
    private fun looksLikeAd(page: AccessibilityNodeInfo): Boolean = page.firstDescendant { node ->
        listOfNotNull(node.text, node.contentDescription).any { it.toString().trim() in AD_LABELS } ||
            node.viewIdResourceName?.substringAfter(":id/")?.let { "_ads" in it || it.startsWith("ads_") } == true
    } != null

    private fun snapshotOf(entry: PageCache): ReelSnapshot {
        val details = checkNotNull(entry.details)
        val state = checkNotNull(entry.state)
        return ReelSnapshot(
            packageName = PACKAGE,
            creator = entry.creator,
            caption = details.caption,
            audio = details.audio,
            mediaType = details.mediaType,
            isAd = details.isAd,
            liked = state.liked,
            saved = state.saved,
            likeCount = details.likeCount,
            commentCount = details.commentCount,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            commentsOpen = state.commentsOpen,
            feedIndex = entry.page.collectionItemInfo?.rowIndex,
        )
    }
    /** Caption, ad label, etc. can render late, so later reads fill in what earlier ones missed. */
    private fun readDetails(page: AccessibilityNodeInfo, creator: String, previous: Details?): Details {
        val mediaType = readMediaType(page)
        return Details(
            caption = readCaption(page) ?: previous?.caption,
            audio = readAudio(page) ?: previous?.audio,
            mediaType = if (mediaType != MediaType.VIDEO) mediaType else previous?.mediaType ?: mediaType,
            isAd = previous?.isAd == true || isAd(page, creator),
            likeCount = page.findById(LIKE_COUNT).firstOrNull()?.text?.let(CountParser::parse) ?: previous?.likeCount,
            commentCount = page.findById(COMMENT_COUNT).firstOrNull()?.text?.let(CountParser::parse)
                ?: previous?.commentCount,
        )
    }

    private fun readState(root: AccessibilityNodeInfo, page: AccessibilityNodeInfo): State {
        // The scrubber's range is the playback position and length in milliseconds.
        val scrubber = page.findById(SCRUBBER).firstOrNull { it.isVisibleToUser }?.rangeInfo
        return State(
            liked = page.findById(LIKE_BUTTON).firstOrNull()?.isSelected,
            saved = page.findById(SAVE_BUTTON).firstOrNull()?.isSelected,
            positionMs = scrubber?.current?.toLong(),
            durationMs = scrubber?.max?.toLong()?.takeIf { it > 0 },
            commentsOpen = root.findById(COMMENT_INPUT).any { it.isVisibleToUser },
        )
    }

    private fun readMediaType(page: AccessibilityNodeInfo): MediaType = when {
        page.findById(CAROUSEL).isNotEmpty() -> MediaType.CAROUSEL
        page.findById(SINGLE_IMAGE).isNotEmpty() || page.findById(SINGLE_IMAGE_AD).isNotEmpty() -> MediaType.IMAGE
        else -> MediaType.VIDEO
    }

    /**
     * Ads say so in three places: an ad-specific media view, the video container's description
     * ("tokscaleai님의 광고 릴스" instead of "…님이 만든 릴스입니다"), and a "광고" label next to
     * the action buttons. The label is a virtual node, which findAccessibilityNodeInfosByText
     * can't see, so it is found by walking the action-button component instead.
     */
    private fun isAd(page: AccessibilityNodeInfo, creator: String): Boolean {
        if (page.findById(SINGLE_IMAGE_AD).isNotEmpty()) return true
        val container = page.findById(VIDEO_CONTAINER).firstOrNull()?.contentDescription?.toString()
        // Drop the creator's name first so an account called e.g. "광고천재" doesn't count.
        if (container != null && AD_MARKERS.any { container.replace(creator, "").contains(it, ignoreCase = true) }) {
            return true
        }
        val actions = page.findById(UFI_COMPONENT).firstOrNull() ?: return false
        return actions.firstDescendant { node ->
            listOfNotNull(node.text, node.contentDescription).any { it.toString().trim() in AD_LABELS }
        } != null
    }

    /** Collapsed caption, e.g. "고가치남 멘트 모음 …". Null when there is none. */
    private fun readCaption(page: AccessibilityNodeInfo): String? {
        val component = page.findById(CAPTION).firstOrNull() ?: return null
        val text = component.firstDescendant { it.isClickable && !it.contentDescription.isNullOrBlank() }
            ?.contentDescription?.toString() ?: return null
        return text.removeSuffix("…").trim().takeUnless { it.isEmpty() || it in MORE_LABELS }
    }

    /** Audio label such as "Marc Scibilia · Bittersweet Symphony". */
    private fun readAudio(page: AccessibilityNodeInfo): String? {
        val author = page.findById(AUTHOR_INFO).firstOrNull() ?: return null
        return author.firstDescendant {
            it.viewIdResourceName == null && it.contentDescription?.contains(" · ") == true
        }?.contentDescription?.toString()
    }

    private fun AccessibilityNodeInfo.findById(id: String): List<AccessibilityNodeInfo> =
        findAccessibilityNodeInfosByViewId("$PACKAGE:id/$id")

    private fun AccessibilityNodeInfo.firstDescendant(
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(this to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (node !== this && predicate(node)) return node
            if (depth >= MAX_DEPTH) continue
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to depth + 1) }
        }
        return null
    }

    private companion object {
        const val PACKAGE = "com.instagram.android"
        const val PAGER = "clips_viewer_view_pager"
        const val USERNAME = "clips_author_username"
        const val AUTHOR_INFO = "clips_author_info_component"
        const val CAPTION = "clips_caption_component"
        const val LIKE_BUTTON = "like_button"
        const val LIKE_COUNT = "like_count"
        const val COMMENT_COUNT = "comment_count"
        const val SAVE_BUTTON = "save_button"
        const val COMMENT_INPUT = "layout_comment_thread_edittext_multiline"
        const val SCRUBBER = "scrubber"
        const val CAROUSEL = "clips_carousel_viewpager"
        const val SINGLE_IMAGE = "clips_single_image_media_content"
        const val SINGLE_IMAGE_AD = "clips_single_image_ads_media_content"
        const val VIDEO_CONTAINER = "clips_video_container"
        const val MEDIA_COMPONENT = "clips_media_component"
        const val UFI_COMPONENT = "clips_ufi_component"
        const val MAX_DEPTH = 20
        const val STATE_INTERVAL_MS = 1_000L
        const val OTHER_ITEM_MIN_MS = 700L

        /** When details are read, measured from the moment the reel appeared. */
        val DETAIL_READ_TIMES_MS = longArrayOf(0, 700, 2_000, 4_000)
        val AD_LABELS = setOf("광고", "Sponsored")
        val AD_MARKERS = listOf("광고", "sponsored")
        val MORE_LABELS = setOf("더 보기", "more", "More")
    }
}

private val COLLAB_SUFFIX = Regex("""\s+(외\s*\d+명|and\s+\d+\s+others?)$""")
private val COLLAB_PAIR = Regex("""^(\S+)님과\s+\S+?(님)?$|^(\S+)\s+and\s+\S+$""")
private val MEDIA_DESCRIPTION = Regex("""^(.+?)님(?:이 만든 릴스|의 광고 릴스)""")

/**
 * Collab posts show "wo.0z 외 2명", "plforindia님과 berbo9님", "wo.0z and 2 others" or
 * "a and b"; keep the lead creator.
 */
internal fun leadCreator(text: String): String {
    val trimmed = text.trim().replace(COLLAB_SUFFIX, "").trim()
    val pair = COLLAB_PAIR.matchEntire(trimmed) ?: return trimmed
    return pair.groupValues[1].ifEmpty { pair.groupValues[3] }
}

/** "peterang8659님이 만든 릴스입니다…" or "daelim_univ님의 광고 릴스…" -> the creator. */
internal fun creatorFromMediaDescription(description: String): String? =
    MEDIA_DESCRIPTION.find(description.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
