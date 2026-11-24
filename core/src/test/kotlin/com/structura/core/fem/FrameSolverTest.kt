package com.structura.core.fem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Closed-form beam theory results (Roark / any SOM text) checked against the solver. */
class FrameSolverTest {

    private val mat = Material("Test", 2.0e8, 0.8e8, 0.0)
    private val sec = Section.rect(0.3, 0.5)
    private val ei = mat.e * sec.iy

    private fun near(expected: Double, actual: Double, rel: Double = 1e-6) =
        assertEquals(expected, actual, abs(expected) * rel + 1e-9)

    @Test
    fun cantilever_tipLoad() {
        val l = 4.0; val p = 10.0
        val model = FrameModel(
            nodes = listOf(Node(1, 0.0, 0.0, 0.0), Node(2, l, 0.0, 0.0)),
            members = listOf(Member(1, 1, 2, mat, sec)),
            supports = listOf(Support.fixed(1)),
            nodalLoads = listOf(NodalLoad(2, listOf(0.0, 0.0, -p, 0.0, 0.0, 0.0))),
            selfWeight = false,
        )
        val r = FrameSolver.analyse(model)
        near(-p * l * l * l / (3 * ei), r.nodeDisplacements[2]!![2])      // δ = PL³/3EI
        near(p, r.reactions[1]!![2])
        near(-p * l, r.internalForces(1).first().my)                       // hogging at root
        near(0.0, r.internalForces(1).last().my + 0.0)
        assertTrue(r.equilibriumError.all { abs(it) < 1e-6 })
    }

    @Test
    fun simplySupported_udl() {
        val l = 6.0; val w = 12.0
        val model = Generators.continuousBeam(listOf(l), sec, mat, w, selfWeight = false)
        val r = FrameSolver.analyse(model)
        val mid = r.internalForces(1, 2)[1]
        near(w * l * l / 8, mid.my)                                        // +wL²/8 sagging
        near(w * l / 2, r.reactions[1]!![2])
        near(w * l / 2, r.internalForces(1).first().vz)
        val shape = r.deformedShape(1, 1.0, 2)
        near(-5 * w * l * l * l * l / (384 * ei), shape[1].z)              // 5wL⁴/384EI
    }

    @Test
    fun fixedFixed_udl() {
        val l = 5.0; val w = 20.0
        val model = FrameModel(
            nodes = listOf(Node(1, 0.0, 0.0, 0.0), Node(2, l, 0.0, 0.0)),
            members = listOf(Member(1, 1, 2, mat, sec)),
            supports = listOf(Support.fixed(1), Support.fixed(2)),
            memberLoads = listOf(MemberLoad(1, LoadDir.GLOBAL_Z, -w)),
            selfWeight = false,
        )
        val st = FrameSolver.analyse(model).internalForces(1, 2)
        near(-w * l * l / 12, st[0].my)                                    // −wL²/12 at supports
        near(w * l * l / 24, st[1].my)                                     // +wL²/24 at midspan
    }

    @Test
    fun twoSpanContinuous_supportMoment() {
        val l = 4.0; val w = 15.0
        val r = FrameSolver.analyse(Generators.continuousBeam(listOf(l, l), sec, mat, w, selfWeight = false))
        near(-w * l * l / 8, r.internalForces(1).last().my)                // −wL²/8 over middle support
        near(1.25 * w * l, r.reactions[2]!![2])                            // 10wL/8 central reaction
    }

    @Test
    fun portalFrame_equilibriumAndSymmetry() {
        val m = Material.concrete(25.0)
        val model = Generators.portalFrame(
            listOf(6.0), listOf(3.5), Section.rect(0.3, 0.45), Section.rect(0.3, 0.6), m, beamUdl = 25.0,
        )
        val r = FrameSolver.analyse(model)
        assertTrue(r.equilibriumError.all { abs(it) < 1e-6 })
        near(r.reactions[1]!![2], r.reactions[2]!![2])                    // symmetric vertical reactions
        near(-r.reactions[1]!![0], r.reactions[2]!![0])                    // equal & opposite thrusts
        near(-r.totalLoad[2] / 2, r.reactions[1]!![2])
    }

    @Test
    fun spaceFrame_solvesAndBalances() {
        val m = Material.concrete(25.0)
        val model = Generators.spaceFrame(
            listOf(5.0, 5.0), listOf(4.0), listOf(3.2, 3.2),
            Section.rect(0.3, 0.3), Section.rect(0.23, 0.45), m, beamUdl = 20.0, lateralX = 15.0,
        )
        val r = FrameSolver.analyse(model)
        assertEquals(18, model.nodes.size)
        assertTrue(r.equilibriumError.all { abs(it) < 1e-6 })
        assertTrue(r.nodeDisplacements.values.any { it[0] > 0 })            // sway in +X under lateral load
    }

    @Test(expected = UnstableModelException::class)
    fun mechanism_isReported() {
        val model = FrameModel(
            nodes = listOf(Node(1, 0.0, 0.0, 0.0), Node(2, 4.0, 0.0, 0.0)),
            members = listOf(Member(1, 1, 2, mat, sec)),
            supports = listOf(Support.rollerX(1), Support.rollerX(2)),   // nothing restrains X
            selfWeight = false,
        )
        FrameSolver.analyse(model)
    }
}
