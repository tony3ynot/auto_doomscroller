"""Text on the frame (hook text, subtitles) read with OCR, for offline experiments.

On the phone this would be ML Kit's on-device text recognizer; offline EasyOCR (Korean +
English) stands in for it. Raw results, with each word's box relative to the frame, are cached
in ``<export>/cache/ocr_<source>*.jsonl`` so the cleaning rules can change without re-reading.

    python -m autodoom.ocr data/<export> --source first     # 224 px first frame, upscaled 2x
    python -m autodoom.ocr data/<export> --source hires     # 540 px frame

EasyOCR gets slower with more threads on a CPU, so for speed run several single-threaded
shards side by side (``--shards 3 --shard 0`` ... ``--shard 2``).

Frames from before schema v9 hold the whole screen; the status bar and navigation are cut off
first so they look like the cropped frames the app saves now.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import numpy as np
import pandas as pd

SOURCES = ("first", "hires")
# Old full-screen captures on the 1080x2340 test phone: the feed spans y = 103..2052.
FEED_TOP, FEED_BOTTOM, SCREEN_HEIGHT = 103, 2052, 2340
UPSCALE_BELOW_WIDTH = 400
MIN_CONFIDENCE = 0.3
# Instagram's tabs and YouTube's tool bar end about 6% of the way down the feed.
TOP_BAR = 0.075

# App UI that OCR picks up on almost every frame.
UI_WORDS = {
    "팔로우", "팔로잉", "구독", "공유", "리믹스", "저장", "더 보기", "더보기", "내 스토리", "회원님을 위한 추천",
    "instagram", "shorts", "follow", "subscribe", "share", "remix", "more",
}
COUNT = re.compile(r"^[\d.,]+\s*(천|만|억|k|m|b|회|개|명)?$", re.IGNORECASE)
LETTERS = re.compile(r"[가-힣A-Za-z]")


def frame_path(export_dir: Path, reel_id: int, source: str) -> Path | None:
    name = f"{reel_id}_0.jpg" if source == "first" else f"{reel_id}_hires.jpg"
    path = export_dir / "thumbnails" / name
    return path if path.exists() else None


def prepare(path: Path, full_screen: bool):
    from PIL import Image

    image = Image.open(path).convert("RGB")
    if full_screen:
        h = image.height
        image = image.crop((0, h * FEED_TOP // SCREEN_HEIGHT, image.width, h * FEED_BOTTOM // SCREEN_HEIGHT))
    if image.width < UPSCALE_BELOW_WIDTH:
        image = image.resize((image.width * 2, image.height * 2), Image.BICUBIC)
    return image


def read_words(reader, image) -> list[dict]:
    """Words with confidence and box (x0, y0, x1, y1) as fractions of the frame."""
    w, h = image.size
    words = []
    for box, text, conf in reader.readtext(np.asarray(image)):
        xs, ys = [p[0] for p in box], [p[1] for p in box]
        words.append({
            "text": text, "conf": round(float(conf), 3),
            "box": [round(min(xs) / w, 4), round(min(ys) / h, 4), round(max(xs) / w, 4), round(max(ys) / h, 4)],
        })
    return words


def load_raw(export_dir: str | Path, source: str) -> dict[int, list[dict]]:
    """Every cached result for ``source``, from all shard files."""
    rows = []
    for path in sorted((Path(export_dir) / "cache").glob(f"ocr_{source}*.jsonl")):
        rows += [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    return {row["id"]: row["words"] for row in rows}


def clean_text(words: list[dict], known: tuple[str | None, ...] = ()) -> str | None:
    """The frame's own text, without app UI.

    Drops low-confidence words, the app's tab / tool bar across the top (OCR misspells its
    labels, so position decides), the action-button column on the right, the author / caption
    block at the bottom (the app reads those itself), UI labels, bare counts, and anything
    repeating ``known`` strings such as the creator name.
    """
    known_lower = [k.lower() for k in known if isinstance(k, str) and k]
    kept = []
    for word in sorted(words, key=lambda w: (round(w["box"][1], 2), w["box"][0])):
        text = word["text"].strip()
        x0, y0, x1, y1 = word["box"]
        if word["conf"] < MIN_CONFIDENCE or len(LETTERS.findall(text)) < 2:
            continue
        if y1 < TOP_BAR or x0 > 0.82 or y0 > 0.80:
            continue
        lower = text.lower()
        if lower in UI_WORDS or COUNT.match(text) or any(lower in k or k in lower for k in known_lower):
            continue
        kept.append(text)
    return " ".join(kept) if kept else None


def main() -> None:
    import easyocr
    import torch

    from .data import load_export

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--source", choices=SOURCES, required=True)
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--shards", type=int, default=1)
    parser.add_argument("--shard", type=int, default=0)
    parser.add_argument("--ids", type=Path, help="only these reel ids (one per line)")
    args = parser.parse_args()

    torch.set_num_threads(args.threads)
    _, reels = load_export(args.export)
    if args.ids:
        wanted = {int(x) for x in args.ids.read_text().split()}
        reels = reels[reels["id"].isin(wanted)]
    full_screen = (reels["first_frame_ms"].isna() if "first_frame_ms" in reels else pd.Series(True, reels.index))
    done = load_raw(args.export, args.source)
    suffix = f".part{args.shard}" if args.shards > 1 else ""
    out = args.export / "cache" / f"ocr_{args.source}{suffix}.jsonl"
    out.parent.mkdir(exist_ok=True)
    reader = easyocr.Reader(["ko", "en"], gpu=False, verbose=False)
    todo = [(int(r.id), bool(f)) for r, f in zip(reels.itertuples(), full_screen)
            if int(r.id) not in done and int(r.id) % args.shards == args.shard]
    with out.open("a") as f:
        for n, (reel_id, full) in enumerate(todo, 1):
            path = frame_path(args.export, reel_id, args.source)
            if path is None:
                continue
            words = read_words(reader, prepare(path, full))
            f.write(json.dumps({"id": reel_id, "words": words}, ensure_ascii=False) + "\n")
            f.flush()
            if n % 50 == 0:
                print(f"{args.source}: {n}/{len(todo)}", flush=True)
    print(f"{args.source}: done, {len(load_raw(args.export, args.source))} frames")


if __name__ == "__main__":
    main()
