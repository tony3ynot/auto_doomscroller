# Data format

The app exports everything it knows as JSON Lines plus optional thumbnails. This is the
contract between the Android app (`Exporter.kt`) and the offline tooling (`ml/`).

```
exports/<yyyyMMdd-HHmmss>/
├── sessions.jsonl
├── reels.jsonl
└── thumbnails/            # research mode only
    ├── <reel_id>_<frame_index>.jpg
    └── <reel_id>_hires.jpg
```

Pull an export from the phone with:

```bash
adb pull /sdcard/Android/data/com.autodoomscroller/files/exports ./data/
```

## sessions.jsonl

One line per session (from leaving Off until returning to Off).

| Field | Type | Meaning |
|---|---|---|
| `id` | int | Session ID |
| `started_at_ms` | int | Unix time in ms |
| `ended_at_ms` | int or null | Null if the app was killed mid-session |
| `device_model` | string | Manufacturer and model |

## reels.jsonl

One line per reel the user landed on.

| Field | Type | Meaning |
|---|---|---|
| `id` | int | Reel row ID, also used in thumbnail file names |
| `session_id` | int | Session it belongs to |
| `package` | string | Source app: `com.instagram.android` or `com.google.android.youtube` (Shorts only) |
| `reel_key` | string | Best-effort identity of the reel (creator + caption hash) |
| `creator` | string or null | Creator handle as shown on screen |
| `caption` | string or null | Caption text as shown on screen (may be truncated) |
| `audio` | string or null | Audio / music label |
| `media_type` | `VIDEO`, `IMAGE`, `CAROUSEL` or null | Kind of post (null in rows from before it was detected) |
| `is_ad` | bool | An ad slot ("광고" / "스폰서" / "Sponsored") |
| `paid_promotion` | bool | Creator content with a sponsorship or affiliate disclosure ("유료 광고 포함", "수수료 지급"). Not an ad slot: recorded and labeled like any reel |
| `mode` | `LEARNING` or `AUTO` | Mode at the time the reel started |
| `started_at_ms` | int | When the reel appeared |
| `ended_at_ms` | int | When it was replaced, or the user left |
| `paused_ms` | int | Time spent away from the feed mid-reel (notification shade, quick trip to DMs) before coming back to the same reel within 30 s. Dwell = `ended_at_ms` − `started_at_ms` − `paused_ms` |
| `moved_to_next` | bool | Ended by swiping to another reel (false: left the app or stopped) |
| `looped` | bool | Playback wrapped around to the start at least once |
| `duration_ms` | int or null | Length of the video, if known |
| `duration_source` | `SCRUBBER`, `LOOP` or null | Read from the on-screen scrubber, or estimated by seeing the video start over |
| `max_position_ms` | int or null | Furthest playback position read from the scrubber |
| `loop_estimate_ms` | int or null | Length estimated by frame-based loop detection. In research mode it is also recorded for about one in four reels with a scrubber, so `duration_ms` can serve as ground truth for the estimate |
| `liked` | bool | The like button was on when the reel ended |
| `saved` | bool | The save button was on when the reel ended |
| `opened_comments` | bool | The comment sheet was opened at some point |
| `carousel_swipes` | int | Times the user swiped between carousel slides |
| `like_count` | int or null | Like count shown on the reel (null if hidden) |
| `comment_count` | int or null | Comment count shown on the reel |
| `auto_skipped` | bool | Auto mode swiped past it |
| `undone` | bool | The user came back to it right after Auto mode skipped it |
| `revisit` | bool | The user scrolled back to it: same feed position and creator as an earlier reel |
| `feed_index` | int or null | Position in the feed as reported by the app; restarts when the feed refreshes |
| `label` | `SKIP`, `WATCH` or null | Label computed on device (see below) |
| `frame_count` | int | Frames captured for this reel |

## Labels

Raw behaviour is exported next to the label, so the rule can be re-applied with different
thresholds offline (`autodoom.labels.relabel`). The rule, in order:

1. `is_ad` or `auto_skipped` → no label (not content the user chose, or never watched)
2. `undone` → `WATCH` (a correction; weight it higher when training)
3. engaged (`liked`, `saved`, `opened_comments` or `carousel_swipes` > 0) → `WATCH`
4. idle → no label: stayed longer than 3× `duration_ms` (at least 30 s), or longer than
   60 s when the length is unknown, without engaging. Likely the phone was left alone
5. completed or dwell ≥ 5 s → `WATCH`
6. `moved_to_next` and dwell < 2 s and not `revisit` → `SKIP`
7. anything else → no label

"Completed" means `looped`, or `max_position_ms` ≥ 90% of `duration_ms`.

The label answers what Auto mode decides in the first second and a half: would the user
swipe away on first impression? That doesn't depend on length or media type, so `WATCH`
means "gave it a chance", not "watched it all". How much the user liked a reel (watch
ratio for videos, dwell percentile within the media type for images and carousels) is
derived offline from the raw fields.

### Video length

Some reels show a scrubber, which gives the exact position and length. For videos without
one, the app compares a small fingerprint of the screen about once a second with the first
frames of the reel; when the video is seen starting over, the elapsed time is its length
(`duration_source = LOOP`). Near-static videos may never produce an estimate. Images and
carousels have no length.

## Thumbnails

Saved only when research mode is on, JPEG, original aspect ratio:

- `<reel_id>_<frame_index>.jpg`: 224 px wide, taken about 0.3 s, 0.8 s and 1.3 s into the
  reel. The encoder input (224×224 center crop) is produced from these.
- `<reel_id>_hires.jpg`: the 1.3 s frame at 540 px wide, so on-screen text (subtitles, meme
  captions) is readable for OCR.
