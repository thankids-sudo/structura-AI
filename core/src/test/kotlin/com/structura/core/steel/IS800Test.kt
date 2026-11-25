package com.structura.core.steel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class IS800Test {
    private fun near(e: Double, a: Double, rel: Double) = assertEquals(e, a, abs(e) * rel + 1e-9)
    private val ismb300 = SteelSection.library.first { it.name == "ISMB 300" }

    @Test
    fun ismb300_properties_vsIS808() {
        near(8603.6e4, ismb300.izz, 0.03)               // IS 808 tabulated Izz (fillets add ~1.4 %)
        near(56.26 * 100, ismb300.area, 0.05)
        assertEquals(SectionClass.PLASTIC, IS800.classifyBending(ismb300, 250.0).first)
    }

    @Test
    fun compressionStress_table9() {
        near(118.2, IS800.fcd(100.0, 250.0, 0.34), 0.01)   // IS 800 Table 9(b), fy 250, KL/r 100
        near(227.27, IS800.fcd(5.0, 250.0, 0.34), 0.001)   // stocky → fy/γm0
        assertTrue(IS800.fcd(100.0, 250.0, 0.21) > IS800.fcd(100.0, 250.0, 0.49))
    }

    @Test
    fun beam_supportedAndLtb() {
        val g = SteelGrade.E250
        val sup = IS800.bendingCapacity(ismb300, g, 0.0)
        near(ismb300.zpz * 250 / 1.1 / 1e6, sup.md, 1e-9)
        val ltb = IS800.bendingCapacity(ismb300, g, 6000.0)
        assertTrue(ltb.md < sup.md && ltb.chiLT < 1)
        near(ismb300.avZ * 250 / (sqrt(3.0) * 1.1) / 1e3, sup.vd, 1e-9)
        val r = IS800.beam(ismb300, g, mu = 100.0, vu = 80.0, span = 6000.0, llt = 0.0, wService = 10.0)
        assertTrue(r.checks.any { it.item == "Deflection (service)" })
    }

    @Test
    fun highShear_reducesMoment() {
        val g = SteelGrade.E250
        val low = IS800.beam(ismb300, g, 50.0, 10.0, 4000.0, 0.0)
        val vd = IS800.bendingCapacity(ismb300, g, 0.0).vd
        val high = IS800.beam(ismb300, g, 50.0, 0.9 * vd, 4000.0, 0.0)
        assertTrue(high.capacity < low.capacity)
    }

    @Test
    fun boltsAndWelds() {
        val r = IS800.boltGroup(IS800.Bolt.g46(20.0), 410.0, 10.0, 200.0, e = 40.0, pitch = 60.0)
        near(400 * 0.78 * Math.PI * 100 / (sqrt(3.0) * 1.25) / 1e3, r.capacity, 1e-9)   // 45.3 kN, shear governs
        val w = IS800.filletWeld(6.0, 150.0, 410.0, 10.0, 12.0)
        val perMm = 0.7 * 6 * 410 / (sqrt(3.0) * 1.25) / 1e3
        assertTrue((w.capacity - 12) * perMm >= 150.0)
    }

    @Test
    fun tensionAndCombined() {
        val g = SteelGrade.E250
        val t = IS800.tension(ismb300, g, 500.0, holes = 4, holeDia = 22.0)
        near(minOf(ismb300.area * 250 / 1.1, 0.9 * (ismb300.area - 4 * 22 * ismb300.tf) * 410 / 1.25) / 1e3, t.capacity, 1e-9)
        val c = IS800.beamColumn(ismb300, g, p = 300.0, mz = 60.0, my = 5.0, klz = 4000.0, kly = 4000.0, llt = 4000.0)
        assertTrue(c.utilisation > 0 && c.checks.size >= 3)
    }
}
