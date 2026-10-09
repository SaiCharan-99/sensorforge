# Design System

The visual source of truth for every SensorForge screen (studio-app, Forge, end apps, HelloForge).
**Theme: soft and light.** Calm, low-saturation surfaces; strong, unambiguous status colours; everything readable at a glance.
Screen content and flows live in `ui-design.md`. This file defines *how things look*.

## 1. Principles
1. **Clarity over decoration.** One primary action per screen. No ornamental gradients, shadows or illustrations.
2. **Status is never colour alone.** Every verdict and state uses **colour + icon + text**.
3. **Readable from 2 m.** Verdicts are big, high-contrast and short (judges at the table).
4. **Soft surfaces, firm text.** Backgrounds are pastel or near-white. Text and icons always meet WCAG AA contrast (≥ 4.5:1; all pairs below are checked).
5. **Honest UI.** Labels follow the claims policy (`agent-instructions.md`). SIMULATION is always visible when active.

## 2. Colour tokens (light theme only)
Hex values; contrast ratios measured against the stated background.

### Neutrals
| Token | Hex | Use | Contrast |
|---|---|---|---|
| `background` | `#F7F8FA` | App background | — |
| `surface` | `#FFFFFF` | Cards, sheets, dialogs | — |
| `surfaceVariant` | `#EEF1F5` | Inputs, chips, list rows, pending state | — |
| `outline` | `#D5DAE1` | Card borders, dividers | — |
| `outlineStrong` | `#B6BDC8` | Focused/active borders | — |
| `textPrimary` | `#1C2430` | Headings, body | 15.6 on surface |
| `textSecondary` | `#4A5565` | Supporting text, labels | 7.6 on surface |
| `textTertiary` | `#6B7280` | Hints, timestamps (≥ 14 sp only) | 4.8 on surface |
| `scrim` | `#1C2430` @ 40% | Behind modals | — |

### Brand
| Token | Hex | Use | Contrast |
|---|---|---|---|
| `primary` | `#4F5BD5` | Primary buttons, active stepper, links | 5.5 (white text on it) |
| `onPrimary` | `#FFFFFF` | Text on primary | — |
| `primaryContainer` | `#E6E8FB` | Selected tiles, active step background | — |
| `onPrimaryContainer` | `#2A3290` | Text on primaryContainer | 8.8 |

### Status (verdicts and states)
| State | Container (bg) | Content (text/icon) | Border | Icon | Contrast |
|---|---|---|---|---|---|
| **PASS** | `#E3F4EA` | `#1E7A46` | `#A8D9BB` | check-circle | 4.7 |
| **FAIL** | `#FDE8E6` | `#B42318` | `#F4B4AD` | x-circle | 5.6 |
| **UNVERIFIED** | `#FFF3D6` | `#8A5A00` | `#F2D18A` | eye-off / help-circle | 5.4 |
| Pending / not yet observed | `#EEF1F5` | `#4A5565` | `#D5DAE1` | clock / hollow circle | 6.7 |
| Info / coaching | `#E4F0FA` | `#1F6FB2` | `#B5D3EE` | info / speaker | 4.6 |
| **SIMULATION** | `#F1E8FB` | `#6B3FA0` | `#D2BDEB` | play-circle + diagonal stripes | 6.2 |

Step statuses map as: `OBSERVED` → PASS colours · `ABSENT` → FAIL colours · `NOT_OBSERVABLE` → UNVERIFIED colours · not yet evaluated → Pending.

Solid variants (white text, for the big verdict banner and the CrashGuard Cancel button):
| Token | Hex | Contrast with white |
|---|---|---|
| `passSolid` | `#1E7A46` | 5.4 |
| `failSolid` | `#B42318` | 6.6 |
| `unverifiedSolid` | `#8A5A00` | 5.9 |

### CrashGuard incident
The countdown screen uses `failContainer` (`#FDE8E6`) as its background, with `textPrimary` text. **Cancel** uses `primary` solid (a calm, safe action). **"I need help"** uses `failSolid`. The "SIMULATED SOS" banner uses the SIMULATION colours, never red-solid. It must not look like a real alert being sent.

### Data visualisation (loss curve, IMU traces, metrics)
| Token | Hex | Use |
|---|---|---|
| `chart1` | `#4F5BD5` | Train loss / \|a\| |
| `chart2` | `#E07A3F` | Val loss / \|ω\| |
| `chart3` | `#2E9C8F` | Third series |
| `chartGrid` | `#E5E8ED` | Gridlines |
| `chartAxis` | `#6B7280` | Axis labels |
Lines 2 dp, no area fills, legend always visible (never colour-only identification).

## 3. Typography
Font: **Inter** (fallback Roboto/system sans). Mono: **JetBrains Mono** (fallback Roboto Mono). Bundle fonts in the APK (offline).
| Style | Size / line | Weight | Use |
|---|---|---|---|
| `displayVerdict` | 40 / 48 sp | 700 | PASS / FAIL / UNVERIFIED banner |
| `headlineLarge` | 28 / 36 sp | 600 | Screen titles, countdown subtitle |
| `headlineMedium` | 22 / 28 sp | 600 | Section titles |
| `titleMedium` | 18 / 24 sp | 600 | Card titles, tile names |
| `bodyLarge` | 16 / 24 sp | 400 | Body, coaching text |
| `bodyMedium` | 14 / 20 sp | 400 | Secondary text |
| `labelLarge` | 16 / 20 sp | 600 | Buttons |
| `labelMedium` | 12 / 16 sp | 600 | Chips, badges (UPPERCASE for status chips) |
| `mono` | 14 / 20 sp | 500 | Hashes, IDs, reason codes, JSON |
| `countdown` | 96 sp | 700, tabular figures | CrashGuard countdown |
Minimum body text 14 sp. Never below 12 sp. Respect system font scaling (layouts must survive 1.3×).

## 4. Spacing, shape, elevation
- **Spacing scale (dp):** 4, 8, 12, 16, 24, 32, 48. Screen padding 16. Card padding 16. Gap between cards 12.
- **Corner radius:** chips 8 · buttons 12 · cards 16 · sheets/dialogs 24 · verdict banner 20.
- **Elevation:** flat. Cards use a 1 dp `outline` border instead of shadows. Only dialogs/bottom sheets get elevation 2.
- **Touch targets:** ≥ 48 × 48 dp. Primary buttons 56 dp tall, full width on mobile. CrashGuard Cancel ≥ 96 dp tall, full width.

## 5. Components
| Component | Spec |
|---|---|
| **Primary button** | `primary` fill, `onPrimary` text, 56 dp, radius 12. Disabled: `surfaceVariant` fill + `textTertiary` |
| **Secondary button** | `surface` fill, 1 dp `outlineStrong` border, `primary` text |
| **Destructive button** | `failSolid` fill, white text. Used only for delete-all / "I need help" |
| **Card** | `surface`, 1 dp `outline`, radius 16, padding 16 |
| **VerdictBanner** | Full width, radius 20, solid status colour, white `displayVerdict` text + 32 dp icon. Below it, the first reason in `mono` on the status container colour |
| **StatusChip** | Container bg + content text + border, `labelMedium` uppercase, leading 16 dp icon |
| **StepChecklist row** | 56 dp min; left 24 dp status icon; step id in `mono`, plain-language description under it in `bodyMedium`; trailing timestamp |
| **ReasonList item** | Code in `mono` (status colour) + one-line explanation in `bodyMedium` |
| **CoachingOverlay** | Info container, speaker icon, `bodyLarge`, pinned at the bottom of the camera preview with 16 dp margin, radius 16 |
| **HashCard** | Label + truncated hash `3f9a…c71e` in `mono`; Before → After with arrow; when changed, After is shown in a PASS chip "changed". Tap copies the full hash |
| **SimulationChip** | SIMULATION colours + 45° stripe pattern (`#D2BDEB` on `#F1E8FB`, 6 dp stripes), pinned under the top bar, not dismissible while active |
| **OfflineBadge** | Pending colours + airplane icon, "Offline". Shown only after a verified airplane-mode run |
| **RecordingDot** | 10 dp `failSolid` dot + "REC" label in the top bar whenever the camera is active |
| **ErrorCard** | FAIL container, error type in `mono`, fix hint in `bodyLarge`, one action button |
| **Stepper (Forge)** | 7 labelled steps (Describe, Plan, Capture, Train, Test, Export, Import); done = `primary` filled circle with check; current = `primaryContainer` + `primary` ring; upcoming = `outline` ring |
| **Top bar** | `surface` bg, 1 dp bottom `outline`, title `titleMedium`; right side: OfflineBadge, RecordingDot |
| **Camera preview** | Radius 16 card. Overlays (boxes, zones) drawn 2 dp: detector boxes `primary`, zones `chart3` dashed, impostor/forbidden `failSolid`. Labels on `surface` @ 90% pills |

## 6. Iconography
Material Symbols **Rounded**, 24 dp default, weight 400. Status icons are filled; navigation icons are outlined. Every icon-only button has a content description.

## 7. Motion
- Duration 150–250 ms, standard easing. No looping or decorative animations.
- Step ticked: icon cross-fade + 1 brief scale (1.0 → 1.1 → 1.0).
- Verdict reveal: banner slides up 16 dp + fade, 250 ms.
- Countdown: number change only, plus haptic each second. No pulsing red.
- Respect "Remove animations" system setting.

## 8. Accessibility & clarity checklist
- [ ] All text/background pairs ≥ 4.5:1 (large text ≥ 3:1).
- [ ] Status = colour + icon + text everywhere.
- [ ] Touch targets ≥ 48 dp. Critical actions (Cancel, Stop, Train) are large and isolated.
- [ ] One primary action per screen. Destructive actions need confirmation (except Cancel on countdown).
- [ ] Plain-language explanation next to every reason code.
- [ ] TalkBack labels on all controls. Verdict changes announced via live region.
- [ ] Layout works at 1.3× font scale and on 360 dp wide screens.
- [ ] No text over the camera image without a solid pill/background.

## 9. Compose implementation
- `sf-sdk` exposes no theme. Theme lives in `studio-app` (`ui/theme/Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt`) and is copied minimally into `hello-forge`.
- Map tokens into a Material 3 `lightColorScheme` (`primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `background`, `surface`, `surfaceVariant`, `outline`, `error = failSolid`, `errorContainer = failContainer`).
- Status, simulation and chart colours go into a custom `SensorForgeColors` object provided via `CompositionLocal` (they are not part of the M3 scheme).
- Disable dynamic colour (Material You). The palette must be identical on every device.
- Light theme only. Ignore system dark mode.
