# Structura AI

Android app for structural design to Indian Standards. It has four modules, built one phase at a time:

| # | Module | Codes | Status |
|---|--------|-------|--------|
| 1 | **RCC Design**: beam, slab, column, footing, BBS | IS 456, IS 875, IS 1893, SP 16 | **Beam design + UCS detail drawing / DXF done** |
| 2 | **Steel Design**: beams, columns, tension members, connections, checks from 3D Studio | IS 800, IS 808, IS 4923 | Done |
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

## RCC member design (IS 456:2000)
- **Beams:** singly, doubly and flanged T/L beams (Annex G-2.2; flange-width check Cl. 23.1.2; deflection with the Fig. 6 reduction). Bars that don't fit one layer go into two layers automatically (Cl. 26.3.2(b)) and d is recalculated to the bar centroid.
- **Slabs:** one-way, cantilever, two-way simply supported (Table 27) and two-way restrained (Table 26). Includes minimum steel, spacing, maximum bar size, shear with the slab factor k (Cl. 40.2.1.1) and deflection.
  - **Table 26 (corners held down):** all 9 edge cases; bottom steel from αx+, αy+; top steel over continuous edges from αx−, αy− (bars extended 0.3·lx, Annex D practice / SP 34).
  - **Corner torsion steel:** 3/4·Ast,x in 4 layers over lx/5 where both edges are discontinuous (D-1.8), half where one is (D-1.9), none where both are continuous (D-1.10). The BBS adds marks S3/S4 (top bars) and S5 (torsion mesh).
  - Deflection basic l/d: 26 (continuous) only when both long edges are continuous, otherwise 20 (conservative for end panels). ly/lx is clamped to 2.0; above that design as one-way.
- **Columns:** slenderness (Cl. 25.1.2), minimum eccentricity (Cl. 25.4), additional moments with the k reduction for slender columns (Cl. 39.7). Uniaxial capacities come from strain compatibility (parabolic-rectangular concrete, Fig. 23 steel, εcu reduced when the whole section is in compression, Cl. 39.1). Then the biaxial check (Cl. 39.6) and tie rules (Cl. 26.5.3).
- **Isolated footings:** plan size from SBC, moments at the column face, depth iterated for flexure, one-way shear and punching (ks·0.25√fck), central band, minimum steel, development length.
  - **With column moments:** service ML / MB enlarge the plan until qmax = P/A + 6ML/(BL²) + 6MB/(LB²) ≤ SBC and qmin ≥ 0 (no tension). Flexure and one-way shear integrate the factored trapezoidal pressure on the heavier side; punching uses average qu (moment transfer, Cl. 31.6.2.2, flagged).

## Branding
- Logo source: `docs/branding/structura_logo_original.png`; Play Store icon `docs/branding/playstore_icon_512.png`.
- Launcher: `res/mipmap-*/ic_launcher*.png` and adaptive icon `mipmap-anydpi-v26` (background #1A273A). In-app logo: `res/drawable-nodpi/structura_logo.png`.
- **Bar bending schedule** for every member, exported as CSV. Shape-specific cut lengths use IS 2502 bend deductions (90° = 2φ, 135° = 3φ) and unit weight d²/162; every row lists its formula.

## Steel member design (IS 800:2007, limit state)
- **Sections:** ISMB / ISHB / ISMC (IS 808 nominal dimensions), SHS / RHS (IS 4923), CHS and custom rolled or welded I-sections. Properties are computed without root fillets, so A and I come out 1–4 % below the tables; for example, ISMB 300 Izz is within 3 % of IS 808 (checked in tests).
- **Classification:** Table 2.
- **Tension (Cl. 6):** gross yielding and net rupture with holes, plus the L/r ≤ 400 check.
- **Compression (Cl. 7.1.2):** buckling classes from Table 10 and fcd from the Perry–Robertson formula. Verified against Table 9(b): 118 MPa at KL/r 100, fy 250.
- **Beams:**
  - Laterally supported Md ≤ 1.2·Ze·fy/γm0 (Cl. 8.2.1).
  - Lateral-torsional buckling with the Annex E simplified Mcr and χLT (Cl. 8.2.2).
  - Shear Vd and high-shear moment reduction (Cl. 8.4, 9.2.2); web d/tw ≤ 67ε.
  - Service deflection span/240, /300 or /360 (Table 6).
- **Beam-columns:** section check (Cl. 9.3.1.3) and the two member-buckling interaction equations with Ky, Kz and KLT (Cl. 9.3.2.2).
- **Connections:**
  - Bearing bolts 4.6 / 8.8: shear and bearing (kb), pitch, edge distance, long-joint flag (Cl. 10.3).
  - Fillet welds: fwd, effective length, minimum size (Table 21) and maximum size (Cl. 10.5).

### Steel checks from 3D Studio (RFEM "steel design add-on" style)
- **Assign:** Member tool → Section "IS 808 rolled" (ISMB / ISHB / ISMC) or SHS / RHS / CHS, Material "Steel" with grade E250 / E350 (IS 2062).
- **Run:** ▶ Analyse is optional — "⚙ Steel checks" analyses every IS 456 Table 18 ULS combination itself and checks each steel member with `SteelModelCheck.check` (core/steel/SteelModelCheck.kt).
- **Forces used:** largest compression (or tension), |Mz,major| (FEM My), |My,minor| (FEM Mz) and |V| along the member, combined conservatively even if they occur at different stations.
- **Checks:** class (Table 2), beam-column Cl. 9.3.1.3 + 9.3.2.2 (or tension + bending Cl. 9.3.1.3), LTB Cl. 8.2.2, shear Cl. 8.4. The governing combination is kept per member.
- **Lengths:** K = 0.65 / 0.8 / 1.0 / 1.2 / 2.0 applied to node-to-node length about both axes; LLT/L = 0 (restrained) … 1.2. Split members at bracing points to shorten L.
- **Output:** members coloured by η in 3D (red > 1.0), sortable table, tap for the full clause-by-clause check sheet; section 11 of the HTML report and `steelChecksIS800` in Engineering JSON.
- FEM section mapping: `SteelModelCheck.femSection` puts the IS 808 major axis (Izz) in FEM Iy — web vertical, gravity bending = major axis.

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

### Batch B additions
- **Member loads:** uniform, partial (a → b) and trapezoidal or triangular, in global X/Y/Z or local z. Fixed-end forces come from exact Gauss integration of the point-load solution; internal forces are exact by statics. Verified: triangular load gives wL/6, wL/3 and wL²/(9√3); partial UDL reactions; deflection matches a subdivided model.
- **Rigid end offsets** at i and j (joint zones), using a rigid-arm transformation. Loads on the arms go straight to the node. Verified: cantilever with an offset gives δ = P·Lf³/3EI. Buckling and modal analyses use the full member length.
- **Q4 flat-shell panels** (slabs, walls, FRP plates): membrane + Reissner–Mindlin plate, with 1-point transverse shear plus t²/h²-scaled stabilisation, 6 DOF/node, normal pressure and self-weight. Results: mean normal deflection, N, M and max face stress at the centroid.
  - **Panel tool:** tap 4 corners.
  - **Panel mesh:** splits a panel into nx × ny and splits beams along its edges so they stay connected.
  - Verified against Timoshenko's square plate: simply supported 0.00406 qa⁴/D and clamped 0.00126 qa⁴/D, within 4 % on an 8 × 8 mesh.
- **Indian Standards · Load assumptions** screen:
  - Floor area load calculator (DL, LL by occupancy, factored load, seismic weight).
  - Slab → beam load transfer (one-way / two-way equivalent UDL).
  - Quick reference for IS 875 Parts 1/2/3/5, IS 456 Table 16/18, IS 1893 and IS 800.

### Batch C additions
- **Floor load tool (STAAD-style):** an area load q (kN/m²) on a bay (tap 4 corners) or on a whole level (tap any node on it; every rectangular bay enclosed by beams is found automatically). It is distributed as two-way 45° (triangles and trapezoids) or one-way (q·lx/2 on the long beams); Auto picks one-way when ly/lx > 2. Edges made of several members are handled, and the total transferred is always q × area. The results are stored as ordinary member loads.
- **{ } Engineering JSON:** the full model plus results for every combination (displacements, reactions, end forces, internal-force stations, panels), the ULS envelope, buckling and modal results, in one machine-readable file.
- **# STAAD .std export:** a STAAD.Pro input file with joints, members, shells, materials, properties (PRIS / TUBE / PIPE), betas, offsets, supports, releases and springs, load cases (SELFWEIGHT, JOINT, MEMBER UNI/TRAP/CON, ELEMENT PR) and IS 456 combinations, using SET Z UP. Check the import in STAAD.Pro before running.

### Seismic — IS 1893 (Part 1):2016 response spectrum
- Inputs: zone factor Z (II–V), importance factor I, response reduction factor R, soil type I/II/III, and how Ta is taken (RC / steel MRF formula, 0.09h/√d, or modal T1). Seismic weight is DL + 25 % or 50 % of LL.
- Sa/g follows Cl. 6.4.2 and Ah = (Z/2)(Sa/g)/(R/I). Peak modal responses are combined by CQC with 5 % damping (Cl. 7.7.5.4).
- Base shear is scaled up to the static VB̄ = Ah(Ta)·W when lower (Cl. 7.7.3). Storey drift is checked against 0.004 (Cl. 7.11.1).
- The seismic design envelope covers 1.5(DL+LL), 1.2(DL+LL±EL), 1.5(DL±EL) and 0.9DL±1.5EL with EL in X and Y separately. The 100 % + 30 % orthogonal rule is not applied. The envelope feeds the "design critical beam" button.
- Verified on a single-mass column against the hand calculation (T = 2π√(m/k), VB = Ah·W), on the scaling rule, and against the code spectrum values.

## Detail drawing (UCS-based, DXF)

- Every view has its own **named UCS**: `ELEVATION` (origin at the soffit, outer face of the left support), `SECTION_A-A` (origin at the bottom-left corner of the section), and `NOTES`.
- Geometry is at true size in mm; the UCS maps each view into world coordinates.
- **DXF R12 export** has layers S-CONC, S-REBAR, S-STIRRUP, S-HATCH (45° diagonal hatching), S-DIM, S-TEXT and S-CENTER. Bars are drawn as donuts, φ is written as `%%c`, and the named UCS table is included. In AutoCAD, use **UCS ▸ Named ▸ SECTION_A-A ▸ Set Current**.
- Cantilevers automatically put the main steel at the top.

## Maintaining

- To add a new code table, put it in `core/codes/` with the clause in its KDoc.
- For every new design routine, add a test in `core/src/test/` whose expected values come from an independent hand calculation (see `tools/verify_beam.py`).
- UI screens only call `:core`. Keep formulas out of the `app` module.
