package com.structura.core.fem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class SeismicTest {
    private fun near(e: Double, a: Double, rel: Double) = assertEquals(e, a, abs(e) * rel + 1e-12)

    @Test
    fun designSpectrum_IS1893() {
        val s = Seismic.Soil.II
        near(1.75, Seismic.saByG(0.05, s), 1e-12)
        near(2.5, Seismic.saByG(0.3, s), 1e-12)
        near(1.36, Seismic.saByG(1.0, s), 1e-12)
        near(0.34, Seismic.saByG(5.0, s), 1e-12)
        near(2.5, Seismic.saByG(0.05, s, static = true), 1e-12)
        near(1.0 / 0.8, Seismic.saByG(0.8, Seismic.Soil.I), 1e-12)
        near(1.67 / 2.0, Seismic.saByG(2.0, Seismic.Soil.III), 1e-12)
        near(1.0, Seismic.rho(10.0, 10.0), 1e-12)
        assertTrue(Seismic.rho(10.0, 20.0) < 0.02)
    }

    private val mat = Material("C", 2.5e7, 1.04e7, 1.0)
    private val sec = Section.rect(0.3, 0.3)

    private fun column(mass: Double = 200.0) = FrameModel(
        nodes = listOf(Node(1, 0.0, 0.0, 0.0), Node(2, 0.0, 0.0, 3.0)),
        members = listOf(Member(1, 1, 2, mat, sec)),
        supports = listOf(Support.fixed(1)),
        nodalLoads = listOf(NodalLoad(2, listOf(0.0, 0.0, -mass, 0.0, 0.0, 0.0), LoadCase.DL)),
    )

    @Test
    fun singleMassColumn_matchesHandCalculation() {
        val p = Seismic.Params(z = 0.24, importance = 1.0, r = 5.0, soil = Seismic.Soil.II, building = Seismic.BuildingType.MODAL, nModes = 6)
        val r = Seismic.analyse(column(), p)
        val k = 3 * mat.e * sec.iy / 27.0
        val m = 200.0 / Modal.G
        val t1 = 2 * PI * sqrt(m / k)
        val x = r.x
        val first = x.rows.maxByOrNull { it.massRatio }!!
        near(t1, first.period, 0.02)
        assertTrue(x.cumulativeMass > 0.95)
        val expected = Seismic.ah(t1, p) * x.weight
        near(expected, x.baseShearRs, 0.03)
        near(1.0, x.scale, 2e-3)                                // Ta = T1 → VB ≈ VB̄ (only the higher-mode mass differs)
        near(x.nodeDisplacement[2]!! / 3.0, x.drifts.single().drift, 1e-9)
        // the column base moment = V·L
        val base = x.stations(1, listOf(0.0)).single()
        near(x.baseShear * 3.0, base.my + base.mz, 0.03)
    }

    @Test
    fun scaling_toStaticBaseShear() {
        val p = Seismic.Params(z = 0.16, soil = Seismic.Soil.II, building = Seismic.BuildingType.RC_MRF, nModes = 6)
        val r = Seismic.analyse(column(), p)
        assertTrue(r.x.scale > 1.0)
        near(r.x.baseShearStatic, r.x.baseShear, 1e-9)
    }

    @Test
    fun frameEnvelope_includesEarthquake() {
        val m = Generators.spaceFrame(listOf(5.0, 5.0), listOf(4.0), listOf(3.2, 3.2), Section.rect(0.3, 0.4), Section.rect(0.25, 0.45),
            Material.concrete(25.0), beamUdl = 10.0, liveUdl = 6.0)
        val res = Seismic.analyse(m, Seismic.Params(nModes = 8))
        assertTrue(res.x.baseShear > 0 && res.y.baseShear > 0)
        assertTrue(res.x.drifts.size == 2)
        val env = Seismic.envelope(m, res)
        val grav = FemReport.envelope(FrameSolver.analyse(m, LoadCombination.isLimitState(false).first()))
        env.zip(grav).forEach { (e, g) -> assertTrue("M${e.member}: ${e.myMax} vs ${g.myMax}, ${e.myMin} vs ${g.myMin}", e.myMax >= g.myMax - 1e-6 && e.myMin <= g.myMin + 1e-6) }
        assertTrue(env.any { !it.horizontal && abs(it.myMin) > 1.0 })   // columns pick up seismic moments
    }
}
