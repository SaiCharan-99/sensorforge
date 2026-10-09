# Prompts

Reusable prompts for phase-wise development. The kickoff prompt is from the tech spec, §32.

## Kickoff (tech spec §32)
```
Read docs/sensorforge-technical-spec-v3.md, docs/sensorforge-problem-solution.md and docs/sensorforge-model-stack.md. Follow Part A rules. Create modules per §3. Implement in §30 order, starting with contracts + verify engine + golden JVM tests, then CrashGuard detect engine and real head training. Stop at the Saturday gate until §31 T1 items pass. After every slice: compile, run tests, install on device, report changed files, commands, test output, blockers, next smallest task.
```

## Continue
```
Read CLAUDE.md and docs/PROGRESS.md. Continue with the next unchecked task in the current phase. Follow tech spec Part A rules. After the slice: compile, run tests, install on device, report changed files, commands, test output, blockers, next smallest task, and update PROGRESS.md.
```

## Start a phase
```
Read CLAUDE.md and docs/PROGRESS.md. Start Phase <N>. Implement its tasks one slice at a time per docs/build-architecture.md and the referenced tech spec sections. Do not start T2 work until the Phase 5 gate passes.
```

## Build a screen
```
Implement screen <Sxx> as specified in docs/ui-design.md, using its shared components and the tokens in docs/design.md and only the public sf-sdk API (SensorForge.load/start/events/stop) and the app behaviour in docs/feature-spec.md. Follow the claims policy in docs/agent-instructions.md for all UI text.
```

## Add a model
```
Add model <ID> per docs/sensorforge-model-stack.md. First record its id, source, licence, size, runtime and fallback. Log runtime, execution provider, backend and latency. Check its gate on the iQOO and fill its row in docs/measurements/.
```

## Gate check (Phase 5)
```
Run every T1 acceptance test (T-B01–05, T-C01–06, T-S01–02) on the iQOO in airplane mode. Report pass/fail per ID with evidence. List the gaps. Do not start T2 work until all of them pass.
```

## Claims audit
```
Scan all UI strings, logs and slide text against the claims policy in docs/agent-instructions.md. Flag any "never say" phrase, any backend claim without a log line, any "SENT" text, and any LIVE label on replay data.
```
