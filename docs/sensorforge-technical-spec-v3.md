# SensorForge — Technical Specification v3

**Version:** 3.0 FINAL · **Date:** 2026-10-09 · **Target device:** iQOO loaner (expected iQOO 15, Snapdragon 8 Elite Gen 5, OriginOS 6 / Android 16 — verify on the unit)
**Doc set:** `sensorforge-problem-solution.md` (scope, apps, claims) · **this file** (implementation) · `sensorforge-model-stack.md` (models, runtimes, gates)
**Supersedes:** tech-spec v2 (v0.5), `02_SENSORFORGE_TECHNICAL_ARCHITECTURE.md`, `SENSORFORGE_FINAL_SPEC.md`.

---

# Part A — Foundations

## 1. Non-negotiable engineering rules (for Claude Code)

1. Build the smallest runnable thing first; compile and run on device after every slice.
2. **No fake LIVE data.** Replays/fixtures are visibly labelled `SIMULATION`.
3. **No fake training.** "Training" must change real weights (hash before/after). Threshold tuning is called "calibration".
4. **No unlogged backend claims.** Every model logs runtime, execution provider, backend, latency.
5. **Unknown by default:** errors, missing permission, occlusion, dropped frames → `UNVERIFIED`/`NOT_OBSERVABLE`, never `PASS`.
6. **Verifier is pure Kotlin**, no Android dependency, deterministic, unit-tested with golden fixtures.
7. **End apps use only the public `sf-sdk` API.** If something is missing, add it to the API.
8. **No background camera/mic, no real SOS, no SMS, no emergency calls.**
9. No network calls in any core flow. Airplane-mode run is part of done.
10. Before adding a model dependency: record id, source, licence, size, runtime, fallback in the model stack.

## 2. System overview

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

**AI roles never conflated:** (1) authoring (template compiler; local LLM P1), (2) perception (frozen pretrained), (3) learned heads (trained on phone), (4) decision (deterministic code). Laya, if used, adds one bounded observation; it never decides a verdict.

## 3. Repository and modules

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

`sf-sdk/contracts` and `sf-sdk/engine` must compile as plain JVM code (tests run with `./gradlew :sf-sdk:test`).

Stack: Kotlin, Jetpack Compose, ViewModel + StateFlow, manual DI, Room for sessions, app-private files, Android Keystore (AES-GCM) for incident logs, CameraX (`STRATEGY_KEEP_ONLY_LATEST`), kotlinx.serialization.

## 4. Public SDK API

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

`previewView` is null for sensor-only packs (CrashGuard). Errors are typed: `ContractError`, `PerceptionStackMismatch`, `PermissionMissing`, `CapabilityMissing`.

---

# Part B — Contracts

## 5. Verifier Pack (`.sfpkg`, zip)

```text
manifest.json     schema_version, pack_id, pack_version, mode (verify|detect),
                  perception_profile[], sensors[], created_on_device, pack_sha256
spec.json         procedure (verify) or detect policy (detect)
heads/<id>.json   trained head: type, weights, bias, normalizer, labels, sha256
thresholds.json   calibrated per-step / per-class thresholds
regression.json   example summaries + expected verdicts
training.json     proof artifacts (§16.4)
models.lock       frozen base models: id, version, sha256, runtime, expected backend
assets/           zones.json, code lists (optional)
```

Frozen base models ship inside `sf-sdk` and are referenced by hash. Hash mismatch at load → `PerceptionStackMismatch`, fail closed.

## 6. Procedure spec (verify mode)

```json
{
  "schema_version": "3.0",
  "procedure_id": "packcheck",
  "version": 3,
  "parameters": { "items": { "type": "list<code>", "source": "host_app", "optional": true } },
  "entities": [
    { "id": "box", "cls": "box", "identity_policy": "track_continuity" },
    { "id": "charger", "cls": "charger", "identity_policy": "track_continuity", "head": "identity" },
    { "id": "adapter", "cls": "adapter", "identity_policy": "code_required", "expected_code": "AD-42" }
  ],
  "steps": [
    { "id": "box_opened", "primitive": "state_change", "entity": "box", "state": "open", "observe_target": "box" },
    { "id": "charger_out", "primitive": "exits_container", "entity": "charger", "container": "box", "observe_target": "box" },
    { "id": "adapter_out", "primitive": "exits_container", "entity": "adapter", "container": "box", "observe_target": "box" },
    { "id": "adapter_label", "primitive": "code_read", "entity": "adapter", "observe_target": "adapter" },
    { "id": "box_empty", "primitive": "region_empty", "zone": "box_interior", "observe_target": "zone:box_interior" }
  ],
  "constraints": [
    { "type": "BEFORE", "a": "box_opened", "b": "charger_out", "source": "induced" },
    { "type": "ANY_ORDER", "steps": ["charger_out", "adapter_out"], "source": "induced" },
    { "type": "SAME_IDENTITY", "a": "adapter_out", "b": "adapter_label", "source": "developer" },
    { "type": "BEFORE", "a": "adapter_out", "b": "box_empty" }
  ],
  "prohibited": [
    { "type": "MAX_INSTANCES", "entity": "adapter", "max": 1 },
    { "type": "FORBIDDEN_CLASS", "cls": "impostor_cable", "min_confidence": 0.8 }
  ],
  "policy": { "min_observable_coverage": 0.9, "unexplained_change_blocks_pass": true, "coaching_timeout_s": 3 }
}
```

Step defaults: `required = true`, `min_confidence = 0.75`. Steps may use `"for_each": "items"` with `{item}` placeholders; expansion happens before evaluation and parameter values are written to the receipt.

## 7. Detect spec (detect mode — CrashGuard)

```json
{
  "schema_version": "3.0",
  "procedure_id": "crashguard",
  "version": 2,
  "mode": "detect",
  "window": { "length_s": 3.0, "hop_s": 0.5, "imu_hz": 100 },
  "gate": { "accel_peak_g": 3.0, "gyro_peak_dps": 300 },
  "head": { "id": "imu_risk", "positive_class": "impact_like", "min_prob": 0.8 },
  "corroboration": { "post_event_stillness_s": 2.0, "stillness_max_g_std": 0.08 },
  "incident": { "countdown_s": 15, "auto_escalate": false, "transport": "mock" },
  "evidence": { "imu_ring_buffer_s": 20, "location": "last_fix_with_accuracy" }
}
```

## 8. Contract validation

Reject before running: unknown keys/primitives, dangling references, duplicate IDs, confidence outside [0,1], unsupported schema version, cyclic `BEFORE`, missing heads referenced by entities, sensors not permitted, `auto_escalate=true`, `transport != "mock"` (finale build). Error type: `ContractError(path, message)`.

## 9. Observation timeline (verifier's only input)

```text
clip_id, perception_stack, duration_s
tracks[]:        track_id, cls, identity_id, t_start, t_end, code?
primitives[]:    id, type, t, entity{track_id, cls}, container?, zone?, state?, confidence, evidence_frames[]
observability[]: target, intervals[[t0,t1]]
```

Identity is never restored from appearance alone; only from track continuity, a matching code, or a trained identity head above its calibrated threshold (recorded as evidence).

## 10. Primitive vocabulary (closed; new primitives need a schema version bump)

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

Semantics:
- `camera_unstable [t, t_end]` removes evidence and coverage in the interval.
- `identity_unresolved`: new track *may* equal an old track but it's unproven.
- `unexplained_change`: significant scene change not tied to an expected step; blocks PASS if policy says so.
- `at_location` is never sufficient evidence alone.

---

# Part C — Verify engine (pure Kotlin)

## 11. Observability
Perception owns observability. A class window is open while the class is in view, image quality usable, blur below threshold. A zone window is open while the zone is visible, geometry valid, sufficiently unoccluded. The engine never infers visibility from missing detections.

## 12. Candidate matching
A primitive matches a step if `type == step.primitive` and `entity.cls == step.entity.cls` (except `region_empty`), and container / zone / state match when specified.

## 13. Trustworthy evidence
`observable && confidence >= step.min_confidence && t not in camera_unstable`.

## 14. Step evaluation
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

## 15. Constraints, identity, verdict

**Identity:** union-find over `identity_id` linked by `identity_unresolved`. For a class: min distinct = components; max distinct = identity IDs.

- `SAME_IDENTITY(a,b)`: different components → `IDENTITY_MISMATCH` (FAIL); same component via unresolved link → `IDENTITY_UNPROVEN` (UNVERIFIED).
- `MAX_INSTANCES(E,max)`: min_possible > max → `PROHIBITED_OBSERVED` (FAIL); min ≤ max < max_possible → `PROHIBITED_UNDETERMINED` (UNVERIFIED).
- `BEFORE(a,b)` both observed and t_a > t_b → `ORDER_VIOLATED` (FAIL).
- Code mismatch → `CODE_MISMATCH` (FAIL). Required ABSENT → `REQUIRED_ABSENT` (FAIL). Required NOT_OBSERVABLE → `REQUIRED_NOT_OBSERVABLE` (UNVERIFIED). `FORBIDDEN_CLASS` observed → `PROHIBITED_OBSERVED`.

**Reasons** (sorted by `(code, ref)`): `REQUIRED_ABSENT, REQUIRED_NOT_OBSERVABLE, CODE_MISMATCH, ORDER_VIOLATED, IDENTITY_MISMATCH, IDENTITY_UNPROVEN, PROHIBITED_OBSERVED, PROHIBITED_UNDETERMINED, UNEXPLAINED_CHANGE, PERCEPTION_STACK_MISMATCH`.

**Verdict:** any FAIL reason → FAIL; else any UNVERIFIED reason → UNVERIFIED; else PASS. All reasons kept.

**Determinism:** same timeline + same spec → identical result (property-tested).

## 16. Forge

### 16.1 Pipeline
`description → draft spec (template/LLM) → developer review → capture examples → perception → timelines → engine → score → candidate deltas → approval → new version → calibration → head training → regression → export .sfpkg`

### 16.2 Authoring
P0: template compiler — a small set of sentence patterns per primitive ("show X", "open X", "remove X from Y", "only after T", "hold for N seconds") mapped to steps/constraints; every draft is editable JSON + cards. P1: local LLM producing schema-valid JSON; invalid output → template fallback. Assistant also emits an **example checklist** (valid variants + hard negatives).

### 16.3 Examples, scoring, induction, calibration
- Labels: `valid | invalid | insufficient` (+ optional `missing_steps`); split `dev | held_out` by session. Held-out never used for calibration.
- Score: `false_accepts`, `false_rejects`, `wrong_kind` (UNVERIFIED vs FAIL confusion). False accepts dominate.
- Order induction: propose `BEFORE` only if consistently observed in ≥ 5 dev examples; otherwise ask the developer and mark `source: developer`. Different orders across valid examples → `ANY_ORDER`.
- Requirement induction: step observed in all valid examples → required; else optional (developer confirms).
- Threshold calibration: per step, pick threshold maximising separation on dev examples with zero false accepts; label `ok | fragile | infeasible`. Infeasible → tell the developer perception can't support the step.
- Every approved delta re-runs regression; if any previously correct example breaks, the delta is shown with its consequence before approval.

### 16.4 Head training (real weights)
Heads: logistic regression, 2-layer MLP, or prototype (nearest-centroid on embeddings). Algorithm:
```text
assert per-class count >= demo_min (5)
split by session → train/val (held_out untouched)
fit standardiser on train only
init W,b with seeded RNG (or warm start)
mini-batch softmax cross-entropy + L2, Adam, grad clip, early stop on val loss
restore best; compute per-class metrics on val and held_out
save NEW immutable head version; promotion needs user approval; rollback allowed
```
Proof artifacts in `training.json`: `trainedAt, datasetVersion, trainSamples, valSamples, lossCurve, perClassMetrics, parentSha256, trainedWeightsSha256, changedParameterCount, trainingBackend (CPU), inferenceBackend, deviceFingerprint`. UI shows before/after hash.

### 16.5 Export
Build `.sfpkg` (§5), compute `pack_sha256`, share via Android Sharesheet/Nearby or app storage. HelloForge loads from shared storage.

---

# Part D — Detect engine (CrashGuard)

## 17. Pipeline
```text
User taps Arm (visible foreground session, motion sensors only; camera/mic OFF)
→ SensorManager accel + gyro + rotation vector @ ~100 Hz → IMU ring buffer (20 s)
→ sliding windows (3 s, hop 0.5 s) → features
→ cheap gate (peak |a|, |ω|) → trained IMU head → stillness corroboration
→ policy → incident lifecycle
```

## 18. Features (per window, ~32–64 dims)
|a| and |ω| max/mean/std, jerk stats, time above threshold, orientation delta (rotation vector), post-peak stillness (std of |a| over 2 s), zero-crossing rate. Standardised with training stats.

## 19. Incident lifecycle
`DISARMED → ARMED → CANDIDATE → SUSPECTED → COUNTDOWN → (CANCELLED | CONFIRMED | TIMEOUT) → EVIDENCE_SAVED → SOS_SIMULATED → RESOLVED`
- Countdown screen: big Cancel, "I need help", vibration.
- TIMEOUT with `auto_escalate=false` → stays at EVIDENCE_SAVED, offers manual action.
- `MockIncidentTransport` returns `SIMULATED`; banner "SIMULATED SOS"; never displays `SENT`.
- Evidence: frozen IMU buffer, location + accuracy + age, head version, decision trace, receipt.

## 20. Safety & Android rules
Motion Watch only. If app not visible, never attempt to start camera/mic; show `CAPTURE_NOT_AVAILABLE`. Impact samples are **replay fixtures** injected through the same `SensorObservation` interface, labelled SIMULATION. Live negatives: hand shake, table knock, pocket motion.

---

# Part E — Perception (Android)

## 21. Camera pipeline
CameraX Preview + ImageAnalysis, keep-latest, close every `ImageProxy`; analysis 5–15 fps; detector at fixed stride, Lucas–Kanade flow between detector frames; ROI crops for heads; monotonic timestamps (`elapsedRealtimeNanos`) + UTC for display.

## 22. Modules (loaded per `perception_profile`)
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

Only listed modules run. Primitive extractor emits the timeline incrementally; engine re-evaluates ~1 Hz for coaching and finally at stop.

## 23. Live coaching
If the active required step's target is NOT_OBSERVABLE for > `coaching_timeout_s`, emit `coaching_prompt {step_id, target, message}` → overlay + Android TTS. Never changes verdict logic.

## 24. Evidence receipt
```text
receipt.json: pack_id, pack_version, pack_sha256, perception_stack, params,
              verdict | incident_state, reasons[], step statuses,
              clip_sha256, timeline_sha256, created_at_utc, elapsed_ns, device_model
receipt_sha256 = SHA-256(canonical JSON)
```
Verify-receipt function recomputes hashes; any change → "receipt invalid".

---

# Part F — App technical designs

| App | Profile | Heads | Spec highlights | Key negatives |
|---|---|---|---|---|
| **PackCheck** | objects, zones, codes, identity, quality | identity (prototype on crop embeddings: genuine vs impostor) | §6 example | missing adapter, duplicate, impostor, covered lens, item leaves & returns |
| **CrashGuard** | sensors | IMU risk (MLP: normal, shake, drop, impact_like) | §7 | shake, knock, pocket, drop |
| **SealSafe** | objects, state, quality | state (seal intact/broken) | `state_is(seal,intact) BEFORE state_change(seal,broken)`; `state_change AFTER param start_time`; `papers exits_container envelope` | opened early, seal never shown, covered |
| **HelloForge** | — (loads pack) | — | two-line import; shows pack hash | wrong/absent pack → typed error |
| **RepRight** | body | none (P0) | `rep_completed(knee_ext, angle [min,max], hold ≥ t, tempo window)`; tree pose `joint_angle_in_range` + `pose_held` | too fast, partial, keypoints lost |
| **HandoverProof** | objects, hands, zones, codes, sensors | — | `for_each items: transferred(partner_side→customer_side)`; slip `code_read`; `steps_walked ≥ n`; `at_location` recorded | not handed, taken back, left frame mid-transfer |

Each end app is a Compose screen in `studio-app` that only calls `SensorForge.load/start/events/stop`.

---

# Part G — Quality, measurement, operations

## 25. Tests

**JVM (sf-sdk):** contract validator; step evaluation; each reason; identity union-find; MAX_INSTANCES bounds; determinism; parameter expansion; IMU feature extraction; head training reduces loss and changes hash; receipt verification.

**Golden verify scenarios (fixtures/timelines):** valid order A; valid order B; required absent; required not observable; camera_unstable during step; order violated; code mismatch; same-identity mismatch; identity unproven; max instances exceeded; max instances undetermined; unexplained change; perception stack mismatch.

**Acceptance (on iQOO, airplane mode):**

| ID | Given | Then |
|---|---|---|
| T-B01 | adapter missing | FAIL `REQUIRED_ABSENT` adapter_out |
| T-B02 | correct kit, valid order | PASS with evidence |
| T-B03 | impostor or duplicate | FAIL `PROHIBITED_OBSERVED` / identity |
| T-B04 | hand covers contents | UNVERIFIED + coaching prompt |
| T-B05 | item leaves and similar returns | UNVERIFIED `IDENTITY_UNPROVEN` |
| T-C01 | live shake | no incident |
| T-C02 | impact replay + stillness | SUSPECTED → countdown |
| T-C03 | countdown timeout | SOS_SIMULATED, never "SENT" |
| T-C04 | Cancel pressed | CANCELLED, audited, no alert |
| T-C05 | press Train | new head version, different sha256, held-out metrics |
| T-C06 | offline | all of the above work |
| T-S01 | SealSafe forged + exported | pack sha matches in HelloForge |
| T-S02 | envelope opened before start | FAIL `ORDER_VIOLATED` |
| T-P01–03 | RepRight valid / too fast / keypoints lost | count / needs adjustment / UNVERIFIED |

## 26. Measurement log
Per model: version, runtime, execution provider, backend, input size, cold latency, warm p50/p95, memory, thermal after 5 runs. Per app: end-to-end decision time. Stored in `docs/measurements/`. Pitch uses only these numbers.

## 27. Telemetry (25% of score, measured on device)
Earn it with real use: camera in every verify app; NPU for detector/encoder (logged); IMU + step counter + location; mic for voice authoring (P1) and speaker for coaching TTS; Office Kit used for all Red Light work. **No synthetic load.**

## 28. Thermal
Sustained NPU profile; detector at low stride; clips < 60 s; LLM never loaded during verification; cool-down between rehearsals; power bank.

## 29. Errors
Typed errors surfaced in UI with a fix hint; never crash into PASS. Logging: per session JSONL (observations summary, decisions, backends, timings).

---

# Part H — Build plan & done

## 30. 48-hour plan

| Window | Deliverable |
|---|---|
| Fri (Green Light) | Repo + 3 modules; device diagnostics (`ro.soc.model`, sensors); contracts + validator; verify engine + golden tests |
| Fri night | IMU capture, features, Kotlin head training + proof artifacts; CrashGuard lifecycle + mock transport + replays |
| Sat morning | CameraX → detector → tracker → zones → codes → timeline; PackCheck + identity head + coaching |
| Sat noon | Pack export/import; HelloForge; SealSafe template authoring + state head; receipts |
| **Sat afternoon GATE** | All T1 acceptance tests pass offline; one full rehearsal |
| Sat evening | RepRight; held-out recordings for PackCheck/CrashGuard/SealSafe |
| Sat night | HandoverProof (side zones); spikes: RelateAnything, local LLM authoring, Laya |
| Sun morning | Measurement log, evidence slide, labelled backup video, ≥5 rehearsals |

Cut order if behind: Laya → RelateAnything → local LLM → HandoverProof → RepRight.

## 31. Definition of done (finale)
- [ ] T1 acceptance tests (T-B, T-C, T-S) pass on the iQOO in airplane mode.
- [ ] Training changes weights hash; held-out metrics shown.
- [ ] SealSafe authored → trained → exported in < 90 s with pre-captured examples.
- [ ] HelloForge loads the exported pack (matching hash) and verifies live.
- [ ] All end apps use only public SDK API.
- [ ] Measurement log complete for every model in use.
- [ ] No screen shows fake LIVE data or "SENT".

## 32. Claude Code kickoff prompt
> Read `docs/sensorforge-technical-spec-v3.md`, `docs/sensorforge-problem-solution.md` and `docs/sensorforge-model-stack.md`. Follow Part A rules. Create modules per §3. Implement in §30 order, starting with contracts + verify engine + golden JVM tests, then CrashGuard detect engine and real head training. Stop at the Saturday gate until §31 T1 items pass. After every slice: compile, run tests, install on device, report changed files, commands, test output, blockers, next smallest task.
