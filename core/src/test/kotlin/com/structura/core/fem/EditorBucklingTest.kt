package com.structura.core.fem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

class EditorBucklingTest {
    private val mat = Material("Test", 2.0e8, 0.8e8, 0.0)
    private val sec = Section.rect(0.3, 0.5)

    private fun cantileverColumn(l: Double, p: Double): FrameModel {
        var m = ModelEditor.empty()
        val (m1, a) = ModelEditor.addNode(m, Vec3(0.0, 0.0, 0.0)); m = m1
        val (m2, b) = ModelEditor.addNode(m, Vec3(0.0, 0.0, l)); m = m2
        m = ModelEditor.addMember(m, a, b, mat, sec).first
        m = ModelEditor.setSupport(m, a, ModelEditor.SupportType.FIXED)
        m = ModelEditor.addNodalLoad(m, b, Vec3(0.0, 0.0, -p), LoadCase.DL)
        return m.copy(selfWeight = false)
    }

    @Test
    fun eulerCantilever_bucklingMultiplier() {
        val l = 3.0; val p = 100.0
        val r = Buckling.analyse(cantileverColumn(l, p), segments = 4)
        val pcr = PI * PI * mat.e * min(sec.iy, sec.iz) / (4 * l * l)   // Euler, K = 2
        assertEquals(pcr / p, r.lambda, pcr / p * 0.002)
        assertTrue(r.modeShape[1]!!.size == 5)
    }

    @Test
    fun tensionOnly_givesNegativeMultiplier() {
        val r = Buckling.analyse(cantileverColumn(3.0, -50.0), segments = 4)
        assertTrue(r.lambda < 0)
    }

    @Test
    fun editor_addMergeSplitDelete() {
        var m = ModelEditor.empty()
        val (m1, a) = ModelEditor.addNode(m, Vec3(0.0, 0.0, 0.0)); m = m1
        val (m2, b) = ModelEditor.addNode(m, Vec3(6.0, 0.0, 0.0)); m = m2
        val (m3, again) = ModelEditor.addNode(m, Vec3(6.0, 0.0, 0.00001)); m = m3
        assertEquals(b, again)                                              // merged
        m = ModelEditor.addMember(m, a, b, mat, sec).first
        assertEquals(1, m.members.size)
        assertEquals(m, ModelEditor.addMember(m, b, a, mat, sec).first)     // duplicate refused
        m = ModelEditor.addMemberUdl(m, 1, LoadDir.GLOBAL_Z, -10.0, LoadCase.DL)
        m = ModelEditor.addPointLoad(m, 1, LoadDir.GLOBAL_Z, -20.0, 4.5, LoadCase.LL)
        val (ms, mid) = ModelEditor.splitMember(m, 1)
        assertEquals(3, ms.nodes.size); assertEquals(2, ms.members.size)
        assertEquals(3.0, ms.node(mid).x, 1e-12)
        assertEquals(2, ms.memberLoads.size)                                // UDL copied to both halves
        val pl = ms.pointLoads.single()
        assertEquals(2, pl.member); assertEquals(1.5, pl.a, 1e-12)          // moved to second half
        val md = ModelEditor.deleteNode(ms, mid)
        assertEquals(0, md.members.size)
        assertTrue(md.memberLoads.isEmpty() && md.pointLoads.isEmpty())
    }

    @Test
    fun splitDoesNotChangeResults() {
        val model = Generators.continuousBeam(listOf(6.0), sec, mat, 12.0, selfWeight = false, pointLoadMid = 0.0)
            .let { ModelEditor.addPointLoad(it, 1, LoadDir.GLOBAL_Z, -30.0, 2.0, LoadCase.DL) }
        val (split, _) = ModelEditor.subdivide(model, 3)
        val r1 = FrameSolver.analyse(model)
        val r2 = FrameSolver.analyse(split)
        assertEquals(r1.reactions[1]!![2], r2.reactions[1]!![2], 1e-9)
        assertEquals(r1.reactions[2]!![2], r2.reactions[2]!![2], 1e-9)
        val mMax1 = r1.internalForces(1, 120).maxOf { it.my }
        val mMax2 = split.members.maxOf { mm -> r2.internalForces(mm.id, 40).maxOf { it.my } }
        assertEquals(mMax1, mMax2, abs(mMax1) * 2e-3)
    }

    @Test
    fun modelCheck_reportsProblems() {
        val issues = ModelEditor.check(ModelEditor.empty())
        assertTrue(issues.any { it.contains("no members") })
        assertTrue(issues.any { it.contains("No supports") })
    }
}

class HtmlReportTest {
    @Test
    fun reportContainsSections() {
        val mat = Material.concrete(25.0)
        val model = Generators.portalFrame(listOf(6.0), listOf(3.5), Section.rect(0.3, 0.45), Section.rect(0.3, 0.6), mat, 15.0, 20.0, liveUdl = 10.0)
        val combos = LoadCombination.isLimitState(true)
        val all = FrameSolver.analyseAll(model, combos)
        val html = HtmlReport.build(model, all.first(), FemReport.envelope(all.map { it.second }), combos, Buckling.analyse(model, combos.first()))
        listOf("<svg", "Nodes and supports", "Members", "Load combinations", "Support reactions", "Member envelope", "λcr").forEach {
            assertTrue("missing $it", html.contains(it))
        }
        assertTrue(html.trimEnd().endsWith("</html>"))
    }
}
