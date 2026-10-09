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
- `docs/WORKFLOW.md` — one-shot end-to-end build runbook (`build all`)
- `docs/data-requirements.md` — good/bad examples needed per use case (Phase 10)
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

## One-shot build: `build all` / `continue build`
Development is no longer phase-by-phase. When the user says **"build all"** (or "continue build"):
1. Read `docs/WORKFLOW.md` (the runbook) and `docs/PROGRESS.md` (start with "Session handoff").
2. Run Stage A milestones A1–A13 in order, then Stage B (simulation test), **without stopping for questions**: take the default, log it under Decisions, and add it to "Questions for user".
3. Every use case must satisfy the WORKFLOW.md compliance matrix (tech spec sections + model-stack slots). Use a model-stack §7 fallback only when necessary, and label the claim.
4. After each milestone: compile, `./gradlew :sf-sdk:test`, install on the iQOO, simulation run, commit + push, tick it in PROGRESS.md, and refresh "Session handoff".
5. Stop at Stage C and hand over: the per-use-case compliance report, Stage B results, questions, and the manual test runbook. Real good/bad examples come from the user afterwards (Stage D).

UI/function bar: screens follow `docs/design.md` + `docs/ui-design.md`, no dead UI, empty/loading/error/permission states, and a "not installed" state + fallback for every model. Approved model downloads are listed in model stack §10.

## Workflow
- Follow `docs/WORKFLOW.md` (one-shot: build → simulation test → handover → user's real-data tests).
- After every slice: compile, run tests, install on device, report changed files, commands, test output, blockers, next smallest task. Then update `docs/PROGRESS.md`.
- Cut order if behind: Laya → RelateAnything → local LLM → HandoverProof → RepRight.

## Commands
- JVM tests for contracts + engine: `./gradlew :sf-sdk:test`
- Device SoC check: `adb shell getprop ro.soc.model` (SM8850 ⇒ Snapdragon 8 Elite Gen 5)

## Stack
Kotlin, Jetpack Compose, ViewModel + StateFlow, manual DI, Room for sessions, app-private files, Android Keystore (AES-GCM) for incident logs, CameraX (`STRATEGY_KEEP_ONLY_LATEST`), kotlinx.serialization.

## Repo note
The spec's target layout is `sf-sdk/`, `studio-app/`, `hello-forge/`, `fixtures/` (see `docs/build-architecture.md`). Done in Phase 1 (package root `com.application.sensorforge`). Extra: `tools/` (laptop-side scripts, e.g. detector fine-tune).
