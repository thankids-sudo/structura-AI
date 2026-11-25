package com.structura.core.steel

import com.structura.core.fem.FrameSolver
import com.structura.core.fem.Generators
import com.structura.core.fem.LoadCombination
import com.structura.core.fem.Material
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteelModelCheckTest {
    private val col = SteelSection.library.first { it.name == "ISHB 200" }
    private val beam = SteelSection.library.first { it.name == "ISMB 300" }

    @Test
    fun femSection_roundTrip() {
        val f = SteelModelCheck.femSection(beam)
        assertEquals(beam.izz * 1e-12, f.iy, 1e-15)          // FEM Iy = major axis
        val back = SteelModelCheck.steelSection(f)!!
        assertEquals(beam.zpz, back.zpz, 1e-6)
    }

    @Test
    fun portal_allMembersChecked_matchesDirectCall() {
        val m = Generators.portalFrame(listOf(6.0), listOf(4.0), SteelModelCheck.femSection(col), SteelModelCheck.femSection(beam),
            Material.steel(250.0), beamUdl = 12.0, lateral = 10.0, liveUdl = 8.0)
        val combos = LoadCombination.isLimitState(true)
        val res = FrameSolver.analyseAll(m, combos)
        val checks = SteelModelCheck.check(m, res)
        assertEquals(m.members.size, checks.size)
        assertTrue(checks.all { it.utilisation > 0 })
        // beam member: recompute directly with the governing forces
        val b = checks.first { it.section == "ISMB 300" }
        val direct = IS800.beamColumn(beam, SteelGrade.E250, maxOf(b.p, 0.0), b.mMajor, b.mMinor, 6000.0, 6000.0, 6000.0)
        assertTrue(b.utilisation >= direct.utilisation - 1e-9)
        val html = com.structura.core.fem.HtmlReport.build(m, null, emptyList(), combos, steel = checks)
        assertTrue(html.contains("Steel member checks"))
        val json = com.structura.core.fem.EngineeringJson.build(m, res, steel = checks)
        assertTrue(json.contains("steelChecksIS800"))
        org.json.JSONObject(json)
    }
}
