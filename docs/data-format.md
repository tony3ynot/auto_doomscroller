# Data format

The app exports everything it knows as JSON Lines plus optional thumbnails. This is the
contract between the Android app (`Exporter.kt`) and the offline tooling (`ml/`).

```
exports/<yyyyMMdd-HHmmss>/
├── sessions.jsonl
├── reels.jsonl
└── thumbnails/            # research mode only
    └── <reel_id>_<frame_index>.jpg
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
| `package` | string | Source app, e.g. `com.instagram.android` |
| `reel_key` | string | Best-effort identity of the reel (creator + caption hash) |
| `creator` | string or null | Creator handle as shown on screen |
| `caption` | string or null | Caption text as shown on screen (may be truncated) |
| `audio` | string or null | Audio / music label |
| `media_type` | `VIDEO`, `IMAGE`, `CAROUSEL` or null | Kind of post (null in rows from before it was detected) |
| `is_ad` | bool | Shown as an ad ("광고" / "Sponsored") |
| `mode` | `LEARNING` or `AUTO` | Mode at the time the reel started |
| `started_at_ms` | int | When the reel appeared |
| `ended_at_ms` | int | When it was replaced, or the user left |
| `moved_to_next` | bool | Ended by swiping to another reel (false: left the app or stopped) |
| `looped` | bool | Playback wrapped around to the start at least once |
| `duration_ms` | int or null | Length of the video, if known |
| `duration_source` | `SCRUBBER`, `LOOP` or null | Read from the on-screen scrubber, or estimated by seeing the video start over |
| `max_position_ms` | int or null | Furthest playback position read from the scrubber |
| `loop_estimate_ms` | int or null | Length estimated by frame-based loop detection. In research mode it is also recorded for reels with a scrubber, so `duration_ms` can serve as ground truth for the estimate |
| `liked` | bool | The like button was on when the reel ended |
| `saved` | bool | The save button was on when the reel ended |
| `opened_comments` | bool | The comment sheet was opened at some point |
| `like_count` | int or null | Like count shown on the reel (null if hidden) |
| `comment_count` | int or null | Comment count shown on the reel |
| `auto_skipped` | bool | Auto mode swiped past it |
| `undone` | bool | The user came back to it right after Auto mode skipped it |
| `revisit` | bool | Seen earlier in the session (the user scrolled back to it) |
| `feed_index` | int or null | Position in the feed as reported by the app; restarts when the feed refreshes |
| `label` | `SKIP`, `WATCH` or null | Label computed on device (see below) |
| `frame_count` | int | Frames captured for this reel |

## Labels

Raw behaviour is exported next to the label, so the rule can be re-applied with different
thresholds offline (`autodoom.labels.relabel`). The rule, in order:

1. `is_ad` or `auto_skipped` → no label (not content the user chose, or never watched)
2. `undone` → `WATCH` (a correction; weight it higher when training)
3. `liked` or `saved` or completed or dwell ≥ 5 s → `WATCH`
4. `moved_to_next` and dwell < 2 s and not `revisit` → `SKIP`
5. anything else → no label

"Completed" means `looped`, or `max_position_ms` ≥ 90% of `duration_ms`.

### Video length

Some reels show a scrubber, which gives the exact position and length. For videos without
one, the app compares a small fingerprint of the screen about once a second with the first
frames of the reel; when the video is seen starting over, the elapsed time is its length
(`duration_source = LOOP`). Near-static videos may never produce an estimate. Images and
carousels have no length.

## Thumbnails

Saved only when research mode is on. Width 224 px, original aspect ratio, JPEG. The
encoder input (224×224 center crop) is produced from these at inference time.
