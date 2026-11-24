package com.structura.core.drawing

import com.structura.core.codes.IS456.Support
import com.structura.core.rcc.BeamDesigner
import com.structura.core.rcc.BeamInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeamDetailerTest {

    private val inp = BeamInput(
        widthB = 230.0, overallDepthD = 450.0, effectiveSpan = 5000.0,
        fck = 20.0, fy = 500.0, factoredMoment = 90.0, factoredShear = 80.0,
    )
    private val res = BeamDesigner.design(inp)

    @Test
    fun ucsMapsSectionOriginIntoWcs() {
        val u = Ucs("T", P2(1000.0, 500.0), 90.0)
        val p = u.toWcs(P2(100.0, 0.0))
        assertEquals(1000.0, p.x, 1e-9)
        assertEquals(600.0, p.y, 1e-9)
    }

    @Test
    fun sectionHasOneBarPerDesignedBar() {
        val dwg = BeamDetailer.detail(inp, res)
        val sec = dwg.views.first { it.ucs.name == "SECTION_A-A" }
        val bars = sec.prims.filterIsInstance<Prim.Circle>()
        assertEquals(res.tensionBars.count + 2, bars.size)            // 3 bottom + 2 hangers
        // every bar lies inside the stirrup cage, measured in the section's own UCS
        assertTrue(bars.all { it.c.x - it.r >= inp.clearCover && it.c.x + it.r <= inp.widthB - inp.clearCover })
    }

    @Test
    fun stirrupsFollowDesignedSpacing() {
        val dwg = BeamDetailer.detail(inp, res)
        val el = dwg.views.first { it.ucs.name == "ELEVATION" }
        val st = el.prims.filterIsInstance<Prim.Line>().filter { it.layer == Layer.STIRRUP }.map { it.a.x }.sorted()
        assertTrue(st.size >= 15)
        assertEquals(res.stirrupSpacing!!, st[1] - st[0], 1e-6)
    }

    @Test
    fun cantileverPutsMainBarsOnTop() {
        val ci = inp.copy(support = Support.CANTILEVER, effectiveSpan = 1500.0, factoredMoment = 60.0)
        val dwg = BeamDetailer.detail(ci, BeamDesigner.design(ci))
        val sec = dwg.views.first { it.ucs.name == "SECTION_A-A" }
        val top = sec.prims.filterIsInstance<Prim.Circle>().filter { it.c.y > ci.overallDepthD / 2 }
        assertEquals(BeamDesigner.design(ci).tensionBars.count, top.size)
    }

    @Test
    fun dxfIsR12WithNamedUcsAndLayers() {
        val dxf = DxfWriter.write(BeamDetailer.detail(inp, res))
        assertTrue(dxf.contains("AC1009"))
        assertTrue(dxf.contains("\r\nUCS\r\n2\r\nSECTION_A-A\r\n"))
        assertTrue(dxf.contains("\r\nUCS\r\n2\r\nELEVATION\r\n"))
        assertTrue(dxf.contains("S-REBAR"))
        assertTrue(dxf.contains("%%c"))
        assertTrue(dxf.trimEnd().endsWith("EOF"))
        assertTrue(dxf.all { it.code < 128 })                         // pure ASCII
    }
}
