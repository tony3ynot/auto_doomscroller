"""Does the text on the frame help? OCR of the first frame as one more similarity score.

    python -m autodoom.ocr data/<export> --source hires
    python ml/scripts/frame_text.py data/<export> --out results/<date>-frame-text [--until YYYY-MM-DD]

Since schema v9 the app keeps the first frame at 540 px (``<id>_hires.jpg``), sharp enough for
OCR; at 224 px much of the text is unreadable (results/2026-10-25-frame-text). The test set is
the labeled first impressions that have such a frame. Earlier reels whose 540 px frame was taken
later (at 1.3 s, before v9) still serve as neighbours: their labels are known by then.
Writes metrics.json, summary.md, run.json and auc_frame_text.pdf/png. Aggregates only.
"""

from __future__ import annotations

import argparse
import json
from datetime import date
from pathlib import Path

from autodoom.experiment import CONTEXT, CREATOR, METRIC_NOTES, auc_figure, build, evaluate, table
from autodoom.report import write_run

SIMILARITY = {
    "frame_mobilenet": "mobilenet_v3_small",
    "caption_e5": "e5_small",
    "frame_text": "e5_ocr_hires",
}
TASTE = ["creator_skip_rate", "frame_mobilenet", "caption_e5"]
TEXT = ["frame_text", "has_frame_text"]
FEATURE_SETS = {
    "Taste (MobileNetV3)": (TASTE, True),
    "Frame text sim.": (["frame_text"], False),
    "Taste + frame text": (TASTE + TEXT, True),
    "Context + taste": (CONTEXT + CREATOR + TASTE[1:], True),
    "Context + taste + frame text": (CONTEXT + CREATOR + TASTE[1:] + TEXT, True),
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--k", type=int, default=20, help="neighbours for the similarity scores")
    parser.add_argument("--until", type=date.fromisoformat, help="last day to use (YYYY-MM-DD), e.g. before a test day")
    args = parser.parse_args()

    data = build(args.export, args.k, SIMILARITY, args.until)
    sharp_first = data["first_frame_ms"].notna() & data["id"].map(
        lambda i: (args.export / "thumbnails" / f"{i}_hires.jpg").exists()
    )
    test = data[data["label"].notna() & ~data["revisit"] & data["has_frame"] & sharp_first]
    results = {name: evaluate(test, f, fitted) for name, (f, fitted) in FEATURE_SETS.items()}

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(json.dumps(results, indent=2) + "\n")
    auc_figure(results, args.out / "auc_frame_text", "Taste + frame text")
    run = write_run(
        args.out, "ml/scripts/frame_text.py", args.export, collection=[test["day"].min(), test["day"].max()],
        reels=len(data), evaluated=len(test), k=args.k, until=args.until,
        text_share=float(test["has_frame_text"].mean()), skip_share=float((test["label"] == "SKIP").mean()),
    )

    lines = [
        "# Text on the frame",
        f"- Data: {run['collection'][0]} – {run['collection'][1]}, {len(test)} labeled first impressions with a",
        f"  540 px first frame ({run['skip_share']:.0%} SKIP); text found on {run['text_share']:.0%} of them",
        "- Folds: test on each day from the third on, train on all earlier days",
        "- Frame text: OCR (EasyOCR, Korean + English) of the first frame with the app's own UI, counts, the",
        "  author row and the creator's name removed, embedded with multilingual-e5-small. Similarity: skip",
        f"  share among the {args.k} most similar earlier labeled reels. Taste = creator history + frame sim. +",
        "  caption sim. Context: as in the skip model (app, counts, time of day, place in the session, ...)",
        *METRIC_NOTES,
        "",
        *table(results),
    ]
    text = "\n".join(lines) + "\n"
    (args.out / "summary.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
