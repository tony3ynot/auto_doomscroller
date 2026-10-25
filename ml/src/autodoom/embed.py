"""Frame and caption embeddings for offline experiments.

Only the first frame (captured 0.3 s in) is embedded: later frames exist mostly for reels the
user didn't skip, so they would leak the label. Results are cached in ``<export>/cache/`` so
the export folder (git-ignored) holds everything derived from personal data.

    python -m autodoom.embed data/<export>

Models:
    mobilenet_v3_small  the encoder the app ships (``quantize.build_encoder``), 576-d
    clip_vit_b32        OpenCLIP ViT-B/32 (LAION-2B), 512-d; a stronger reference, too big for the phone
    e5_small            multilingual-e5-small on caption + audio title, 384-d
    e5_ocr_first        the same on text read from the first frame (``autodoom.ocr``, run it first)
    e5_ocr_hires        the same on text read from the 540 px frame
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

IMAGE_MODELS = ("mobilenet_v3_small", "clip_vit_b32")
TEXT_MODELS = ("e5_small", "e5_ocr_first", "e5_ocr_hires")
BATCH = 64


def first_frame(export_dir: str | Path, reel_id: int) -> Path | None:
    path = Path(export_dir) / "thumbnails" / f"{reel_id}_0.jpg"
    return path if path.exists() else None


def reel_text(row) -> str | None:
    """Caption (Instagram) or title (YouTube), plus the audio name when there is one."""
    parts = [t for t in (row.caption, row.audio) if isinstance(t, str) and t.strip()]
    return "\n".join(parts) if parts else None


def _texts(export_dir: Path, reels: pd.DataFrame, model: str) -> dict[int, str | None]:
    if model == "e5_small":
        return {int(r.id): reel_text(r) for r in reels.itertuples()}
    from .ocr import clean_text, load_raw

    raw = load_raw(export_dir, model.removeprefix("e5_ocr_"))
    return {int(r.id): clean_text(raw[int(r.id)], known=(r.creator,)) if int(r.id) in raw else None
            for r in reels.itertuples()}


def _normalize(x: np.ndarray) -> np.ndarray:
    return x / np.linalg.norm(x, axis=1, keepdims=True)


def _mobilenet(paths: list[Path]) -> np.ndarray:
    import tensorflow as tf

    from .quantize import build_encoder, load_frame

    frames = tf.data.Dataset.from_tensor_slices([str(p) for p in paths]).map(load_frame)
    return build_encoder().predict(frames.batch(BATCH), verbose=0)


def _clip(paths: list[Path]) -> np.ndarray:
    import open_clip
    import torch
    from PIL import Image

    model, _, preprocess = open_clip.create_model_and_transforms("ViT-B-32", pretrained="laion2b_s34b_b79k")
    model.eval()
    out = []
    with torch.no_grad():
        for start in range(0, len(paths), BATCH):
            batch = torch.stack([preprocess(Image.open(p).convert("RGB")) for p in paths[start:start + BATCH]])
            out.append(model.encode_image(batch).numpy())
    return np.concatenate(out)


def _e5(texts: list[str]) -> np.ndarray:
    from sentence_transformers import SentenceTransformer

    model = SentenceTransformer("intfloat/multilingual-e5-small")
    return model.encode([f"query: {t}" for t in texts], batch_size=BATCH)


def embeddings(export_dir: str | Path, reels: pd.DataFrame, model: str) -> pd.DataFrame:
    """L2-normalised embeddings indexed like ``reels``; NaN rows where there is no frame / text.

    Cached in ``<export>/cache/<model>.npz``: per reel id, and for text models per text too, so
    a change to the OCR cleaning rules re-embeds only the reels whose text changed.
    """
    export_dir = Path(export_dir)
    cache = export_dir / "cache" / f"{model}.npz"
    known: dict[int, np.ndarray] = {}
    keys: dict[int, str] = {}
    if cache.exists():
        saved = np.load(cache)
        known = dict(zip(saved["ids"].tolist(), saved["vectors"]))
        if "keys" in saved:
            keys = dict(zip(saved["ids"].tolist(), saved["keys"].tolist()))

    if model in IMAGE_MODELS:
        inputs = {int(r.id): first_frame(export_dir, r.id) for r in reels.itertuples()}
        compute = _mobilenet if model == "mobilenet_v3_small" else _clip
    elif model in TEXT_MODELS:
        inputs = _texts(export_dir, reels, model)
        compute = _e5
    else:
        raise ValueError(f"unknown model {model!r}")

    def key(i: int) -> str:
        return inputs[i] if model in TEXT_MODELS else ""

    todo = [i for i, x in inputs.items() if x is not None and (i not in known or keys.get(i, "") != key(i))]
    if todo:
        vectors = _normalize(np.asarray(compute([inputs[i] for i in todo]), dtype=np.float32))
        known.update(zip(todo, vectors))
        keys.update((i, key(i)) for i in todo)
        cache.parent.mkdir(exist_ok=True)
        ids = np.array(sorted(known))
        np.savez(cache, ids=ids, vectors=np.stack([known[i] for i in ids]),
                 keys=np.array([keys.get(i, "") for i in ids]))

    dim = len(next(iter(known.values()))) if known else 0
    nan = np.full(dim, np.nan, dtype=np.float32)
    rows = [known.get(int(i), nan) if inputs[int(i)] is not None else nan for i in reels["id"]]
    return pd.DataFrame(np.stack(rows), index=reels.index)


def main() -> None:
    from .clean import clean
    from .data import load_export

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path)
    parser.add_argument("--models", nargs="+", default=[*IMAGE_MODELS, *TEXT_MODELS])
    args = parser.parse_args()
    _, reels = load_export(args.export)
    reels = clean(reels)
    for model in args.models:
        emb = embeddings(args.export, reels, model)
        print(f"{model}: {emb.notna().all(axis=1).sum()}/{len(reels)} reels, {emb.shape[1]}-d")


if __name__ == "__main__":
    main()
