"""Build the frame encoder and export LiteRT variants for the optimization study.

Variants: fp32, fp16, int8_dynamic, int8_full. All take a 224x224x3 float input in [0, 255]
and output a 576-d embedding. ``int8_full`` is calibrated on real thumbnails and keeps
float input/output.

Requires TensorFlow (``pip install -e "ml[quant]"``). Usage::

    python -m autodoom.quantize --thumbnails data/<export>/thumbnails --out models/
"""

from __future__ import annotations

import argparse
from pathlib import Path

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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--thumbnails", type=Path, required=True, help="calibration frames (*.jpg)")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--calibration-size", type=int, default=200)
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    saved_model_dir = args.out / "encoder_saved_model"
    build_encoder().export(str(saved_model_dir))

    calibration = sorted(args.thumbnails.glob("*.jpg"))[: args.calibration_size]
    for variant in VARIANTS:
        model = convert(saved_model_dir, variant, calibration)
        path = args.out / f"encoder_{variant}.tflite"
        path.write_bytes(model)
        print(f"{variant:>13}: {len(model) / 1e6:.2f} MB -> {path}")


if __name__ == "__main__":
    main()
