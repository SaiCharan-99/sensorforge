# SensorForge — Problem, Solution & Pitch

**Version:** 1.0 FINAL · **Date:** 2026-10-09 · **Event:** iQOO Hackathon 2026 Grand Finale (Bengaluru, Oct 9–11) · **Track:** Developer Tools
**Doc set (authoritative, supersedes all earlier SensorForge docs):**
1. `sensorforge-problem-solution.md` — *this file*: why, what, apps, demo, pitch, claims.
2. `sensorforge-technical-spec-v3.md` — contracts, engine, SDK, training, apps' technical design, build plan, tests.
3. `sensorforge-model-stack.md` — every model/library, role, runtime, gates, fallbacks.

> **Rule for Claude Code:** product behaviour comes from this file; technical behaviour from the tech spec; model choices from the model stack. If two files disagree, the tech spec wins on implementation, this file wins on scope and claims.

---

## 1. One-liner

**SensorForge lets a developer teach a phone to verify or detect a real-world event — on the phone — and ship it to any app in two lines of code.**

Tagline: *"The AI sees. The code decides. The receipt proves it."*

---

## 2. The problem

Phones can see, hear and feel, yet turning raw sensor data into a **trustworthy judgement about what physically happened** still needs datasets, models, calibration, temporal logic and runtime engineering for every single use case. Most projects stop at "a detector saw a charger" and never answer the question the business actually has:

> *Did the right thing happen, in the right order, under the right conditions — and can you prove it?*

Concrete pain we open with:
- A packer seals a box while an item is still on the table. The order system says "packed".
- India's open-box deliveries and missing-item claims are settled with photos or videos that **nobody checks step by step**. A raw video can't say "the charger was never shown".
- A rider falls and can't reach their phone; a generic app has no idea anything happened.

---

## 3. The solution

SensorForge is an **on-phone authoring + training + verification runtime** with an SDK.

1. **Describe** the event (template or sentence).
2. **Capture** GOOD and BAD examples on the phone.
3. **Train & calibrate** on the phone: small model heads are genuinely trained (weights change, hash shown); the procedure's order, thresholds and rules are calibrated.
4. **Test** against held-out examples — false accepts treated as the worst error.
5. **Export** a **Verifier Pack** (`.sfpkg`) — it contains the trained model + rules.
6. **Import in two lines** into any Android app; get a callback with the verdict, reasons, evidence and a tamper-evident receipt.

Two engine modes:
- **Verify** a procedure → `PASS | FAIL | UNVERIFIED` (UNVERIFIED = "we couldn't see it", never a silent pass).
- **Detect** an event and respond → incident lifecycle (e.g. possible impact → countdown → simulated SOS).

Everything runs **offline on the phone**: authoring, training, verification.

---

## 4. Why we win with apps, not a platform pitch

Judges remember an app that catches *them*. So we show **real end-user apps first**, then reveal every one was built with SensorForge, then forge a new one live and import it into a fresh app.

All end apps use **only the public SDK** — so "built on SensorForge" is a fact.

---

## 5. Final app line-up

| # | App | End-user value | Sensors | Mode | Tier |
|---|---|---|---|---|---|
| A1 | **PackCheck** | Can't seal a box with a missing/wrong item | Camera | Verify | **T1 hero** |
| A2 | **CrashGuard** | Possible-impact detection → incident screen → *simulated* SOS with evidence | Accelerometer, gyroscope, location | Detect | **T1 climax** |
| A5 | **SealSafe** | Exam envelope opened only after seal shown intact and start time | Camera, clock | Verify | **T1 forged live** |
| A6 | **HelloForge** | Fresh app imports SealSafe in two lines | — | Import | **T1 platform proof** |
| A3 | **RepRight** | Physio/yoga rep counted only if range, tempo and hold are met | Camera (pose) | Verify | T2 |
| A4 | **HandoverProof** | Delivery: each item handed over, steps walked, slip returned | Camera, step counter, location | Verify | T2 (booth) |

**Tier rule:** T1 must pass all acceptance tests offline before any T2 work (hard gate: Saturday afternoon).

**Gallery only (one slide, labelled "not built"):** PPE gate, syringe packaging, distress detection, road anomaly, technician service log, seat belt.

### App summaries

**PackCheck.** Box on a printed marker mat; task card lists charger, cable, adapter. The app checks each item comes out of the box, reads its label on the same tracked object, and the box ends empty. A look-alike impostor cable is rejected by a head trained on the phone. Covering the lens or an item leaving and returning gives UNVERIFIED. Voice coaching: "Show the adapter label to the camera."

**CrashGuard.** User taps **Arm** (visible session, motion sensors only). A cheap motion gate plus a small model trained on the phone separates normal / shake / drop / impact-like. Possible impact → countdown with big Cancel → evidence saved (motion trace, location with accuracy, receipt) → **SIMULATED SOS** banner. Never sends anything real; never shows "SENT".

**SealSafe.** Built live: "Show the sealed envelope, open only after start, remove the papers." Seal intact/broken head trained from a few crops. Opening early or seal never shown → FAIL / UNVERIFIED.

**HelloForge.** An empty app containing only:
```kotlin
val verifier = SensorForge.load(context, "sealsafe.sfpkg")
verifier.start(previewView) { result -> statusText.text = result.verdict.name }
```

**RepRight.** Therapist-configured knee extension or yoga tree pose. Too fast / partial range → "needs adjustment" with the exact criterion. "Meets configured criteria", never "clinically correct".

**HandoverProof.** Items A–E (list supplied by the host app) each handed from partner side to customer side; signed slip code read; steps walked recorded; location recorded with accuracy but never sole evidence.

---

## 6. Five-minute stage run

| Clock | What happens | Takeaway |
|---|---|---|
| 0:00–0:35 | **PAST vignette** (Place, Action, Speech, Thoughts): a packer seals a box; a charger is still on the table; "Did everything go in?" "I think so." | Real pain |
| 0:35–1:30 | **PackCheck** live: judge removes adapter → FAIL with frame; swaps impostor → FAIL; covers lens → UNVERIFIED | It catches you |
| 1:30–2:40 | **CrashGuard**: press Train → loss curve, weights hash changes; live hand shake → nothing; labelled impact replay → countdown → SIMULATED SOS with location + trace | The phone learned, on the phone |
| 2:40–3:00 | **Reveal:** "Every app you just saw was built with SensorForge — on this phone." | Platform |
| 3:00–4:20 | **SealSafe forged live** (sentence → spec → pre-captured examples → train → export) → **HelloForge** shows two lines → judge opens envelope early → FAIL | Build once, use anywhere |
| 4:20–5:00 | Airplane mode badge, backend log, held-out results; close: *"Teach it on the phone. Ship it in two lines."* | Credible |

RepRight and HandoverProof are shown during table evaluations.

### Wow moments (rehearse each ≥5× on the real phone, poor lighting)
1. Judge tries to fool PackCheck — caught at the exact step.
2. The app admits what it couldn't see (UNVERIFIED, "identity trail broken").
3. Airplane mode switched on by a judge.
4. Weights hash changes after pressing Train.
5. A new verifier forged from a sentence, exported, imported in two lines.
6. Tamper-evident receipt: edit one byte of the saved clip → "receipt invalid".

---

## 7. Claims policy (what we may say)

| Say | Never say |
|---|---|
| "Possible impact detected" | "Crash detection", "saves lives", "split-second SOS" |
| "Trained a small model on this phone" | "Trained an AI model from scratch", "NPU training" |
| "Ran on the NPU" — only with a backend log | Any backend without a log |
| "Meets configured criteria" (RepRight) | "Clinically correct" |
| "Evidence receipt" | "Legally admissible proof" |
| "Offline" — only after airplane-mode run | "Works everywhere" |
| Real held-out numbers from our recordings | Invented benchmarks |

Prior art exists (Retrocausal, Roboflow, Drishti, viAct for factory SOP compliance; OEM crash detection on some phones). **Never say "first".** Our differentiation: offline on any Android phone; authoring and training on the phone; three-state observability; regression-gated packs; two-line SDK import.

---

## 8. Judge defence sheet

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

---

## 9. Users

| Persona | Gets |
|---|---|
| Android developer | Verifier Packs + SDK; no ML pipeline to build |
| Ops lead (warehouse, exam centre, logistics) | Configured verifier with evidence receipts |
| Worker / patient / rider | Clear feedback: done, fix this, or "show me" |
| Trusted contact (CrashGuard, opt-in) | Simulated alert preview in demo |

---

## 10. Privacy & safety
On-device by default; separate consent per sensor; visible recording indicators; no background camera/mic; minimal retention; delete-all; face blur in stored evidence; CrashGuard uses motion only and a mock transport; no automatic emergency calls.

---

## 11. Slides (6)
1. PAST still — "Software records work; it doesn't verify it."
2. Three apps that caught a judge (screens).
3. Reveal: Define → Capture → Train → Test → Export → Import (two lines).
4. Architecture: sensors → perception → observations → trained heads → rules → verdict + receipt.
5. Evidence: held-out confusion matrix, false accepts, UNVERIFIED rate, latency + backend per model (real numbers only).
6. Roadmap/gallery + limits.

---

## 12. Honest limits
Visible surfaces only; look-alikes leaving view can't be proven identical (→ UNVERIFIED); few examples ≠ general reliability; markers needed for zones; CrashGuard is a prototype using replays for impacts; GPS indoors unreliable.
