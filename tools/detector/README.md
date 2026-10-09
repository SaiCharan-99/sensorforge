# Unboxing detector: capture → label → train → ship

Classes: `box`, `seal`, `envelope`, `phone`, `cable` (spec: `fixtures/specs/unboxing.spec.json`).

## 1. Capture (on the iQOO)
studio-app → **Camera** tab → **Record dataset frames** (≈3 JPEG/s, upright, 480×640).
Record several short sessions (each press = one session), varying angle, distance, lighting and hands:
- closed sealed box; box being unsealed; open box with contents
- envelope, phone, cable each in and out of the box, held in hand, lying beside it
- phone with film on and off, screen off and on

Aim for ≥ 60 boxes per class across ≥ 5 sessions. Keep at least one session for held-out testing.

Pull to the laptop (data stays out of git):
```bash
bash tools/detector/pull_dataset.sh
```

## 2. Label
Label Studio (local, `pip install label-studio`, then `label-studio`): project type *Object Detection with Bounding Boxes*,
labels exactly `box, seal, envelope, phone, cable`. Import `dataset/images/`. Export **COCO** → `unboxing_coco.zip`.

## 3. Train (Google Colab, GPU)
Upload `train_unboxing.py` and `unboxing_coco.zip`, then:
```
!pip install -q mediapipe-model-maker
!python train_unboxing.py unboxing_coco.zip
```
Model: MediaPipe Model Maker `MOBILENET_MULTI_AVG` SSD (Model Maker does not fine-tune EfficientDet).
Split is by capture session. Download `exported_model/`.

## 4. Ship
Copy `unboxing_detector.tflite` to `sf-sdk/src/main/assets/models/`, record sha256/size/metrics in the model stack and
`docs/measurements/`, then measure on the iQOO (Camera tab shows backend + latency).
