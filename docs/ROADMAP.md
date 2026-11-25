# Structura AI: build roadmap

Each step is built, tested and committed separately.

## Phase 1: RCC Design (IS 456)
- [x] 1.0 Project scaffold: `:core` engine + `:app` Compose UI, home with all 4 modules
- [x] 1.1 Rectangular beam: singly/doubly, shear, deflection, detailing checks, shareable report
- [x] 1.1a UCS-based beam detail drawing (elevation + section A-A) with DXF R12 export
- [x] 1.2 Two-layer bars with automatic d recalculation; flanged (T/L) beams per Cl. 23.1.2 & Annex G-2
- [x] 1.3 Slabs: one-way, cantilever, two-way simply supported (Table 27)
- [ ] 1.3b Two-way restrained slabs (Table 26 coefficients)
- [x] 1.4 Columns: axial + uniaxial/biaxial (Cl. 39, SP 16 interaction charts), slenderness (Cl. 25)
- [x] 1.5 Isolated footings: bearing, one-way & punching shear, flexure (Cl. 34)
- [ ] 1.6 Loads: IS 875 Part 1/2 dead & imposed, load combinations (Table 18)
- [x] 1.7 Bar bending schedule export (CSV / Excel)

## Phase 2: Steel Design (IS 800:2007)
- [x] 2.1 Section library: ISMB / ISHB / ISMC / SHS / RHS / CHS / custom I (computed from IS 808 nominal dimensions)
- [ ] 2.1b Full IS 808 table import (angles, ISLB, ISWB, ~1000 sections with tabulated properties)
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
- [ ] 3.4 AI explains results and sanity-checks them (load path, unusual ratios, missing combinations)

## Phase 4: Drawing → Structure AI
- [ ] 4.1 Import a plan (PDF/image/DXF) and detect the grid
- [ ] 4.2 Propose a column and beam layout for the user to edit
- [ ] 4.3 Load take-down and preliminary sizing of every member
- [ ] 4.4 Export the design summary and BOQ (link to EstimateX / JAUEstimate)
