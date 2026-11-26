# Structura AI: build roadmap

Each step is built, tested and committed separately.

## Phase 1: RCC Design (IS 456)
- [x] 1.0 Project scaffold: `:core` engine + `:app` Compose UI, home with all 4 modules
- [x] 1.1 Rectangular beam: singly/doubly, shear, deflection, detailing checks, shareable report
- [x] 1.1a UCS-based beam detail drawing (elevation + section A-A) with DXF R12 export
- [x] 1.2 Two-layer bars with automatic d recalculation; flanged (T/L) beams per Cl. 23.1.2 & Annex G-2
- [x] 1.3 Slabs: one-way, cantilever, two-way simply supported (Table 27)
- [x] 1.3b Two-way restrained slabs (Table 26, 9 edge cases, top steel, corner torsion D-1.8 – D-1.10, BBS)
- [x] 1.4 Columns: axial + uniaxial/biaxial (Cl. 39, SP 16 interaction charts), slenderness (Cl. 25)
- [x] 1.5 Isolated footings: bearing, one-way & punching shear, flexure (Cl. 34)
- [x] 1.5b Footings with uniaxial / biaxial moment: kern check, trapezoidal pressure for flexure and shear
- [x] Branding: launcher icon (adaptive + legacy), Play Store 512 px icon, logo on Home
- [x] 1.6 Loads: IS 875 Part 1/2 unit weights, imposed loads, wall/slab loads, partition allowance, LL reduction (Cl. 3.2.1), Table 18
- [x] 1.7 Bar bending schedule export (CSV / Excel)

## Phase 2: Steel Design (IS 800:2007)
- [x] 2.1 Section library: ISMB / ISHB / ISMC / SHS / RHS / CHS / custom I (computed from IS 808 nominal dimensions)
- [x] 2.1b Section table import (CSV from SP 6(1) / IS 808: ISLB, ISWB, ISJB, channels, tubes; tabulated A, I, Z override geometry). Angles: import rejected until angle design (Cl. 7.5) is added
- [x] 2.2 Laterally supported / unsupported beams (Cl. 8.2), shear (Cl. 8.4), deflection (Table 6)
- [x] 2.3 Compression members (Cl. 7.1, buckling curves a–d); beam-columns (Cl. 9.3)
- [x] 2.4 Tension members (Cl. 6); bolted & welded connections (Cl. 10)
- [x] 2.5 Steel member checks straight from 3D Studio results (SteelModelCheck: all ULS combos, η colouring, report + JSON)

## Phase 3: Frame Analysis + AI
- [x] 3.1 3D frame FEM (6 DOF/node): continuous beam, portal frame and space frame templates, N/V/M diagrams, deformed shape
- [x] 3.2 Equilibrium check, mechanism detection, CSV export
- [x] 3.3 Critical beam from FEM handed to the IS 456 beam designer
- [x] 3.3a Member end hinges, point loads on members, DL/LL/WL load cases, IS 456 Table 18 combinations, ULS envelope
- [x] 3.3c Interactive live loads with animated 3D deformation
- [x] 3.3b 3D Studio: node/member editor with work planes, snap, split, supports, loads, undo/redo, project save/open, HTML report
- [x] 3.3d Global elastic buckling λcr with mode shape
- [x] 3.3e Modal analysis (frequencies, mode shapes, mass participation)
- [x] 3.3e2 IS 1893 response spectrum (CQC), base-shear scaling, drift check, seismic design envelope
- [x] 3.3f Timoshenko beams, hollow-section catalogue (steel + GFRP), rotational end springs
- [x] 3.3f2 Rigid end offsets, partial / trapezoidal member loads
- [x] 3.3g Q4 flat-shell panels (slabs / walls) with panel mesh and pressure loads
- [x] 3.3h Indian Standards reference + load assumptions calculators
- [x] 3.3i STAAD-style floor load transfer to beams, Engineering JSON export, STAAD.Pro .std export
- [x] 3.4 AI result check: load path, equilibrium, uplift, hanging columns, beam axial, idle members, deflection span/250, drift 0.004h, near-mechanism, proportions, missing cases/combinations — with clauses

## Phase 4: Drawing → Structure AI
- [x] 4.1 Import a DXF plan and detect the grid (LINE, LWPOLYLINE, POLYLINE, CIRCLE, blocks; $INSUNITS)
- [x] 4.1b PDF / photo of a plan: on-device vision (adaptive threshold, morphology, blob detection), scale from one span
- [x] 4.2 Propose a column and beam layout for the user to edit (tap to remove columns; beams re-proposed)
- [x] 4.3 Load take-down and preliminary sizing of every member (slab lx/32, beam span/12, columns Cl. 39.3)
- [x] 4.4 Export the design summary and quantities (CSV); 3D model straight into Studio
- [x] 4.4b Hand-over to EstimateX / JAUEstimate: BOQ .xlsx (Measurement + Abstract with live formulas, bordered) and JSON (schema structura-ai/boq-v1) with the user's SOR codes and rates

## Phase 5 — STAAD workflow parity (CEE 330L)
- [x] 5.1 Truss / tension-only members, roller & spring supports, 2D plane mode, point moments
- [x] 5.2 Six-component STATIC CHECK
- [x] 5.3 Parametric sections (T, I, channel, circle, triangle, angle) with Iω; ISA / JIS angles; ACI Ec
- [x] 5.4 Translational repeat, Fink and Pratt truss generators
- [x] 5.5 IS 1893 equivalent static and IS 875-3 wind load generators
- [x] 5.6 CEE 330L benchmark suite B1–B4 (CI) + Studio templates
- [x] 5.7 Inclined supports (support axes rotated, reactions reported globally)
- [x] 5.8 Non-uniform (Vlasov) warping torsion: warping restrained/free per member end, exact torsional stiffness, bimoment / St Venant / warping torque output
- [x] 5.9 Imperial ↔ SI unit converter (Loads screen); benchmarks convert at the boundary
- [x] 5.10 IS 800 single-angle members: tension Cl. 6.2 / 6.3.3 (α·An·fu/γm1), compression Cl. 7.5.1.2 (λe, Table 12), Table 2 classification; angles in Studio steel checks; Steel screen "Single angle"
- [x] 5.11 IS 1893 accidental eccentricity ±5 % (Cl. 7.8.2) and EX / EY cases with 100 % + 30 % combinations (Cl. 6.3.4.1, 25 combinations)
- [x] 5.12 Warping continuity across joints: 7th (warping) DOF at nodes where member ends are marked "continuous", exact Vlasov [φ, θ] member matrix (split cantilever = single-member closed form to 1e-15)
- [x] 5.13 Imperial input in the Studio editors (ft, kip, kip/ft, psf; node inspector in in / kip / kip·ft), stored in SI
- [x] 5.14 Block shear for angles, IS 800 Cl. 6.4.1 (one bolt line)

## Phase 6 — Projects, roofs, walls, PDF
- [x] 6.1 Project start screen: new / open / continue / delete; autosave of model and design inputs
- [x] 6.2 Roof truss analysis and design (Fink, Pratt, Howe, Fan; angles; purlins)
- [x] 6.3 Self-supported corrugated arch roof with custom profile
- [x] 6.4 Walls: masonry (IS 1905), RCC (IS 456 Cl. 32), compound wall
- [x] 6.5 Cantilever retaining wall
- [x] 6.6 PDF reports (design modules and frame analysis)
- [x] 6.7 Counterfort retaining wall (continuous stem/heel slabs, T-beam counterfort, ties); double-angle truss members (IS 800 Cl. 7.5.2, tack spacing Cl. 7.8.1); gusset / bolt joint sketches in the PDF and a DXF detail drawing
- [x] 6.8 Gusset Whitmore yield and block shear (IS 800 Cl. 6.2, 6.4.1); welded truss connections (Cl. 10.5.7); counterfort weight net of displaced fill
- [x] 6.9 Workbench viewport: build-in and idle-spin animation, animated deflected shape after Analyse, view / combination chips, load-case filter, one-finger rotate, two-finger pan + zoom, double-tap fit
- [x] 6.10 Animated 3D scene in every design module (core/viz Scene3D, WallScenes, RoofScenes; app SceneView): staged construction build-up, reinforcement, earth / base pressure, loads, dimensions; X-ray, layer toggles, section / plan views, full screen
- [x] 6.11 Workbench: live auto-analysis with the animated deflected shape; Draw mode in the main space (Select, Node, Member, ½ Split, Fix, Pin, Load, UDL, Delete, undo / redo, work plane XY / XZ / YZ, snap 0.5–3 m)

