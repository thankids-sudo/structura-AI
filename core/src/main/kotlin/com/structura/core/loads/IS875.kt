package com.structura.core.loads

/**
 * IS 875 Part 1 (dead loads) and Part 2 (imposed loads) helpers. Unit weights marked "typical" vary with the
 * product — use the manufacturer's value where known. Concrete weights follow IS 456 Cl. 19.2.1.
 */
object IS875 {
    data class UnitWeight(val material: String, val kNm3: Double, val source: String)

    val unitWeights = listOf(
        UnitWeight("Reinforced cement concrete", 25.0, "IS 456 Cl. 19.2.1"),
        UnitWeight("Plain cement concrete", 24.0, "IS 456 Cl. 19.2.1"),
        UnitWeight("Brick masonry (burnt clay)", 18.85, "IS 875-1 Table 1 (≈ 1920 kg/m³)"),
        UnitWeight("Stone masonry (granite / trap)", 23.55, "IS 875-1 Table 1 (typical)"),
        UnitWeight("Solid concrete block masonry", 22.0, "typical"),
        UnitWeight("Hollow concrete block masonry", 15.0, "typical — check product"),
        UnitWeight("AAC block masonry", 6.5, "typical — check product (IS 2185-3)"),
        UnitWeight("Cement plaster / mortar", 20.4, "IS 875-1 Table 1"),
        UnitWeight("Lime plaster", 17.25, "IS 875-1 Table 1"),
        UnitWeight("Marble / granite stone flooring", 26.7, "IS 875-1 Table 1 (typical)"),
        UnitWeight("Structural steel", 78.5, "IS 800 Cl. 2.2.4.1"),
        UnitWeight("Water", 9.81, "—"),
        UnitWeight("Earth fill (compacted)", 18.0, "typical"),
    )

    data class Occupancy(val use: String, val udl: Double, val note: String = "")

    /** IS 875 Part 2 Table 1 (selected) and Table 2 (roofs), kN/m². */
    val imposed = listOf(
        Occupancy("Residential — rooms, kitchens, toilets", 2.0),
        Occupancy("Residential — corridors, stairs, balconies", 3.0),
        Occupancy("Office — with separate storage", 2.5),
        Occupancy("Office — without separate storage", 4.0),
        Occupancy("Educational — classrooms", 3.0),
        Occupancy("Educational — corridors, stairs", 4.0),
        Occupancy("Laboratories (light)", 3.0, "heavy equipment: actual"),
        Occupancy("Hospital — wards, rooms", 2.0),
        Occupancy("Hospital — OT, X-ray, corridors", 3.0),
        Occupancy("Assembly — fixed seats", 4.0),
        Occupancy("Assembly — without fixed seats", 5.0),
        Occupancy("Library — reading rooms", 3.0),
        Occupancy("Library — stack rooms", 6.0, "per 2.2 m stack height, min"),
        Occupancy("Stores / godowns (light)", 5.0, "min — actual stacking load governs"),
        Occupancy("Garage — light vehicles", 2.5),
        Occupancy("Roof — flat, access provided", 1.5, "Table 2"),
        Occupancy("Roof — flat, no access", 0.75, "Table 2"),
    )

    /** Live-load reduction for columns / foundations by number of floors carried, incl. roof (IS 875-2 Cl. 3.2.1, Table 3). */
    fun reduction(floorsCarried: Int): Double = when {
        floorsCarried <= 1 -> 0.0
        floorsCarried == 2 -> 0.10
        floorsCarried == 3 -> 0.20
        floorsCarried == 4 -> 0.30
        floorsCarried <= 10 -> 0.40
        else -> 0.50
    }

    /** Partition allowance (IS 875-2 Cl. 3.1.2): ≥ 1/3 of the partition weight per metre run, but ≥ 1.0 kN/m². */
    fun partitionAllowance(wallKNperM: Double) = maxOf(1.0, wallKNperM / 3)

    /** Wall line load (kN/m): masonry t × γ × h plus 12 mm plaster both faces (20.4 kN/m³). */
    fun wallLoad(thickness: Double, height: Double, gamma: Double = 18.85, plasterEachFace: Double = 0.012) =
        thickness * gamma * height + 2 * plasterEachFace * 20.4 * height

    /** Slab dead load (kN/m²): RCC t × 25 + floor finish + ceiling plaster. */
    fun slabDead(thickness: Double, finish: Double = 1.0, ceilingPlaster: Double = 0.012) = 25 * thickness + finish + ceilingPlaster * 20.4

    /** IS 456 Table 18 limit-state factors for display. */
    val table18 = listOf(
        "DL + LL" to "1.5 DL + 1.5 LL",
        "DL + WL / EL" to "1.5 DL ± 1.5 WL;  0.9 DL ± 1.5 WL",
        "DL + LL + WL / EL" to "1.2 DL + 1.2 LL ± 1.2 WL",
        "Serviceability" to "1.0 DL + 1.0 LL;  1.0 DL + 0.8 LL ± 0.8 WL",
    )
}
