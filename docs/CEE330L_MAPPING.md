# CEE 330L (STAAD.Pro lab manual) feature parity — mapping to Structura AI (Android / Kotlin)

The brief was written for a web/Tauri stack (React 19, Zustand, AG Grid, WebGPU, gRPC, 14-DOF warping kernel).
Structura AI is a native Android app with a pure-Kotlin engine (`:core`) and Jetpack Compose UI (`:app`).
Each requirement was mapped to that architecture; this table records what was built, where, and what is not applicable.

| Brief item | Status | Where |
|---|---|---|
| Model type SPACE / PLANE / TRUSS | Done — `FrameModel.planeXZ` (STAAD PLANE: uy, φx, φz held), truss members | `fem/Model.kt`, Studio "2D plane XZ" |
| Units kip/ft/in | Benchmarks converted at the boundary; imperial ↔ SI converter on the Loads screen; app input stays SI | `fem/Benchmarks.kt`, `ui/loads/LoadsScreen.kt` |
| Camera presets, labels, load vectors | Existing: 3D, Top XY, Front XZ, Right YZ; load arrows | `ui/fem/FrameView3D.kt` |
| Assign to selected / to all | Existing per-member Apply / Apply to all | Studio member editor |
| Translational repeat (steps, spacings, link) | Done — node merging, link members, veranda projections | `fem/Builders.kt` `translationalRepeat`, Studio "⇉ Repeat" |
| Fink roof truss / Pratt bridge truss wizards | Done — truss members, pinned + roller, plane model | `Builders.finkTruss / prattTruss`, Studio "Benchmarks / trusses" |
| Parametric sections Rect, T, I, Channel, Circle, Triangle with A, As, I, It, Iω | Done (`iw` field; Iω reported, kernel is 12-DOF St Venant) | `Section.rect/tee/iBeam/channel/circle/triangle/angle` |
| IS 808 ISMB/ISMC/ISHB/ISWB/ISA | ISMB/ISHB/ISMC built in; ISA angles + JIS L added to the catalogue; any SP 6(1) table by CSV import | `SectionCatalogue`, `steel/SectionTableImport.kt` |
| AISC W / C / L / WT | Via CSV import (no AISC data shipped) | Steel → Import section table |
| Ec IS 456 / ACI 318 (4700√f'c, wc^1.5·0.043√f'c) | Done | `Material.concrete`, `Material.concreteAci` |
| Releases My/Mz, springs, offsets, beta | Existing; truss (axial-only incl. torsion release) and tension-only (iterative) added | `Member.truss`, `Member.tensionOnly`, `FrameSolver.analyse` |
| Warping torsion / bimoment B(x) | Done per member (Vlasov, warping condensed: restrained or free at each end; no warping continuity across joints) — tip twist matches closed form to 1e-16 | `fem/Warping.kt`, member editor |
| Supports fixed / pinned / roller (axis) / springs | Done — `Support.roller(axis)`, `Support.spring(...)` | Studio node editor |
| Inclined support coordinate systems | Done — `Support.inclination` (X, Y, Z angles); test R = (17.32, 0, 30) kN on a 30° roller | node editor "Incline" |
| Self-weight factor, partial / trapezoidal / point loads, floor loads | Existing | `MemberLoad`, `PointLoad`, `FloorLoad` |
| Point moment on a member at distance a | Done (exact: split + nodal moment) | `Builders.memberMoment`, member editor "+ Moment" |
| STATIC CHECK ΣF and ΣM | Done — 6 components, table in Studio and test logs | `FrameResults.staticCheck()` |
| Node inspector / member force graphs | Node inspector added (6 displacements + reaction); member N/V/M diagrams existing | Studio |
| Tables: displacements, reactions, end forces (CSV/JSON) | Existing (FEM CSV, Engineering JSON, HTML report) | `FemReport.csv`, `EngineeringJson` |
| IS 1893 equivalent static, IS 875-3 wind | Done — VB = Ah·W, Qi ∝ Wi·hi²; Vz, pz, pd, F = Cf·pd·Ae | `fem/LateralLoads.kt`, Studio "≋ IS 1893 / wind" |
| IS 1893 accidental eccentricity, 100 %/30 % | Done — `seismicStatic(eccentricity)`, `LoadCombination.seismic100_30()` | `fem/LateralLoads.kt`, `fem/Model.kt` |
| Steel angle design | Done — `steel/AngleDesign.kt`; B2 Fink L 60×60×4 under 10 kN panel loads gives η = 2.67 (fails: the manual's angle is for its own loading) | Steel screen, Studio steel checks |
| Benchmark suite B1–B4 + templates | Done — `Cee330lBenchmarksTest`, Studio "Benchmarks / trusses" | `core/src/test/.../verification/` |
| React / Zustand / AG Grid / WebGPU / gRPC / origin attribution / proto | Not applicable to the Android architecture | — |

## Benchmark results (CI, build after commit "Studio: benchmarks & truss wizards…")
| Benchmark | Max force residual | Max moment residual | Other assertions |
|---|---|---|---|
| B1 pitched portal, 1.0 DL + 1.0 LL | 4.3e-12 kN | 9.1e-12 kN·m | RA + RF = ΣP; joint C member forces balance; finite displacements B–E |
| B2 Fink truss | 2.8e-14 kN | 2.0e-13 kN·m | all end moments < 1e-9; every joint in equilibrium; symmetric reactions |
| B3 Pratt truss | 1.6e-12 kN | 8.6e-12 kN·m | symmetric reactions; bottom chords tension, top chords compression; zero end moments |
| B4 3-storey building + veranda | ≤ 7.3e-12 kN (7 combos) | ≤ 1.0e-10 kN·m | W = 3382.5 kN, Ah = 0.0400, VB = 135.30 kN = Ah·W; ΣRx under 1.5(DL+WL) = −202.95 kN = −1.5·VB; IS 456 beam & column designs run |

Assumed loads where the brief leaves them open (B1 LL: 15 kip/ft normal to BC over 0–8 ft, 20 kip on CD at 7.5 ft,
2→4 kip/ft on DE; B2 12 m × 3 m, 10 kN panel loads; B3 24 m, 6 panels, 50 kN; B4 3 × 2 bays 5 × 4 m, 3 storeys of 3.2 m,
1.5 m veranda, slab DL 4.75 kN/m², LL 3 kN/m², zone III, soil II, R 5).
