# SensorForge — Model Stack

**Version:** 1.0 FINAL · **Date:** 2026-10-09
**Doc set:** `sensorforge-problem-solution.md` (scope, claims) · `sensorforge-technical-spec-v3.md` (implementation) · **this file** (models, runtimes, gates, fallbacks)

> **Selection rule.** A model is in the build only if (1) it produces a primitive or head output the spec needs, (2) no simpler algorithm does the job, (3) it passes its gate on the actual iQOO with backend + latency logged. No model is mentioned in the pitch without a log line.

---

## 1. Summary table

| ID | Component | Choice | Apps | Tier |
|---|---|---|---|---|
| M1 | Object detector | YOLOE (open-vocab) → fallback fine-tuned small detector | PackCheck, SealSafe, HandoverProof | T1 |
| M2 | Image encoder for heads | Small MobileNet-class encoder (frozen) | PackCheck, SealSafe | T1 |
| H1 | Identity head | Prototype / linear head on M2 embeddings (trained on phone) | PackCheck | T1 |
| H2 | State head | Linear head on M2 embeddings (trained on phone) | SealSafe | T1 |
| H3 | IMU risk head | Logistic / 2-layer MLP on IMU features (trained on phone) | CrashGuard | T1 |
| C1 | Classical CV | OpenCV Android: ArUco, homography, LK flow, Laplacian, MOG2, QR | PackCheck, SealSafe, HandoverProof | T1 |
| C2 | Codes | zxing-cpp | PackCheck, HandoverProof | T1 |
| C3 | Tracker | IoU + Kalman (ByteTrack-style), Kotlin | all camera apps | T1 |
| M3 | Pose | MediaPipe Pose Landmarker | RepRight | T2 |
| M4 | Hands | MediaPipe Hand Landmarker | HandoverProof | T2 |
| M5 | Relations | RelateAnything (keyframes) | HandoverProof | T2 spike |
| M6 | Local LLM (authoring) | Qwen3-4B or SmolLM2-1.7B-Instruct via Qualcomm Genie/GenieX | Forge | P1 |
| M7 | STT (voice authoring) | Android on-device recognizer | Forge | P1 |
| O1 | Laya | Forge step-typer / one bounded adjudication | Forge | Optional |
| O2 | TAPNext++ | Laptop-side example labelling only | Forge (offline prep) | Optional |
| — | TTS | Android TextToSpeech | coaching | T1 |
| ✗ | FreeMoCap | Not adopted (multi-camera desktop) | — | — |
| ✗ | JEV | Not adopted (proprietary) | — | — |

---

## 2. Pre-flight device checks (do first)

1. `adb shell getprop ro.soc.model` (SM8850 ⇒ Snapdragon 8 Elite Gen 5); record Android version, RAM.
2. Enumerate sensors: accelerometer, gyroscope, rotation vector, step counter, location providers.
3. Confirm CameraX analysis resolution/fps.
4. Run one AI Hub sample model and log its execution provider.
5. Airplane-mode run of the empty app with bundled assets.

---

## 3. Core models (T1)

### M1 — Object detector
| Field | Value |
|---|---|
| Job | Boxes for box, envelope, charger, cable, adapter, papers, impostor, hands-region items |
| Primary | YOLOE (prompt- or vocabulary-based open-vocab detection), vocabulary fixed at export from pack class list |
| Fallback | Fine-tune a small detector on our real props (4–6 classes); or tagged props (QR/colour), declared openly |
| Licence | YOLOE/Ultralytics code is AGPL — acceptable for hackathon; review before distribution. Apache alternatives listed in Qualcomm AI Hub |
| Export | Qualcomm AI Hub → TFLite/QNN or ONNX; profile on hosted device first |
| Runtime | LiteRT + QNN delegate, or ONNX Runtime + QNN EP; GPU/CPU fallback |
| Input | Smallest resolution keeping recall (start 320–640) |
| Gate | Correct classes on new recordings of our props under demo lighting; ≥ 5 fps sustained |

### M2 — Image encoder
| Field | Value |
|---|---|
| Job | Embeddings of item/seal crops for H1, H2 |
| Choice | Small MobileNet-class feature extractor (pick by spike; log backend) |
| Gate | Linear separability on our crops (genuine vs impostor; intact vs broken) on held-out sessions |

### H1 / H2 — Vision heads (trained on phone)
Prototype (nearest-centroid) or linear softmax on M2 embeddings; Kotlin training (tech spec §16.4). Gate: held-out accuracy > baseline, weights hash changes, train time < 10 s.

### H3 — IMU risk head (trained on phone, live on stage)
| Field | Value |
|---|---|
| Input | 32–64 window features (tech spec §18) |
| Model | Logistic regression or 2-layer MLP (Kotlin, Adam) |
| Classes | normal, shake, drop, impact_like |
| Data | Live-recorded normal/shake/drop; impact_like from labelled replay fixtures |
| Gate | Session-split held-out: shake/knock never → SUSPECTED; impact replays → SUSPECTED; training < 5 s |

### C1–C3 — Classical (no ML)
| Function | Produces |
|---|---|
| ArUco + homography | zones → `enters_zone`, `leaves_zone`, `region_empty`, partner/customer sides |
| Lucas–Kanade flow | box propagation between detector frames |
| Laplacian + IMU | `camera_unstable` |
| MOG2 background subtraction | candidate `unexplained_change` |
| zxing-cpp / OpenCV QR | `code_read` |
| IoU + Kalman tracker | tracks, `track_break`, `identity_unresolved` |

Pose/hand/face landmarks are **MediaPipe**, not OpenCV.

### As built (Phase 3, 2026-10-10)
| Id | Built with | Source / licence / size | Runtime | Fallback / note |
|---|---|---|---|---|
| M1 | **Tagged props** (QR tag per prop, payload `SF1\|OBJ\|cls\|code`), the declared fallback. Claim: "controlled props" | `com.google.zxing:core:3.5.3`, Maven Central, Apache-2.0, ~550 KB | JVM, CPU | YOLOE/fine-tuned detector still needs a model file; runtime decided: LiteRT + QNN, GPU then CPU |
| M1 (learned) | MediaPipe EfficientDet-Lite0 float32 (COCO 80 classes), `sf-sdk/src/main/assets/models/efficientdet_lite0.tflite`, sha256 `40338edf…bf58` | storage.googleapis.com/mediapipe-models, Apache-2.0, 13.8 MB | MediaPipe Tasks 0.10.21 (TFLite), GPU delegate, CPU fallback | COCO has cell phone but no box/envelope/cable/seal: fine-tune with MediaPipe Model Maker on our props |
| C1 zones | QR corner markers + floor marker instead of ArUco + homography (quad in image space) | same ZXing | CPU | ArUco needs OpenCV; not added |
| C2 codes | ZXing core `QRCodeMultiReader` (Java, not zxing-cpp) | same | CPU | none |
| C3 tracker | Greedy per-class association, constant-velocity alpha-beta filter (fixed-gain Kalman), code-proven re-id | in repo | CPU | none |
| quality | Mean luma + Laplacian variance (subsampled); frame gap > 0.5 s | in repo | CPU | lenient thresholds (luma 25-250, var >= 8); MOG2 `unexplained_change` not built |
| M2 | **Not integrated** (no model file). H1 uses a handcrafted 24-dim YUV histogram crop embedding (`yuv-hist-24/v1`), declared as such | in repo | CPU | MobileNet-class encoder via LiteRT when available |
| H1 | 2-layer MLP (hidden 8) via `HeadTrainer`, classes `charger` / `impostor_cable` | trained on phone | CPU | tags decide class when no head is approved |


---

## 4. T2 models

### M3 — Pose (RepRight)
MediaPipe Pose Landmarker, bundled offline. Angles smoothed; reps via state machine. Gate: correct count on valid reps, rejects too-fast/partial, UNVERIFIED when keypoints lost.

### M4 — Hands (HandoverProof)
MediaPipe Hand Landmarker; hand–object overlap + side zones decide `held_by_hand`/`transferred`. Gate: transfer detected at correct moment on demo clips.

### M5 — RelateAnything (spike)
| Field | Value |
|---|---|
| Job | "who is holding what" relations on keyframes |
| Source | github.com/Maelic/RelateAnything (Apache-2.0); DINOv3 ViT-S+/16 backbone — check DINOv3 weight licence |
| Vocabulary | Predicate strings are a runtime input: `holding, in, on, handing to` |
| Size | ~105 MB relation model (+ YOLOE-11s ~24 MB) |
| Runtime | ONNX Runtime Android; keyframes only |
| Known numbers | GPU only (~17 ms RTX 3080 laptop); phone unknown |
| Gate | "holding" flips partner → customer correctly on iQOO within keyframe budget |
| Fallback | hand overlap + side zones |

---

## 5. Authoring models (P1)

### M6 — Local LLM
| Field | Value |
|---|---|
| Job | Sentence → schema-valid spec JSON; clarifying questions; example checklist |
| Candidates | Qwen3-4B (Qualcomm Genie example, 8 Elite Gen 5 target); SmolLM2-1.7B-Instruct |
| Integration | Qualcomm GenieX Android SDK (developer preview) or Genie bundle |
| Loaded | Forge only; never during verification |
| Fallback | Template compiler (P0) |
| Gate | Valid schema on ≥ 8/10 test sentences offline on the iQOO |

### M7 — STT
Android on-device speech recognizer (offline language pack installed beforehand). Gate: works in airplane mode.

---

## 6. Optional

### O1 — Laya
Text-only, non-generative typed-decision model. Base checkpoints near chance zero-shot; fp32 ONNX ≈ 1.7 GB (use int8 and verify agreement vs fp32). Roles allowed: (a) Forge step-typer — clause → primitive / constraint / observable; (b) one bounded adjudication producing an observation. **Never** decides a verdict; never in CrashGuard's fast path. Gate: beats rule baseline on held-out data and runs on iQOO with backend logged; else omit.

### O2 — TAPNext++
github.com/google-deepmind/tapnet (Apache-2.0). Online point tracking with occlusion handling; documented real-time on GPU. Use only on the laptop (Green Light) to auto-label example clips. No on-phone claim unless a phone spike passes.

---

## 7. Fallback matrix

| If on phone | Fallback | Claim becomes |
|---|---|---|
| M1 slow on NPU | smaller input / GPU | "GPU" for that model |
| M1 inaccurate | fine-tuned detector or tagged props | "controlled props" |
| H1/H2 weak | code read only (PackCheck); drop SealSafe state → order + container only | no head claim for that app |
| H3 unstable | stricter gate + stillness rule | "rule-based triage" (still train live) |
| M3 flaky | drop RepRight | — |
| M5 slow | side zones | "rule-based hold detection" |
| M6 invalid JSON | template compiler | "guided authoring" |
| O1/O2 fail | omit | — |

---

## 8. Measurement log (fill on device; source of every pitch number)

| Model | Version | Runtime | Exec provider | Backend | Input | Cold ms | Warm p50/p95 ms | Memory MB | Thermal after 5 runs | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| M1 detector | | | | | | | | | | |
| M2 encoder | | | | | | | | | | |
| H1 identity (train / infer) | | CPU | | | | | | | | |
| H2 state (train / infer) | | CPU | | | | | | | | |
| H3 IMU (train / infer) | | CPU | | | | | | | | |
| M3 pose | | | | | | | | | | |
| M4 hands | | | | | | | | | | |
| M5 RelateAnything | | | | | | | | | | |
| M6 LLM | | | | | | | | | | |
| O1 Laya | | | | | | | | | | |

---

## 9. Build order
1. Pre-flight (§2).
2. H3 + IMU features (no camera dependency — fastest real training).
3. M1 + C1–C3 → PackCheck; then M2 + H1.
4. H2 → SealSafe; pack export → HelloForge.
5. **Gate.** Then M3 → RepRight; M4 (+M5 spike) → HandoverProof.
6. M6/M7 authoring; O1, O2 last.

---

## 10. Model files in the build (rule 10 record, appended 2026-10-10)

Bundled in `sf-sdk/src/main/assets/models/`. Source for the MediaPipe files: `https://storage.googleapis.com/mediapipe-models/<path>` (Google, Apache-2.0). Runtime: MediaPipe Tasks / LiteRT. Backend is logged per run.

| Slot | File | Path (under mediapipe-models) | Bytes | sha256 | Fallback |
|---|---|---|---|---|---|
| M1 (current) | efficientdet_lite0.tflite | object_detector/efficientdet_lite0 | (added earlier) | (see registry) | tagged props (QR) |
| M1 (upgrade) | efficientdet_lite2.tflite | object_detector/efficientdet_lite2/float32/latest | 23,096,891 | `ad2abbf2b4e10585e15176fd7b5ef03c28dda959ae26fc142549fdd1814db91d` | Lite0 → tagged props |
| M2 | mobilenet_v3_large.tflite (Image Embedder) | image_embedder/mobilenet_v3_large/float32/latest | 10,889,458 | `11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d` | handcrafted crop embedding (labelled) |
| M3 | pose_landmarker_heavy.task | pose_landmarker/pose_landmarker_heavy/float16/latest | 30,664,242 | `64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b` | drop RepRight (§7) |
| M4 | hand_landmarker.task | hand_landmarker/hand_landmarker/float16/latest | 7,819,105 | `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1` | side zones only |

**Approved by the user, to be fetched in their phase** (confirm exact file + size at download, then add a row above):
| Slot | What | Source / licence | Notes |
|---|---|---|---|
| M1 primary | YOLOE-11S, vocabulary fixed from the pack class list, exported to TFLite on the laptop | Ultralytics (pip + GitHub release weights), **AGPL**: hackathon OK, review before distribution | Laptop export via `tools/`; LiteRT + QNN/GPU on phone |
| M5 | RelateAnything (~105 MB relation model) | github.com/Maelic/RelateAnything, Apache-2.0 | DINOv3 backbone weights are Meta licence-gated: **the user must accept that licence**; fallback side zones |
| M6 | SmolLM2-1.7B-Instruct, 4-bit GGUF (~1 GB) | Hugging Face, Apache-2.0 | Runs on **llama.cpp** (CPU/GPU) because Genie needs a Qualcomm account; deviation from §5 "Integration" recorded here. Forge only. Too large for git: install to device storage, not assets |

Not obtainable by the agent: Qwen3-4B Genie bundle (Qualcomm AI Hub account), Laya (no source given), M7 offline language pack (installed by the user in Android settings).

### Downloaded and installed 2026-10-10 (device storage `files/models/`, not in git)
| Slot | File | Bytes | sha256 | Source / licence | Runtime and measured backend |
|---|---|---|---|---|---|
| M6 | smollm2-1.7b-instruct-q4_k_m.gguf | 1,055,609,536 | `decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33` | Hugging Face HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF, Apache-2.0 | llama.cpp via `io.github.ljcamargo:llamacpp-kotlin:0.4.0` (Apache-2.0, prebuilt AAR), CPU; load ~1 s, ~10 s per sentence |
| M1 primary | yoloe-11s-seg.onnx (vocabulary: adapter, box, cable, charger, envelope, impostor_cable, papers, phone, seal; 320 px) | 40,398,952 | `9d911320c779e0c9bc373769c9a383b1f7efb0b070f66c42ff6665608013fa45` | Ultralytics 8.4.175 `yoloe-11s-seg.pt`, exported by `tools/yoloe/export.py`, AGPL-3.0 (hackathon only) | **ONNX Runtime Android 1.22.0** (deviation: Ultralytics cannot export TFLite on Windows). Providers timed at load: NNAPI 250 ms, CPU 72 ms; CPU is used. Zero-shot weights, not fine-tuned for our props |

Still not obtainable by the agent: M5 RelateAnything (needs Meta DINOv3 licence acceptance by the user), O1 Laya (no source), Qwen3-4B Genie bundle (Qualcomm account), M7 offline language pack (Android settings).
