# AGENTS.md

Instructions for any AI coding agent working on SensorForge.

## Read in this order
1. `docs/PROGRESS.md` — current phase and next task
2. `docs/agent-instructions.md` — rules, standards, claims policy, definition of done
3. `docs/build-architecture.md` — modules, stack, contracts, engines, perception
4. `docs/feature-spec.md` — apps, acceptance tests
5. `docs/design.md` — design system (colours, type, components); `docs/ui-design.md` — screen specs
6. `docs/data-requirements.md` — examples needed per use case

Workflow is **one-shot** (`docs/WORKFLOW.md`): build every use case to the spec + model stack, test with SIMULATION data, hand over, then the user tests with real good/bad examples.

Authoritative sources (on conflict: tech spec wins on implementation, problem-solution wins on scope and claims):
- `docs/sensorforge-problem-solution.md`
- `docs/sensorforge-technical-spec-v3.md`
- `docs/sensorforge-model-stack.md`

## Hard rules (summary — full list in `CLAUDE.md` / tech spec §1)
- No fake LIVE data (replays labelled `SIMULATION`), no fake training (weights hash must change), no unlogged backend claims.
- Unknown → `UNVERIFIED` / `NOT_OBSERVABLE`, never `PASS`.
- Verifier engine is pure Kotlin, no Android dependency, deterministic, golden-tested.
- End apps use only the public `sf-sdk` API.
- No background camera/mic, no real SOS/SMS/emergency calls, no network calls in core flows.
- Record id, source, licence, size, runtime, fallback before adding any model dependency.
- After every slice: compile, test, install on device, report changed files / commands / test output / blockers / next task.
