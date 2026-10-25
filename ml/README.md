# ml

Offline side of auto_doomscroller: load exports from the phone, re-label, split, run
baselines, evaluate, and build the quantized encoder variants that ship in the app.

## Setup

```bash
cd ml
uv venv && uv pip install -e ".[dev]"      # or: pip install -e ".[dev]"
pytest
```

For the encoder / quantization step add the `quant` extra (TensorFlow):

```bash
pip install -e ".[quant]"
```

On Colab: `!pip install -e "auto_doomscroller/ml[dev,quant]"` after cloning.

## Layout

| Module | Purpose |
|---|---|
| `autodoom.data` | Load `sessions.jsonl` / `reels.jsonl` and thumbnails |
| `autodoom.clean` | Repairs exports from before schema v8 (split viewings, loose revisit flags) |
| `autodoom.labels` | The SKIP / WATCH rule, kept in sync with `ReelLabeler.kt` |
| `autodoom.split` | Time-based train/test split and rolling day-by-day folds |
| `autodoom.features` | Features known at decision time (0.3 s in), history-based taste scores |
| `autodoom.embed` | First-frame and caption embeddings, cached inside the export folder |
| `autodoom.model` | The skip model (logistic regression) |
| `autodoom.report` | `run.json` records for result folders |
| `autodoom.metrics` | Skip AUC and coverage at a fixed precision |
| `autodoom.baselines` | Majority and creator-history baselines |
| `autodoom.quantize` | MobileNetV3 frame encoder → FP32 / FP16 / INT8 LiteRT variants |

Scripts in `scripts/` write a result folder under `results/` at the repo root
(see [results/README.md](../results/README.md)):

| Script | Writes |
|---|---|
| `prelim_figures.py` | Collection statistics and the two preliminary figures |
| `skip_model.py` | Feature-set comparison for the skip model |

From the repo root, with exports in `data/`:

```bash
pip install -e "ml[embed,viz]"
python -m autodoom.embed data/<export>                 # once per export; cached
python ml/scripts/skip_model.py data/<export> --out results/<date>-skip-model
```

## Typical loop

```python
from autodoom.data import load_export
from autodoom.clean import clean
from autodoom.labels import relabel
from autodoom.split import temporal_split
from autodoom.baselines import creator_history_scores
from autodoom.metrics import skip_auc, coverage_at_precision

sessions, reels = load_export("data/20261101-210000")
reels = clean(reels)  # only needed for exports recorded before schema v8; harmless otherwise
reels["label"] = relabel(reels)
train, test = temporal_split(reels, test_days=2)

test = test[test["label"].notna()]
is_skip = (test["label"] == "SKIP").to_numpy()
p_skip = creator_history_scores(train, test)
print(skip_auc(is_skip, p_skip), coverage_at_precision(is_skip, p_skip))
```

Exports contain personal viewing data. Keep them under `data/` (git-ignored) or in Drive,
never in the repository.
