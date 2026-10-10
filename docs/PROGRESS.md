# Progress

Phases 0–3 followed tech spec §30. From Phase 4 the plan is **build-first** (user decision, see "Build-first plan"): build every feature and model slot (4–9), then data, training and acceptance (10), then rehearsal (11).
If time runs out, the spec's cut order still applies: Laya → RelateAnything → local LLM → HandoverProof → RepRight.

**Current stage:** Stage A (build), A1–A4 done; **next A5** (PackCheck complete). Runbook: `docs/WORKFLOW.md`, trigger `build all`.
**Last updated:** 2026-10-10 (A4 done; resume with `continue build` at A5)

## Session handoff (read this first in a new session)
- **Last session:** A3 done 2026-10-10 (guided Forge flow, see A3 log). Next: A5 PackCheck complete (M1 detector chain, M2 embedder + H1, coaching, moved into the guided flow).
- **Code so far:** `sf-sdk/.../sdk/contracts/`, `engine/` (`VerifyEngine`), `detect/`, `train/` (`HeadTrainer`, `HeadStore`), `sensors/`, **`perception/`** (pure: `Tags` payload grammar `SF1|OBJ|cls|code`, `SF1|ZONE|zone|i`, `SF1|FLOOR|zone`, `SF1|STATE|cls|state`; `Tracker`; `TimelineBuilder`; `Coach`; `CropEmbedding`), **`camera/`** (Android: `TagFrameReader` ZXing + quality + embeddings, `CameraVerifier`, `IdentityForge`). Public API in `sdk/SensorForge.kt` (`load`, `identityForge`, `TagSheet`) and `sdk/Verifier.kt` (`Verifier`, `VerifierEvent`, `VerifierResult`, `PackInfo`). `load()` takes a spec JSON path (`asset:` or file) until `.sfpkg` lands. `VerifierResult.receiptPath` is null until Phase 4 receipts. `PurityTest` covers contracts/engine/detect/train/perception.
- **studio-app:** router: Home (tiles) → UseCase → Forge (7 steps) / Run; Camera diagnostics. PackCheck = `packcheck/PackCheckViewModel.kt` + `PackCheckScreens.kt` (public API only).
- **Tests:** `./gradlew :sf-sdk:test` (68 pass); instrumented: `./gradlew :studio-app:connectedDebugAndroidTest` (**uninstalls the app afterwards, which wipes recordings, heads and incidents**). Golden timelines `fixtures/timelines/`, specs `fixtures/specs/`, printable tags `fixtures/tags/`.
- **Install:** `./gradlew assembleDebug`, then `adb install -r studio-app/build/outputs/apk/debug/studio-app-debug.apk`. Device serial `10BFAX1BPJ0010U`.
- **Build facts:** AGP 9.3.3 (built-in Kotlin), Gradle 9.5, Kotlin 2.2.10, compileSdk/targetSdk 37, minSdk 26, kotlinx-serialization 1.9.0, coroutines 1.10.2, CameraX 1.5.1, ZXing core 3.5.3. Package root `com.application.sensorforge`.
- **Open human tasks:** Phase 2 shake/drop recordings + retrain + airplane run; Phase 3 printed-tag runs of T-B01–B05 and an H1 capture/train.
- **Plan changed (2026-10-10):** build-first. See "Build-first plan". Data needs per use case: `docs/data-requirements.md`.
- **Next task:** Phase 4: confirm the proposed `AFTER_PARAM` constraint with the user first, then `.sfpkg` export/import and receipts (`VerifierResult.receiptPath`).
- **Device scripting:** logcat tags `SF.IMU`, `SF.H3`, `SF.CG`, `SF.CAM`, `SF.PERC`, `SF.H1`. Timelines: `adb shell run-as com.application.sensorforge ls -R files/verify`.

## Phase 0: Docs setup
- [x] CLAUDE.md, AGENTS.md, docs/ structure
- [x] Spec docs copied into `docs/`; working docs filled
- [x] UI design defined in-repo (`docs/ui-design.md`); no external wireframes
- [x] Design system defined (`docs/design.md`, soft light theme)

## Phase 1: Fri (Green Light): foundations
- [ ] Pre-flight device checks (model stack §2):
  - [x] `adb shell getprop ro.soc.model` → SM8850 (Snapdragon 8 Elite Gen 5) ✔; Android 16 (SDK 36); RAM ~15.6 GB
  - [x] Enumerate sensors: accelerometer, gyroscope, rotation vector, step counter, step detector, gps/fused/network location ✔
  - [x] CameraX analysis: 640×480 YUV, rotation 90°, 30–31 fps, keep-latest, back camera (2026-10-10, Phase 3 slice 1)
  - [ ] Run one AI Hub sample model and log its execution provider (needs runtime dependency decision, see blockers)
  - [ ] Airplane-mode run of the empty app with bundled assets
- [x] Repo + 3 modules (`sf-sdk`, `studio-app`, `hello-forge`) + `fixtures/` (`:app` renamed to `:studio-app`); all build, both apps install + launch on iQOO
- [x] Contracts + validator (`ContractError(path, message)`) + `for_each` expander: 17 JVM tests
- [x] Verify engine (pure Kotlin) + 14 golden timelines (13 spec scenarios + forbidden impostor), determinism under shuffling, purity check: `./gradlew :sf-sdk:test` 22/22 pass

## Phase 2: Fri night: CrashGuard + real training
- [x] IMU capture (accel + gyro + rotation vector @ ~100 Hz, 20 s ring buffer): `sdk/detect/` (`ImuSample`, `SensorObservation`, `ImuRingBuffer`, pure) + `sdk/sensors/AndroidImuSource`; measured on iQOO: 100.0 Hz on all 3 channels, buffer caps at 20.0 s / ~6000 samples (logcat tag `SF.IMU`)
- [x] Window features (3 s, hop 0.5 s, ~32–64 dims): `sdk/detect/WindowFeatures` (36 dims, names in `NAMES`; null = NOT_OBSERVABLE when a channel is missing or below half rate); 8 JVM tests; on iQOO at rest acc_max 1.01 g, 10–18 ms/window (debug build, CPU, main thread)
- [x] Kotlin head training (H3) + proof artifacts (`training.json`, hash before/after): `sdk/train/HeadTrainer` (logistic or 2-layer MLP, Adam, L2, clip, early stop, seeded, session split), `HeadStore` (immutable `v<n>`, explicit promote/rollback), `sdk/detect/ImuDataset` + `RecordingStore`; 12 synthetic impact replays `fixtures/imu/impact_*.csv` (SIMULATION, `gen_impacts.py`); studio-app S10 model card (record normal/shake/drop, held-out toggle, Train, loss curve, HashCard, held-out metrics, approve/rollback). On device: live recording saved with LIVE provenance; Train refuses with typed error until each class has ≥ 5 dev windows. **Pending: a full 4-class train on the phone (needs a human to record shake + drop), which gives the T-C05 numbers.**
- [x] CrashGuard incident lifecycle + `MockIncidentTransport` + replay fixtures (labelled SIMULATION): `sdk/detect/Incident.kt` (`IncidentController`: gate → anchored window → head or rule-based fallback → stillness → lifecycle; `TransportResult` has only `SIMULATED`), `sdk/sensors/ReplaySource`; studio-app S10 arm/trace/replay, S11 countdown, S12 evidence + SIMULATED SOS, evidence encrypted with Keystore AES-GCM (`IncidentLog`). JVM tests cover T-C01–C04 logic plus NOT_OBSERVABLE and auto-escalate. On iQOO: replay → COUNTDOWN (SIMULATION chip); timeout → EVIDENCE_SAVED → manual → SOS_SIMULATED; I need help → SOS_SIMULATED; Cancel → CANCELLED, no file written; live motion crossing the gyro gate rejected (no stillness). Keystore round trip passes as an instrumented test.
- [ ] **Still to do for T-C01/T-C05:** a human records normal/shake/drop (≥ 5 dev windows each, plus held-out sessions), presses Train, approves the new version; then a live hard shake with the head active must give no incident. Log train ms and hashes in `docs/measurements/`.
- [x] Location for evidence: `sdk/sensors/LocationTracker` (GPS + cached fixes, no network calls, runs only while armed and visible), its own consent button on S10; without permission evidence says `LOCATION_NOT_AVAILABLE`. Consent dialog verified on iQOO; fix-in-evidence still to be checked after the user grants it
- [x] H3 v1 trained on the iQOO (2026-10-10 03:31): 121 ms CPU, parent `48ed1518…` → trained `00df4666…`, 660/660 params changed, val recall 1.0 (no live held-out sessions yet). Live 10 g real motion rejected (p=0.20); replay → countdown (p=1.00); evidence with location OK. **Caveat:** synthetic impact replays (6–12 g, 350–700 dps) are weaker than real bed drops (10–12 g, 1500–2200 dps), so the head may separate synthetic from real rather than impact from drop; don't claim crash accuracy from it
- [x] Threshold mode (calibration, not training): `DetectionSettings` (HEAD | THRESHOLD, `impactG`), slider 3–16 g on S10 with per-class recording peaks; persisted in `files/crashguard/detection.json`. On device: 9.7 g real motion rejected (no stillness), replay 10.7 g → countdown
- [x] Alarm on detection: flashlight blink (torch only, no capture), high-pitch two-tone siren synthesized on device (2.8 kHz / 3.5 kHz alternating every 0.25 s, AudioTrack on USAGE_ALARM), vibration; starts at COUNTDOWN, Cancel stops it, keeps ringing after timeout/help until "Silence alarm" or Resolve. Verified via `dumpsys media.camera` / `dumpsys audio`
- [ ] Held-out live sessions (1 each of normal/shake/drop with the held-out box ticked) and retrain
- [ ] Airplane-mode run of CrashGuard (T-C06): user enables airplane mode, then arm, replay, timeout, cancel, Train

## Phase 3: Sat morning: camera perception + PackCheck
- [x] Slice 1: CameraX Preview + ImageAnalysis (`studio-app/.../perception/CameraCheck.kt`, Camera tab, SF.CAM logs); unbinds when backgrounded (verified: no frames after HOME)
- [x] CameraX → detector (M1, **tagged-prop fallback**) → tracker (C3) → zones (QR corner markers, not ArUco) → codes → timeline: pure `sdk/perception/` (`Tags`, `Tracker`, `TimelineBuilder`, `Coach`, `CropEmbedding`), Android `sdk/camera/` (`TagFrameReader`, `CameraVerifier`). 12 JVM scenario tests (`PackCheckScenarioTest`, SIMULATION) cover T-B01–B05 logic, closed-box observability, camera_unstable, determinism. On iQOO: 1280×720 analysis at 30 fps, ZXing decode p50 10–12 ms
- [~] M2 encoder + identity head (H1): **M2 not integrated** (no model file); H1 trains on the phone on a handcrafted YUV-histogram crop embedding (`IdentityForge`, PackCheck "Identity head" card: capture genuine/impostor, held-out toggle, Train, HashCard, approve). Train refusal verified on device; **a real capture + train needs a human with the props**
- [x] PackCheck + coaching (overlay + TTS): `SensorForge.load(context, "asset:specs/packcheck.spec.json")` → `Verifier.start/events/stop`; S08 live (preview, zone/box overlay, live checklist, coaching overlay + Android TTS), S09 result (verdict, reasons, steps, evidence frame, timeline summary). Empty-scene run on iQOO → UNVERIFIED (all NOT_OBSERVABLE); coaching "Show the box to the camera." fired after 3 s
- [ ] **Needs a human:** print `fixtures/tags/*.png` (≥ 4 cm), tag the box (4 rim corners, floor, inside lid "open", lid top "closed"), charger, adapter AD-42, impostor and duplicate; run T-B01–B05 on the iQOO and log results in `docs/measurements/`

> **Superseded by `docs/WORKFLOW.md` (one-shot, user decision 2026-10-10).** The Phase 4–11 lists below stay as the detailed task checklist for milestones A1–A13 and Stages B–D; tick tasks here as they are done.

## Build-first plan (from Phase 4)
**User decision 2026-10-10:** build the whole application first: every feature in the spec docs, every screen, every model slot loaded. Then the user supplies good/bad examples per use case (`docs/data-requirements.md`), and we train, calibrate and run acceptance. So the "T1 must pass before T2" gate moves to Phase 10. Phases 4–9 are **build** phases. Anything that needs real data is collected under Phase 10, not left as a blocker.

**Every build phase must also meet the UI/function bar:**
- Screens follow `docs/ui-design.md` (screen content) and `docs/design.md` (tokens, components, accessibility checklist). No hard-coded colours. Light theme only.
- **No dead UI:** every button, toggle and navigation path works end to end on the iQOO, or is visibly disabled with a reason ("Needs ≥ 5 samples", "Model not installed").
- Empty, loading, error (typed error + fix hint) and permission-denied states exist for every screen.
- Data paths are ready before data exists: capture → label → train/calibrate → test → export works with whatever examples are present, and refuses clearly when there are too few.
- Each model sits behind the model registry (Phase 9): loads at start or on first use, logs runtime / provider / backend / latency, and shows a clear "not installed" state with its fallback if the file is missing.
- Model files are downloaded only with the user's OK (name, source, size, licence first); record each one in the model stack.

## Phase 4: packs, receipts, HelloForge, SealSafe
- [ ] Resolve the SealSafe blocker: proposed schema 3.0 addition, constraint `{"type":"AFTER_PARAM","step":"<id>","param":"start_time"}` (the step's evidence time must be ≥ the param value). Violation → `ORDER_VIOLATED`; param missing → `ContractError`. Confirm with the user, then add it to validator, engine and golden tests
- [ ] `.sfpkg` export/import: manifest, spec, heads/, thresholds, regression, training.json, models.lock, assets; `pack_sha256`; models.lock hash check → `PerceptionStackMismatch` (fail closed); share via Sharesheet + app storage
- [ ] Receipts: canonical JSON, `receipt_sha256`, verify-receipt ("receipt invalid" on any byte change); fill `VerifierResult.receiptPath`; S16 receipt screen
- [ ] HelloForge: the two-line import, pack id + hash footer, typed errors (S19)
- [ ] SealSafe: spec, M2 encoder integration (MediaPipe Image Embedder or equivalent, through the registry), H2 state head (intact/broken) capture + train UI, S13 screen
- [ ] T-S01/T-S02 logic covered by JVM tests (on-device run moves to Phase 10)

## Phase 5: Forge studio (authoring → capture → train → test → export) + shell screens
- [ ] S01 Studio home (tiles for all apps + "Forge new verifier"), app navigation
- [ ] **Guided setup flow for every use case** (user decision): Describe → Plan → Capture → Train → Test → Export → Import, shared by PackCheck, CrashGuard, SealSafe, RepRight, HandoverProof and new verifiers. Retrofit the existing CrashGuard and PackCheck capture/train cards into it
- [ ] S02b Plan: example plan generated from the spec + `docs/data-requirements.md` (good / bad / unclear items, head classes, counts, held-out, how-to, expected verdict, required config); editable; gates Capture
- [ ] S02 Describe: P0 template compiler (show X, open X, remove X from Y, only after T, hold for N seconds), editable step cards + JSON, example checklist
- [ ] S03 Capture: record examples with labels (`valid|invalid|insufficient`, `missing_steps`), session split `dev|held_out`, per-class counts; **plus import of clips/recordings already on the phone** so supplied data drops straight in
- [ ] Scoring (false accepts / false rejects / wrong_kind), order + requirement induction, threshold calibration (`ok|fragile|infeasible`), regression on every approved delta (S05)
- [ ] S04 Train (any head type), approve/rollback; S06 Export → `.sfpkg`
- [ ] S17 Backend log (from the model registry + measurement log), S18 Settings/Privacy (per-sensor consent, retention, delete-all, face blur in stored evidence)
- [ ] Session JSONL logging (observations summary, decisions, backends, timings)

## Phase 6: RepRight
- [ ] M3 MediaPipe Pose Landmarker bundled offline (through the registry); angle smoothing, rep state machine, `joint_angle_in_range`, `pose_held`, `rep_completed`
- [ ] Therapist config screen (knee extension angles/hold/tempo; tree pose); S14 screen; "Meets configured criteria" wording
- [ ] JVM tests for T-P01–03 logic on synthetic landmark fixtures (labelled SIMULATION)

## Phase 7: HandoverProof
- [ ] M4 MediaPipe Hand Landmarker (registry); `held_by_hand`, `released`, `transferred(from_side,to_side)` via side zones
- [ ] Step counter (`ACTIVITY_RECOGNITION` consent) → `steps_walked`; `at_location` recorded, never sole evidence; slip `code_read`
- [ ] Host-supplied items list (`for_each`), S15 screen
- [ ] M5 RelateAnything spike (ONNX Runtime, keyframes) behind the registry, with side-zone fallback

## Phase 8: authoring models (P1 + optional)
- [ ] M6 local LLM (Qwen3-4B or SmolLM2-1.7B-Instruct via Qualcomm Genie/GenieX) in Forge only, never during verification; invalid JSON → template compiler
- [ ] M7 on-device STT (offline language pack) for voice authoring; TTS already used for coaching
- [ ] O1 Laya (Forge step-typer only) if a model file is available, else marked omitted
- [ ] Each: "not installed" state + fallback works without it

## Phase 9: model readiness + UI polish/QA
- [ ] Model registry: every model in the model stack listed with file, sha256, licence, size, runtime, expected backend, status (installed / missing / fallback); `models.lock` generated from it
- [ ] M1 learned detector: integrate the fine-tuned detector from `tools/detector/` when trained; YOLOE via AI Hub if the user gets an export; tagged props stay the declared fallback
- [ ] Warm-up and one timed run per installed model → `docs/measurements/measurement-log.md`
- [ ] Full UI pass against the `docs/design.md` §8 checklist on every screen (contrast, 48 dp targets, 1.3× font, 360 dp width, TalkBack labels, status = colour + icon + text)
- [ ] Click-through QA on the iQOO: every screen, every button, every state (empty / loading / error / permission denied / SIMULATION); fix every dead end
- [ ] Airplane-mode run of the whole app with all bundled assets
- [ ] Claims audit of all UI strings (prompts.md "Claims audit")

## Phase 10: data, training, acceptance (user supplies data)
- [ ] User captures/imports examples per `docs/data-requirements.md` (incl. the open Phase 2/3 human tasks: CrashGuard normal/shake/drop + held-out; PackCheck printed-tag runs; H1 genuine/impostor crops)
- [ ] Train all heads (H1, H2, H3), fine-tune the detector if used, calibrate, regression; approve versions
- [ ] Run every acceptance test on the iQOO in airplane mode: T-B01–05, T-C01–06, T-S01–02, T-P01–03, HandoverProof cases
- [ ] Fill the measurement log with held-out results; fix failures

## Phase 11: evidence + rehearsal
- [ ] Evidence slide (real numbers only), labelled backup video
- [ ] ≥ 5 rehearsals (each wow moment ≥ 5× on the real phone, poor lighting)

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
| `ro.soc.model` | SM8850 (QTI) — Snapdragon 8 Elite Gen 5 |
| Product model | I2501 (build PD2505F_EX_A_16.0.24.1.W30) |
| Android version | 16 (SDK 36) |
| RAM | 15,583,752 kB (~15.6 GB) |
| Sensors present | Accelerometer (ST lsm6dsvx), gyroscope (vsen_gyro), rotation vector, step counter (pedometer), step detector (both need ACTIVITY_RECOGNITION), magnetometer; location providers gps, fused, network |
| CameraX analysis res/fps | 640×480 @ 30–31 fps (ImageAnalysis, keep-latest, debug build, no analyzer work) |

## Decisions log
| Date | Decision |
|---|---|
| 2026-10-10 | Docs set v1.0/v3.0 FINAL adopted; supersedes earlier SensorForge docs |
| 2026-10-10 | No external wireframes; UI designed by the agent in `docs/ui-design.md` |
| 2026-10-10 | Timeline `identity_unresolved` carries `other_track_id` (the earlier track it may equal); spec §9 doesn't name the field |
| 2026-10-10 | `PERCEPTION_STACK_MISMATCH` and `UNEXPLAINED_CHANGE` classed as UNVERIFIED reasons; stack mismatch fails closed (all steps NOT_OBSERVABLE, no other reasons) |
| 2026-10-10 | A `code_read` with no decoded value is not trustworthy evidence (→ NOT_OBSERVABLE, rule 5) |
| 2026-10-10 | Step `entity`/`container` are entity ids; matched against primitive `entity.cls` / `container` class |
| 2026-10-10 | Manifest `created_on_device` is a boolean; detect-mode packs may not list camera/microphone |
| 2026-10-10 | Golden timelines are synthetic fixtures, labelled as such in each file |
| 2026-10-10 | T-C03 says timeout → SOS_SIMULATED, but §19 says TIMEOUT with `auto_escalate=false` stays at EVIDENCE_SAVED. Implemented §19: manual "Send simulated SOS" → SOS_SIMULATED; with `auto_escalate=true` it is automatic. T-C03 is run with the manual tap |
| 2026-10-10 | `impact_like` training data = 12 synthetic replays (`fixtures/imu/gen_impacts.py`, SIMULATION); last 3 are held-out. Drop/impact windows are anchored on the peak the same way the detector evaluates them |
| 2026-10-10 | Detection mode is user-selectable: trained head (H3) or calibrated threshold (default 1.5 × gate = 4.5 g) + stillness; with no head approved, HEAD mode uses the threshold. Labelled "calibrated", never "trained" |
| 2026-10-10 | Local alarm (torch blink, alarm sound, vibration) on COUNTDOWN at the user's request. Torch is not camera capture; S10 status now reads "no camera capture, mic off". Alarm contacts no one |
| 2026-10-10 | Unobservable decision window → no incident, trace says NOT_OBSERVABLE |
| 2026-10-10 | Runtime for M1/M2: LiteRT + QNN delegate, GPU then CPU fallback (user said proceed on this recommendation). ONNX Runtime only where the model stack names it |
| 2026-10-10 | M1 = tagged props (QR), the model stack's declared fallback, since no detector model file is available; claim "controlled props". Zones from QR corner markers + floor marker instead of ArUco (no OpenCV) |
| 2026-10-10 | Zone/container observability = corners valid AND interior visible (floor tag read, or an object read inside). A visibly closed container (`closed` state tag) also counts as observed: nothing can leave it unseen. An open lid alone proves nothing (else a covering hand would give ABSENT → FAIL instead of UNVERIFIED) |
| 2026-10-10 | First state seen is `state_is`, not `state_change`: a box already open at start never "opened" (box_opened ABSENT) |
| 2026-10-10 | Identity across tracks only from an equal instance code on a non-concurrent track; otherwise new identity + `identity_unresolved` to the latest ended (or jumped) track of that class |
| 2026-10-10 | H1 uses a handcrafted crop embedding until an M2 encoder exists; labelled as such in UI and model stack |
| 2026-10-10 | **Build-first:** the user asked to build every feature, screen and model slot before supplying data; the gate and acceptance move to Phase 10, and the open human data tasks move there too. Every build phase must meet the UI/function bar (design.md, no dead UI) |
| 2026-10-10 | Every use case gets a guided Describe → **Plan** → Capture → Train → Test → Export → Import flow. Plan is a dedicated step that shows the example checklist (tech spec §16.2) with counts, held-out sessions and how-to; capture is driven by it |
| 2026-10-10 | Model downloads approved by the user: MediaPipe bundles (pose heavy, hands, MobileNet-V3-large embedder, EfficientDet-Lite2: downloaded to `sf-sdk/src/main/assets/models/`, hashes in model stack §10), YOLOE-11S (AGPL), SmolLM2-1.7B on llama.cpp (deviation from Genie), RelateAnything (DINOv3 licence for the user to accept). Sessions still confirm exact file + size at download time |
| 2026-10-10 | **One-shot workflow** (`docs/WORKFLOW.md`): no phase-by-phase sessions; `build all` runs milestones A1–A13 and the simulation test, then hands over for the user's real-data tests. Decisions are defaulted and queued as questions, not blocking |
| 2026-10-10 | Post-peak stillness (§18) is measured from 0.25 s after the acceleration-magnitude peak (`SETTLE_NANOS`) so the impact tail is excluded |

## Stage B results (simulation test)
_Filled during the run._

## Questions for user (decisions taken by default during the run)
- SealSafe start-time rule implemented as `AFTER_PARAM` (proposed; not yet confirmed by the user). Needs a new `time` parameter type (UTC epoch ms) and `Timeline.start_utc_ms`; missing either is a `ContractError`.

## A1 log
- `AFTER_PARAM` in contracts/validator/expander/engine + `fixtures/specs/sealsafe.spec.json` + `SealSafeSpecTest` (8 tests).
- `sdk/models/ModelCatalog` (pure: all model-stack slots, hashes, licences, fallbacks, `models.lock`, fail-closed `check`), `sdk/runtime/ModelRegistry` (live status per slot), `BackendLog` (JSONL, summaries, airplane flag), detector wired to it.

## A2 log
- `sdk/pack`: `Canonical` JSON + sha256, `Pack.build/open` (.sfpkg zip; pack_sha256 over canonical entries; fail-closed `PerceptionStackMismatch` on models.lock mismatch; `PackIntegrityError`), `Receipts.create/verify` (canonical, tamper = invalid). 12 JVM tests in `PackReceiptTest`.
- `SensorForge.load()` now takes `.sfpkg` or spec JSON; `CameraVerifier` writes `receipt.json` and sets `receiptPath`; `SensorForge.readReceipt`; S16 `ReceiptView` shown from PackCheck result.
- Export UI + Sharesheet come with A3 (Forge Export step).

## A3 log (in progress, uncommitted work in the tree)
**Done (sf-sdk, all JVM tests pass):**
- `sdk/forge/`: `Simulator` (spec-driven SIMULATION timelines per scenario; every scenario of packcheck/sealsafe/unboxing gives the expected verdict+reason), `Plan`/`PlanBuilder` (good/bad/unclear + head items, min/target counts, held-out), `Scoring` (false accepts/rejects/wrong_kind, dev vs held-out), `Induction` (order needs >=5, requirements), `Calibration` (ok/fragile/infeasible), `Regression` (deltas + consequence), `TemplateCompiler` (show/open/close/remove/read/at most one/never/only after; pronouns), `UseCases` (PACKCHECK, SEALSAFE, CRASHGUARD defs), `ForgeProject` (file-backed spec versions, plan, config, examples, simulate, export .sfpkg), `ForgeSession` (Android facade: crop heads, export with approved heads + models.lock, `openPack` typed outcomes).
- `SensorForge.forge(ctx, id)`, `openPack`, `cropHeadForge`; `IdentityForge` now takes a head id and has `simulate()` (SIMULATION crops, flagged in `IdentityExample.simulation`).
- `detect/ImuSynth` (SIMULATION normal/shake/drop) + `RecordingStore.saveSimulated`; test trains H3 on them and the hash changes.
- `studio-app/ui/Components.kt`: ErrorCard, InfoCard, ForgeStepper, VerdictBanner, SectionTitle, Mono.
**Done (studio-app UI, verified on the iQOO with SIMULATION data):**
- Router in `MainActivity` (home | uc:<id> | forge:<id> | run:<id>[:<planItem>] | camera) replaces the tab bar; back handling; CrashGuard countdown/evidence still take over globally.
- `forge/ForgeViewModel` + `forge/ForgeScreens.kt`: 7 pages (Describe, Plan, Capture, Train, Test, Export, Import) with gating (plan must be accepted, min counts, typed errors, SIMULATION chip when synthetic data is present).
- Capture: RUN (record live via Run screen with "Save as example", import timeline JSON, simulate), CROP (camera capture + simulate), IMU (record, simulate via `ImuSynth`), "Fill plan with SIMULATION examples", remove simulated, delete all.
- PackCheck Run screen generalised (`PackCheckViewModel.configure(path, title, params)`), so SealSafe runs the same screen from its exported pack; the H1 card is now in Train.
- Share via FileProvider (`file_paths.xml`).
- Device run (SealSafe): plan accepted → filled with simulated examples → H2 trained (218/218 params changed, hash changed) → approved → Test: no false accepts on 55 examples → exported `sealsafe-v1-99a8f624.sfpkg` → Import: hash ok, stack ok → Run live opens. CrashGuard: Plan → fill → Train shows ModelCard.
**Deferred:** video clip import → A5.
**Notes:** video clip import needs a bitmap path in perception (do in A5). Python edits must use `encoding='utf-8'` (a cp1252 write once corrupted `SensorForge.kt`; fixed).

## Blockers
- SealSafe needs `state_change AFTER param start_time` (tech spec Part F) but §6 defines no AFTER/param constraint. Proposed `AFTER_PARAM` in Phase 4; the user must confirm.
- Model files: MediaPipe pose/hands/embedder/EfficientDet-Lite2 downloaded (model stack §10). YOLOE (AGPL, AI Hub export), RelateAnything + DINOv3 weights (licence check), Qwen3-4B/SmolLM2 Genie bundles (large) and Laya (no source in the spec) need the user to obtain or approve them. Fallbacks per model stack §7 keep the app working without them. Runtime decided: LiteRT + QNN.

## A4 log
- Home (S01): tiles for built-in + custom use cases, **Forge new verifier** (name + sentence → `CustomUseCases.create`: template-compiled spec, same guided flow, tagged-prop perception, no head), links to Backend log, Settings, Camera diagnostics.
- `sdk/runtime/CustomUseCases` (persisted in `files/forge/custom.json`), `SensorForge.useCases/useCase/createUseCase/deleteUseCase`.
- S17 `BackendLogScreen`: measured runs from `BackendLog` (runtime/provider/backend/cold/warm p50-p95, offline counts), airplane badge, every model-stack slot with status/licence/size/hash/fallback claim.
- S18 `SettingsScreen`: real Android permission state per sensor (camera, location, motion; microphone never requested), retention text, face blur honestly "off", delete-all (forge, heads, verify, crashguard, logs, tags).
- `sdk/runtime/SessionLog`: `files/logs/session-<date>.jsonl` (forge approve/train/export/save_live, verify.result, crashguard.*). Summaries only.
- S16 receipt already shown from the verify result (A2).
- Device-verified: custom "Pill box" verifier created → plan → simulated → test (27 examples, no false accepts) → exported; backend log and settings render; session log line written.
