# Progress

Phases follow tech spec §30 (48-hour plan) and model stack §9 (build order).
Cut order if behind: Laya → RelateAnything → local LLM → HandoverProof → RepRight.

**Current phase:** Phase 1: Fri, Green Light
**Last updated:** 2026-10-10

## Phase 0: Docs setup
- [x] CLAUDE.md, AGENTS.md, docs/ structure
- [x] Spec docs copied into `docs/`; working docs filled
- [x] UI design defined in-repo (`docs/ui-design.md`); no external wireframes
- [x] Design system defined (`docs/design.md`, soft light theme)

## Phase 1: Fri (Green Light): foundations
- [ ] Pre-flight device checks (model stack §2):
  - [ ] `adb shell getprop ro.soc.model` (SM8850 ⇒ Snapdragon 8 Elite Gen 5); record Android version, RAM
  - [ ] Enumerate sensors: accelerometer, gyroscope, rotation vector, step counter, location providers
  - [ ] Confirm CameraX analysis resolution/fps
  - [ ] Run one AI Hub sample model and log its execution provider
  - [ ] Airplane-mode run of the empty app with bundled assets
- [ ] Repo + 3 modules (`sf-sdk`, `studio-app`, `hello-forge`) + `fixtures/`; align current `:app` scaffold
- [ ] Contracts + validator (`ContractError(path, message)`)
- [ ] Verify engine (pure Kotlin) + golden JVM tests (`./gradlew :sf-sdk:test`)

## Phase 2: Fri night: CrashGuard + real training
- [ ] IMU capture (accel + gyro + rotation vector @ ~100 Hz, 20 s ring buffer)
- [ ] Window features (3 s, hop 0.5 s, ~32–64 dims)
- [ ] Kotlin head training (H3) + proof artifacts (`training.json`, hash before/after)
- [ ] CrashGuard incident lifecycle + `MockIncidentTransport` + replay fixtures (labelled SIMULATION)

## Phase 3: Sat morning: camera perception + PackCheck
- [ ] CameraX → detector (M1) → tracker (C3) → zones (ArUco) → codes → timeline
- [ ] M2 encoder + identity head (H1)
- [ ] PackCheck + coaching (overlay + TTS)

## Phase 4: Sat noon: packs, HelloForge, SealSafe
- [ ] Pack export/import (`.sfpkg`, `pack_sha256`, `models.lock` check)
- [ ] HelloForge (two-line import)
- [ ] SealSafe template authoring + state head (H2)
- [ ] Receipts (+ verify-receipt)

## Phase 5: Sat afternoon: GATE
- [ ] All T1 acceptance tests (T-B01–05, T-C01–06, T-S01–02) pass offline on the iQOO
- [ ] One full rehearsal

## Phase 6: Sat evening: RepRight + held-out data
- [ ] RepRight (M3 pose)
- [ ] Held-out recordings for PackCheck / CrashGuard / SealSafe

## Phase 7: Sat night: HandoverProof + spikes
- [ ] HandoverProof (side zones, M4 hands)
- [ ] Spike: RelateAnything (M5)
- [ ] Spike: local LLM authoring (M6) + STT (M7)
- [ ] Spike: Laya (O1)

## Phase 8: Sun morning: evidence + rehearsal
- [ ] Measurement log complete (`docs/measurements/`)
- [ ] Evidence slide (real numbers only)
- [ ] Labelled backup video
- [ ] ≥ 5 rehearsals (each wow moment ≥ 5× on real phone, poor lighting)

## Definition of done (finale)
- [ ] T1 acceptance tests (T-B, T-C, T-S) pass on the iQOO in airplane mode.
- [ ] Training changes weights hash; held-out metrics shown.
- [ ] SealSafe authored → trained → exported in < 90 s with pre-captured examples.
- [ ] HelloForge loads the exported pack (matching hash) and verifies live.
- [ ] All end apps use only public SDK API.
- [ ] Measurement log complete for every model in use.
- [ ] No screen shows fake LIVE data or "SENT".

## Device facts (fill on unit)
| Field | Value |
|---|---|
| `ro.soc.model` | |
| Android version | |
| RAM | |
| Sensors present | |
| CameraX analysis res/fps | |

## Decisions log
| Date | Decision |
|---|---|
| 2026-10-10 | Docs set v1.0/v3.0 FINAL adopted; supersedes earlier SensorForge docs |
| 2026-10-10 | No external wireframes; UI designed by the agent in `docs/ui-design.md` |

## Blockers
_None._
