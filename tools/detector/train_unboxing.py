"""Fine-tune a MediaPipe object detector on the unboxing props (box, seal, envelope, phone, cable).

Run in Google Colab (GPU runtime); MediaPipe Model Maker does not install on Windows.

    !pip install -q mediapipe-model-maker
    # upload unboxing_coco.zip (Label Studio "COCO" export: images/ + result.json)
    !python train_unboxing.py unboxing_coco.zip

Output: exported_model/unboxing_detector.tflite (float) and unboxing_detector_int8.tflite.
Copy the float model to sf-sdk/src/main/assets/models/ and record id, sha256, size,
licence (Apache-2.0 base weights) and metrics in docs/sensorforge-model-stack.md.
"""
import json, os, random, shutil, sys, zipfile
from collections import defaultdict

CLASSES = ["box", "lid", "seal", "envelope", "phone", "cable", "charger"]


def split_coco(src_dir, out_dir, val_fraction=0.2, seed=7):
    """Splits by capture session (file prefix s<id>_) so near-identical frames don't leak into val."""
    coco = json.load(open(os.path.join(src_dir, "result.json")))
    unknown = {c["name"] for c in coco["categories"]} - set(CLASSES)
    if unknown:
        sys.exit(f"Unknown labels {unknown}; expected only {CLASSES}")
    by_session = defaultdict(list)
    for im in coco["images"]:
        name = os.path.basename(im["file_name"])
        by_session[name.split("_")[0]].append(im)
    sessions = sorted(by_session)
    random.Random(seed).shuffle(sessions)
    n_val = max(1, round(len(sessions) * val_fraction)) if len(sessions) > 1 else 0
    parts = {"validation": sessions[:n_val], "train": sessions[n_val:]}
    if n_val == 0:  # single session: fall back to a frame split, and say so
        print("WARNING: one capture session only; validation frames are near-duplicates of training frames")
        ims = by_session[sessions[0]]; random.Random(seed).shuffle(ims)
        k = max(1, len(ims) // 5)
        by_session = {"val": ims[:k], "train": ims[k:]}
        parts = {"validation": ["val"], "train": ["train"]}
    for part, ss in parts.items():
        ims = [im for s in ss for im in by_session[s]]
        ids = {im["id"] for im in ims}
        d = os.path.join(out_dir, part); os.makedirs(os.path.join(d, "images"), exist_ok=True)
        for im in ims:
            shutil.copy(os.path.join(src_dir, im["file_name"]), os.path.join(d, "images", os.path.basename(im["file_name"])))
        out = dict(coco, images=[dict(im, file_name=os.path.basename(im["file_name"])) for im in ims],
                   annotations=[a for a in coco["annotations"] if a["image_id"] in ids])
        json.dump(out, open(os.path.join(d, "labels.json"), "w"))
        counts = defaultdict(int)
        for a in out["annotations"]:
            counts[next(c["name"] for c in coco["categories"] if c["id"] == a["category_id"])] += 1
        print(part, len(ims), "images", dict(counts))


def main(zip_path):
    from mediapipe_model_maker import object_detector, quantization

    src = "coco_src"; data = "coco_split"
    shutil.rmtree(src, ignore_errors=True); shutil.rmtree(data, ignore_errors=True)
    zipfile.ZipFile(zip_path).extractall(src)
    split_coco(src, data)

    train = object_detector.Dataset.from_coco_folder(os.path.join(data, "train"), cache_dir="/tmp/od_train")
    val = object_detector.Dataset.from_coco_folder(os.path.join(data, "validation"), cache_dir="/tmp/od_val")
    options = object_detector.ObjectDetectorOptions(
        supported_model=object_detector.SupportedModels.MOBILENET_MULTI_AVG,
        hparams=object_detector.HParams(export_dir="exported_model", epochs=40, batch_size=8, learning_rate=0.3),
    )
    model = object_detector.ObjectDetector.create(train_data=train, validation_data=val, options=options)
    loss, coco_metrics = model.evaluate(val, batch_size=8)
    print("validation loss", loss)
    print("validation COCO metrics", coco_metrics)
    model.export_model("unboxing_detector.tflite")

    qat = quantization.QuantizationConfig.for_int8(train)  # post-training int8 for NPU/CPU
    model.export_model("unboxing_detector_int8.tflite", quantization_config=qat)
    json.dump({"classes": CLASSES, "val_loss": float(loss), "coco_metrics": {k: float(v) for k, v in coco_metrics.items()}},
              open("exported_model/metrics.json", "w"), indent=2)
    print("done: exported_model/")


if __name__ == "__main__":
    main(sys.argv[1])
