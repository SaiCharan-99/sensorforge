# Measurement Log

Fill this in on the device. Every number in the pitch must come from here (model stack §8, tech spec §26).

| Model | Version | Runtime | Exec provider | Backend | Input | Cold ms | Warm p50/p95 ms | Memory MB | Thermal after 5 runs | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| M1 detector | tags-qr/zxing-3.5.3/v1 (tagged-prop fallback) | ZXing core 3.5.3 (JVM) | CPU | CPU | 1280x720 Y | n/a | 9.9 / 15.9 (no codes in view), 12.0 / 18.9 | n/a | n/a | 2026-10-10, iQOO, debug build; analysis 30-31 fps keep-latest. Live decode of real tags not yet measured |
| M1 EfficientDet-Lite0 (MediaPipe) | float32 COCO | MediaPipe Tasks 0.10.21 / TFLite | GPU delegate | GPU | 640×480 → upright bitmap | n/a | ~26–33 (logged per 15 frames) | n/a | n/a | 2026-10-10 iQOO debug build; 28–30 fps end to end |
| M2 encoder | | | | | | | | | | |
| H1 identity (train / infer) | | CPU | | | | | | | | |
| H2 state (train / infer) | | CPU | | | | | | | | |
| H3 IMU (train / infer) | | CPU | | | | | | | | |
| M3 pose | | | | | | | | | | |
| M4 hands | | | | | | | | | | |
| M5 RelateAnything | | | | | | | | | | |
| M6 LLM | | | | | | | | | | |
| O1 Laya | | | | | | | | | | |

## Per-app end-to-end decision time
| App | Decision time | Notes |
|---|---|---|
| PackCheck | verdict at stop, < 1 frame | 2026-10-10: empty scene 29.2 s gives UNVERIFIED, all steps NOT_OBSERVABLE (correct). Real-prop runs pending |
| CrashGuard | | |
| SealSafe | | |
| HelloForge | | |
| RepRight | | |
| HandoverProof | | |
