# Structura AI: build roadmap

Each step is built, tested and committed separately.

## Phase 1: RCC Design (IS 456)
- [x] 1.0 Project scaffold: `:core` engine + `:app` Compose UI, home with all 4 modules
- [x] 1.1 Rectangular beam: singly/doubly, shear, deflection, detailing checks, shareable report
- [ ] 1.2 Two-layer bars with automatic d recalculation; flanged (T/L) beams per Cl. 23.1.2 & Annex G-2
- [ ] 1.3 Slabs: one-way, two-way (Annex D, Table 26/27)
- [ ] 1.4 Columns: axial + uniaxial/biaxial (Cl. 39, SP 16 interaction charts), slenderness (Cl. 25)
- [ ] 1.5 Isolated footings: bearing, one-way & punching shear, flexure (Cl. 34)
- [ ] 1.6 Loads: IS 875 Part 1/2 dead & imposed, load combinations (Table 18)
- [ ] 1.7 Bar bending schedule export (Excel / PDF)

## Phase 2: Steel Design (IS 800:2007)
- [ ] 2.1 Section database: IS 808 / IS 4923 (reuse the CivilStructuralToolkit JSON, ~1000 sections)
- [ ] 2.2 Laterally supported / unsupported beams (Cl. 8.2), shear (Cl. 8.4), deflection (Table 6)
- [ ] 2.3 Compression members (Cl. 7.1, buckling curves a–d)
- [ ] 2.4 Tension members (Cl. 6); bolted & welded connections (Cl. 10)

## Phase 3: Frame Analysis + AI
- [ ] 3.1 Continuous beam by the stiffness method, with SFD/BMD graphs
- [ ] 3.2 2D portal frames: member loads, supports, moment releases
- [ ] 3.3 Analysis results feed straight into the Phase 1/2 member design
- [ ] 3.4 AI explains results and sanity-checks them (load path, unusual ratios, missing combinations)

## Phase 4: Drawing → Structure AI
- [ ] 4.1 Import a plan (PDF/image/DXF) and detect the grid
- [ ] 4.2 Propose a column and beam layout for the user to edit
- [ ] 4.3 Load take-down and preliminary sizing of every member
- [ ] 4.4 Export the design summary and BOQ (link to EstimateX / JAUEstimate)
