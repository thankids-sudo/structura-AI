package com.structura.core.fem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class BatchBTest {
    private val mat = Material("T", 2.0e8, 0.8e8, 0.0)
    private val sec = Section.rect(0.3, 0.5)
    private val ei = mat.e * sec.iy

    private fun near(expected: Double, actual: Double, rel: Double) =
        assertEquals(expected, actual, abs(expected) * rel + 1e-9)

    private fun ssBeam(l: Double, load: MemberLoad?) =
        Generators.continuousBeam(listOf(l), sec, mat, 0.0, selfWeight = false)
            .let { if (load != null) it.copy(memberLoads = listOf(load)) else it }

    @Test
    fun triangularLoad_simplySupported() {
        val l = 6.0; val w = 12.0
        val r = FrameSolver.analyse(ssBeam(l, MemberLoad(1, LoadDir.GLOBAL_Z, 0.0, LoadCase.DL, w2 = -w)))
        near(w * l / 6, r.reactions[1]!![2], 1e-9)                          // wL/6
        near(w * l / 3, r.reactions[2]!![2], 1e-9)                          // wL/3
        near(w * l * l / (9 * sqrt(3.0)), r.internalForces(1, 600).maxOf { it.my }, 1e-4)   // wL²/(9√3)
    }

    @Test
    fun partialUdl_reactionsAndMoment() {
        val l = 8.0; val w = 10.0; val a = 2.0; val b = 5.0
        val r = FrameSolver.analyse(ssBeam(l, MemberLoad(1, LoadDir.GLOBAL_Z, -w, LoadCase.DL, a = a, b = b)))
        val p = w * (b - a); val c = (a + b) / 2
        near(p * (l - c) / l, r.reactions[1]!![2], 1e-9)
        near(p * c / l, r.reactions[2]!![2], 1e-9)
        assertTrue(r.equilibriumError.all { abs(it) < 1e-9 })
    }

    @Test
    fun partialLoadDeflection_matchesSubdividedModel() {
        val l = 6.0
        val model = ssBeam(l, MemberLoad(1, LoadDir.GLOBAL_Z, -4.0, LoadCase.DL, a = 1.0, b = 4.0, w2 = -10.0))
        val r1 = FrameSolver.analyse(model)
        val (fine, chains) = ModelEditor.subdivide(model, 6)
        val r2 = FrameSolver.analyse(fine)
        val mid1 = r1.deformedShape(1, 1.0, 6)[3].z                       // at x = 3 m
        val nodeAt3 = fine.nodes.first { abs(it.x - 3.0) < 1e-9 }.id
        near(r2.nodeDisplacements[nodeAt3]!![2], mid1, 1e-6)
        assertEquals(6, chains[1]!!.size)
    }

    @Test
    fun rigidOffset_cantilever() {
        val l = 3.0; val o = 0.5; val p = 10.0
        val model = FrameModel(
            nodes = listOf(Node(1, 0.0, 0.0, 0.0), Node(2, l, 0.0, 0.0)),
            members = listOf(Member(1, 1, 2, mat, sec, offsetI = o)),
            supports = listOf(Support.fixed(1)),
            nodalLoads = listOf(NodalLoad(2, listOf(0.0, 0.0, -p, 0.0, 0.0, 0.0))),
            selfWeight = false,
        )
        val r = FrameSolver.analyse(model)
        near(-p * (l - o) * (l - o) * (l - o) / (3 * ei), r.nodeDisplacements[2]!![2], 1e-9)   // only the flexible part bends
        near(-p * l, r.internalForces(1, 6).first().my, 1e-9)             // statics at the node
        near(-p * (l - o), r.internalForces(1, 6).first { abs(it.s - o) < 1e-9 }.my, 1e-9)
    }

    private fun plate(n: Int, a: Double, t: Double, q: Double, clamped: Boolean): Pair<FrameModel, Int> {
        val e = 2.0e8; val nu = 0.3
        val nodes = ArrayList<Node>()
        fun id(i: Int, j: Int) = j * (n + 1) + i + 1
        for (j in 0..n) for (i in 0..n) nodes += Node(id(i, j), a * i / n, a * j / n, 0.0)
        val panels = ArrayList<Panel>()
        for (j in 0 until n) for (i in 0 until n)
            panels += Panel(panels.size + 1, listOf(id(i, j), id(i + 1, j), id(i + 1, j + 1), id(i, j + 1)), t, e, e / (2 * (1 + nu)), nu, 0.0, -q, LoadCase.DL)
        val supports = nodes.filter { it.x < 1e-9 || it.y < 1e-9 || it.x > a - 1e-9 || it.y > a - 1e-9 }.map {
            if (clamped) Support.fixed(it.id) else Support(it.id, listOf(true, true, true, false, false, false))
        }
        return FrameModel(nodes, emptyList(), supports, selfWeight = false, panels = panels) to id(n / 2, n / 2)
    }

    @Test
    fun squarePlate_simplySupported_and_clamped() {
        val a = 2.0; val t = 0.02; val q = 5.0; val e = 2.0e8; val nu = 0.3
        val d = e * t * t * t / (12 * (1 - nu * nu))
        val (ss, c1) = plate(8, a, t, q, false)
        near(-0.00406 * q * a * a * a * a / d, FrameSolver.analyse(ss).nodeDisplacements[c1]!![2], 0.04)
        val (cl, c2) = plate(8, a, t, q, true)
        near(-0.00126 * q * a * a * a * a / d, FrameSolver.analyse(cl).nodeDisplacements[c2]!![2], 0.04)
        val r = FrameSolver.analyse(ss)
        near(-q * a * a, r.totalLoad[2], 1e-9)
        assertTrue(r.equilibriumError.all { abs(it) < 1e-6 })
        assertTrue(r.panelResults.values.all { it.maxFaceStress > 0 })
    }

    @Test
    fun meshPanel_splitsEdgeBeam() {
        var m = ModelEditor.empty()
        val pts = listOf(Vec3(0.0, 0.0, 3.0), Vec3(4.0, 0.0, 3.0), Vec3(4.0, 3.0, 3.0), Vec3(0.0, 3.0, 3.0))
        val ids = pts.map { p -> ModelEditor.addNode(m, p).also { m = it.first }.second }
        m = ModelEditor.addMember(m, ids[0], ids[1], mat, sec).first
        m = ModelEditor.addPanel(m, ids, Panel.rcc(0, ids, 0.15)).first
        val meshed = ModelEditor.meshPanel(m, 1, 4, 3)
        assertEquals(12, meshed.panels.size)
        assertEquals(4, meshed.members.size)                               // edge beam split into 4
        assertEquals(20, meshed.nodes.size)                                // 5 × 4 grid
    }

    @Test
    fun slabOnFrame_equilibrium() {
        var m = Generators.spaceFrame(listOf(4.0), listOf(3.0), listOf(3.0), Section.rect(0.3, 0.3), Section.rect(0.23, 0.45),
            Material.concrete(25.0), 0.0)
        val top = m.nodes.filter { it.z > 2.9 }.sortedWith(compareBy({ it.y }, { it.x })).map { it.id }
        m = ModelEditor.addPanel(m, listOf(top[0], top[1], top[3], top[2]), Panel.rcc(0, top, 0.125, pressure = -3.0)).first
        m = ModelEditor.meshPanel(m, 1, 4, 4)
        val r = FrameSolver.analyse(m)
        assertTrue(r.equilibriumError.all { abs(it) < 1e-6 })
        val slabLoad = (25.0 * 0.125 + 3.0) * 12.0
        assertTrue(-r.totalLoad[2] > slabLoad)
    }
}
