package com.structura.core.fem

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BatchCTest {
    private val mat = Material.concrete(25.0)
    private val col = Section.rect(0.3, 0.3)
    private val beam = Section.rect(0.23, 0.45)

    private fun frame(bx: List<Double>, by: List<Double>) = Generators.spaceFrame(bx, by, listOf(3.0), col, beam, mat, 0.0, selfWeight = false)

    private fun transferred(m: FrameModel): Double = m.memberLoads.sumOf { l ->
        val mem = m.members.first { it.id == l.member }
        val len = (m.node(mem.j).pos - m.node(mem.i).pos).length
        val a = l.a ?: 0.0; val b = l.b ?: len
        (l.w + (l.w2 ?: l.w)) / 2 * (b - a)
    }

    @Test
    fun twoWayBay_totalAndShapes() {
        val m = frame(listOf(6.0), listOf(4.0))
        val top = m.nodes.filter { it.z > 2.9 }.sortedWith(compareBy({ it.y }, { it.x })).map { it.id }
        val r = FloorLoad.applyToBay(m, listOf(top[0], top[1], top[3], top[2]), -5.0, LoadCase.LL, FloorLoad.Mode.TWO_WAY)
        assertEquals(-5.0 * 24.0, transferred(r.model), 1e-9)          // q × area
        assertEquals(-5.0 * 24.0, r.total, 1e-9)
        // short (4 m) beams: triangle peak q·lx/2 = 10; long (6 m) beams: trapezoid plateau 10
        assertTrue(r.model.memberLoads.all { abs(it.w) <= 10.0 + 1e-9 && abs(it.w2 ?: it.w) <= 10.0 + 1e-9 })
        val res = FrameSolver.analyse(r.model)
        assertTrue(res.equilibriumError.all { abs(it) < 1e-6 })
        assertEquals(120.0, res.reactions.values.sumOf { it[2] }, 1e-6)
    }

    @Test
    fun oneWayBay_longBeamsOnly() {
        val m = frame(listOf(9.0), listOf(4.0))                         // ly/lx = 2.25 → one-way
        val top = m.nodes.filter { it.z > 2.9 }.sortedWith(compareBy({ it.y }, { it.x })).map { it.id }
        val r = FloorLoad.applyToBay(m, listOf(top[0], top[1], top[3], top[2]), -4.0, LoadCase.DL)
        assertEquals(2, r.model.memberLoads.size)
        assertTrue(r.model.memberLoads.all { it.w == -8.0 })            // q·lx/2
        assertEquals(-4.0 * 36.0, transferred(r.model), 1e-9)
    }

    @Test
    fun level_findsAllBays_and_splitEdges() {
        var m = frame(listOf(5.0, 4.0), listOf(3.0, 3.0))
        // split one edge beam to check load placement on a chain of members
        val edge = m.members.first { mm -> m.node(mm.i).z > 2.9 && m.node(mm.j).z > 2.9 && abs(m.node(mm.i).y) < 1e-9 && abs(m.node(mm.j).y) < 1e-9 }
        m = ModelEditor.splitMember(m, edge.id, 0.3).first
        val r = FloorLoad.applyToLevel(m, 3.0, -3.0, LoadCase.LL)
        assertEquals(4, r.bays)
        assertEquals(-3.0 * 9.0 * 6.0, transferred(r.model), 1e-9)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun engineeringJson_parses() {
        val m = FloorLoad.applyToLevel(frame(listOf(5.0), listOf(4.0)), 3.0, -3.0, LoadCase.LL).model
        val res = FrameSolver.analyseAll(m, LoadCombination.isLimitState(false))
        val json = EngineeringJson.build(m, res, FemReport.envelope(res.map { it.second }), Buckling.analyse(m, res.first().first), Modal.analyse(m, 3))
        val o = JSONObject(json)
        assertEquals("structura-ai-engineering", o.getString("format"))
        assertEquals(m.nodes.size, o.getJSONArray("nodes").length())
        assertEquals(1, o.getJSONArray("results").length())
        assertTrue(o.getJSONObject("buckling").getDouble("lambdaCr") > 0)
        assertEquals(3, o.getJSONObject("modal").getJSONArray("modes").length())
        assertEquals("100000", Json.write(100000.0))
        assertEquals("2.0", Json.write(2.0))
    }

    @Test
    fun staadExport_structure() {
        var m = FloorLoad.applyToLevel(frame(listOf(5.0), listOf(4.0)), 3.0, -3.0, LoadCase.LL).model
        m = m.copy(selfWeight = true, members = m.members.map { if (it.id == 1) it.copy(hingeJ = true, offsetI = 0.2) else it })
        val top = m.nodes.filter { it.z > 2.9 }.map { it.id }
        m = ModelEditor.addPanel(m, listOf(top[0], top[1], top[3], top[2]), Panel.rcc(0, top, 0.125, pressure = -2.0)).first
        val std = StaadExport.write(m)
        listOf("STAAD SPACE", "SET Z UP", "UNIT METER KN", "JOINT COORDINATES", "MEMBER INCIDENCES", "ELEMENT INCIDENCES SHELL",
            "PRIS YD 0.45 ZD 0.23", "FIXED", "MEMBER RELEASE", "1 END MY MZ", "MEMBER OFFSET", "SELFWEIGHT Z -1", "TRAP GZ",
            "ELEMENT LOAD", "LOAD COMB 101 1.5(DL+LL)", "PERFORM ANALYSIS", "FINISH").forEach { assertTrue("missing $it", std.contains(it)) }
        assertEquals(m.nodes.size, std.substringAfter("JOINT COORDINATES").substringBefore("MEMBER INCIDENCES").trim().lines().size)
    }
}
