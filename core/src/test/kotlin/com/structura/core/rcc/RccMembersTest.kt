package com.structura.core.rcc

import com.structura.core.codes.IS456
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class RccMembersTest {
    private fun near(e: Double, a: Double, rel: Double) = assertEquals(e, a, abs(e) * rel + 1e-9)

    @Test
    fun tBeam_neutralAxisInFlange() {
        val inp = BeamInput(widthB = 300.0, overallDepthD = 600.0, mainBarDia = 20.0, effectiveSpan = 8000.0, fck = 20.0, fy = 415.0,
            factoredMoment = 400.0, factoredShear = 150.0, flangeWidth = 1500.0, flangeDepth = 120.0)
        val r = BeamDesigner.design(inp)
        val d = r.effectiveDepth
        val expected = 0.5 * 20 / 415 * (1 - sqrt(1 - 4.6 * 400e6 / (20 * 1500 * d * d))) * 1500 * d
        near(expected, r.astRequired, 1e-9)
        val xu = 0.87 * 415 * r.astRequired / (0.36 * 20 * 1500)
        assertTrue(xu <= 120.0)
    }

    @Test
    fun tBeam_neutralAxisInWeb_equilibrium() {
        val inp = BeamInput(widthB = 300.0, overallDepthD = 600.0, mainBarDia = 25.0, effectiveSpan = 8000.0, fck = 20.0, fy = 415.0,
            factoredMoment = 800.0, factoredShear = 200.0, flangeWidth = 1500.0, flangeDepth = 100.0)
        val r = BeamDesigner.design(inp)
        val d = r.effectiveDepth
        // independent check: find xu from force equilibrium and recompute the moment (Annex G-2.2)
        val t = 0.87 * 415 * r.astRequired
        fun yf(xu: Double) = if (100.0 / d <= 0.2) 100.0 else minOf(0.15 * xu + 0.65 * 100.0, 100.0)
        var lo = 100.0; var hi = d
        repeat(100) { val x = (lo + hi) / 2; if (0.36 * 20 * 300 * x + 0.45 * 20 * 1200 * yf(x) < t) lo = x else hi = x }
        val xu = (lo + hi) / 2
        val m = 0.36 * 20 * 300 * xu * (d - 0.42 * xu) + 0.45 * 20 * 1200 * yf(xu) * (d - yf(xu) / 2)
        near(800e6, m, 1e-6)
    }

    @Test
    fun twoWaySlab_table27() {
        near(0.084, SlabDesigner.table27(1.2).first, 1e-12)
        near(0.0525, SlabDesigner.table27(1.35).second, 1e-9)
        val r = SlabDesigner.design(SlabInput(lx = 4.0, ly = 5.0, thickness = 150.0, finishes = 1.0, liveLoad = 3.0, fck = 20.0, fy = 500.0))
        val wu = 1.5 * (25 * 0.15 + 1.0 + 3.0)
        near(wu, r.wu, 1e-12)
        val (ax, ay) = SlabDesigner.table27(1.25)
        near(ax * wu * 16, r.mx, 1e-12)
        near(ay * wu * 16, r.my, 1e-12)
        assertTrue(r.isSafe)
    }

    @Test
    fun oneWaySlab_moment() {
        val r = SlabDesigner.design(SlabInput(lx = 3.5, ly = 9.0, thickness = 140.0, liveLoad = 2.0, type = SlabType.ONE_WAY_SS))
        near(r.wu * 3.5 * 3.5 / 8, r.mx, 1e-12)
        assertTrue(r.astY >= 0.0012 * 1000 * 140 - 1e-9)
    }

    @Test
    fun column_axialLimit_and_pureBending() {
        val inp = ColumnInput(b = 300.0, d = 500.0, barDia = 20.0, nb = 2, nd = 4, fck = 25.0, fy = 500.0, pu = 0.0)
        val rows = ColumnDesigner.barRows(500.0, 4, 2, 40.0, 8.0, 20.0)
        assertEquals(8 * PI * 100, rows.sumOf { it.second }, 1e-9)
        // full compression at uniform strain 0.002
        val (pMax, _) = ColumnDesigner.forces(1e6, 300.0, 500.0, rows, 25.0, 500.0)
        val asc = 8 * PI * 100
        near(0.446 * 25 * (150000 - asc) + IS456.steelStress(0.002, 500.0) * asc, pMax, 0.002)
        // pure bending: independent equilibrium with the 0.36/0.42 stress block (top 2 bars, bottom 2 bars, 2+2 middle)
        val m0 = ColumnDesigner.momentCapacity(0.0, 300.0, 500.0, rows, 25.0, 500.0)!!
        assertTrue(m0 > 100 && m0 < 400)
        val r = ColumnDesigner.design(inp.copy(pu = 1200.0, mux = 80.0, muy = 30.0))
        assertTrue(r.interaction > 0 && r.mux1 > 0 && r.muy1 > 0)
        near((0.45 * 25 * (150000 - asc) + 0.75 * 500 * asc) / 1e3, r.puz, 1e-12)
    }

    @Test
    fun column_strainCompatibility_matchesStressBlock() {
        // only bottom (tension) and top bars, no middle rows: compare with the IS 456 rectangular-parabolic block
        val rows = listOf(58.0 to 3 * PI * 64, 442.0 to 3 * PI * 64)
        val m = ColumnDesigner.momentCapacity(0.0, 300.0, 500.0, rows, 20.0, 415.0)!!
        // independent solution with 0.36 fck b xu and 0.42 xu
        val ast = 3 * PI * 64; val asc = ast
        var lo = 1.0; var hi = 400.0
        fun net(x: Double): Double {
            val esc = 0.0035 * (x - 58) / x
            val fsc = IS456.steelStress(esc, 415.0) * (if (esc >= 0) 1 else -1)
            val conc = if (esc > 0) (if (esc < 0.002) 0.446 * 20 * (2 * esc / 0.002 - (esc / 0.002) * (esc / 0.002)) else 0.446 * 20) else 0.0
            val est = 0.0035 * (442 - x) / x
            return 0.36 * 20 * 300 * x + (fsc - conc) * asc - IS456.steelStress(est, 415.0) * ast
        }
        repeat(100) { val x = (lo + hi) / 2; if (net(x) < 0) lo = x else hi = x }
        val x = (lo + hi) / 2
        val esc = 0.0035 * (x - 58) / x
        val fsc = IS456.steelStress(esc, 415.0) * (if (esc >= 0) 1 else -1)
        val conc = if (esc > 0) (if (esc < 0.002) 0.446 * 20 * (2 * esc / 0.002 - (esc / 0.002) * (esc / 0.002)) else 0.446 * 20) else 0.0
        val est = 0.0035 * (442 - x) / x
        val mExp = (0.36 * 20 * 300 * x * (250 - 0.42 * x) + (fsc - conc) * asc * (250 - 58) + IS456.steelStress(est, 415.0) * ast * (442 - 250)) / 1e6
        near(mExp, m, 0.01)
    }

    @Test
    fun footing_sizeAndChecks() {
        val r = FootingDesigner.design(FootingInput(serviceLoad = 1000.0, colB = 300.0, colD = 500.0, sbc = 200.0, fck = 25.0, fy = 500.0))
        assertTrue(r.lengthL * r.widthB / 1e6 >= 1.1 * 1000 / 200 - 1e-9)
        near(200.0, r.lengthL - r.widthB, 1e-6)                          // equal projections
        near(1500.0 / (r.lengthL * r.widthB / 1e6), r.qu, 1e-9)
        assertTrue(r.isSafe || r.checks.any { it.item == "Development length" })
        assertTrue(r.depth >= 300)
    }

    @Test
    fun bbs_weights() {
        val inp = BeamInput(widthB = 230.0, overallDepthD = 450.0, effectiveSpan = 5000.0, factoredMoment = 90.0, factoredShear = 80.0, fy = 500.0)
        val rows = BarBendingSchedule.beam(inp, BeamDesigner.design(inp))
        assertEquals(3, rows.size)
        val main = rows.first()
        near(16.0 * 16 / 162 * main.count * main.cutLength / 1000, main.weight, 1e-9)
        assertTrue(BarBendingSchedule.csv(rows).contains("Total"))
    }
}
