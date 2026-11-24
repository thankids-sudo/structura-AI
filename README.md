# Structura AI

Android app for structural design to Indian Standards. It has four modules, built one phase at a time:

| # | Module | Codes | Status |
|---|--------|-------|--------|
| 1 | **RCC Design**: beam, slab, column, footing, BBS | IS 456, IS 875, IS 1893, SP 16 | **Phase 1: Beam design done** |
| 2 | **Steel Design**: beams, columns, tension members, connections | IS 800, IS 808, IS 4923 | Planned |
| 3 | **Frame Analysis + AI**: continuous beams, 2D frames, SFD/BMD, AI result check | Stiffness method | Planned |
| 4 | **Drawing → Structure AI**: plan import, grid/column layout, load take-down, member sizing | — | Planned |

See [`docs/ROADMAP.md`](docs/ROADMAP.md) for the step-by-step build plan.

## Project layout

```
structura-ai/
├── core/                     Pure-Kotlin design engine (no Android). Unit-tested on JVM.
│   └── src/main/kotlin/com/structura/core/
│       ├── codes/IS456.kt     Constants, Table 19, Table 20, Fig. 23 steel curve, Cl. 23.2.1 deflection
│       └── rcc/
│           ├── BeamDesign.kt  Limit-state beam design: singly/doubly, shear, deflection, detailing checks
│           └── BeamReport.kt  Plain-text design note (share via WhatsApp / e-mail)
├── app/                      Android app (Kotlin, Jetpack Compose, Material 3, minSdk 26)
│   └── src/main/java/com/structura/app/
│       ├── MainActivity.kt    Navigation host
│       └── ui/home, ui/rcc, ui/theme
├── tools/verify_beam.py      Independent hand-calculation used to set the unit-test expected values
└── docs/ROADMAP.md
```

All the code-specific logic lives in `:core`, so the same engine can later serve a web app or an API.

## Build & run

1. Open the folder in **Android Studio (Koala 2024.1 or newer)**. It downloads Gradle 8.9 and AGP 8.5.2 on first sync.
2. Run the engine tests: `./gradlew :core:test`
3. Install on a phone or emulator: `./gradlew :app:installDebug`, or press ▶ in Android Studio.

To build an APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

## Phase 1: what the beam design does

Inputs: b, D, cover, bar diameters, span, support type (simply supported / continuous / cantilever), M-grade, Fe-grade, and either a factored UDL or Mu and Vu directly.

Every check carries its clause:

| Check | Clause |
|-------|--------|
| xu,max/d, Mu,lim, singly / doubly reinforced Ast & Asc | Cl. 38.1, Annex G-1.1 / G-1.2 |
| Compression steel stress fsc from the design stress-strain curve | Cl. 38.1(e), Fig. 23 (matches SP 16 Table A) |
| Min / max tension steel, max compression steel | Cl. 26.5.1.1(a), (b), 26.5.1.2 |
| Clear spacing of bars | Cl. 26.3.2(a) |
| Nominal cover, concrete grade | Cl. 26.4, Table 16; Cl. 6.1.2, Table 5 |
| τv, τc (Table 19, interpolated), τc,max | Cl. 40.1, 40.2, Tables 19 & 20 |
| Stirrup design, minimum shear steel, max spacing, fy ≤ 415 for stirrups | Cl. 40.4(a), 26.5.1.6, 26.5.1.5 |
| Span/depth with kt, kc and the > 10 m reduction | Cl. 23.2.1, Fig. 4 & 5 |

### Assumptions to know about

- **Fig. 4 / Fig. 5 (kt, kc)** use the standard closed-form curve fits, not values read off the graph. They agree with the figure to about ±3 %.
- **Table 19 / 20 at an intermediate grade** (e.g. M22) use the next *lower* tabulated grade. This is conservative; the code gives no rule for interpolating between grades.
- Bars are assumed to be **in one layer**. If they don't fit, the spacing check fails and asks for two layers. In that case d has to be recomputed; automatic two-layer d is a Phase 1.1 item.
- Exposure is taken as **moderate**.

## Maintaining

- To add a new code table, put it in `core/codes/` with the clause in its KDoc.
- For every new design routine, add a test in `core/src/test/` whose expected values come from an independent hand calculation (see `tools/verify_beam.py`).
- UI screens only call `:core`. Keep formulas out of the `app` module.
