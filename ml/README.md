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
| `autodoom.split` | Time-based train/test split |
| `autodoom.metrics` | Skip AUC and coverage at a fixed precision |
| `autodoom.baselines` | Majority and creator-history baselines |
| `autodoom.quantize` | MobileNetV3 frame encoder → FP32 / FP16 / INT8 LiteRT variants |

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
