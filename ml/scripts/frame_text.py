"""Does the text on the frame help? OCR of the frame as one more similarity score.

    python -m autodoom.ocr data/<export> --source first      # then --source hires
    python ml/scripts/frame_text.py data/<export> --out results/<date>-frame-text

Two comparisons, each on one fixed set of reels:
  A. Decision at the first frame, all labeled first impressions: text read from the 224 px
     first frame (upscaled; what exports before schema v9 have).
  B. Reels still on screen at 1.3 s, which have a 540 px frame: text from that sharper frame
     against text from the 224 px one, to see how much OCR quality matters. Since schema v9 the
     540 px frame is the first one, so B previews what the app records now.
Writes metrics.json, summary.md, run.json and auc_frame_text.pdf/png. Aggregates only.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from autodoom.experiment import METRIC_NOTES, auc_figure, build, evaluate, table
from autodoom.report import write_run

SIMILARITY = {
    "frame_mobilenet": "mobilenet_v3_small",
    "caption_e5": "e5_small",
    "text_first": "e5_ocr_first",
    "text_hires": "e5_ocr_hires",
}
TASTE = ["creator_skip_rate", "frame_mobilenet", "caption_e5"]
FIRST_FRAME_SETS = {
    "Taste (MobileNetV3)": (TASTE, True),
    "Frame text sim. (224 px)": (["text_first"], False),
    "Taste + frame text (224 px)": (TASTE + ["text_first", "has_text_first"], True),
}
SHARP_FRAME_SETS = {
    "Taste (MobileNetV3)": (TASTE, True),
    "Frame text sim. (224 px)": (["text_first"], False),
    "Frame text sim. (540 px)": (["text_hires"], False),
    "Taste + frame text (224 px)": (TASTE + ["text_first", "has_text_first"], True),
    "Taste + frame text (540 px)": (TASTE + ["text_hires", "has_text_hires"], True),
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--k", type=int, default=20, help="neighbours for the similarity scores")
    args = parser.parse_args()

    data = build(args.export, args.k, SIMILARITY)
    first = data[data["label"].notna() & ~data["revisit"] & data["has_frame"]]
    has_sharp = first["id"].map(lambda i: (args.export / "thumbnails" / f"{i}_hires.jpg").exists())
    sharp = first[has_sharp]
    results = {
        "first_frame": {name: evaluate(first, f, fitted) for name, (f, fitted) in FIRST_FRAME_SETS.items()},
        "sharp_frame": {name: evaluate(sharp, f, fitted) for name, (f, fitted) in SHARP_FRAME_SETS.items()},
    }

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(json.dumps(results, indent=2) + "\n")
    auc_figure(results["first_frame"], args.out / "auc_frame_text", "Taste + frame text (224 px)")
    days = data["day"]
    run = write_run(
        args.out, "ml/scripts/frame_text.py", args.export, collection=[days.min(), days.max()], reels=len(data),
        evaluated_first_frame=len(first), evaluated_sharp_frame=len(sharp), k=args.k,
        text_share_first=float(first["has_text_first"].mean()), text_share_sharp=float(sharp["has_text_hires"].mean()),
    )

    lines = [
        "# Text on the frame",
        f"- Data: {run['collection'][0]} – {run['collection'][1]}; folds test on each day from the third on",
        "- Frame text: OCR (EasyOCR, Korean + English) with the app's own UI, counts, the author row and",
        "  the creator's name removed, embedded with multilingual-e5-small. Similarity: skip share among",
        f"  the {args.k} most similar earlier labeled reels. Taste = creator history + frame sim. + caption sim.",
        *METRIC_NOTES,
        "",
        f"## A. First frame (0.3 s, 224 px), {len(first)} first impressions",
        f"Text found on {run['text_share_first']:.0%} of them.",
        "",
        *table(results["first_frame"]),
        "",
        f"## B. Still on screen at 1.3 s, with a 540 px frame: {len(sharp)} reels "
        f"({(sharp['label'] == 'SKIP').mean():.0%} SKIP)",
        f"Text found on {run['text_share_sharp']:.0%} of them (540 px).",
        "",
        *table(results["sharp_frame"]),
    ]
    text = "\n".join(lines) + "\n"
    (args.out / "summary.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
