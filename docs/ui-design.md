# UI Design

There are no external wireframes. The UI is designed in this doc and built directly in Jetpack Compose.

- **Spec-derived:** every label, state, flow and piece of content below comes from the three spec docs.
- **Our decision:** the visual system (theme, colours, type, layout). This doc can change it. The spec docs can't override it, but they **do** constrain the UI text (claims policy) and the safety behaviour.

## UI rules from the spec (non-negotiable)
- Three verdict states, always visually distinct: `PASS`, `FAIL`, `UNVERIFIED` (UNVERIFIED = "we couldn't see it", never shown as success).
- Replay/fixture data carries a visible `SIMULATION` label. No fake LIVE data.
- Never display "SENT". CrashGuard shows "SIMULATED SOS".
- Show "Offline" only after an airplane-mode run.
- Backend (NPU/GPU/CPU) is shown only next to a logged measurement.
- Visible recording indicator whenever the camera is in use. No background camera/mic. If the app is not visible, show `CAPTURE_NOT_AVAILABLE`.
- Show typed errors (`ContractError`, `PerceptionStackMismatch`, `PermissionMissing`, `CapabilityMissing`) with a fix hint. Never fall through to PASS.
- Ask for consent separately for each sensor. Offer delete-all.
- Show training proof: loss curve plus the weights hash before and after.
- Wording follows the claims policy in `agent-instructions.md` (e.g. "Possible impact detected", "Meets configured criteria", "Evidence receipt").

## Design system
All colours, typography, spacing, component styling, motion and accessibility rules live in **`design.md`**. It uses a soft, light theme only, for every app including HelloForge. Use its tokens; never hard-code colours.

### Shared components
- `VerdictBanner(verdict, reasons)`
- `StepChecklist(steps)`: each row shows step id + status icon (`OBSERVED` / pending / `ABSENT` / `NOT_OBSERVABLE`)
- `ReasonList`: reason code (mono) + plain-language line
- `CoachingOverlay(message)`: banner over the preview + speaker icon (TTS)
- `HashCard(label, before?, after)`: highlights a change
- `LossCurveChart`
- `SimulationChip`, `OfflineBadge`, `RecordingDot`
- `ErrorCard(type, hint)`
- `ReceiptView`

## Screens

### S01: Studio home (`studio-app`)
App tiles: PackCheck, CrashGuard, SealSafe, RepRight, HandoverProof. Each tile shows its sensors and its mode (Verify/Detect). Primary button: "Forge new verifier". Links: Backend log, Settings/Privacy.

### S02–S07: Forge (`SensorForge.forge()`)
Stepper: **Define → Capture → Train → Test → Export → Import** (slide 3 flow).
- **S02 Describe:** sentence input (+ mic, P1 voice authoring) or template. The draft spec appears as editable step cards (id, primitive, entity) with constraint chips (`BEFORE`, `ANY_ORDER`, `SAME_IDENTITY`, `source: induced|developer`) and a JSON toggle. The example checklist shows valid variants and hard negatives. Example sentence: "Show the sealed envelope, open only after start, remove the papers."
- **S03 Capture:** camera preview + recording dot; **Import** button for clips/recordings already on the phone (labelled on import). Example list labelled `valid | invalid | insufficient` and tagged `dev | held_out`. Per-class count against the minimum of 5.
- **S04 Train:** Train button, live loss curve, HashCard (parent → trained sha256), changed parameter count, training backend (CPU), train time. "Approve new version" / "Rollback".
- **S05 Test:** held-out per-class metrics; false accepts (emphasised as the worst error), false rejects, wrong_kind; calibration per step `ok | fragile | infeasible` (infeasible shows "perception can't support this step"); regression deltas with their consequence before approval.
- **S06 Export:** pack summary (pack_id, version, mode, perception_profile chips, pack_sha256). Share via Sharesheet/Nearby or save to storage. Code card with the two-line import.
- **Target:** SealSafe from describe to export in < 90 s with pre-captured examples, so the flow must not need extra taps between steps.

### S08: PackCheck live
Full camera preview with detector boxes and the outlined box-interior zone. StepChecklist: `box_opened`, `charger_out`, `adapter_out`, `adapter_label`, `box_empty`. CoachingOverlay e.g. "Show the adapter label to the camera." Stop button.

### S09: Verify result (PackCheck / SealSafe / HandoverProof / RepRight)
VerdictBanner + ReasonList + StepChecklist + evidence frame thumbnail + "View receipt".
Examples: FAIL `REQUIRED_ABSENT adapter_out`; FAIL `PROHIBITED_OBSERVED` (impostor); UNVERIFIED `IDENTITY_UNPROVEN` ("identity trail broken"); PASS with evidence.

### S10: CrashGuard armed
Arm toggle; status "ARMED: motion sensors only, camera and mic off". Live |a| / |ω| trace. IMU risk model card: classes normal / shake / drop / impact_like, Train button, loss curve, HashCard, held-out metrics. "Play impact replay" button (sets SIMULATION).

### S11: CrashGuard countdown
"Possible impact detected", 15 s countdown, oversized **Cancel**, "I need help", vibration. SIMULATION chip when the trigger came from a replay.

### S12: CrashGuard evidence / simulated SOS
Lifecycle timeline (`DISARMED → ARMED → CANDIDATE → SUSPECTED → COUNTDOWN → CANCELLED|CONFIRMED|TIMEOUT → EVIDENCE_SAVED → SOS_SIMULATED → RESOLVED`). Banner "SIMULATED SOS". Evidence: frozen motion trace, location + accuracy + age, head version, decision trace, receipt. On TIMEOUT with `auto_escalate=false`: stays at EVIDENCE_SAVED with a manual action button. A Cancel path shows CANCELLED (audited, no alert).

### S13: SealSafe live
Preview with the seal region and its state (`intact`/`broken` + confidence). Start time and clock in the top bar. Steps: seal shown intact → opened after start → papers removed. Example result: FAIL `ORDER_VIOLATED` (opened before start).

### S14: RepRight live (T2)
Pose skeleton, live joint angle against the target range bar, hold timer, tempo indicator, rep count. Feedback "Needs adjustment: <exact criterion>". Success text "Meets configured criteria". Keypoints lost → UNVERIFIED.

### S15: HandoverProof live (T2)
Preview split into Partner side / Customer side zones, with hand landmarks. Items A–E checklist (`transferred`). Slip `code_read` status, steps walked, location + accuracy marked "recorded, not sole evidence".

### S16: Receipt
Monospace fields: pack_id, pack_version, pack_sha256, perception_stack, params, verdict | incident_state, reasons, step statuses, clip_sha256, timeline_sha256, created_at_utc, elapsed_ns, device_model, receipt_sha256. "Verify receipt" button → "Receipt valid" / "Receipt invalid".

### S17: Backend log
Per-model rows from the measurement log: runtime, execution provider, backend, input, cold ms, warm p50/p95. Airplane-mode-verified badge.

### S18: Settings / Privacy
Per-sensor consent toggles, retention info, delete clips after receipt, delete-all, face-blur-in-evidence status.

### S19: HelloForge (`hello-forge`)
Deliberately bare, light theme: preview area + one large status text (`result.verdict.name`) + footer with pack id and pack hash. Wrong or absent pack → ErrorCard (e.g. `PerceptionStackMismatch`). This app uses only the two-line import plus minimal UI.

## Build order for UI
Built alongside each phase in `PROGRESS.md` (build-first plan):
- Done in Phases 2–3: S10–S12 (CrashGuard), S08–S09 (PackCheck)
- Phase 4: S13, S16, S19
- Phase 5: S01, S02–S06, S17, S18
- Phase 6: S14
- Phase 7: S15
- Phase 9: full polish + click-through QA of every screen against `design.md` §8

Every screen has empty, loading, error and permission-denied states. No control may be dead: it works, or it is disabled with a visible reason.
