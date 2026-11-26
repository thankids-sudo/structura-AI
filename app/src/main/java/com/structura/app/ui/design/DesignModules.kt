package com.structura.app.ui.design

import com.structura.core.fem.FrameModel
import com.structura.core.report.ReportDoc
import com.structura.core.roof.ArchRoof
import com.structura.core.roof.RoofTruss
import com.structura.core.steel.SteelGrade
import com.structura.core.drawing.DxfWriter
import com.structura.core.viz.RoofScenes
import com.structura.core.viz.Scene3D
import com.structura.core.viz.WallScenes
import com.structura.core.fem.LateralLoads
import com.structura.core.walls.CounterfortWall
import com.structura.core.walls.RetainingWall
import com.structura.core.walls.Walls

/** One input of a design module: free number, or a choice among [options] (the stored value is the option text). */
class FieldSpec(val key: String, val label: String, val default: String, val options: List<String>? = null, val group: String = "")

class DesignModule(
    val id: String, val title: String, val codes: String, val fields: List<FieldSpec>,
    val dxf: ((Map<String, String>) -> String)? = null,
    /** Animated 3D scene of the designed element (built from the same inputs). */
    val scene: ((Map<String, String>) -> Scene3D?)? = null,
    val run: (Map<String, String>) -> Pair<ReportDoc, FrameModel?>,
)

/** Roof and wall design modules; inputs are kept per project (ProjectStore.designInputs). */
object DesignModules {
    private fun Map<String, String>.d(k: String, def: Double) = this[k]?.replace(',', '.')?.toDoubleOrNull() ?: def
    private fun Map<String, String>.dn(k: String) = this[k]?.replace(',', '.')?.toDoubleOrNull()
    private val terrains = listOf("1", "2", "3", "4")

    private fun truss(v: Map<String, String>) = RoofTruss.design(RoofTruss.Input(
                type = RoofTruss.Type.entries.firstOrNull { it.label == v["type"] } ?: RoofTruss.Type.FINK,
                span = v.d("span", 12.0), rise = v.d("rise", 3.0), panels = v.d("panels", 6.0).toInt(), spacing = v.d("spacing", 4.0),
                purlinSpacing = v.d("purlin", 1.4), sheeting = v.d("sheeting", 0.15), services = v.d("services", 0.10), roofLive = v.dn("ll"),
                vb = v.d("vb", 44.0), terrain = v.d("terrain", 2.0).toInt(), eavesHeight = v.d("eaves", 6.0),
                cpeWindward = v.d("cpew", -0.8), cpeLeeward = v.d("cpel", -0.4), cpi = v.d("cpi", 0.2),
                grade = if (v["grade"] == "E350") SteelGrade.E350 else SteelGrade.E250, bolts = v.d("bolts", 2.0).toInt(),
                doubleAngles = v["members"] == "Double angle", gusset = v.d("gusset", 8.0), boltDia = v.d("boltDia", 16.0), welded = v["conn"] == "Welded", weldSize = v.d("weld", 0.0),
            ))

    private fun arch(v: Map<String, String>) = ArchRoof.design(ArchRoof.Input(
                span = v.d("span", 12.0), rise = v.d("rise", 3.0),
                profile = ArchRoof.Profile(v.d("depth", 60.0), v.d("pitch", 200.0), v.d("crest", 40.0), v.d("trough", 60.0), v.d("t", 1.0)),
                fy = v.d("fy", 350.0), extraDead = v.d("dead", 0.05), roofLive = v.dn("ll"), vb = v.d("vb", 44.0), terrain = v.d("terrain", 2.0).toInt(),
                height = v.d("height", 4.0), cpe = Triple(v.d("c1", -0.6), v.d("c2", -0.8), v.d("c3", -0.5)), cpi = v.d("cpi", 0.2),
            ))

    private fun retaining(v: Map<String, String>) = RetainingWall.design(RetainingWall.Input(height = v.d("h", 4.0), gammaSoil = v.d("gamma", 18.0), phi = v.d("phi", 30.0), surcharge = v.d("q", 10.0),
                sbc = v.d("sbc", 150.0), mu = v.d("mu", 0.5), fck = v.d("fck", 25.0), fy = v.d("fy", 500.0), bar = v.d("bar", 16.0),
                stemTop = v.d("stemTop", 0.2), stemBottom = v.d("stemBot", 0.0), baseThickness = v.d("base", 0.0), baseWidth = v.d("b", 0.0), toe = v.d("toe", 0.0),
                autoSize = v.dn("b") == null))

    private fun counterfort(v: Map<String, String>) = CounterfortWall.design(CounterfortWall.Input(height = v.d("h", 8.0), spacing = v.d("l", 3.0), counterfort = v.d("tc", 0.4), stem = v.d("stem", 0.0),
                base = v.d("base", 0.0), baseWidth = v.d("b", 0.0), toe = v.d("toe", 0.0), gammaSoil = v.d("gamma", 18.0), phi = v.d("phi", 30.0),
                surcharge = v.d("q", 10.0), sbc = v.d("sbc", 200.0), mu = v.d("mu", 0.5), fck = v.d("fck", 25.0), fy = v.d("fy", 500.0), bar = v.d("bar", 16.0)))

    val all: List<DesignModule> = listOf(
        DesignModule("roof_truss", "Roof truss", "IS 800 · IS 875 Parts 1–3", listOf(
            FieldSpec("type", "Truss type", "Fink (W)", RoofTruss.Type.entries.map { it.label }, "Geometry"),
            FieldSpec("span", "Span (m)", "12", group = "Geometry"), FieldSpec("rise", "Rise (m)", "3", group = "Geometry"),
            FieldSpec("panels", "Panels (Pratt / Howe / Fan)", "6", group = "Geometry"), FieldSpec("spacing", "Truss spacing (m)", "4", group = "Geometry"),
            FieldSpec("purlin", "Purlin spacing on slope (m)", "1.4", group = "Geometry"),
            FieldSpec("sheeting", "Sheeting (kN/m² slope)", "0.15", group = "Loads"), FieldSpec("services", "Services (kN/m² plan)", "0.10", group = "Loads"),
            FieldSpec("ll", "Roof live load (blank = IS 875-2)", "", group = "Loads"),
            FieldSpec("vb", "Basic wind speed Vb (m/s)", "44", group = "Wind"), FieldSpec("terrain", "Terrain category", "2", terrains, "Wind"),
            FieldSpec("eaves", "Eaves height (m)", "6", group = "Wind"), FieldSpec("cpew", "Cpe windward", "-0.8", group = "Wind"),
            FieldSpec("cpel", "Cpe leeward", "-0.4", group = "Wind"), FieldSpec("cpi", "Cpi (±)", "0.2", group = "Wind"),
            FieldSpec("grade", "Steel", "E250", listOf("E250", "E350"), "Steel"), FieldSpec("bolts", "Bolts per end", "2", group = "Steel"),
            FieldSpec("members", "Members", "Single angle", listOf("Single angle", "Double angle"), "Connections"),
            FieldSpec("gusset", "Gusset thickness (mm)", "8", group = "Connections"), FieldSpec("boltDia", "Bolt φ (mm)", "16", listOf("12", "16", "20", "24"), "Connections"),
            FieldSpec("conn", "Connection", "Bolted", listOf("Bolted", "Welded"), "Connections"), FieldSpec("weld", "Fillet weld size (blank = auto)", "", group = "Connections"),
        ), dxf = { v -> DxfWriter.write(truss(v).detail.drawing) }, scene = { v ->
            val r = truss(v)
            RoofScenes.truss(r.model, v.d("spacing", 4.0), v.d("purlin", 1.4), v.d("eaves", 6.0), r.model.members.associate { it.id to it.section.name },
                gravity = 1.5 * (v.d("sheeting", 0.15) + v.d("services", 0.10) + r.ll), uplift = r.pd * (kotlin.math.abs(v.d("cpew", -0.8)) + v.d("cpi", 0.2)))
        }) { v ->
            val r = truss(v)
            r.report to r.model
        },
        DesignModule("arch_roof", "Self-supported corrugated roof", "IS 801 · IS 800 · IS 875 Parts 2–3", listOf(
            FieldSpec("span", "Span (m)", "12", group = "Arch"), FieldSpec("rise", "Rise (m)", "3", group = "Arch"),
            FieldSpec("depth", "Profile depth (mm)", "60", group = "Custom profile"), FieldSpec("pitch", "Pitch (mm)", "200", group = "Custom profile"),
            FieldSpec("crest", "Crest width (mm)", "40", group = "Custom profile"), FieldSpec("trough", "Trough width (mm)", "60", group = "Custom profile"),
            FieldSpec("t", "Thickness (mm)", "1.0", group = "Custom profile"), FieldSpec("fy", "fy (MPa)", "350", group = "Custom profile"),
            FieldSpec("dead", "Extra dead (kN/m²)", "0.05", group = "Loads"), FieldSpec("ll", "Roof live load (blank = IS 875-2)", "", group = "Loads"),
            FieldSpec("vb", "Vb (m/s)", "44", group = "Wind"), FieldSpec("terrain", "Terrain category", "2", terrains, "Wind"),
            FieldSpec("height", "Springing height (m)", "4", group = "Wind"),
            FieldSpec("c1", "Cpe windward third", "-0.6", group = "Wind"), FieldSpec("c2", "Cpe centre third", "-0.8", group = "Wind"),
            FieldSpec("c3", "Cpe leeward third", "-0.5", group = "Wind"), FieldSpec("cpi", "Cpi (±)", "0.2", group = "Wind"),
        ), scene = { v ->
            val r = arch(v)
            RoofScenes.arch(r.model, 6.0, v.d("pitch", 200.0) / 1000, v.d("depth", 60.0) / 1000, v.d("height", 4.0), 1.5 * (v.d("dead", 0.05) + (v.dn("ll") ?: 0.4)),
                "profile ${v["depth"] ?: "60"} deep @ ${v["pitch"] ?: "200"}, t ${v["t"] ?: "1.0"} mm")
        }) { v ->
            val r = arch(v)
            r.report to r.model
        },
        DesignModule("retaining_wall", "Cantilever retaining wall", "IS 456:2000 Cl. 20, 38, 40", listOf(
            FieldSpec("h", "Height incl. base (m)", "4", group = "Geometry"), FieldSpec("stemTop", "Stem top (m)", "0.2", group = "Geometry"),
            FieldSpec("stemBot", "Stem bottom (blank = auto)", "", group = "Geometry"), FieldSpec("base", "Base thickness (blank = auto)", "", group = "Geometry"),
            FieldSpec("b", "Base width (blank = auto-size)", "", group = "Geometry"), FieldSpec("toe", "Toe (blank = B/3)", "", group = "Geometry"),
            FieldSpec("gamma", "Soil γ (kN/m³)", "18", group = "Soil"), FieldSpec("phi", "φ (°)", "30", group = "Soil"),
            FieldSpec("q", "Surcharge (kN/m²)", "10", group = "Soil"), FieldSpec("sbc", "SBC (kN/m²)", "150", group = "Soil"), FieldSpec("mu", "Base friction μ", "0.5", group = "Soil"),
            FieldSpec("fck", "fck (MPa)", "25", group = "Materials"), FieldSpec("fy", "fy (MPa)", "500", group = "Materials"), FieldSpec("bar", "Bar φ (mm)", "16", group = "Materials"),
        ), scene = { v -> retaining(v).scene }) { v ->
            val r = retaining(v)
            r.report to null
        },
        DesignModule("counterfort_wall", "Counterfort retaining wall", "IS 456:2000 Cl. 20, 26, 38, 40", listOf(
            FieldSpec("h", "Height incl. base (m)", "8", group = "Geometry"), FieldSpec("l", "Counterfort spacing (m)", "3", group = "Geometry"),
            FieldSpec("tc", "Counterfort thickness (m)", "0.4", group = "Geometry"), FieldSpec("stem", "Stem thickness (blank = auto)", "", group = "Geometry"),
            FieldSpec("base", "Base thickness (blank = auto)", "", group = "Geometry"), FieldSpec("b", "Base width (blank = auto-size)", "", group = "Geometry"),
            FieldSpec("toe", "Toe (blank = B/4)", "", group = "Geometry"),
            FieldSpec("gamma", "Soil γ (kN/m³)", "18", group = "Soil"), FieldSpec("phi", "φ (°)", "30", group = "Soil"),
            FieldSpec("q", "Surcharge (kN/m²)", "10", group = "Soil"), FieldSpec("sbc", "SBC (kN/m²)", "200", group = "Soil"), FieldSpec("mu", "Base friction μ", "0.5", group = "Soil"),
            FieldSpec("fck", "fck (MPa)", "25", group = "Materials"), FieldSpec("fy", "fy (MPa)", "500", group = "Materials"), FieldSpec("bar", "Bar φ (mm)", "16", group = "Materials"),
        ), scene = { v -> counterfort(v).scene }) { v -> counterfort(v).report to null },
        DesignModule("wall_masonry", "Load-bearing masonry wall", "IS 1905:1987", listOf(
            FieldSpec("h", "Storey height (m)", "3.0"), FieldSpec("t", "Thickness (mm)", "230"), FieldSpec("p", "Load at top (kN/m)", "60"),
            FieldSpec("e", "Eccentricity (mm)", "0"), FieldSpec("fb", "Basic compressive stress fb (MPa, Table 8)", "0.50"),
            FieldSpec("restraint", "End restraint", "Both ends (0.75H)", listOf("Both ends (0.75H)", "Lateral only (1.0H)", "Free top (2.0H)")),
            FieldSpec("kp", "Shape factor kp (Table 10)", "1.0"),
        ), scene = { v -> WallScenes.masonry(v.d("h", 3.0), v.d("t", 230.0), v.d("p", 60.0), title = "Load-bearing masonry wall — ${v["t"] ?: "230"} mm") }) { v ->
            Walls.masonry(Walls.Masonry(v.d("h", 3.0), v.d("t", 230.0), v.d("p", 60.0), v.d("e", 0.0), v.d("fb", 0.5),
                listOf("Both ends (0.75H)", "Lateral only (1.0H)", "Free top (2.0H)").indexOf(v["restraint"]).coerceAtLeast(0), v.d("kp", 1.0))) to null
        },
        DesignModule("wall_rcc", "RCC wall", "IS 456:2000 Cl. 32", listOf(
            FieldSpec("h", "Height (m)", "3.5"), FieldSpec("t", "Thickness (mm)", "200"), FieldSpec("pu", "Factored load Pu (kN/m)", "800"),
            FieldSpec("e", "Eccentricity (mm)", "0"), FieldSpec("fck", "fck (MPa)", "25"), FieldSpec("fy", "fy (MPa)", "500"),
            FieldSpec("r", "End restraint", "Restrained (0.75H)", listOf("Restrained (0.75H)", "Not restrained (1.0H)")),
        ), scene = { v ->
            val t = v.d("t", 200.0); val fy = v.d("fy", 500.0)
            val vMin = (if (fy >= 415) 0.0012 else 0.0015) * 1000 * t / 2; val hMin = (if (fy >= 415) 0.0020 else 0.0025) * 1000 * t / 2
            WallScenes.rccWall(v.d("h", 3.5), t, v.d("pu", 800.0), WallScenes.spacingFor(vMin, 10.0, minOf(3 * t, 450.0) / 1000),
                WallScenes.spacingFor(hMin, 10.0, minOf(3 * t, 450.0) / 1000), 10.0, 10.0)
        }) { v ->
            Walls.rccWall(Walls.RccWall(v.d("h", 3.5), v.d("t", 200.0), v.d("pu", 800.0), v.d("e", 0.0), v.d("fck", 25.0), v.d("fy", 500.0), v["r"] != "Not restrained (1.0H)")) to null
        },
        DesignModule("wall_compound", "Compound (boundary) wall", "IS 875-3 · IS 1905 · IS 456 Cl. 20", listOf(
            FieldSpec("h", "Wall height (m)", "2.0", group = "Wall"), FieldSpec("t", "Thickness (mm)", "230", group = "Wall"), FieldSpec("ft", "Masonry flexural tension (MPa)", "0.07", group = "Wall"),
            FieldSpec("vb", "Vb (m/s)", "44", group = "Wind"), FieldSpec("terrain", "Terrain category", "2", terrains, "Wind"), FieldSpec("cf", "Force coefficient Cf", "1.2", group = "Wind"),
            FieldSpec("fw", "Footing width (m)", "0.9", group = "Footing"), FieldSpec("fd", "Founding depth (m)", "0.9", group = "Footing"), FieldSpec("ftk", "Footing thickness (m)", "0.3", group = "Footing"),
            FieldSpec("sbc", "SBC (kN/m²)", "100", group = "Footing"), FieldSpec("mu", "μ", "0.5", group = "Footing"),
        ), scene = { v ->
            val h = v.d("h", 2.0); val vz = v.d("vb", 44.0) * LateralLoads.k2(h, v.d("terrain", 2.0).toInt())
            val f = v.d("cf", 1.2) * 0.81 * 0.6 * vz * vz / 1000 * h
            WallScenes.masonry(h, v.d("t", 230.0), 0.0, v.d("fw", 0.9), v.d("fd", 0.9), len = 6.0, title = "Compound wall — ${v["t"] ?: "230"} mm", wind = f)
        }) { v ->
            Walls.compound(Walls.Compound(height = v.d("h", 2.0), thickness = v.d("t", 230.0), vb = v.d("vb", 44.0), terrain = v.d("terrain", 2.0).toInt(), cf = v.d("cf", 1.2),
                footingWidth = v.d("fw", 0.9), footingDepth = v.d("fd", 0.9), footingThk = v.d("ftk", 0.3), sbc = v.d("sbc", 100.0), mu = v.d("mu", 0.5), ft = v.d("ft", 0.07))) to null
        },
    )

    fun get(id: String) = all.first { it.id == id }
}
