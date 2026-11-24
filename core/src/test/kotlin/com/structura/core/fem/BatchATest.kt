package com.structura.core.fem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class BatchATest {
    private val gfrp = Material.custom("GFRP test", 18000.0, 3200.0, 19.0)
    private val tube = Section.rhs(0.1, 0.1, 0.006)

    private fun near(expected: Double, actual: Double, rel: Double) =
        assertEquals(expected, actual, abs(expected) * rel + 1e-12)

    private fun cantilever(sec: Section, mat: Material, l: Double, theory: BeamTheory, tipFz: Double = 0.0, sw: Boolean = false): FrameModel =
        FrameModel(
            nodes = listOf(Node(1, 0.0, 0.0, 0.0), Node(2, l, 0.0, 0.0)),
            members = listOf(Member(1, 1, 2, mat, sec)),
            supports = listOf(Support.fixed(1)),
            nodalLoads = if (tipFz != 0.0) listOf(NodalLoad(2, listOf(0.0, 0.0, tipFz, 0.0, 0.0, 0.0))) else emptyList(),
            selfWeight = sw,
            beamTheory = theory,
        )

    @Test
    fun sections_hollowProperties() {
        val c = Section.chs(0.1143, 0.0045)
        assertEquals(2 * c.iy, c.j, 1e-15)
        near(PI / 4 * (0.1143 * 0.1143 - 0.1053 * 0.1053), c.a, 1e-12)
        val r = Section.rhs(0.08, 0.12, 0.005)
        near(0.08 * 0.12 - 0.07 * 0.11, r.a, 1e-12)
        assertTrue(r.iy > r.iz)                                   // depth along local z
        near(r.a, r.asy + r.asz, 1e-12)
        assertEquals("RECT", Section.rect(0.3, 0.5).shape)
    }

    @Test
    fun timoshenkoCantilever_addsShearDeflection() {
        val l = 1.5; val p = 2.0
        val eb = FrameSolver.analyse(cantilever(tube, gfrp, l, BeamTheory.EULER, -p)).nodeDisplacements[2]!![2]
        val tim = FrameSolver.analyse(cantilever(tube, gfrp, l, BeamTheory.TIMOSHENKO, -p)).nodeDisplacements[2]!![2]
        val bending = p * l * l * l / (3 * gfrp.e * tube.iy)
        val shear = p * l / (gfrp.g * tube.asz)
        near(-bending, eb, 1e-9)
        near(-(bending + shear), tim, 1e-9)                           // PL³/3EI + PL/(G·As), exact
    }

    @Test
    fun timoshenkoSimplySupported_udlMidspan() {
        val l = 3.0; val w = 1.5
        val model = Generators.continuousBeam(listOf(l), tube, gfrp, w, selfWeight = false).copy(beamTheory = BeamTheory.TIMOSHENKO)
        val r = FrameSolver.analyse(model)
        val expect = 5 * w * l * l * l * l / (384 * gfrp.e * tube.iy) + w * l * l / (8 * gfrp.g * tube.asz)
        near(-expect, r.deformedShape(1, 1.0, 2)[1].z, 1e-6)
        near(w * l * l / 8, r.internalForces(1, 2)[1].my, 1e-9)       // statically determinate: unchanged
    }

    @Test
    fun rotationalSprings_partialFixity() {
        val l = 5.0; val w = 10.0
        val mat = Material("T", 2.0e8, 0.8e8, 0.0)
        val sec = Section.rect(0.3, 0.5)
        val ei = mat.e * sec.iy
        val k = 2 * ei / l                                            // → end moment = wL²/24
        val model = FrameModel(
            nodes = listOf(Node(1, 0.0, 0.0, 0.0), Node(2, l, 0.0, 0.0)),
            members = listOf(Member(1, 1, 2, mat, sec, springI = k, springJ = k)),
            supports = listOf(Support.fixed(1), Support.fixed(2)),
            memberLoads = listOf(MemberLoad(1, LoadDir.GLOBAL_Z, -w)),
            selfWeight = false,
        )
        val st = FrameSolver.analyse(model).internalForces(1, 2)
        near(-w * l * l / 24, st[0].my, 1e-9)
        near(w * l * l / 8 - w * l * l / 24, st[1].my, 1e-9)
        // a spring of 0 behaves as a hinge
        val hinge = model.copy(members = listOf(Member(1, 1, 2, mat, sec, springI = 0.0, springJ = 0.0)))
        near(w * l * l / 8, FrameSolver.analyse(hinge).internalForces(1, 2)[1].my, 1e-9)
    }

    @Test
    fun modal_cantileverFirstFrequency() {
        val l = 2.0
        val r = Modal.analyse(cantilever(tube, gfrp, l, BeamTheory.EULER, sw = true), nModes = 3, segments = 6)
        val mu = gfrp.gamma * tube.a / Modal.G
        val i = minOf(tube.iy, tube.iz)
        val f1 = 1.875104 * 1.875104 / (2 * PI) * sqrt(gfrp.e * i / (mu * l * l * l * l))
        near(f1, r.modes[0].frequency, 0.005)
        assertTrue(r.modes[0].frequency <= r.modes[1].frequency)
    }

    @Test
    fun modal_simplySupportedBeam() {
        val l = 4.0
        val mat = Material.concrete(25.0)
        val sec = Section.rect(0.3, 0.5)
        val model = Generators.continuousBeam(listOf(l), sec, mat, 0.0, selfWeight = true)
        val r = Modal.analyse(model, nModes = 4, segments = 6)
        val mu = mat.gamma * sec.a / Modal.G
        val fz = PI / 2 * sqrt(mat.e * sec.iz / mu) / (l * l)     // lateral (weak-axis) bending
        val fy = PI / 2 * sqrt(mat.e * sec.iy / mu) / (l * l)     // vertical bending
        near(fz, r.modes[0].frequency, 0.005)
        assertTrue(r.modes.any { abs(it.frequency - fy) / fy < 0.005 })
        assertTrue(r.modes[0].participation[1] > 0.5)              // first mode moves in Y
    }
}
