"""How should the skip model use the frame: similarity to past reels, or a learned head?

    python ml/scripts/frame_head.py data/<export> --out results/<date>-frame-head [--until YYYY-MM-DD]

Everything is added to the same base, context + creator history, and compared with it on the
same reels. A similarity score needs every past embedding kept as a memory to search; a head
is a few principal components of the embedding fed to the logistic regression, which on the
phone folds into one weight vector. Writes metrics.json, summary.md, run.json and
auc_frame_head.pdf/png. Aggregates only.
"""

from __future__ import annotations

import argparse
import json
from datetime import date
from pathlib import Path

import numpy as np

from autodoom.embed import embeddings
from autodoom.experiment import (CONTEXT, CREATOR, METRIC_NOTES, auc_change, auc_figure, build, fold_scores,
                                 summarize, table, with_embedding)
from autodoom.report import write_run

SIMILARITY = {"frame_mobilenet": "mobilenet_v3_small", "caption_e5": "e5_small"}
BASE = "Context + creator"
# name -> (similarity scores, embedding model for a head, principal components)
SETS = {
    BASE: ([], None, 0),
    "+ frame sim.": (["frame_mobilenet"], None, 0),
    "+ caption sim.": (["caption_e5"], None, 0),
    "+ frame sim. + caption sim.": (["frame_mobilenet", "caption_e5"], None, 0),
    "+ frame head, 4 PCs": ([], "mobilenet_v3_small", 4),
    "+ frame head, 8 PCs": ([], "mobilenet_v3_small", 8),
    "+ frame head, 16 PCs": ([], "mobilenet_v3_small", 16),
    "+ frame head, 32 PCs": ([], "mobilenet_v3_small", 32),
    "+ frame head (CLIP), 8 PCs": ([], "clip_vit_b32", 8),
    "+ caption head, 8 PCs": ([], "e5_small", 8),
}
# What goes on the phone: one image encoder, no text encoder, no memory of past embeddings.
DEPLOYABLE = "+ frame head, 8 PCs"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--k", type=int, default=20, help="neighbours for the similarity scores")
    parser.add_argument("--c", type=float, default=1.0, help="inverse L2 strength of the logistic regression")
    parser.add_argument("--until", type=date.fromisoformat, help="last day to use (YYYY-MM-DD), e.g. before a test day")
    parser.add_argument("--without", type=date.fromisoformat, nargs="+", default=[],
                        help="days to leave out altogether (YYYY-MM-DD), e.g. ones spent testing the app")
    args = parser.parse_args()

    data = build(args.export, args.k, SIMILARITY, args.until, args.without)
    columns = {}
    for model in sorted({m for _, m, _ in SETS.values() if m}):
        # A reel without a caption gets the zero vector; has_caption is already in the context.
        vectors = np.nan_to_num(embeddings(args.export, data, model).to_numpy())
        data, columns[model] = with_embedding(data, model, vectors)
    eval_set = data[data["label"].notna() & ~data["revisit"] & data["has_frame"]]

    base = CONTEXT + CREATOR
    scores = {name: fold_scores(eval_set, base + sims, True, args.c, columns.get(model, ()), pcs)
              for name, (sims, model, pcs) in SETS.items()}
    results = {}
    for name, (sims, model, pcs) in SETS.items():
        results[name] = summarize(scores[name], base + sims + ([f"{model}: {pcs} principal components"] if model else []))
        if name != BASE:
            results[name] |= auc_change(scores[name], scores[BASE])

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(json.dumps(results, indent=2) + "\n")
    auc_figure(results, args.out / "auc_frame_head", DEPLOYABLE)
    run = write_run(
        args.out, "ml/scripts/frame_head.py", args.export, collection=[data["day"].min(), data["day"].max()],
        reels=len(data), evaluated=len(eval_set), k=args.k, c=args.c, until=args.until, without=args.without,
    )

    lines = [
        "# Frame signal: similarity or a learned head",
        f"- Data: {run['collection'][0]} – {run['collection'][1]}, {len(eval_set)} labeled first impressions",
        "- Folds: test on each day from the third on, train on all earlier days",
        "- Context + creator: the skip model's context features, the creator's skip share so far and how",
        "  often the creator has been seen. Every other row adds to it",
        f"- sim.: skip share among the {args.k} most similar earlier labeled reels, by first frame (MobileNetV3)",
        "  or caption + audio name (multilingual-e5-small)",
        "- head: the embedding's leading principal components (PCs), fitted on the training days, as",
        "  extra inputs to the logistic regression. Frame: MobileNetV3-Small unless CLIP is named",
        *METRIC_NOTES,
        f"- AUC change: against {BASE} on the same reels, with a paired 95% bootstrap interval",
        "",
        *table(results, "AUC change"),
    ]
    text = "\n".join(lines) + "\n"
    (args.out / "summary.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
