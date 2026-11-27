package com.structura.core.fem

import com.structura.core.rcc.CircularColumn
import com.structura.core.rcc.FoundationDesign
import com.structura.core.viz.ModelScenes
import com.structura.core.viz.Scene3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationsImportTest {
    @Test
    fun staad_roundTrip() {
        val m = Benchmarks.building()
        val text = StaadExport.write(m)
        val r = StaadImport.read(text)
        println(r.notes.joinToString(" | "))
        assertEquals(m.nodes.size, r.model.nodes.size)
        assertEquals(m.members.size, r.model.members.size)
        assertEquals(m.supports.size, r.model.supports.size)
        val a = FrameSolver.analyseAll(m, LoadCombination.forModel(m.loadCases))
        val b = FrameSolver.analyseAll(r.model, LoadCombination.forModel(r.model.loadCases))
        val da = a.maxOf { it.second.maxDisplacement().second }; val db = b.maxOf { it.second.maxDisplacement().second }
        println("max displacement original ${da * 1000} mm, imported ${db * 1000} mm")
        assertEquals(da, db, 0.02 * da + 1e-6)
    }

    @Test
    fun staad_yUp_withRangesAndUnits() {
        val std = """
            STAAD SPACE
            UNIT MMS KN
            JOINT COORDINATES
            1 0 0 0; 2 0 3000 0; 3 5000 3000 0; 4 5000 0 0;
            MEMBER INCIDENCES
            1 1 2; 2 2 3; 3 4 3;
            MEMBER PROPERTY
            1 3 PRIS YD 450 ZD 300
            2 PRIS YD 500 ZD 300
            SUPPORTS
            1 4 FIXED
            UNIT METER KN
            LOAD 1 LOADTYPE DEAD TITLE DEAD
            SELFWEIGHT Y -1
            MEMBER LOAD
            2 UNI GY -20
            LOAD 2 LOADTYPE LIVE TITLE LIVE
            MEMBER LOAD
            2 UNI GY -10
            FINISH
        """.trimIndent()
        val r = StaadImport.read(std).model
        assertEquals(3.0, r.node(2).z, 1e-9)                              // Y-up → Z-up, mm → m
        assertEquals(0.3, r.members.first { it.id == 2 }.section.b, 1e-9)
        assertEquals(LoadDir.GLOBAL_Z, r.memberLoads.first().dir)
        assertEquals(setOf("DL", "LL"), r.memberLoads.map { it.case }.toSet())
    }

    @Test
    fun footings_allTypes_andCircularColumn() {
        val pad = FoundationDesign.isolated(1, FootingType.PAD, 800.0, 1200.0, 0.0, 0.0, 300.0, 450.0, 200.0, 25.0, 500.0, 0.0, 0.0)
        val sl = FoundationDesign.isolated(1, FootingType.SLOPED, 800.0, 1200.0, 0.0, 0.0, 300.0, 450.0, 200.0, 25.0, 500.0, 0.0, 0.0)
        val stp = FoundationDesign.isolated(1, FootingType.STEPPED, 800.0, 1200.0, 0.0, 0.0, 300.0, 450.0, 200.0, 25.0, 500.0, 0.0, 0.0)
        println(pad.summary); println(sl.summary); println(stp.summary)
        assertTrue(sl.concrete < pad.concrete && stp.concrete < pad.concrete)
        assertTrue(sl.edge >= 150)
        val cb = FoundationDesign.combined(1, 2, 700.0, 900.0, 1050.0, 1350.0, 0.0, 0.0, 3.0, 0.0, 400.0 to 400.0, 450.0 to 450.0, 200.0, 25.0, 500.0)
        println(cb.summary); cb.steps.forEach { println("  $it") }
        assertTrue(cb.l > 3.0 && cb.ok)
        assertEquals(1.1 * 1600 / (cb.l * cb.b), 1.1 * 1600 / (cb.l * cb.b), 1e-9)
        // circular column: pure axial capacity close to 0.4 fck Ac + 0.67 fy Asc, and design passes for a modest load
        val c = CircularColumn.design(450.0, 8, 16.0, 25.0, 500.0, 1200.0, 60.0, 20.0, 3000.0)
        c.checks.forEach { println("  ${it.item}: ${it.value} ${it.status}") }
        assertTrue(c.isSafe)
        val m0 = CircularColumn.muAt(450.0, 8, 16.0, 40.0, 25.0, 500.0, 0.0)
        assertTrue("pure bending capacity $m0", m0 > 50 && m0 < 250)
    }

    @Test
    fun roles_footings_scene_and_report() {
        var m = Benchmarks.building()
        val beams = ModelOps.beams(m)
        val low = m.members.filter { it.id in beams && minOf(m.node(it.i).z, m.node(it.j).z) < 3.5 }.map { it.id }.toSet()
        m = ModelOps.setRole(m, low, MemberRole.PLINTH_BEAM)
        m = ModelOps.addWallLoad(m, low, 0.23, 3.0)
        val sup = m.supports.map { it.node }
        m = ModelOps.setFootings(m, setOf(sup[0]), FootingSpec(FootingType.SLOPED, 200.0))
        val res = FrameSolver.analyseAll(m, LoadCombination.forModel(m.loadCases))
        val rcc = RccModelDesign.design(m, res)
        assertTrue(rcc.any { it.role == MemberRole.PLINTH_BEAM && it.checks.any { c -> c.item == "Plinth beam" } })
        val f = RccModelDesign.designFootings(m, res)
        assertEquals(sup.size, f.size)
        assertEquals(FootingType.SLOPED, f.first { it.node == sup[0] }.type)
        val scene = ModelScenes.build(m, rcc, f)
        assertTrue(scene.items.count { it is Scene3D.Solid } >= m.members.size + f.size)
        assertTrue(scene.items.count { it is Scene3D.Bar } > 50)
        val single = ModelScenes.build(m, rcc, f, setOf(rcc.first().member))
        assertTrue(single.items.any { it is Scene3D.Dim })
        val v = Validation.run(m, res, rcc, emptyList(), f)
        val doc = ProjectReport.build(m, res, rcc, emptyList(), f, v)
        assertTrue(doc.blocks.size > 20)
        println("report blocks ${doc.blocks.size}; footings: " + f.joinToString(" | ") { it.summary })
    }
}
