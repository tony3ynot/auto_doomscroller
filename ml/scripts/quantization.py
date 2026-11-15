"""What quantizing the frame encoder costs: size, how far the embeddings move, and skip AUC.

    python -m autodoom.quantize --thumbnails data/<export>/thumbnails --out ml/models
    python ml/scripts/quantization.py data/<export> --models ml/models --out results/<date>-quantization

Each variant embeds the first frame of every evaluated reel through the LiteRT runtime.
Fidelity compares those embeddings with FP32's. The skip model (context + creator + frame
head) is then refitted on each variant's own embeddings, as it would be on a phone running
that variant, and compared with the FP32 one on the same reels. Latency is measured on the
phone, not here. Writes metrics.json, summary.md and run.json. Aggregates only.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import date
from pathlib import Path

import numpy as np

from autodoom.embed import first_frame
from autodoom.experiment import (CONTEXT, CREATOR, auc_change, build, fold_scores, signed_interval, summarize,
                                 with_embedding)
from autodoom.quantize import VARIANTS, encode
from autodoom.report import write_run

NAMES = {"fp32": "FP32", "fp16": "FP16", "int8_dynamic": "INT8 dynamic range", "int8_full": "INT8 full integer"}
REFERENCE = "fp32"
NO_ENCODER = "No encoder"
NEIGHBOURS = 20


def fidelity(vectors: np.ndarray, reference: np.ndarray) -> dict:
    """How close L2-normalised ``vectors`` are to ``reference``, frame by frame and as a
    neighbourhood: the share of each frame's nearest frames that stay the same."""
    cosine = (vectors * reference).sum(axis=1)
    return {"cosine_mean": float(cosine.mean()), "cosine_p5": float(np.percentile(cosine, 5)),
            "cosine_min": float(cosine.min()),
            "neighbour_overlap": float(np.mean([len(set(a) & set(b)) / NEIGHBOURS for a, b in
                                                zip(nearest(vectors), nearest(reference))]))}


def nearest(vectors: np.ndarray) -> np.ndarray:
    similarity = vectors @ vectors.T
    np.fill_diagonal(similarity, -np.inf)
    return np.argsort(similarity, axis=1)[:, -NEIGHBOURS:]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--models", type=Path, required=True, help="folder with encoder_<variant>.tflite")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--c", type=float, default=1.0, help="inverse L2 strength of the logistic regression")
    parser.add_argument("--components", type=int, default=8, help="principal components of the frame head")
    parser.add_argument("--until", type=date.fromisoformat, help="last day to use (YYYY-MM-DD), e.g. before a test day")
    args = parser.parse_args()

    data = build(args.export, NEIGHBOURS, {}, args.until)
    eval_set = data[data["label"].notna() & ~data["revisit"] & data["has_frame"]]
    frames = [first_frame(args.export, i) for i in eval_set["id"]]
    base = CONTEXT + CREATOR

    files = {v: args.models / f"encoder_{v}.tflite" for v in VARIANTS}
    vectors, scores, results = {}, {}, {}
    for variant in [REFERENCE, *(v for v in VARIANTS if v != REFERENCE)]:
        raw = encode(files[variant], frames)
        vectors[variant] = raw / np.linalg.norm(raw, axis=1, keepdims=True)
        with_frame, columns = with_embedding(eval_set, "frame", vectors[variant])
        scores[variant] = fold_scores(with_frame, base, True, args.c, columns, args.components)
        result = {"size_bytes": files[variant].stat().st_size,
                  "sha256": hashlib.sha256(files[variant].read_bytes()).hexdigest()}
        result |= summarize(scores[variant], base + [f"frame: {args.components} principal components"])
        if variant != REFERENCE:
            shift = np.abs(scores[variant]["p"] - scores[REFERENCE]["p"])
            result |= fidelity(vectors[variant], vectors[REFERENCE])
            result |= auc_change(scores[variant], scores[REFERENCE])
            result |= {"p_shift_mean": float(shift.mean()), "p_shift_max": float(shift.max())}
        results[NAMES[variant]] = result
        print(f"{NAMES[variant]}: done", flush=True)
    without = fold_scores(eval_set, base, True, args.c)
    results[NO_ENCODER] = summarize(without, base) | auc_change(without, scores[REFERENCE])

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(json.dumps(results, indent=2) + "\n")
    run = write_run(
        args.out, "ml/scripts/quantization.py", args.export, collection=[data["day"].min(), data["day"].max()],
        reels=len(data), evaluated=len(eval_set), c=args.c, components=args.components, until=args.until,
    )

    rows = ["| Encoder | Size | Cosine to FP32 | Same neighbours | P(skip) shift | Skip AUC | AUC change | Coverage @80% |",
            "|---|---|---|---|---|---|---|---|"]
    for name, r in results.items():
        quantized = "cosine_mean" in r
        rows.append("| " + " | ".join([
            name,
            f"{r['size_bytes'] / 1e6:.2f} MB" if "size_bytes" in r else "–",
            f"{r['cosine_mean']:.3f} ({r['cosine_p5']:.3f})" if quantized else "–",
            f"{r['neighbour_overlap']:.0%}" if quantized else "–",
            f"{r['p_shift_mean']:.3f}" if quantized else "–",
            f"{r['auc']:.3f} ({r['auc_ci95'][0]:.2f}–{r['auc_ci95'][1]:.2f})",
            signed_interval(r["auc_change"], r["auc_change_ci95"]) if "auc_change" in r else "–",
            f"{r['coverage_at_80']:.0%}",
        ]) + " |")
    lines = [
        "# Encoder quantization, off the phone",
        f"- Data: {run['collection'][0]} – {run['collection'][1]}, {len(eval_set)} labeled first impressions, each with",
        "  its first frame embedded by every variant (MobileNetV3-Small, 576-d, LiteRT on a PC)",
        "- Cosine to FP32: between a frame's embedding and its FP32 embedding; mean, 5th percentile in brackets",
        f"- Same neighbours: of a frame's {NEIGHBOURS} nearest frames under FP32, the share still among its {NEIGHBOURS}",
        "  nearest under the variant",
        f"- Skip model: context + creator + {args.components} principal components of the frame embedding, refitted on",
        "  each variant's embeddings. Folds: test on each day from the third on, train on all earlier days",
        "- P(skip) shift: mean absolute change of the predicted skip probability against the FP32 model",
        "- AUC: mean of the per-day AUCs, 95% bootstrap interval. AUC change: against FP32 on the same",
        "  reels, paired 95% bootstrap interval. Coverage: share of SKIPs caught at 80% precision, with the",
        "  best threshold for each day",
        f"- {NO_ENCODER}: the same model without the frame, for scale",
        "",
        *rows,
    ]
    text = "\n".join(lines) + "\n"
    (args.out / "summary.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
