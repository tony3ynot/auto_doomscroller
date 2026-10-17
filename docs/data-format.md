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
| `mode` | `LEARNING` or `AUTO` | Mode at the time the reel started |
| `started_at_ms` | int | When the reel appeared |
| `ended_at_ms` | int | When it was replaced, or the user left |
| `moved_to_next` | bool | Ended by swiping to another reel (false: left the app or stopped) |
| `looped` | bool | Playback wrapped around to the start at least once |
| `liked` | bool | The like button was on at any point |
| `auto_skipped` | bool | Auto mode swiped past it |
| `undone` | bool | The user came back to it right after Auto mode skipped it |
| `label` | `SKIP`, `WATCH` or null | Label computed on device (see below) |
| `frame_count` | int | Frames captured for this reel |

## Labels

Raw behaviour is exported next to the label, so the rule can be re-applied with different
thresholds offline (`autodoom.labels.relabel`). The rule, in order:

1. `auto_skipped` → no label (the user never watched it)
2. `undone` → `WATCH` (a correction; weight it higher when training)
3. `liked` or `looped` or dwell ≥ 20 s → `WATCH`
4. `moved_to_next` and dwell < 2 s → `SKIP`
5. anything else → no label

## Thumbnails

Saved only when research mode is on. Width 224 px, original aspect ratio, JPEG. The
encoder input (224×224 center crop) is produced from these at inference time.
