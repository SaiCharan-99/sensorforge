# CLAUDE.md

SensorForge — iQOO Hackathon 2026 Grand Finale (Bengaluru, Oct 9–11), Developer Tools track.

> **SensorForge lets a developer teach a phone to verify or detect a real-world event — on the phone — and ship it to any app in two lines of code.**
> Tagline: *"The AI sees. The code decides. The receipt proves it."*

## Authoritative docs (read before any work)
1. `docs/sensorforge-problem-solution.md` — scope, apps, demo, claims.
2. `docs/sensorforge-technical-spec-v3.md` — contracts, engine, SDK, training, app designs, build plan, tests.
3. `docs/sensorforge-model-stack.md` — every model/library, runtime, gates, fallbacks.

**Conflict rule:** the tech spec wins on implementation; problem-solution wins on scope and claims. These three supersede all earlier SensorForge docs.

Working docs derived from them:
- `docs/PROGRESS.md` — current phase (read first)
- `docs/agent-instructions.md` — rules, testing, claims policy, definition of done
- `docs/build-architecture.md` — modules, contracts, engines, perception
- `docs/feature-spec.md` — apps, demo, acceptance tests
- `docs/prompts.md` — reusable prompts per phase
- `docs/design.md` — design system: colours, type, spacing, components (soft light theme)
- `docs/ui-design.md` — screen specs S01–S19 (no external wireframes; UI is designed in-repo)
- `docs/measurements/` — on-device measurement log (only source of pitch numbers)

## Non-negotiable engineering rules (tech spec §1)
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

## "Start phase N" shorthand
Each phase runs in a fresh session. When the user says **"start phase N"** (or "phase N", "continue phase N"):
1. Read `docs/PROGRESS.md`, starting with "Session handoff", then the Phase N tasks and Blockers.
2. If a blocker affects Phase N, ask the user about it before writing code.
3. Read the tech spec / model stack sections the Phase N tasks reference, plus `docs/build-architecture.md`. For UI work, also read `docs/ui-design.md` and `docs/design.md`.
4. Implement the Phase N tasks one slice at a time. After every slice: compile, run `./gradlew :sf-sdk:test`, install on the device, commit, and report changed files, commands, test output, blockers and the next smallest task.
5. At the end, tick the tasks in `PROGRESS.md`, log any decisions, update "Session handoff" for the next session, then commit and push.

Do not start a T2 phase (6+) until the Phase 5 gate has passed.

## Workflow
- Implement in build-plan order (tech spec §30, mirrored in `docs/PROGRESS.md`). Stop at the Saturday-afternoon gate until all T1 acceptance tests pass offline.
- After every slice: compile, run tests, install on device, report changed files, commands, test output, blockers, next smallest task. Then update `docs/PROGRESS.md`.
- Cut order if behind: Laya → RelateAnything → local LLM → HandoverProof → RepRight.

## Commands
- JVM tests for contracts + engine: `./gradlew :sf-sdk:test`
- Device SoC check: `adb shell getprop ro.soc.model` (SM8850 ⇒ Snapdragon 8 Elite Gen 5)

## Stack
Kotlin, Jetpack Compose, ViewModel + StateFlow, manual DI, Room for sessions, app-private files, Android Keystore (AES-GCM) for incident logs, CameraX (`STRATEGY_KEEP_ONLY_LATEST`), kotlinx.serialization.

## Repo note
The spec's target layout is `sf-sdk/`, `studio-app/`, `hello-forge/`, `fixtures/` (see `docs/build-architecture.md`). The current Android Studio scaffold only has `:app` (package `com.application.sensorforge`); aligning it with the spec layout is the first build task.
