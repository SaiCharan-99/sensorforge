# Feature Spec

Sources: problem-solution §1–§12; tech spec Part F, §25, §31.

## Overview
**One-liner:** SensorForge lets a developer teach a phone to verify or detect a real-world event — on the phone — and ship it to any app in two lines of code.
**Tagline:** *"The AI sees. The code decides. The receipt proves it."*
**Event:** iQOO Hackathon 2026 Grand Finale (Bengaluru, Oct 9–11) · Track: Developer Tools.

### Problem
Phones can see, hear and feel, yet turning raw sensor data into a trustworthy judgement about what physically happened still needs datasets, models, calibration, temporal logic and runtime engineering for every use case. Most projects stop at "a detector saw a charger" and never answer: *Did the right thing happen, in the right order, under the right conditions — and can you prove it?*
- A packer seals a box while an item is still on the table; the order system says "packed".
- India's open-box deliveries and missing-item claims are settled with photos/videos nobody checks step by step.
- A rider falls and can't reach their phone; a generic app has no idea anything happened.

### Solution flow
1. **Describe** the event (template or sentence).
1b. **Plan** the examples: the app lists the good, bad and unclear examples and head classes to capture, with counts, held-out sessions and how to perform each (user decision: a dedicated step for every use case).
2. **Capture** GOOD and BAD examples on the phone.
3. **Train & calibrate** on the phone — small heads genuinely trained (weights change, hash shown); order, thresholds and rules calibrated.
4. **Test** against held-out examples — false accepts are the worst error.
5. **Export** a Verifier Pack (`.sfpkg`) — trained model + rules.
6. **Import in two lines** into any Android app; callback with verdict, reasons, evidence and a tamper-evident receipt.

**Engine modes:** Verify → `PASS | FAIL | UNVERIFIED` (UNVERIFIED = "we couldn't see it", never a silent pass). Detect → incident lifecycle (possible impact → countdown → simulated SOS).
Everything runs **offline on the phone**: authoring, training, verification. All end apps use **only the public SDK**.

## Users
| Persona | Gets |
|---|---|
| Android developer | Verifier Packs + SDK; no ML pipeline to build |
| Ops lead (warehouse, exam centre, logistics) | Configured verifier with evidence receipts |
| Worker / patient / rider | Clear feedback: done, fix this, or "show me" |
| Trusted contact (CrashGuard, opt-in) | Simulated alert preview in demo |

## App line-up
**Tier rule (spec):** T1 must pass all acceptance tests offline before any T2 work.
**Build-first override (user decision 2026-10-10):** every app is built first; all acceptance tests (T1 and T2) run in Phase 10 once real examples are supplied (`data-requirements.md`). If time runs short, T1 acceptance still comes first.

| # | App | End-user value | Sensors | Mode | Tier |
|---|---|---|---|---|---|
| A1 | PackCheck | Can't seal a box with a missing/wrong item | Camera | Verify | T1 hero |
| A2 | CrashGuard | Possible-impact detection → incident screen → *simulated* SOS with evidence | Accelerometer, gyroscope, location | Detect | T1 climax |
| A5 | SealSafe | Exam envelope opened only after seal shown intact and start time | Camera, clock | Verify | T1 forged live |
| A6 | HelloForge | Fresh app imports SealSafe in two lines | — | Import | T1 platform proof |
| A3 | RepRight | Physio/yoga rep counted only if range, tempo and hold are met | Camera (pose) | Verify | T2 |
| A4 | HandoverProof | Delivery: each item handed over, steps walked, slip returned | Camera, step counter, location | Verify | T2 (booth) |

**Gallery only (one slide, labelled "not built"):** PPE gate, syringe packaging, distress detection, road anomaly, technician service log, seat belt.

### Forge (studio)
Authoring + capture + train + calibrate + export UI (`SensorForge.forge()`); details in `build-architecture.md` §Forge. Shows loss curve, before/after weights hash, held-out metrics, `ok | fragile | infeasible` calibration labels, regression consequences of deltas.

### A1 — PackCheck (T1 hero)
- Box on a printed marker mat; task card lists charger, cable, adapter.
- Checks each item comes out of the box, reads its label on the same tracked object, and the box ends empty.
- Look-alike impostor cable rejected by a head trained on the phone.
- Covering the lens or an item leaving and returning → UNVERIFIED.
- Voice coaching, e.g. "Show the adapter label to the camera."
- **Profile:** objects, zones, codes, identity, quality. **Head:** identity (prototype on crop embeddings: genuine vs impostor). **Spec:** tech spec §6.
- **Key negatives:** missing adapter, duplicate, impostor, covered lens, item leaves & returns.

### A2 — CrashGuard (T1 climax)
- User taps **Arm** (visible session, motion sensors only).
- Cheap motion gate + small model trained on the phone separates normal / shake / drop / impact-like.
- Possible impact → countdown with big Cancel ("I need help", vibration) → evidence saved (motion trace, location with accuracy, receipt) → **SIMULATED SOS** banner. Never sends anything real; never shows "SENT".
- **Profile:** sensors. **Head:** IMU risk (MLP: normal, shake, drop, impact_like). **Spec:** tech spec §7.
- **Key negatives:** shake, knock, pocket, drop.

### A5 — SealSafe (T1 forged live)
- Built live: "Show the sealed envelope, open only after start, remove the papers."
- Seal intact/broken head trained from a few crops.
- Opening early or seal never shown → FAIL / UNVERIFIED.
- **Profile:** objects, state, quality. **Head:** state (seal intact/broken).
- **Spec:** `state_is(seal,intact) BEFORE state_change(seal,broken)`; `state_change AFTER param start_time`; `papers exits_container envelope`.
- **Key negatives:** opened early, seal never shown, covered.

### A6 — HelloForge (T1 platform proof)
Empty app containing only:
```kotlin
val verifier = SensorForge.load(context, "sealsafe.sfpkg")
verifier.start(previewView) { result -> statusText.text = result.verdict.name }
```
Shows pack hash. Wrong/absent pack → typed error.

### A3 — RepRight (T2)
- Therapist-configured knee extension or yoga tree pose.
- Too fast / partial range → "needs adjustment" with the exact criterion.
- Says "Meets configured criteria", never "clinically correct".
- **Profile:** body. **Heads:** none (P0).
- **Spec:** `rep_completed(knee_ext, angle [min,max], hold ≥ t, tempo window)`; tree pose `joint_angle_in_range` + `pose_held`.
- **Key negatives:** too fast, partial, keypoints lost.

### A4 — HandoverProof (T2, booth)
- Items A–E (list supplied by host app) each handed from partner side to customer side; signed slip code read; steps walked recorded; location recorded with accuracy but never sole evidence.
- **Profile:** objects, hands, zones, codes, sensors. **Heads:** none.
- **Spec:** `for_each items: transferred(partner_side→customer_side)`; slip `code_read`; `steps_walked ≥ n`; `at_location` recorded.
- **Key negatives:** not handed, taken back, left frame mid-transfer.

Each end app is a Compose screen in `studio-app` that only calls `SensorForge.load/start/events/stop`.

## Screens
There are no external wireframes. Screens and the design system are defined in `ui-design.md`.
| Screen | ui-design.md |
|---|---|
| Studio home (app tiles) | S01 |
| Forge / per-use-case setup: describe → plan → capture → train → test → export | S02, S02b, S03–S06 |
| PackCheck live / verify result | S08–S09 |
| CrashGuard armed / countdown / evidence + SIMULATED SOS | S10–S12 |
| SealSafe live | S13 |
| RepRight | S14 |
| HandoverProof | S15 |
| Receipt / verify-receipt | S16 |
| Backend log | S17 |
| Settings / Privacy | S18 |
| HelloForge | S19 |

## Acceptance tests (on iQOO, airplane mode)
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

## Five-minute stage run
| Clock | What happens | Takeaway |
|---|---|---|
| 0:00–0:35 | PAST vignette (Place, Action, Speech, Thoughts): packer seals a box; charger still on the table; "Did everything go in?" "I think so." | Real pain |
| 0:35–1:30 | PackCheck live: judge removes adapter → FAIL with frame; swaps impostor → FAIL; covers lens → UNVERIFIED | It catches you |
| 1:30–2:40 | CrashGuard: press Train → loss curve, weights hash changes; live hand shake → nothing; labelled impact replay → countdown → SIMULATED SOS with location + trace | The phone learned, on the phone |
| 2:40–3:00 | Reveal: "Every app you just saw was built with SensorForge — on this phone." | Platform |
| 3:00–4:20 | SealSafe forged live (sentence → spec → pre-captured examples → train → export) → HelloForge shows two lines → judge opens envelope early → FAIL | Build once, use anywhere |
| 4:20–5:00 | Airplane mode badge, backend log, held-out results; close: *"Teach it on the phone. Ship it in two lines."* | Credible |

RepRight and HandoverProof are shown during table evaluations.

### Wow moments (rehearse each ≥ 5× on the real phone, poor lighting)
1. Judge tries to fool PackCheck — caught at the exact step.
2. The app admits what it couldn't see (UNVERIFIED, "identity trail broken").
3. Airplane mode switched on by a judge.
4. Weights hash changes after pressing Train.
5. A new verifier forged from a sentence, exported, imported in two lines.
6. Tamper-evident receipt: edit one byte of the saved clip → "receipt invalid".

## Slides (6)
1. PAST still — "Software records work; it doesn't verify it."
2. Three apps that caught a judge (screens).
3. Reveal: Define → Capture → Train → Test → Export → Import (two lines).
4. Architecture: sensors → perception → observations → trained heads → rules → verdict + receipt.
5. Evidence: held-out confusion matrix, false accepts, UNVERIFIED rate, latency + backend per model (real numbers only).
6. Roadmap/gallery + limits.

## Judge defence sheet
| They say | We say |
|---|---|
| "This is just object detection." | Detection says a charger exists. We say it came out of *this* box, after it was opened, and is the same object whose label was read. Detection is input; the verdict is the product. |
| "Why not send the video to Gemini/GPT?" | Offline, private, reproducible — and video models are measurably weak at event order and object consistency. AI sees; code decides. |
| "So you train a model?" | Yes, a small head on the phone (hash before/after shown) on top of frozen pretrained models; the examples also calibrate the procedure. |
| "Five examples can't be reliable." | They calibrate a demo; here are held-out results and the clips behind every error. |
| "Someone could swap it off-camera." | We return UNVERIFIED — "identity trail broken". A system that says PASS there is the dangerous one. |
| "Phones already detect crashes." | Some do. This one was built by a developer in minutes with SensorForge, runs offline, and is one of many packs. |
| "Is any of it cloud?" | No. Watch — airplane mode. |
| "Which part ran on the NPU?" | Here's the per-model backend log. |
| "Real warehouses don't have markers." | A printed mat is standard for vision stations; marker-free is roadmap. |
| "Privacy?" | On-device; clips deletable after the receipt; faces blurred in stored evidence; aligns with India's DPDP regime. |

**Rule:** correct a misunderstanding in one sentence, then restate the one-liner.

## Non-functional requirements
- **Offline:** no network in any core flow; airplane-mode run part of done.
- **Privacy & safety:** on-device by default; separate consent per sensor; visible recording indicators; no background camera/mic; minimal retention; delete-all; face blur in stored evidence; CrashGuard motion only + mock transport; no automatic emergency calls.
- **Thermal:** sustained NPU profile; detector at low stride; clips < 60 s; LLM never loaded during verification.
- **Performance:** detector ≥ 5 fps sustained; head training < 10 s (H1/H2), < 5 s (H3); SealSafe authored → trained → exported in < 90 s with pre-captured examples.
- **Claims policy & honest limits:** see `agent-instructions.md`.

## Out of scope
Gallery apps (PPE gate, syringe packaging, distress detection, road anomaly, technician service log, seat belt); real SOS/SMS/emergency calls; background camera/mic; marker-free zones (roadmap); FreeMoCap and JEV (not adopted).
