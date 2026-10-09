# Build & Architecture

Sources: tech spec Parts A–E, G (§2–§24, §26–§29); model stack §1–§8. Full detail lives in `sensorforge-technical-spec-v3.md` and `sensorforge-model-stack.md`.

## Target device
iQOO loaner — expected iQOO 15, Snapdragon 8 Elite Gen 5, OriginOS 6 / Android 16 (**verify on the unit**).

## Tech stack
| Area | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| State | ViewModel + StateFlow |
| DI | Manual DI |
| Persistence | Room for sessions; app-private files |
| Secure storage | Android Keystore (AES-GCM) for incident logs |
| Camera | CameraX Preview + ImageAnalysis, `STRATEGY_KEEP_ONLY_LATEST` |
| Serialization | kotlinx.serialization |
| ML runtimes | LiteRT + QNN delegate, or ONNX Runtime + QNN EP (GPU/CPU fallback); MediaPipe; Qualcomm Genie/GenieX (P1) |
| Classical CV | OpenCV Android, zxing-cpp |
| TTS / STT | Android TextToSpeech; Android on-device speech recognizer (P1) |

## Repository and modules
```text
sensorforge/
├── CLAUDE.md                  points to the three docs, restates §1 rules
├── docs/                      the three spec files + measurements/
├── sf-sdk/                    Android library — the only dependency apps take
│   ├── contracts/             pack, spec, timeline, decision, receipt types + validator
│   ├── engine/                pure Kotlin: verify + detect engines, identity, reasons
│   ├── forge/                 calibration, regression, order induction, export
│   ├── training/              Kotlin heads (logistic/MLP/prototype), splits, metrics
│   ├── perception/            CameraX ingest, detector, tracker, zones, codes, pose, hands, IMU
│   ├── runtime/               pack loader, models.lock check, sessions, coaching, receipts, logging
│   └── SensorForge.kt         public API
├── studio-app/                Forge UI + end apps (tiles): PackCheck, CrashGuard, SealSafe,
│                              RepRight, HandoverProof
├── hello-forge/               empty sample app (two-line import)
└── fixtures/                  golden timelines, IMU replays, example clips (labelled)
```
`sf-sdk/contracts` and `sf-sdk/engine` must compile as plain JVM code (`./gradlew :sf-sdk:test`).

> Current scaffold: only `:app` (`com.application.sensorforge`). Must be aligned to the layout above.

## System overview
```text
Android sensors (CameraX, SensorManager, step counter, location, clock)
   │
   ▼
Perception modules (only those in the pack's perception_profile)
   │  detector · tracker · zones · codes · pose · hands · state head · IMU features
   ▼
Observation timeline (typed, timestamped, provenance, quality, observability)
   │
   ▼
Trained heads (per pack: identity / state / IMU risk)
   │
   ▼
Engine
   ├── verify mode → step evaluation → constraints → PASS | FAIL | UNVERIFIED
   └── detect mode → gate → head → corroboration → incident lifecycle
   │
   ▼
Policy (deterministic, permissions)  →  Decision + reasons + evidence + receipt
   │
   ▼
SDK callback to host app
```
AI roles never conflated: authoring · perception (frozen pretrained) · learned heads (trained on phone) · decision (deterministic code).

## Public SDK API
```kotlin
object SensorForge {
    fun load(context: Context, packPath: String, params: Map<String, Any> = emptyMap()): Verifier
    fun forge(context: Context): ForgeSession          // authoring, capture, train, calibrate, export
}

interface Verifier {
    val pack: PackInfo                                  // id, version, mode, hash
    fun start(previewView: PreviewView?, onResult: (VerifierResult) -> Unit)
    fun events(): Flow<VerifierEvent>                    // live step status, coaching, incidents
    fun stop()
}

data class VerifierResult(
    val verdict: Verdict,                // PASS | FAIL | UNVERIFIED  (verify mode)
    val incident: IncidentState?,        // detect mode only
    val reasons: List<Reason>,
    val steps: List<StepResult>,
    val receipt: Receipt
)
```
`previewView` is null for sensor-only packs (CrashGuard). Typed errors: `ContractError`, `PerceptionStackMismatch`, `PermissionMissing`, `CapabilityMissing`.

## Contracts

### Verifier Pack (`.sfpkg`, zip)
```text
manifest.json     schema_version, pack_id, pack_version, mode (verify|detect),
                  perception_profile[], sensors[], created_on_device, pack_sha256
spec.json         procedure (verify) or detect policy (detect)
heads/<id>.json   trained head: type, weights, bias, normalizer, labels, sha256
thresholds.json   calibrated per-step / per-class thresholds
regression.json   example summaries + expected verdicts
training.json     proof artifacts (head training)
models.lock       frozen base models: id, version, sha256, runtime, expected backend
assets/           zones.json, code lists (optional)
```
Frozen base models ship inside `sf-sdk`, referenced by hash. Hash mismatch at load → `PerceptionStackMismatch`, fail closed.

### Procedure spec (verify) — key points
Schema `3.0`. Fields: `procedure_id`, `version`, `parameters` (e.g. `items: list<code>`, source `host_app`), `entities` (`id`, `cls`, `identity_policy` = `track_continuity` | `code_required`, optional `head`, `expected_code`), `steps` (`id`, `primitive`, `entity`, `container`/`zone`/`state`, `observe_target`), `constraints` (`BEFORE`, `ANY_ORDER`, `SAME_IDENTITY`; `source` = `induced` | `developer`), `prohibited` (`MAX_INSTANCES`, `FORBIDDEN_CLASS` with `min_confidence`), `policy` (`min_observable_coverage`, `unexplained_change_blocks_pass`, `coaching_timeout_s`).
Step defaults: `required = true`, `min_confidence = 0.75`. `"for_each": "items"` with `{item}` placeholders expands before evaluation; parameter values are written to the receipt. Full PackCheck example: tech spec §6.

### Detect spec (CrashGuard)
```json
{
  "schema_version": "3.0", "procedure_id": "crashguard", "version": 2, "mode": "detect",
  "window": { "length_s": 3.0, "hop_s": 0.5, "imu_hz": 100 },
  "gate": { "accel_peak_g": 3.0, "gyro_peak_dps": 300 },
  "head": { "id": "imu_risk", "positive_class": "impact_like", "min_prob": 0.8 },
  "corroboration": { "post_event_stillness_s": 2.0, "stillness_max_g_std": 0.08 },
  "incident": { "countdown_s": 15, "auto_escalate": false, "transport": "mock" },
  "evidence": { "imu_ring_buffer_s": 20, "location": "last_fix_with_accuracy" }
}
```

### Contract validation
Reject before running: unknown keys/primitives, dangling references, duplicate IDs, confidence outside [0,1], unsupported schema version, cyclic `BEFORE`, missing heads referenced by entities, sensors not permitted, `auto_escalate=true`, `transport != "mock"` (finale build). Error: `ContractError(path, message)`.

### Observation timeline (verifier's only input)
```text
clip_id, perception_stack, duration_s
tracks[]:        track_id, cls, identity_id, t_start, t_end, code?
primitives[]:    id, type, t, entity{track_id, cls}, container?, zone?, state?, confidence, evidence_frames[]
observability[]: target, intervals[[t0,t1]]
```
Identity is never restored from appearance alone — only track continuity, a matching code, or a trained identity head above its calibrated threshold (recorded as evidence).

### Primitive vocabulary (closed; new primitives need a schema version bump)
| Primitive | Producer | Mode |
|---|---|---|
| `appears`, `disappears`, `track_break` | detector + tracker | verify |
| `enters_zone`, `leaves_zone`, `region_empty` | zones (ArUco) | verify |
| `contained_in`, `exits_container` | geometry over tracks | verify |
| `held_by_hand`, `released`, `transferred(from_side,to_side)` | hands + zones (+ relations P2) | verify |
| `state_is`, `state_change` | state head on crops | verify |
| `code_read` | zxing / OpenCV QR | verify |
| `joint_angle_in_range`, `pose_held`, `rep_completed` | pose landmarks | verify |
| `steps_walked`, `at_location` | step counter, location (with accuracy) | verify |
| `camera_unstable` | IMU + blur | both |
| `identity_unresolved`, `unexplained_change` | tracker / extractor | verify |
| `impact_candidate`, `stillness` | IMU features | detect |

Semantics: `camera_unstable [t, t_end]` removes evidence and coverage in the interval; `identity_unresolved` = new track *may* equal an old one but unproven; `unexplained_change` blocks PASS if policy says so; `at_location` is never sufficient evidence alone.

## Verify engine (pure Kotlin)
- **Observability** is owned by perception (class window: in view, usable quality, blur below threshold; zone window: visible, valid geometry, sufficiently unoccluded). Engine never infers visibility from missing detections.
- **Candidate matching:** `type == step.primitive` and `entity.cls == step.entity.cls` (except `region_empty`), plus container/zone/state when specified.
- **Trustworthy evidence:** `observable && confidence >= step.min_confidence && t not in camera_unstable`.
- **Step evaluation:**
  ```text
  for step:
    candidates = matching primitives
    if any trustworthy: OBSERVED (choose earliest by (t,id))
    elif candidates:    NOT_OBSERVABLE
    else:
       coverage = observability coverage of observe_target over eligible interval
       (eligible interval starts at latest observed BEFORE predecessor, else 0)
       coverage >= min_observable_coverage ? ABSENT : NOT_OBSERVABLE
  ```
- **Identity:** union-find over `identity_id` linked by `identity_unresolved`; min distinct = components, max distinct = identity IDs.
  - `SAME_IDENTITY(a,b)`: different components → `IDENTITY_MISMATCH` (FAIL); same component via unresolved link → `IDENTITY_UNPROVEN` (UNVERIFIED).
  - `MAX_INSTANCES(E,max)`: min_possible > max → `PROHIBITED_OBSERVED` (FAIL); min ≤ max < max_possible → `PROHIBITED_UNDETERMINED` (UNVERIFIED).
- `BEFORE(a,b)` both observed and t_a > t_b → `ORDER_VIOLATED` (FAIL). Code mismatch → `CODE_MISMATCH` (FAIL). Required ABSENT → `REQUIRED_ABSENT` (FAIL). Required NOT_OBSERVABLE → `REQUIRED_NOT_OBSERVABLE` (UNVERIFIED). `FORBIDDEN_CLASS` observed → `PROHIBITED_OBSERVED`.
- **Reasons** sorted by `(code, ref)`: `REQUIRED_ABSENT, REQUIRED_NOT_OBSERVABLE, CODE_MISMATCH, ORDER_VIOLATED, IDENTITY_MISMATCH, IDENTITY_UNPROVEN, PROHIBITED_OBSERVED, PROHIBITED_UNDETERMINED, UNEXPLAINED_CHANGE, PERCEPTION_STACK_MISMATCH`.
- **Verdict:** any FAIL reason → FAIL; else any UNVERIFIED → UNVERIFIED; else PASS. All reasons kept.
- **Determinism:** same timeline + spec → identical result (property-tested).

## Forge
Pipeline: `description → draft spec (template/LLM) → developer review → capture examples → perception → timelines → engine → score → candidate deltas → approval → new version → calibration → head training → regression → export .sfpkg`

- **Authoring:** P0 template compiler — sentence patterns per primitive ("show X", "open X", "remove X from Y", "only after T", "hold for N seconds") → steps/constraints; every draft is editable JSON + cards. P1 local LLM producing schema-valid JSON; invalid output → template fallback. Assistant also emits an example checklist (valid variants + hard negatives).
- **Examples:** labels `valid | invalid | insufficient` (+ optional `missing_steps`); split `dev | held_out` by session; held-out never used for calibration.
- **Score:** `false_accepts`, `false_rejects`, `wrong_kind` (UNVERIFIED vs FAIL confusion). False accepts dominate.
- **Order induction:** propose `BEFORE` only if consistently observed in ≥ 5 dev examples; else ask the developer and mark `source: developer`. Different orders across valid examples → `ANY_ORDER`.
- **Requirement induction:** observed in all valid examples → required; else optional (developer confirms).
- **Threshold calibration:** per step, threshold maximising separation on dev examples with zero false accepts; label `ok | fragile | infeasible`; infeasible → tell the developer perception can't support the step.
- **Regression:** every approved delta re-runs regression; if a previously correct example breaks, show the delta with its consequence before approval.
- **Head training (real weights):** logistic regression, 2-layer MLP, or prototype (nearest-centroid on embeddings).
  ```text
  assert per-class count >= demo_min (5)
  split by session → train/val (held_out untouched)
  fit standardiser on train only
  init W,b with seeded RNG (or warm start)
  mini-batch softmax cross-entropy + L2, Adam, grad clip, early stop on val loss
  restore best; compute per-class metrics on val and held_out
  save NEW immutable head version; promotion needs user approval; rollback allowed
  ```
  `training.json`: `trainedAt, datasetVersion, trainSamples, valSamples, lossCurve, perClassMetrics, parentSha256, trainedWeightsSha256, changedParameterCount, trainingBackend (CPU), inferenceBackend, deviceFingerprint`. UI shows before/after hash.
- **Export:** build `.sfpkg`, compute `pack_sha256`, share via Android Sharesheet/Nearby or app storage. HelloForge loads from shared storage.

## Detect engine (CrashGuard)
```text
User taps Arm (visible foreground session, motion sensors only; camera/mic OFF)
→ SensorManager accel + gyro + rotation vector @ ~100 Hz → IMU ring buffer (20 s)
→ sliding windows (3 s, hop 0.5 s) → features
→ cheap gate (peak |a|, |ω|) → trained IMU head → stillness corroboration
→ policy → incident lifecycle
```
- **Features (~32–64 dims/window):** |a| and |ω| max/mean/std, jerk stats, time above threshold, orientation delta (rotation vector), post-peak stillness (std of |a| over 2 s), zero-crossing rate; standardised with training stats.
- **Lifecycle:** `DISARMED → ARMED → CANDIDATE → SUSPECTED → COUNTDOWN → (CANCELLED | CONFIRMED | TIMEOUT) → EVIDENCE_SAVED → SOS_SIMULATED → RESOLVED`
  - Countdown screen: big Cancel, "I need help", vibration.
  - TIMEOUT with `auto_escalate=false` → stays at EVIDENCE_SAVED, offers manual action.
  - `MockIncidentTransport` returns `SIMULATED`; banner "SIMULATED SOS"; never displays `SENT`.
  - Evidence: frozen IMU buffer, location + accuracy + age, head version, decision trace, receipt.
- **Safety:** Motion Watch only. If app not visible, never start camera/mic; show `CAPTURE_NOT_AVAILABLE`. Impact samples are replay fixtures injected through the same `SensorObservation` interface, labelled SIMULATION. Live negatives: hand shake, table knock, pocket motion.

## Perception (Android)
- **Camera pipeline:** CameraX Preview + ImageAnalysis, keep-latest, close every `ImageProxy`; analysis 5–15 fps; detector at fixed stride, Lucas–Kanade flow between detector frames; ROI crops for heads; monotonic timestamps (`elapsedRealtimeNanos`) + UTC for display.
- **Modules per `perception_profile`** (only listed modules run):

| Profile | Components |
|---|---|
| `objects` | detector → tracker (IoU + Kalman) → primitive extractor (containment, exits, appears) |
| `zones` | ArUco mat → homography → zones.json |
| `codes` | zxing-cpp (+ OpenCV QR backup) |
| `state` | state head on crops |
| `identity` | identity head on crops |
| `body` | pose landmarker → angles, holds, reps |
| `hands` | hand landmarker → held/released/transferred via side zones |
| `sensors` | IMU features, step counter, location |
| `quality` | blur (Laplacian), IMU motion → `camera_unstable`; MOG2 → `unexplained_change` |

Primitive extractor emits the timeline incrementally; engine re-evaluates ~1 Hz for coaching and finally at stop.
- **Live coaching:** if the active required step's target is NOT_OBSERVABLE for > `coaching_timeout_s`, emit `coaching_prompt {step_id, target, message}` → overlay + Android TTS. Never changes verdict logic.
- **Evidence receipt:**
  ```text
  receipt.json: pack_id, pack_version, pack_sha256, perception_stack, params,
                verdict | incident_state, reasons[], step statuses,
                clip_sha256, timeline_sha256, created_at_utc, elapsed_ns, device_model
  receipt_sha256 = SHA-256(canonical JSON)
  ```
  Verify-receipt recomputes hashes; any change → "receipt invalid".

## Model stack
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

Pose/hand/face landmarks are MediaPipe, not OpenCV.

### Model details & gates
| Model | Key details | Gate |
|---|---|---|
| M1 | Boxes for box, envelope, charger, cable, adapter, papers, impostor, hands-region items. Vocabulary fixed at export from pack class list. Fallback: fine-tune small detector on real props (4–6 classes), or tagged props (QR/colour) declared openly. Licence: YOLOE/Ultralytics AGPL — OK for hackathon, review before distribution (Apache alternatives in Qualcomm AI Hub). Export via Qualcomm AI Hub → TFLite/QNN or ONNX; profile on hosted device first. Input: start 320–640, smallest keeping recall. | Correct classes on new recordings of our props under demo lighting; ≥ 5 fps sustained |
| M2 | Embeddings of item/seal crops for H1/H2; pick by spike; log backend | Linear separability (genuine vs impostor; intact vs broken) on held-out sessions |
| H1/H2 | Prototype (nearest-centroid) or linear softmax on M2 embeddings; Kotlin training | Held-out accuracy > baseline, weights hash changes, train time < 10 s |
| H3 | 32–64 window features; logistic or 2-layer MLP (Kotlin, Adam); classes normal, shake, drop, impact_like; live-recorded normal/shake/drop, impact_like from labelled replay fixtures | Session-split held-out: shake/knock never → SUSPECTED; impact replays → SUSPECTED; training < 5 s |
| M3 | Bundled offline; angles smoothed; reps via state machine | Correct count on valid reps, rejects too-fast/partial, UNVERIFIED when keypoints lost |
| M4 | Hand–object overlap + side zones → `held_by_hand`/`transferred` | Transfer detected at correct moment on demo clips |
| M5 | github.com/Maelic/RelateAnything (Apache-2.0); DINOv3 ViT-S+/16 backbone — check DINOv3 weight licence; predicates as runtime input `holding, in, on, handing to`; ~105 MB (+ YOLOE-11s ~24 MB); ONNX Runtime Android, keyframes only; ~17 ms on RTX 3080 laptop GPU, phone unknown. Fallback: hand overlap + side zones | "holding" flips partner → customer correctly on iQOO within keyframe budget |
| M6 | Sentence → schema-valid spec JSON, clarifying questions, example checklist. Qualcomm GenieX Android SDK (developer preview) or Genie bundle. Loaded in Forge only, never during verification. Fallback: template compiler (P0) | Valid schema on ≥ 8/10 test sentences offline on the iQOO |
| M7 | Offline language pack installed beforehand | Works in airplane mode |
| O1 Laya | Text-only, non-generative typed-decision model; base near chance zero-shot; fp32 ONNX ≈ 1.7 GB (use int8, verify agreement vs fp32). Roles: Forge step-typer or one bounded adjudication. Never decides a verdict; never in CrashGuard fast path | Beats rule baseline on held-out data and runs on iQOO with backend logged; else omit |
| O2 TAPNext++ | github.com/google-deepmind/tapnet (Apache-2.0); laptop only (Green Light) to auto-label example clips. No on-phone claim unless a phone spike passes | — |

### Fallback matrix
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

## Measurement log
Per model: version, runtime, execution provider, backend, input size, cold latency, warm p50/p95, memory, thermal after 5 runs. Per app: end-to-end decision time. Stored in `docs/measurements/`. Pitch uses only these numbers.

## Thermal
Sustained NPU profile; detector at low stride; clips < 60 s; LLM never loaded during verification; cool-down between rehearsals; power bank.
