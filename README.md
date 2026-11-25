# Structura AI

Android app for structural design to Indian Standards. It has four modules, built one phase at a time:

| # | Module | Codes | Status |
|---|--------|-------|--------|
| 1 | **RCC Design**: beam, slab, column, footing, BBS | IS 456, IS 875, IS 1893, SP 16 | **Beam design + UCS detail drawing / DXF done** |
| 2 | **Steel Design**: beams, columns, tension members, connections | IS 800, IS 808, IS 4923 | Planned |
| 3 | **FEM Analysis + AI**: 3D frame FEM (RFEM-style), N/V/M diagrams, deformed shape, AI result check | Direct stiffness method | **3D frame solver done** |
| 4 | **Drawing → Structure AI**: plan import, grid/column layout, load take-down, member sizing | — | Planned |

See [`docs/ROADMAP.md`](docs/ROADMAP.md) for the step-by-step build plan.

## Project layout

```
structura-ai/
├── core/                     Pure-Kotlin design engine (no Android). Unit-tested on JVM.
│   └── src/main/kotlin/com/structura/core/
│       ├── codes/IS456.kt     Constants, Table 19, Table 20, Fig. 23 steel curve, Cl. 23.2.1 deflection
│       ├── fem/               3D frame FEM: Model, FrameSolver, Generators (beam / portal / space frame), FemReport (CSV)
│       ├── drawing/           UCS drawing model, BeamDetailer (elevation + section A-A), DxfWriter (AutoCAD R12)
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

## Install on a phone

**Latest APK (direct link):** https://github.com/thankids-sudo/structura-ai/releases/latest/download/StructuraAI.apk

Every push to `main` builds the APK, signs it with the fixed key in `keystore/`, checks it with `apksigner`, and publishes it as a GitHub Release. All builds share one signature and a rising version number, so each new APK installs over the previous one as an update.

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

## FEM analysis (RFEM-style 3D frame)

- 6 DOF per node, Euler–Bernoulli members, rigid joints, linear static, direct stiffness method, Cholesky solver.
- **Global axes:** X, Y horizontal, **Z up** (gravity −Z).
- **Member local axes (as in RFEM):** x along i→j; on beams y is horizontal and **z points down**, so sagging gives **+My**. Columns: y = global Y. Member rotation β is supported.
- **Loads:** nodal forces/moments, uniform and concentrated member loads (global or local directions), self-weight (γA), in load cases DL / LL / WL.
- **Member end hinges** (moment releases, by static condensation) — e.g. beams simply supported on columns.
- **Combinations:** IS 456 Table 18 limit-state set — 1.5(DL+LL), 1.2(DL+LL±WL), 1.5(DL±WL), 0.9DL±1.5WL — plus service cases; the member envelope covers all ULS combinations.
- **Live loads:** tap a node in the 3D view and drag Fx/Fy/Fz sliders; the frame re-solves and the deformed shape animates (loads applied this way are added at ×1.0 to whichever combination is shown and are not part of the ULS envelope).
- **Results:** node displacements, support reactions, member end forces, N / Vy / Vz / Mt / My / Mz at any number of stations, and the exact deflected shape (Hermite + fixed-end UDL term).
- **Checks:** equilibrium ΣR + ΣF = 0 shown on screen (as in RFEM), and a mechanism is reported with the node and DOF that are free.
- **Templates:** continuous beam, 2D portal frame (bays × storeys), 3D space frame (bays X × bays Y × storeys).
- **Export:** CSV of all results (opens in Excel). "Design this beam" sends the critical beam's My/Vz (×1.5) to the IS 456 beam designer.
- **Verified against closed-form results** in `FrameSolverTest`: cantilever PL³/3EI, simply supported 5wL⁴/384EI and wL²/8, fixed–fixed wL²/12 and wL²/24, two-span support moment wL²/8 and central reaction 1.25wL, PL/4 and PL³/48EI, Pab/L, hinged member = simply supported, combination superposition, portal frame symmetry and equilibrium.

## 3D Studio (model editor)

Same workflow as the Structura AI web version:
- **Tools:** Select, Node, Member (chain drawing), ½ Split, Fix, Pin, Load (Fx/Fy/Fz), Member load (q along global Z), Delete.
- **Work plane** XY / XZ / YZ with offset and snap. Tapping the grid places snapped nodes; coincident nodes merge automatically.
- **Views:** 3D isometric, Top XY, Front XZ, Right YZ. Drag to orbit, pinch to zoom.
- **Undo / Redo** (80 steps), **Model check**, **Save / Open project** (`*.structura.json`, plain JSON), **HTML report** (open in a browser and use Print ▸ Save as PDF).
- **Edit a selection:** node coordinates and support type; member section b × D and hinges at i / j; clear loads.
- **Analysis:** IS 456 Table 18 combinations, ULS envelope, animated deformed shape, N/V/M diagrams, and **global buckling λcr** with the animated mode shape. Buckling uses a consistent geometric stiffness, 4 elements per member and inverse iteration, and is verified against Euler's π²EI/4L² cantilever.

### Batch A additions
- **Beam theory:** Euler–Bernoulli or **Timoshenko** (shear deformable). Φ = 12EI/(G·As·L²) is applied to stiffness and point-load fixed-end forces. This matters for FRP, where G/E is low. Verified: cantilever PL³/3EI + PL/(G·As), and simply supported UDL 5wL⁴/384EI + wL²/(8G·As).
- **Sections:** rectangle, CHS, SHS and RHS, all from dimensions, plus a catalogue: steel IS 4923 SHS/RHS, IS 1161 CHS, and typical GFRP tubes. Shear areas and Bredt torsion constants are included. Properties assume sharp corners; verify them against the code tables or the manufacturer.
- **Materials:** concrete (IS 456 E = 5000√fck), steel E250, GFRP (illustrative E 23 GPa / G 3.5 GPa / γ 19) and custom E/G/γ.
- **Semi-rigid joints:** rotational springs (kN·m/rad) at member ends, by 16-DOF static condensation; a hinge is a spring of 0. Verified: k = 2EI/L gives an end moment of wL²/24.
- **Modal analysis:** consistent mass matrices and subspace iteration. Output: frequencies, periods, mass participation X/Y/Z with cumulative %, and animated mode shapes. Mass = self-weight + DL + 0.25 or 0.5 LL (IS 1893 Table 10). Verified against the cantilever (1.875²) and simply supported (π²) beam frequencies.

## Detail drawing (UCS-based, DXF)

- Every view has its own **named UCS**: `ELEVATION` (origin at the soffit, outer face of the left support), `SECTION_A-A` (origin at the bottom-left corner of the section), and `NOTES`.
- Geometry is at true size in mm; the UCS maps each view into world coordinates.
- **DXF R12 export** has layers S-CONC, S-REBAR, S-STIRRUP, S-HATCH (45° diagonal hatching), S-DIM, S-TEXT and S-CENTER. Bars are drawn as donuts, φ is written as `%%c`, and the named UCS table is included. In AutoCAD, use **UCS ▸ Named ▸ SECTION_A-A ▸ Set Current**.
- Cantilevers automatically put the main steel at the top.

## Maintaining

- To add a new code table, put it in `core/codes/` with the clause in its KDoc.
- For every new design routine, add a test in `core/src/test/` whose expected values come from an independent hand calculation (see `tools/verify_beam.py`).
- UI screens only call `:core`. Keep formulas out of the `app` module.
