# One-Shot Build Workflow

One command builds everything that remains, end to end, in a single run. No phase-by-phase sessions.
**Trigger:** type `build all` (or `continue build` to resume after an interruption).

```text
Stage A  BUILD            every use case, every screen, every model slot (autonomous, no stops)
Stage B  SIMULATION TEST  every use case end to end on the iQOO with labelled SIMULATION data
Stage C  HANDOVER         agent stops and gives the user the manual test runbook
Stage D  REAL DATA        user captures good/bad examples per use case in the app, trains, tests manually
```

## Ground rules for the run
- **Spec compliance is the bar:** every use case implements everything in the compliance matrix below: tech spec sections and model-stack slots, nothing skipped silently. If something truly can't be done, use the model stack §7 fallback, label the claim accordingly, and log it.
- **Don't stop for questions.** When a decision is needed, take the default named here (or the most spec-faithful option), log it in `PROGRESS.md` → Decisions, and add it to `PROGRESS.md` → **Questions for user** so it can be reviewed at handover.
- **Defaults already decided:**
  - SealSafe uses `{"type":"AFTER_PARAM","step":"<id>","param":"start_time"}` (opened before start → `ORDER_VIOLATED`).
  - Runtime is LiteRT + QNN, then GPU, then CPU.
  - Approved downloads: model stack §10. Confirm the exact file + size in the log; don't ask again.
- **Per milestone:** compile → `./gradlew :sf-sdk:test` → install on the iQOO → simulation run of what was built → commit + push → tick it in `PROGRESS.md`. A failed step is fixed before moving on.
- **UI/function bar** (`design.md`, `ui-design.md`): no dead controls; empty, loading, error and permission states; every model has a "not installed" state and a fallback.
- **Engineering rules** (CLAUDE.md, tech spec §1) always apply. In particular: SIMULATION labels on all synthetic data, real weight changes for training, logged backends, unknown → UNVERIFIED.
- **Context:** the run is long. Keep `PROGRESS.md` → Session handoff current after every milestone so `continue build` can resume from it.

## Stage A: Build milestones (in order)
| # | Milestone | Covers |
|---|---|---|
| A1 | Schema: `AFTER_PARAM`; model registry (every model-stack slot, file, sha256, licence, runtime, expected backend, status) + `models.lock` generation; backend log plumbing | §5, §8, §26, model stack §8/§10 |
| A2 | `.sfpkg` export/import, `pack_sha256`, models.lock check (fail closed), receipts + verify-receipt | §5, §16.5, §24 |
| A3 | Guided setup flow shared by all use cases: Describe (template compiler) → **Plan** → Capture (+ import) → Train → Test (score, induction, calibration, regression) → Export → Import | §16, ui-design S02–S06 |
| A4 | Studio shell: S01 home, navigation, S16 receipt, S17 backend log, S18 privacy, session JSONL logs | §27–§29, ui-design |
| A5 | **PackCheck** complete: M1 detector (EfficientDet-Lite2 → Lite0 → tagged props), C1–C3, M2 embedder + H1, coaching/TTS, moved into the guided flow | Part F, model stack M1/M2/H1/C1–C3 |
| A6 | **CrashGuard** complete: moved into the guided flow; H3, lifecycle, mock transport, replays | Part D, H3 |
| A7 | **SealSafe** complete: spec, M2 + H2 intact/broken, `AFTER_PARAM`, S13 | Part F, H2 |
| A8 | **HelloForge**: two-line import of an exported pack, hash footer, typed errors (S19) | §4, Part F |
| A9 | **RepRight**: M3 pose heavy, angles, holds, rep state machine, therapist config, S14 | Part F, M3 |
| A10 | **HandoverProof**: M4 hands, side zones, `transferred`, step counter, location, slip code, `for_each` items, S15; M5 RelateAnything spike with side-zone fallback | Part F, M4/M5 |
| A11 | Authoring models: M6 SmolLM2 (llama.cpp, Forge only) with template fallback; M7 STT; O1 Laya marked omitted unless a file exists | §16.2, M6/M7/O1 |
| A12 | M1 primary: YOLOE-11S laptop export → registry; keep fallbacks | model stack M1 |
| A13 | UI polish + click-through QA of every screen against design.md §8; claims audit; airplane-mode run of the whole app | design.md, problem-solution §7 |

## Stage B: Simulation test (before any real data)
Every use case runs end to end on the iQOO with clearly labelled **SIMULATION** inputs, through the same SDK path the real data will use:

| Use case | Simulation inputs | Must show |
|---|---|---|
| PackCheck | golden timelines (14) replayed through `Verifier` events | PASS / FAIL / UNVERIFIED per scenario, reasons, receipt |
| CrashGuard | IMU replays (`fixtures/imu/`) + synthetic normal/shake/drop windows | countdown, SIMULATED SOS, cancel, train → hash change |
| SealSafe | synthetic timelines (valid, opened early, seal never shown, covered); synthetic seal crops for H2 train | FAIL `ORDER_VIOLATED`, UNVERIFIED, H2 hash change |
| HelloForge | the pack exported from the simulated SealSafe forge | matching hash, verdict shown |
| RepRight | synthetic landmark sequences (valid, too fast, partial, lost) | count / needs adjustment / UNVERIFIED |
| HandoverProof | synthetic timelines (all handed, not handed, taken back, left frame) | per-item status, verdict |
| Forge flow | a full Describe → Plan → Capture (simulated) → Train → Test → Export run | every step works, gating works |

Also run all JVM tests, all instrumented tests (note: they uninstall the app), an airplane-mode pass, and a receipt-tamper check. Results go in `PROGRESS.md` → Stage B results.

## Stage C: Handover (agent stops here)
The agent reports:
1. What was built, per use case, against the compliance matrix (✔ / fallback used / gap).
2. Stage B results.
3. **Questions for user** (decisions taken by default during the run).
4. The manual test runbook (below), with anything use-case-specific filled in.

## Stage D: Manual test runbook (user, per use case)
For each use case in the app:
1. Open the tile → **Describe**: check the spec cards make sense.
2. **Plan**: review the example checklist (from `data-requirements.md`), edit if needed, accept.
3. **Capture**: record each good / bad / unclear item to its count. Mark at least one session as held-out.
4. **Train** (heads) and **Test**: check the hash changes, look at the held-out results and false accepts.
5. Run it **live** a few times, including the hard negatives. Note anything wrong.
6. **Export** (and import into HelloForge for SealSafe).
7. Turn on airplane mode and repeat one run.

Then tell the agent "results:" plus what you saw. The agent fixes issues, fills `docs/measurements/`, and runs the acceptance table (T-B, T-C, T-S, T-P, HandoverProof) with you.

## Compliance matrix (what "follows the spec" means per use case)
| Use case | Tech spec | Models / libraries | Key acceptance |
|---|---|---|---|
| PackCheck | §6 spec, §9–§15 engine, §21–§24 perception/coaching/receipt, Part F | M1, M2, H1, C1 zones/quality, C2 codes, C3 tracker, TTS | T-B01–05 |
| CrashGuard | §7, §17–§20, §16.4 training | H3, IMU features, location | T-C01–06 |
| SealSafe | §6 + `AFTER_PARAM`, §16 forge, §16.5 export | M1, M2, H2, quality | T-S01–02 |
| HelloForge | §4 API, §5 pack, §16.5 | (loads pack) | T-S01 |
| RepRight | Part F, §10 pose primitives | M3, TTS | T-P01–03 |
| HandoverProof | Part F, §6 `for_each`, §10 hands/sensors primitives | M1, M4, M5 (spike), C2, step counter, location | Part F negatives |
| Forge (all) | §16.1–§16.5 | M6 (P1), M7 (P1), O1 (optional) | definition of done |
