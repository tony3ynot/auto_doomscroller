"""Build the frame encoder and export LiteRT variants for the optimization study.

Variants: fp32, fp16, int8_dynamic, int8_full. All take a 224x224x3 float input in [0, 255]
and output a 576-d embedding. ``int8_full`` is calibrated on first frames spread over the
whole collection and keeps float input/output.

Requires TensorFlow and the LiteRT runtime (``pip install -e "ml[quant]"``). Usage::

    python -m autodoom.quantize --thumbnails data/<export>/thumbnails --out models/
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

INPUT_SIZE = 224
VARIANTS = ("fp32", "fp16", "int8_dynamic", "int8_full")


def build_encoder():
    import tensorflow as tf

    # include_preprocessing (default) means the model expects raw [0, 255] pixels.
    return tf.keras.applications.MobileNetV3Small(
        input_shape=(INPUT_SIZE, INPUT_SIZE, 3),
        include_top=False,
        pooling="avg",
        weights="imagenet",
    )


def load_frame(path):
    """Center-crop to a square and resize, matching what the app does before inference.

    ``path`` is a Path or str, or a string tensor inside ``tf.data``."""
    import tensorflow as tf

    image = tf.io.decode_jpeg(tf.io.read_file(path if tf.is_tensor(path) else str(path)), channels=3)
    side = tf.minimum(tf.shape(image)[0], tf.shape(image)[1])
    image = tf.image.resize_with_crop_or_pad(image, side, side)
    return tf.image.resize(image, (INPUT_SIZE, INPUT_SIZE))


def calibration_frames(thumbnails: Path, n: int) -> list[Path]:
    """``n`` first frames evenly spaced in recording order (reel ids count up).

    First frames only, because that is all the encoder sees in the app, and spread out so one
    evening's feed doesn't set the activation ranges."""
    frames = sorted(thumbnails.glob("*_0.jpg"), key=lambda p: int(p.stem.split("_")[0]))
    if len(frames) <= n:
        return frames
    return [frames[i] for i in np.linspace(0, len(frames) - 1, n).round().astype(int)]


def convert(saved_model_dir: Path, variant: str, calibration: list[Path]) -> bytes:
    import tensorflow as tf

    converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))
    if variant == "fp16":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
    elif variant == "int8_dynamic":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
    elif variant == "int8_full":
        if not calibration:
            raise ValueError("int8_full needs calibration thumbnails")

        def representative_dataset():
            for path in calibration:
                yield [tf.expand_dims(load_frame(path), 0)]

        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.representative_dataset = representative_dataset
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    elif variant != "fp32":
        raise ValueError(f"unknown variant {variant!r}")
    return converter.convert()


def encode(model: Path, frames: list[Path], threads: int = 1) -> np.ndarray:
    """Embeddings of ``frames`` from a converted model, one frame at a time as on the phone."""
    from ai_edge_litert.interpreter import Interpreter

    interpreter = Interpreter(model_path=str(model), num_threads=threads)
    interpreter.allocate_tensors()
    source, sink = interpreter.get_input_details()[0]["index"], interpreter.get_output_details()[0]["index"]
    out = []
    for path in frames:
        interpreter.set_tensor(source, load_frame(path).numpy()[None])
        interpreter.invoke()
        out.append(interpreter.get_tensor(sink)[0].copy())
    return np.stack(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--thumbnails", type=Path, required=True, help="folder of an export's frames")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--calibration-size", type=int, default=200)
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    saved_model_dir = args.out / "encoder_saved_model"
    build_encoder().export(str(saved_model_dir))

    calibration = calibration_frames(args.thumbnails, args.calibration_size)
    for variant in VARIANTS:
        model = convert(saved_model_dir, variant, calibration)
        path = args.out / f"encoder_{variant}.tflite"
        path.write_bytes(model)
        print(f"{variant:>13}: {len(model) / 1e6:.2f} MB -> {path}")


if __name__ == "__main__":
    main()
