package com.structura.core.verification

import com.structura.core.fem.Benchmarks
import com.structura.core.fem.FrameSolver
import com.structura.core.fem.LateralLoads
import com.structura.core.fem.LoadCase
import com.structura.core.fem.LoadCombination
import com.structura.core.fem.Seismic
import com.structura.core.steel.AngleDesign
import com.structura.core.steel.SteelGrade
import com.structura.core.steel.SteelModelCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RemainingGapsTest {
    @Test
    fun angle_properties_and_checks() {
        val p = AngleDesign.props(60.0, 60.0, 6.0)
        assertEquals(p.ixx, p.iyy, 1e-6)
        assertEquals(p.ixx - abs(p.ixy), p.imin, 1e-6)                 // equal angle: Ivv = I − |Ixy|
        println("[Angle] 60×60×6: A = ${p.area}, rvv = ${"%.2f".format(p.rvv)} mm (IS 808 table 11.7 mm with root radius)")
        assertEquals(11.7, p.rvv, 0.4)
        val g = SteelGrade.E250
        val fixed2 = AngleDesign.check(60.0, 60.0, 6.0, g, 0.0, 50.0, 1500.0, bolts = 2, fixed = true)
        val hinged2 = AngleDesign.check(60.0, 60.0, 6.0, g, 0.0, 50.0, 1500.0, bolts = 2, fixed = false)
        val fixed1 = AngleDesign.check(60.0, 60.0, 6.0, g, 0.0, 50.0, 1500.0, bolts = 1, fixed = true)
        assertTrue(fixed2.capacity > hinged2.capacity && fixed2.capacity > fixed1.capacity)
        val t2 = AngleDesign.check(60.0, 60.0, 6.0, g, 100.0, 0.0, 1500.0, bolts = 2, holeDia = 18.0)
        val an = p.area - 18 * 6
        assertEquals(minOf(p.area * 250 / 1.1, 0.6 * an * 410 / 1.25) / 1000, t2.capacity, 1e-9)
        val t4 = AngleDesign.check(60.0, 60.0, 6.0, g, 100.0, 0.0, 1500.0, bolts = 4)
        assertTrue(t4.capacity > t2.capacity)
    }

    @Test
    fun finkTruss_angleMembersChecked() {
        val m = Benchmarks.finkTruss()
        val res = FrameSolver.analyseAll(m, LoadCombination.isLimitState(false))
        val checks = SteelModelCheck.check(m, res)
        assertEquals(m.members.size, checks.size)
        assertTrue(checks.all { c -> c.result.checks.any { it.item.startsWith("Compression") || it.item == "Tension" } })
        println("[Fink IS 800] max η = ${"%.3f".format(checks.maxOf { it.utilisation })}")
    }

    @Test
    fun accidentalEccentricity_and_100_30() {
        val m = Benchmarks.building()
        val p = Seismic.Params()
        val plain = LateralLoads.seismicStatic(m, p, 0, case = LoadCase.EX)
        val ecc = LateralLoads.seismicStatic(m, p, 0, case = LoadCase.EX, eccentricity = 0.05)
        assertEquals(plain.baseShear, ecc.loads.sumOf { it.values[0] }, 1e-9 * plain.baseShear)
        // storey torque about the centre of mass = Q·0.05·b
        for (st in ecc.storeys) {
            val lv = ecc.loads.filter { abs(m.node(it.node).z - st.z) < 1e-6 }
            val w = LateralLoads.nodalWeights(m, p.llFactor)
            val tot = lv.sumOf { w[it.node] ?: 0.0 }
            val yBar = lv.sumOf { (w[it.node] ?: 0.0) * m.node(it.node).y } / tot
            val torque = lv.sumOf { it.values[0] * (m.node(it.node).y - yBar) }
            val b = lv.map { m.node(it.node).y }.let { it.max() - it.min() }
            assertEquals(st.force * 0.05 * b, torque, 1e-9 * st.force * b)
        }
        val combos = LoadCombination.seismic100_30()
        assertEquals(25, combos.size)
        assertEquals(combos.map { it.name }.toSet().size, 25)
        assertTrue(combos.any { abs(it.factor(LoadCase.EX) - 1.5) < 1e-12 && abs(it.factor(LoadCase.EY) + 0.45) < 1e-12 })
        val ey = LateralLoads.seismicStatic(m, p, 1, case = LoadCase.EY, eccentricity = 0.05)
        val model = m.copy(nodalLoads = m.nodalLoads + ecc.loads + ey.loads)
        assertEquals(25, LoadCombination.forModel(model.loadCases).size)
        val r = FrameSolver.analyseAll(model, combos)
        for ((c, rr) in r) assertTrue(c.name, rr.equilibriumError.all { abs(it) < 1e-6 } && rr.momentEquilibriumError.all { abs(it) < 1e-5 })
        println("[100/30] ${combos.joinToString { it.name }}")
    }
}
