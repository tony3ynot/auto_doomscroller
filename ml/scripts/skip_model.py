"""First skip model: which signals predict a first-impression skip?

    python ml/scripts/skip_model.py data/<export> --out results/<date>-skip-model

Evaluates feature sets with rolling-origin folds (each day from the third on is a test set,
trained on every earlier day) and writes metrics.json, summary.md, run.json and
auc_by_feature_set.pdf/png to ``--out``. Only aggregate numbers are written; nothing about
individual reels or creators.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from autodoom.experiment import METRIC_NOTES, auc_figure, build, evaluate, table
from autodoom.report import write_run

CONTEXT = [
    "youtube", "image", "carousel", "promotion", "log_likes", "log_comments", "has_caption",
    "log_caption_len", "has_audio", "hour_sin", "hour_cos", "log_session_position",
    "log_session_minutes", "log_prev_dwell", "recent_skip_rate",
]
CREATOR = ["log_creator_seen", "creator_skip_rate"]
TASTE = {
    "frame_mobilenet": "mobilenet_v3_small",
    "frame_clip": "clip_vit_b32",
    "caption_e5": "e5_small",
}
TASTE_MOBILENET = ["creator_skip_rate", "frame_mobilenet", "caption_e5"]
TASTE_CLIP = ["creator_skip_rate", "frame_clip", "caption_e5"]
# name -> (features, fitted). Unfitted sets have one feature, used directly as the score.
# "Taste" = creator history + frame similarity + caption similarity.
FEATURE_SETS = {
    "Majority": ([], False),
    "Creator history": (["creator_skip_rate"], False),
    "Frame sim. (MobileNetV3)": (["frame_mobilenet"], False),
    "Frame sim. (CLIP)": (["frame_clip"], False),
    "Caption sim. (e5)": (["caption_e5"], False),
    "Context": (CONTEXT, True),
    "Taste (MobileNetV3)": (TASTE_MOBILENET, True),
    "Taste (CLIP)": (TASTE_CLIP, True),
    "Context + taste (MobileNetV3)": (CONTEXT + CREATOR + TASTE_MOBILENET[1:], True),
    "Context + taste (CLIP)": (CONTEXT + CREATOR + TASTE_CLIP[1:], True),
}
# The set the app can ship: its frame encoder is the one quantized for the phone.
DEPLOYABLE = "Taste (MobileNetV3)"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--k", type=int, default=20, help="neighbours for the similarity scores")
    parser.add_argument("--c", type=float, default=1.0, help="inverse L2 strength of the logistic regression")
    args = parser.parse_args()

    data = build(args.export, args.k, TASTE)
    # Scrolling back to a reel isn't a first impression, and a reel without its first frame
    # can't be scored by the frame models; drop both so every feature set sees the same reels.
    eval_set = data[data["label"].notna() & ~data["revisit"] & data["has_frame"]]
    dropped = int(data["label"].notna().sum() - len(eval_set))
    results = {name: evaluate(eval_set, f, fitted, args.c) for name, (f, fitted) in FEATURE_SETS.items()}

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(json.dumps(results, indent=2) + "\n")
    auc_figure(results, args.out / "auc_by_feature_set", DEPLOYABLE)
    days = data["day"]
    run = write_run(
        args.out, "ml/scripts/skip_model.py", args.export, collection=[days.min(), days.max()], reels=len(data),
        labeled=int(data["label"].notna().sum()), evaluated=len(eval_set), dropped_revisit_or_no_frame=dropped,
        k=args.k, c=args.c,
    )

    lines = [
        "# Skip model, first pass",
        f"- Data: {run['collection'][0]} – {run['collection'][1]}, {run['evaluated']} labeled first "
        f"impressions ({dropped} revisits / reels without a first frame left out)",
        "- Folds: test on each day from the third on, train on all earlier days",
        f"- Similarity (sim.): skip share among the {args.k} most similar earlier labeled reels, by first frame",
        "  (0.3 s) or by caption + audio name. Taste = creator history + frame sim. + caption sim.",
        "- Context: app, media type, like / comment counts, caption length, audio, paid promotion, time of",
        "  day, position and minutes into the viewing session, previous reel's dwell, recent skip share",
        *METRIC_NOTES,
        "",
        *table(results),
    ]
    text = "\n".join(lines) + "\n"
    (args.out / "summary.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
