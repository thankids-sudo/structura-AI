package com.structura.core.rcc

import com.structura.core.codes.IS456
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected values come from tools/verify_beam.py — an independent hand calculation
 * written straight from the IS 456 formulas and SP 16 Table A (not from this engine).
 */
class BeamDesignTest {

    private fun near(expected: Double, actual: Double, relTol: Double = 0.005) =
        assertEquals(expected, actual, kotlin.math.abs(expected) * relTol)

    @Test
    fun singlyReinforced_230x450_M20_Fe500() {
        val r = BeamDesigner.design(
            BeamInput(
                widthB = 230.0, overallDepthD = 450.0, mainBarDia = 16.0,
                effectiveSpan = 5000.0, fck = 20.0, fy = 500.0,
                factoredMoment = 90.0, factoredShear = 80.0,
            )
        )
        near(409.0, r.effectiveDepth)
        near(188.14, r.xuMax)
        near(102.809, r.muLim)
        assertFalse(r.doublyReinforced)
        near(602.62, r.astRequired)
        assertEquals(3, r.tensionBars.count)
        assertNull(r.compressionBars)
        assertEquals(300.0, r.stirrupSpacing!!, 0.01)   // limited by Cl. 26.5.1.5
        assertTrue(r.isSafe)
    }

    @Test
    fun doublyReinforced_230x450_M20_Fe500() {
        val r = BeamDesigner.design(
            BeamInput(
                widthB = 230.0, overallDepthD = 450.0, mainBarDia = 20.0, compBarDia = 16.0,
                effectiveSpan = 5000.0, fck = 20.0, fy = 500.0,
                factoredMoment = 140.0, factoredShear = 120.0,
            )
        )
        near(407.0, r.effectiveDepth)
        near(101.806, r.muLim)
        assertTrue(r.doublyReinforced)
        near(259.25, r.ascRequired, 0.01)
        near(952.62, r.astRequired, 0.005)
        assertEquals(4, r.tensionBars.count)
        assertNotNull(r.compressionBars)
        assertEquals(265.0, r.stirrupSpacing!!, 5.0)
    }

    @Test
    fun doublyReinforced_Fe415_flagsBarSpacing() {
        val r = BeamDesigner.design(
            BeamInput(
                widthB = 300.0, overallDepthD = 500.0, mainBarDia = 20.0, compBarDia = 16.0,
                effectiveSpan = 6000.0, fck = 25.0, fy = 415.0,
                factoredMoment = 260.0, factoredShear = 150.0,
            )
        )
        near(216.102, r.muLim)
        near(309.09, r.ascRequired, 0.01)
        near(1932.69, r.astRequired, 0.005)
        assertEquals(7, r.tensionBars.count)
        val spacing = r.checks.first { it.clause.contains("26.3.2") }
        assertEquals(Status.FAIL, spacing.status)        // 7-20φ do not fit one layer in 300 mm
        assertFalse(r.isSafe)
    }

    @Test
    fun shearBeyondTauCMax_fails() {
        val r = BeamDesigner.design(
            BeamInput(
                widthB = 230.0, overallDepthD = 450.0, effectiveSpan = 4000.0,
                factoredMoment = 50.0, factoredShear = 300.0,
            )
        )
        assertNull(r.stirrupSpacing)
        assertFalse(r.isSafe)
    }

    @Test
    fun table19_interpolation() {
        assertEquals(0.48, IS456.tauC(0.50, 20.0), 1e-9)
        assertEquals(0.52, IS456.tauC(0.625, 20.0), 1e-9)
        assertEquals(0.28, IS456.tauC(0.05, 20.0), 1e-9)   // clamped
        assertEquals(0.36, IS456.tauC(0.25, 22.0), 1e-9)   // intermediate grade → M20 column
    }

    @Test
    fun steelStress_matchesSP16TableA() {
        assertEquals(360.9, IS456.steelStress(0.0038, 415.0), 0.2)
        assertEquals(342.8, IS456.steelStress(0.00241, 415.0), 0.3)
        assertEquals(413.0, IS456.steelStress(0.00277, 500.0), 0.3)
        assertEquals(200.0, IS456.steelStress(0.001, 500.0), 1e-9)   // elastic
        assertEquals(217.5, IS456.steelStress(0.005, 250.0), 1e-9)   // Fe 250 yields at 0.87 fy
    }

    @Test
    fun udlHelpers() {
        val (m, v) = BeamDesigner.simplySupportedUdl(30.0, 5.0)
        assertEquals(93.75, m, 1e-9)
        assertEquals(75.0, v, 1e-9)
    }
}
