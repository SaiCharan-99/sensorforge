# Agent Instructions

Sources: tech spec §1, §2, §25–§32; problem-solution §7, §10, §12; model stack selection rule.

## Role
Build SensorForge — an on-phone authoring + training + verification runtime with an Android SDK — plus end apps built only on that SDK, for the iQOO Hackathon 2026 Grand Finale (Developer Tools track).
Target device: iQOO loaner (expected iQOO 15, Snapdragon 8 Elite Gen 5, OriginOS 6 / Android 16 — **verify on the unit**).

## Source-of-truth rule
Product behaviour → `sensorforge-problem-solution.md`. Technical behaviour → `sensorforge-technical-spec-v3.md`. Model choices → `sensorforge-model-stack.md`. On disagreement: tech spec wins on implementation, problem-solution wins on scope and claims.

## Non-negotiable engineering rules
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

## Model selection rule
A model is in the build only if (1) it produces a primitive or head output the spec needs, (2) no simpler algorithm does the job, (3) it passes its gate on the actual iQOO with backend + latency logged. No model is mentioned in the pitch without a log line.

## AI roles — never conflate
1. Authoring (template compiler; local LLM P1)
2. Perception (frozen pretrained)
3. Learned heads (trained on phone)
4. Decision (deterministic code)

Laya, if used, adds one bounded observation; it never decides a verdict and is never in CrashGuard's fast path.

## Per-slice reporting
After every slice: compile, run tests, install on device, report **changed files, commands, test output, blockers, next smallest task**. Update `PROGRESS.md`.

## Errors & logging
- Typed errors surfaced in UI with a fix hint; never crash into PASS.
- SDK error types: `ContractError`, `PerceptionStackMismatch`, `PermissionMissing`, `CapabilityMissing`.
- Logging: per-session JSONL (observations summary, decisions, backends, timings).

## Testing requirements
- **JVM (`./gradlew :sf-sdk:test`):** contract validator; step evaluation; each reason; identity union-find; MAX_INSTANCES bounds; determinism; parameter expansion; IMU feature extraction; head training reduces loss and changes hash; receipt verification.
- **Golden verify scenarios (`fixtures/timelines`):** valid order A; valid order B; required absent; required not observable; camera_unstable during step; order violated; code mismatch; same-identity mismatch; identity unproven; max instances exceeded; max instances undetermined; unexplained change; perception stack mismatch.
- **Acceptance on the iQOO in airplane mode:** see `feature-spec.md` §Acceptance tests.

## Telemetry (25% of score, measured on device)
Earn it with real use: camera in every verify app; NPU for detector/encoder (logged); IMU + step counter + location; mic for voice authoring (P1) and speaker for coaching TTS; Office Kit used for all Red Light work. **No synthetic load.**

## Thermal
Sustained NPU profile; detector at low stride; clips < 60 s; LLM never loaded during verification; cool-down between rehearsals; power bank.

## Privacy & safety
On-device by default; separate consent per sensor; visible recording indicators; no background camera/mic; minimal retention; delete-all; face blur in stored evidence; CrashGuard uses motion only and a mock transport; no automatic emergency calls.

## Claims policy (UI text, logs, pitch)
| Say | Never say |
|---|---|
| "Possible impact detected" | "Crash detection", "saves lives", "split-second SOS" |
| "Trained a small model on this phone" | "Trained an AI model from scratch", "NPU training" |
| "Ran on the NPU" — only with a backend log | Any backend without a log |
| "Meets configured criteria" (RepRight) | "Clinically correct" |
| "Evidence receipt" | "Legally admissible proof" |
| "Offline" — only after airplane-mode run | "Works everywhere" |
| Real held-out numbers from our recordings | Invented benchmarks |

Never say "first" (prior art: Retrocausal, Roboflow, Drishti, viAct for factory SOP compliance; OEM crash detection on some phones). Differentiation: offline on any Android phone; authoring and training on the phone; three-state observability; regression-gated packs; two-line SDK import.

## Honest limits (do not paper over)
Visible surfaces only; look-alikes leaving view can't be proven identical (→ UNVERIFIED); few examples ≠ general reliability; markers needed for zones; CrashGuard is a prototype using replays for impacts; GPS indoors unreliable.

## Build-phase bar (build-first plan)
- Screens follow `ui-design.md` + `design.md`; run the `design.md` §8 checklist.
- No dead UI: each control works on the iQOO, or is disabled with a visible reason.
- Every screen has empty / loading / error (typed + fix hint) / permission-denied states.
- Capture → label → train/calibrate → test → export paths work with whatever data exists and refuse clearly below minimums.
- Every model: registry entry, logged backend/latency, "not installed" state, fallback per model stack §7.
- Ask the user before downloading any model file (name, source, size, licence).

## Definition of done (finale)
- [ ] T1 acceptance tests (T-B, T-C, T-S) pass on the iQOO in airplane mode.
- [ ] Training changes weights hash; held-out metrics shown.
- [ ] SealSafe authored → trained → exported in < 90 s with pre-captured examples.
- [ ] HelloForge loads the exported pack (matching hash) and verifies live.
- [ ] All end apps use only public SDK API.
- [ ] Measurement log complete for every model in use.
- [ ] No screen shows fake LIVE data or "SENT".
