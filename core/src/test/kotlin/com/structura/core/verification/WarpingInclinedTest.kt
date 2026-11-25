package com.structura.core.verification

import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameSolver
import com.structura.core.fem.LoadDir
import com.structura.core.fem.Material
import com.structura.core.fem.Member
import com.structura.core.fem.NodalLoad
import com.structura.core.fem.Node
import com.structura.core.fem.PointLoad
import com.structura.core.fem.Section
import com.structura.core.fem.Support
import com.structura.core.fem.Warping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

class WarpingInclinedTest {
    private val sec = Section.iBeam(0.3, 0.14, 0.0124, 0.0075)
    private val st = Material.steel(250.0)

    @Test
    fun cantilever_warpingRestrainedAtRoot() {
        val l = 4.0; val t = 5.0
        val m = FrameModel(listOf(Node(1, 0.0, 0.0, 0.0), Node(2, l, 0.0, 0.0)), listOf(Member(1, 1, 2, st, sec, warpI = true)),
            listOf(Support.fixed(1)), nodalLoads = listOf(NodalLoad(2, listOf(0.0, 0.0, 0.0, t, 0.0, 0.0))), selfWeight = false)
        val r = FrameSolver.analyse(m)
        val gj = st.g * sec.j; val eiw = st.e * sec.iw; val lam = sqrt(gj / eiw)
        val exact = t / gj * (l - tanh(lam * l) / lam)                         // Trahair / Kollbrunner–Basler
        println("[Warping] λL = ${"%.3f".format(lam * l)}, tip twist FE ${r.nodeDisplacements[2]!![3]} vs exact $exact; St Venant only ${t * l / gj}")
        assertEquals(exact, r.nodeDisplacements[2]!![3], 1e-9 * exact)
        val w = r.warping(1, 40)
        assertEquals(t * tanh(lam * l) / lam, abs(w.first().bimoment), 1e-6 * t * l)   // root bimoment
        assertEquals(0.0, w.last().bimoment, 1e-9)                                     // free warping at the tip
        assertTrue(w.all { abs(it.stVenant + it.warpingTorque - t) < 1e-6 * t })       // T = Tsv + Tw everywhere
        assertTrue(r.momentEquilibriumError.all { abs(it) < 1e-9 })
    }

    @Test
    fun bothEndsRestrained_stiffness() {
        val l = 6.0
        val mem = Member(1, 1, 2, st, sec, warpI = true, warpJ = true)
        val gj = st.g * sec.j; val lam = sqrt(gj / (st.e * sec.iw))
        assertEquals(gj / (l - 2 * tanh(lam * l / 2) / lam), Warping.stiffness(mem, l), 1e-9 * gj / l)
        assertEquals(gj / l, Warping.stiffness(mem.copy(warpI = false, warpJ = false), l), 1e-12)
    }

    @Test
    fun inclinedRoller_reactionNormalToIncline() {
        val col = Section.rhs(0.15, 0.15, 0.006)
        val m = FrameModel(listOf(Node(1, 0.0, 0.0, 0.0), Node(2, 6.0, 0.0, 0.0)), listOf(Member(1, 1, 2, st, col)),
            listOf(Support(1, listOf(true, true, true, true, false, false)), Support(2, listOf(false, true, true, false, false, false), inclination = listOf(0.0, 30.0, 0.0))),
            pointLoads = listOf(PointLoad(1, LoadDir.GLOBAL_Z, -60.0, 3.0)), selfWeight = false)
        val r = FrameSolver.analyse(m)
        val r2 = r.reactions[2]!!
        println("[Inclined] R2 = (${r2[0]}, ${r2[1]}, ${r2[2]})")
        assertEquals(30.0, r2[2], 1e-9)
        assertEquals(30.0 * kotlin.math.tan(Math.toRadians(30.0)), r2[0], 1e-9)
        assertEquals(-r2[0], r.reactions[1]!![0], 1e-9)
        assertTrue(r.equilibriumError.all { abs(it) < 1e-9 } && r.momentEquilibriumError.all { abs(it) < 1e-9 })
        // support moves along the incline only: displacement normal to it is zero
        val u = r.nodeDisplacements[2]!!
        assertEquals(0.0, u[0] * 0.5 + u[2] * sqrt(3.0) / 2, 1e-12)
    }

    @Test
    fun warpingContinuity_acrossAJoint() {
        val l = 4.0; val t = 5.0
        val nodes = listOf(Node(1, 0.0, 0.0, 0.0), Node(2, l / 2, 0.0, 0.0), Node(3, l, 0.0, 0.0))
        fun model(cont: Boolean) = FrameModel(nodes, listOf(
            Member(1, 1, 2, st, sec, warpI = true, warpContJ = cont),
            Member(2, 2, 3, st, sec, warpContI = cont),
        ), listOf(Support.fixed(1)), nodalLoads = listOf(NodalLoad(3, listOf(0.0, 0.0, 0.0, t, 0.0, 0.0))), selfWeight = false)
        val gj = st.g * sec.j; val lam = sqrt(gj / (st.e * sec.iw))
        val exact = t / gj * (l - tanh(lam * l) / lam)
        val r = FrameSolver.analyse(model(true))
        println("[Warping 7th DOF] split cantilever tip twist ${r.nodeDisplacements[3]!![3]} vs single-member exact $exact; joint θ = ${r.warpingDofs[2]}")
        assertEquals(exact, r.nodeDisplacements[3]!![3], 1e-8 * exact)
        val b1 = r.warping(1, 10).last().bimoment; val b2 = r.warping(2, 10).first().bimoment
        assertEquals(b1, b2, 1e-6 * abs(b1) + 1e-12)                               // bimoment continuous through the joint
        assertEquals(-t, r.memberEndForces[2]!![3], 1e-9); assertEquals(t, r.memberEndForces[1]!![9], 1e-9)
        assertTrue(r.equilibriumError.all { abs(it) < 1e-9 } && r.momentEquilibriumError.all { abs(it) < 1e-9 })
        // without continuity the joint warps freely → more twist
        val loose = FrameSolver.analyse(model(false))
        assertTrue(loose.nodeDisplacements[3]!![3] > r.nodeDisplacements[3]!![3])
        // reversed member direction: same result
        val rev = FrameModel(nodes, listOf(Member(1, 1, 2, st, sec, warpI = true, warpContJ = true), Member(2, 3, 2, st, sec, warpContJ = true)),
            listOf(Support.fixed(1)), nodalLoads = listOf(NodalLoad(3, listOf(0.0, 0.0, 0.0, t, 0.0, 0.0))), selfWeight = false)
        assertEquals(exact, FrameSolver.analyse(rev).nodeDisplacements[3]!![3], 1e-8 * exact)
        // exact 4×4 condensed with restrained i / free j reproduces the closed-form member stiffness
        val mem = Member(9, 1, 2, st, sec, warpI = true)
        val k4 = Warping.matrix4(mem, l)
        val kc = Array(4) { a -> DoubleArray(4) { b -> k4[a][b] - k4[a][3] * k4[3][b] / k4[3][3] } }
        assertEquals(Warping.stiffness(mem, l), kc[2][2], 1e-6 * kc[2][2])
    }
}
