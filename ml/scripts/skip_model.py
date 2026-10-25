"""First skip model: which signals predict a first-impression skip?

    python ml/scripts/skip_model.py data/<export> --out results/<date>-skip-model

Evaluates feature sets with rolling-origin folds (each day from the third on is a test set,
trained on every earlier day) and writes metrics.json, summary.md, run.json and\nauc_by_feature_set.pdf/png to ``--out``.
Only aggregate numbers are written; nothing about individual reels or creators.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from autodoom.clean import clean
from autodoom.data import load_export
from autodoom.embed import embeddings
from autodoom.figures import BLUE, COLUMN_WIDTH_IN, GRID, INK, INK_2, MUTED, SURFACE, use_style
from autodoom.features import decision_features, knn_skip_rate, viewing_sessions
from autodoom.labels import SKIP, relabel
from autodoom.metrics import bootstrap_ci, coverage_at_precision, group_auc, skip_auc
from autodoom.model import skip_model
from autodoom.report import write_run
from autodoom.split import rolling_days

use_style()

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


def build(export: Path, k: int) -> pd.DataFrame:
    """All reels in time order with their label, decision-time features and taste scores."""
    _, reels = load_export(export)
    reels = clean(reels).sort_values("started_at_ms").reset_index(drop=True)
    reels["label"] = relabel(reels)
    features = decision_features(reels)
    labeled = reels["label"].notna().to_numpy()
    is_skip = (reels["label"] == SKIP).to_numpy()
    for name, model in TASTE.items():
        emb = embeddings(export, reels, model).to_numpy()
        features[name] = knn_skip_rate(emb, is_skip, labeled, k=k)
    features["session"] = viewing_sessions(reels)
    features["has_frame"] = reels["id"].map(lambda i: (export / "thumbnails" / f"{i}_0.jpg").exists())
    return pd.concat([reels, features], axis=1)


def evaluate(data: pd.DataFrame, features: list[str], fitted: bool, c: float) -> dict:
    """Fit on earlier days, score each test day. Headline: mean of the per-day AUCs."""
    folds, ys, ps, days, sessions = [], [], [], [], []
    for day, train, test in rolling_days(data):
        y_train, y_test = (train["label"] == SKIP).to_numpy(), (test["label"] == SKIP).to_numpy()
        if not features:
            p = np.full(len(test), y_train.mean())
        elif not fitted:
            p = test[features[0]].fillna(y_train.mean()).to_numpy()
        else:
            p = skip_model(c).fit(train[features], y_train).predict_proba(test[features])[:, 1]
        point = coverage_at_precision(y_test, p, 0.8)
        folds.append({"test_day": str(day), "n": len(test), "skip_share": float(y_test.mean()),
                      "auc": skip_auc(y_test, p) if features else 0.5, "coverage_at_80": point.coverage})
        ys.append(y_test), ps.append(p), days.append(np.full(len(test), len(folds)))
        sessions.append(test["session"].to_numpy())
    y, p, day, session = map(np.concatenate, (ys, ps, days, sessions))
    result = {"features": features, "folds": folds, "auc": float(np.mean([f["auc"] for f in folds])),
              "coverage_at_80": float(np.mean([f["coverage_at_80"] for f in folds]))}
    if features:
        result["auc_ci95"] = bootstrap_ci(group_auc, y, p, day, strata=day)
        result["session_auc"] = group_auc(y, p, session, weighted=True)
        result["session_auc_ci95"] = bootstrap_ci(lambda *a: group_auc(*a, weighted=True), y, p, session,
                                                  strata=day)
    return result


def auc_figure(results: dict, out: Path) -> None:
    """Mean per-day AUC with its 95% interval for each feature set; the deployable set in color."""
    names = [n for n, r in results.items() if r["features"]][::-1]
    fig, ax = plt.subplots(figsize=(COLUMN_WIDTH_IN, 0.2 * len(names) + 0.45))
    ax.grid(True, axis="x", color=GRID, linewidth=0.5)
    ax.set_axisbelow(True)
    ax.axvline(0.5, color=INK_2, linewidth=0.6)
    ax.annotate("chance", (0.5, len(names) - 0.5), xytext=(3, 0), textcoords="offset points", color=INK_2,
                fontsize=6, va="bottom")
    for i, name in enumerate(names):
        r = results[name]
        color = BLUE if name == DEPLOYABLE else MUTED
        low, high = r["auc_ci95"]
        ax.plot([low, high], [i, i], color=color, linewidth=1.4, solid_capstyle="round")
        ax.plot([r["auc"]], [i], marker="o", markersize=4.5, color=color, markeredgecolor=SURFACE,
                markeredgewidth=0.8, zorder=3)
        if name == DEPLOYABLE:
            ax.annotate(f"{r['auc']:.2f}", (high, i), xytext=(4, 0), textcoords="offset points", color=INK,
                        fontsize=6.5, va="center")
    ax.set_yticks(range(len(names)))
    ax.set_yticklabels(names)
    for label in ax.get_yticklabels():
        label.set_color(INK if label.get_text() == DEPLOYABLE else INK_2)
    ax.set_xlim(0.38, 0.72)
    ax.set_ylim(-0.6, len(names) - 0.1)
    ax.set_xlabel("Skip AUC, mean of test days (95% CI)")
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.tick_params(axis="y", length=0)
    fig.tight_layout(pad=0.3)
    for ext in ("pdf", "png"):
        fig.savefig(out / f"auc_by_feature_set.{ext}", dpi=300)
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--k", type=int, default=20, help="neighbours for the similarity scores")
    parser.add_argument("--c", type=float, default=1.0, help="inverse L2 strength of the logistic regression")
    args = parser.parse_args()

    data = build(args.export, args.k)
    # Scrolling back to a reel isn't a first impression, and a reel without its first frame
    # can't be scored by the frame models; drop both so every feature set sees the same reels.
    eval_set = data[data["label"].notna() & ~data["revisit"] & data["has_frame"]]
    dropped = int(data["label"].notna().sum() - len(eval_set))
    results = {name: evaluate(eval_set, f, fitted, args.c) for name, (f, fitted) in FEATURE_SETS.items()}

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "metrics.json").write_text(json.dumps(results, indent=2) + "\n")
    auc_figure(results, args.out)
    days = pd.to_datetime(data["started_at_ms"], unit="ms", utc=True).dt.tz_convert("Asia/Seoul").dt.date
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
        "- AUC: mean of the per-day AUCs. Within-session AUC: ranking inside each viewing session",
        "  (5 min gap = new session), weighted by size. Brackets: 95% bootstrap intervals",
        "- Coverage: share of SKIPs caught at 80% precision, with the best threshold for each day",
        "  (an upper bound; Auto mode has to pick its threshold in advance)",
        "",
        "| Feature set | AUC | Within-session AUC | Coverage @80% |",
        "|---|---|---|---|",
    ]
    for name, r in results.items():
        cells = []
        for key in ("auc", "session_auc"):
            ci = r.get(f"{key}_ci95")
            cells.append(f"{r[key]:.3f} ({ci[0]:.2f}–{ci[1]:.2f})" if ci else (f"{r[key]:.3f}" if key in r else "–"))
        cov = f"{r['coverage_at_80']:.0%}" if r["features"] else "–"
        lines.append(f"| {name} | {cells[0]} | {cells[1]} | {cov} |")
    text = "\n".join(lines) + "\n"
    (args.out / "summary.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
