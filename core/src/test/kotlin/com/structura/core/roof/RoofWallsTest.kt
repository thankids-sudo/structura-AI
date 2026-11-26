package com.structura.core.roof

import com.structura.core.rcc.Status
import com.structura.core.walls.RetainingWall
import com.structura.core.walls.Walls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RoofWallsTest {
    @Test
    fun roofLive_is875() {
        assertEquals(0.75, RoofTruss.roofLive(8.0), 1e-12)
        assertEquals(0.75 - 0.02 * 16.565, RoofTruss.roofLive(26.565), 1e-9)
        assertEquals(0.40, RoofTruss.roofLive(40.0), 1e-12)
        assertEquals(0.75 - 0.52 * 0.04, ArchRoof.curvedLive(2.4, 12.0), 1e-12)
    }

    @Test
    fun trusses_allTypes_designed() {
        for (t in RoofTruss.Type.entries) {
            val r = RoofTruss.design(RoofTruss.Input(type = t, span = 12.0, rise = 3.0, panels = 6))
            println("[Truss ${t.name}] ${r.groups.joinToString { "${it.name}: ${it.section} η ${"%.2f".format(it.result.utilisation)} (T ${"%.1f".format(it.tension)}, C ${"%.1f".format(it.compression)})" }}; purlin ${r.purlin}")
            assertTrue(r.groups.all { it.result.utilisation <= 1.0 })
            // gravity: bottom chord in tension, top chord in compression
            assertTrue(r.groups.first { it.name == "Bottom chord" }.tension > 0)
            assertTrue(r.groups.first { it.name == "Top chord" }.compression > 0)
            assertTrue(r.report.blocks.isNotEmpty())
        }
    }

    @Test
    fun truss_panelLoads_sumToRoofLoad() {
        val inp = RoofTruss.Input(type = RoofTruss.Type.PRATT, span = 10.0, rise = 2.5, panels = 4)
        val r = RoofTruss.design(inp)
        val ll = r.model.nodalLoads.filter { it.case == "LL" }.sumOf { -it.values[2] }
        assertEquals(r.ll * inp.span * inp.spacing, ll, 1e-9)              // live load on plan area
        val res = com.structura.core.fem.FrameSolver.analyse(r.model)
        assertTrue(res.equilibriumError.all { abs(it) < 1e-6 })
    }

    @Test
    fun archRoof_profileAndChecks() {
        val p = ArchRoof.Profile(depth = 60.0, pitch = 200.0, crest = 40.0, trough = 60.0, t = 1.0)
        val g = ArchRoof.props(p)
        val n = 1000.0 / 200
        assertEquals((40 + 60 + 2 * p.webLen) * 1.0 * n, g.area, 1e-9)
        // effective width: slender crest is reduced
        assertTrue(ArchRoof.effectiveWidth(200.0, 0.6, 350.0, 2e5) < 200.0)
        assertEquals(40.0, ArchRoof.effectiveWidth(40.0, 1.0, 350.0, 2e5), 1e-12)
        val r = ArchRoof.design(ArchRoof.Input(span = 10.0, rise = 2.5, profile = p.copy(t = 1.2)))
        println("[Arch] λcr ${"%.2f".format(r.lambdaCr)}, η ${"%.3f".format(r.maxUtil)}, thrust ${"%.2f".format(r.thrust)} kN/m, δ ${"%.1f".format(r.deflection)} mm")
        assertTrue(r.lambdaCr > 1.0 && r.thrust > 0 && r.maxUtil > 0)
    }

    @Test
    fun retainingWall_autoSizedIsStable() {
        val r = RetainingWall.design(RetainingWall.Input(height = 4.5))
        println("[Retaining] B = ${r.st.b}, toe ${r.st.t}, e ${"%.3f".format(r.st.e)}, q ${"%.1f".format(r.st.qmax)}/${"%.1f".format(r.st.qmin)}")
        assertTrue(r.checks.filter { it.item in listOf("Overturning", "Sliding", "Eccentricity", "Base pressure") }.all { it.status == Status.PASS })
        // hand check of Pa
        val ka = RetainingWall.ka(30.0)
        assertEquals(1.0 / 3, ka, 1e-12)
        assertEquals(0.5 * ka * 18 * 4.5 * 4.5, r.st.pa, 1e-9)
    }

    @Test
    fun walls() {
        assertEquals(0.84, Walls.ksSlender(12.0), 1e-12)
        assertEquals((0.89 + 0.84) / 2, Walls.ksSlender(11.0), 1e-12)
        val m = Walls.masonry(Walls.Masonry(height = 3.0, thickness = 230.0, load = 50.0))
        assertTrue(m.blocks.isNotEmpty())
        val rc = Walls.rccWall(Walls.RccWall(height = 3.0, thickness = 200.0, pu = 600.0, fck = 25.0))
        val puw = 0.3 * (200 - 1.2 * 10 - 2 * (2250.0 * 2250 / (2500 * 200))) * 25
        assertTrue(rc.text().contains(String.format(java.util.Locale.US, "%.1f", puw)))
        val c = Walls.compound(Walls.Compound())
        assertTrue(c.text().contains("Overturning"))
    }
}
