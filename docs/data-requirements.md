# Data Requirements (good / bad examples per use case)

The app is built first; real data comes afterwards (Phase 10). **In the app, this content is the Plan step** (S02b) of each use case's guided setup: Describe → Plan → Capture → Train → Test → Export. The Plan step shows these items as a checklist, and Capture records against them. This file says exactly what to capture so training, calibration and acceptance run without surprises.
Sources: tech spec §16.3–§16.4, §18, §20, Part F, §25; model stack §3–§4. The Unboxing section comes from `tools/detector/README.md` (added in a later session; not in the three spec docs).

## Rules that apply to every use case
- **Labels:** each example is `valid | invalid | insufficient` (+ optional `missing_steps`). Head-training samples are labelled by class.
- **Split by session:** every recording session is `dev` or `held_out`. Held-out is never used for calibration or training. Plan **at least one held-out session per class / per scenario**.
- **Minimum counts:**
  - Each head class needs **≥ 5 dev samples** (`demo_min`). Training refuses below this.
  - Order induction proposes `BEFORE` only if the order is seen consistently in **≥ 5 dev examples**.
  - More data gives better held-out numbers.
- **Capture on the iQOO, inside the app.** Perception runs on the phone, so examples must go through the same camera and sensor pipeline. Forge → Capture records and labels them; the in-app import path (Phase 5) also accepts clips recorded elsewhere on the phone.
- **Conditions:** demo lighting, including poor lighting (rehearsal rule), with the props you will use on stage.
- **Clip length:** < 60 s (thermal rule).
- **Hard negatives matter most:** false accepts are the worst error.

## A2 CrashGuard (H3 IMU risk head)
| Class | Source | Count | Notes |
|---|---|---|---|
| normal | live recording | ≥ 5 dev windows + 1 held-out session | walking, holding, pocket motion |
| shake | live | ≥ 5 dev + 1 held-out | hand shake, table knock |
| drop | live | ≥ 5 dev + 1 held-out | drop onto a soft surface |
| impact_like | **replay fixtures only** (SIMULATION) | 12 exist (`fixtures/imu/`) | never fake as live |

Acceptance: T-C01–C06. Live negatives: hand shake, table knock, pocket motion.

## A1 PackCheck
**Detector (M1) classes:** box, charger, cable, adapter, impostor. Today the app uses tagged props (QR). For a learned detector, capture frames of each prop: varied angle, distance, lighting and hands.

**H1 identity head:** crops of the **genuine** cable vs the **impostor** cable, ≥ 5 each (dev) plus a held-out session.

**Example clips:**
| Clip | Label | Expected |
|---|---|---|
| Correct kit, order A (charger then adapter) | valid | PASS |
| Correct kit, order B | valid | PASS |
| Adapter missing | invalid | FAIL `REQUIRED_ABSENT` (T-B01) |
| Duplicate adapter / impostor cable | invalid | FAIL `PROHIBITED_OBSERVED` (T-B03) |
| Hand covers contents | insufficient | UNVERIFIED + coaching (T-B04) |
| Item leaves view, similar one returns | insufficient | UNVERIFIED `IDENTITY_UNPROVEN` (T-B05) |
| Wrong label code | invalid | FAIL `CODE_MISMATCH` |

## A5 SealSafe
**Detector classes:** envelope, seal, papers.
**H2 state head:** seal crops, **intact** vs **broken**, ≥ 5 each plus held-out.
**Parameter:** exam `start_time`.

| Clip | Label | Expected |
|---|---|---|
| Seal shown intact, opened after start, papers removed | valid | PASS |
| Opened before start | invalid | FAIL `ORDER_VIOLATED` (T-S02) |
| Seal never shown | insufficient | UNVERIFIED |
| Covered lens | insufficient | UNVERIFIED |

To fit the < 90 s on-stage forge, SealSafe examples must be **pre-captured** before the demo.

## A3 RepRight (no trained head, P0)
**Config you must supply:**
- knee extension: angle `[min, max]`, hold ≥ t, tempo window
- tree pose: joint angle ranges, hold time

| Clip | Expected |
|---|---|
| Valid reps | correct count (T-P01) |
| Too fast | "needs adjustment" with the criterion (T-P02) |
| Partial range | "needs adjustment" |
| Keypoints lost / leaves frame | UNVERIFIED (T-P03) |

## A4 HandoverProof
**Config:** items A–E list (from the host app), signed-slip code, steps `n`.

| Clip | Expected |
|---|---|
| Every item handed partner side → customer side, slip read, steps walked | PASS |
| An item not handed | FAIL / UNVERIFIED |
| Item taken back | FAIL |
| Item leaves frame mid-transfer | UNVERIFIED |

Location is recorded with accuracy but is never sole evidence.

## Unboxing (added by a later session; not in the spec docs)
Classes: box, lid, seal, envelope, phone, cable, charger. Target ≥ 60 boxes per class across ≥ 5 sessions; keep one session held out. Pipeline: `tools/detector/README.md`.

## Hand-over checklist (fill in Phase 10)
| Use case | Head samples done | Clips done (valid / invalid / insufficient) | Held-out session | Config supplied |
|---|---|---|---|---|
| CrashGuard | | n/a | | n/a |
| PackCheck | | | | item list / codes |
| SealSafe | | | | start_time |
| RepRight | n/a | | | angles, hold, tempo |
| HandoverProof | n/a | | | items, slip code, n steps |
