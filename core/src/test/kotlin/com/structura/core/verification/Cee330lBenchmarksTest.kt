package com.structura.core.verification

import com.structura.core.fem.Benchmarks
import com.structura.core.fem.Builders
import com.structura.core.fem.FrameResults
import com.structura.core.fem.FrameSolver
import com.structura.core.fem.LateralLoads
import com.structura.core.fem.LoadCase
import com.structura.core.fem.LoadCombination
import com.structura.core.fem.Material
import com.structura.core.fem.Seismic
import com.structura.core.fem.Section
import com.structura.core.fem.Support
import com.structura.core.fem.Vec3
import com.structura.core.fem.FrameModel
import com.structura.core.fem.Node
import com.structura.core.fem.Member
import com.structura.core.fem.NodalLoad
import com.structura.core.rcc.BeamDesigner
import com.structura.core.rcc.BeamInput
import com.structura.core.rcc.ColumnDesigner
import com.structura.core.rcc.ColumnInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** CEE 330L verification suite (STAAD.Pro lab manual structures) — residuals are printed to the CI log. */
class Cee330lBenchmarksTest {
    private val dlLl = LoadCombination("1.0 DL + 1.0 LL", mapOf(LoadCase.DL to 1.0, LoadCase.LL to 1.0))

    private fun report(name: String, r: FrameResults): Pair<Double, Double> {
        val f = r.equilibriumError.maxOf { abs(it) }; val m = r.momentEquilibriumError.maxOf { abs(it) }
        println("[$name] force residual = ${"%.3e".format(f)} kN, moment residual = ${"%.3e".format(m)} kN·m")
        println(r.staticCheck())
        return f to m
    }

    /** Σ of member end forces (global) at [node] — equals applied nodal load + reaction there. */
    private fun nodeSum(r: FrameResults, node: Int): DoubleArray {
        val s = DoubleArray(6)
        for (m in r.model.members) {
            val e = r.memberEndForces[m.id]!!; val ax = r.memberAxes[m.id]!!
            val off = when (node) { m.i -> 0; m.j -> 6; else -> continue }
            val f = ax.toGlobal(Vec3(e[off], e[off + 1], e[off + 2])); val mm = ax.toGlobal(Vec3(e[off + 3], e[off + 4], e[off + 5]))
            s[0] += f.x; s[1] += f.y; s[2] += f.z; s[3] += mm.x; s[4] += mm.y; s[5] += mm.z
        }
        return s
    }

    @Test
    fun b1_pitchedPortal() {
        val m = Benchmarks.pitchedPortal()
        val r = FrameSolver.analyse(m, dlLl)
        val (fr, mr) = report("B1", r)
        assertTrue(fr < 1e-6); assertTrue(mr < 1e-5)
        // reactions at A and F carry the whole vertical load
        assertEquals(-r.totalLoad[2], r.reactions[1]!![2] + r.reactions[6]!![2], 1e-6)
        assertTrue(r.reactions[1]!![2] > 0 && r.reactions[6]!![2] > 0)
        // joint C (BC → CD): member end forces balance (no nodal load) — SFD/BMD continuity
        assertTrue(nodeSum(r, 3).all { abs(it) < 1e-6 })
        for (n in 2..5) assertTrue(r.nodeDisplacements[n]!!.all { it.isFinite() && abs(it) < 1.0 })
        val cd = r.internalForces(3, 20)
        assertTrue(cd.all { it.my.isFinite() && it.vz.isFinite() })
    }

    @Test
    fun b2_finkTruss() {
        val m = Benchmarks.finkTruss()
        val r = FrameSolver.analyse(m, dlLl)
        val (fr, mr) = report("B2", r)
        assertTrue(fr < 1e-6); assertTrue(mr < 1e-6)
        for (mem in m.members) {
            val e = r.memberEndForces[mem.id]!!
            for (k in listOf(3, 4, 5, 9, 10, 11)) assertTrue("M${mem.id} end moment ${e[k]}", abs(e[k]) < 1e-9)
        }
        // equilibrium of every joint: Σ end forces = nodal load + reaction
        for (n in m.nodes) {
            val s = nodeSum(r, n.id)
            val p = DoubleArray(3); m.factored(dlLl).nodalLoads.filter { it.node == n.id }.forEach { l -> for (a in 0 until 3) p[a] += l.values[a] }
            val re = r.reactions[n.id] ?: DoubleArray(6)
            for (a in 0 until 3) assertEquals("node ${n.id} dir $a", p[a] + re[a], s[a], 1e-6)
        }
        // symmetric truss, symmetric load
        assertEquals(r.reactions[1]!![2], r.reactions[4]!![2], 1e-6)
    }

    @Test
    fun b3_prattTruss() {
        val m = Benchmarks.prattTruss()
        val r = FrameSolver.analyse(m, dlLl)
        report("B3", r)
        val left = m.supports[0].node; val right = m.supports[1].node
        assertEquals(r.reactions[left]!![2], r.reactions[right]!![2], 1e-6 * r.reactions[left]!![2])
        for (mem in m.members) {
            val a = m.node(mem.i); val b = m.node(mem.j)
            val n = r.internalForces(mem.id, 2)[1].n
            if (a.z == 0.0 && b.z == 0.0) assertTrue("bottom chord M${mem.id} in tension", n > 0)
            if (a.z > 0 && b.z > 0) assertTrue("top chord M${mem.id} in compression", n < 0)
            val e = r.memberEndForces[mem.id]!!
            for (k in listOf(4, 5, 10, 11)) assertTrue(abs(e[k]) < 1e-9)
        }
    }

    @Test
    fun b4_building_seismicWindAndDesign() {
        val base = Benchmarks.building()
        val cols = base.members.count { abs(base.node(it.i).z - base.node(it.j).z) > 1.0 }
        assertEquals(12 * 3, cols)
        val veranda = base.nodes.count { it.y > 8.0 + 1e-6 }
        assertEquals(4 * 3, veranda)
        // IS 1893 equivalent static
        val p = Seismic.Params(z = 0.16, importance = 1.0, r = 5.0, soil = Seismic.Soil.II)
        val eq = LateralLoads.seismicStatic(base, p, axis = 0)
        val w = LateralLoads.nodalWeights(base, 0.25).filterKeys { id -> base.supports.none { it.node == id } && base.node(id).z > 0.1 }.values.sum()
        val h = 3 * 3.2
        val ah = Seismic.ah(0.075 * Math.pow(h, 0.75), p, static = true)
        println("[B4] W = ${"%.1f".format(w)} kN, Ah = ${"%.5f".format(ah)}, VB = ${"%.2f".format(eq.baseShear)} kN")
        assertEquals(ah * w, eq.baseShear, 1e-6 * eq.baseShear)
        assertEquals(eq.baseShear, eq.storeys.sumOf { it.force }, 1e-9 * eq.baseShear)
        assertEquals(eq.baseShear, eq.loads.sumOf { it.values[0] }, 1e-9 * eq.baseShear)
        val m = base.copy(nodalLoads = base.nodalLoads + eq.loads)
        val combos = LoadCombination.isLimitState(true)
        val res = FrameSolver.analyseAll(m, combos)
        for ((c, r) in res) {
            val (fr, mr) = report("B4 ${c.name}", r)
            assertTrue(fr < 1e-5 * (abs(r.totalLoad[2]) + 1)); assertTrue(mr < 1e-4 * (abs(r.totalLoad[2]) * 20 + 1))
        }
        val (c15, r15) = res.first { it.first.name == "1.5(DL+WL)" }
        assertEquals(-1.5 * eq.baseShear, r15.reactions.values.sumOf { it[0] }, 1e-6 * eq.baseShear)
        println("[B4] ${c15.name}: Σ Rx = ${"%.3f".format(r15.reactions.values.sumOf { it[0] })} kN")
        // IS 875-3 wind
        val wind = LateralLoads.wind(base, LateralLoads.WindParams(vb = 44.0, terrain = 2), axis = 0)
        assertEquals(wind.baseShear, wind.loads.sumOf { it.values[0] }, 1e-9 * wind.baseShear)
        assertTrue(wind.baseShear > 0)
        // IS 456 design runs on the governing beam and column
        var mu = 0.0; var vu = 0.0; var pu = 0.0; var mc = 0.0
        for ((_, r) in res) for (mem in m.members) {
            val st = r.internalForces(mem.id, 10)
            if (abs(m.node(mem.i).z - m.node(mem.j).z) < 0.1) { mu = maxOf(mu, st.maxOf { abs(it.my) }); vu = maxOf(vu, st.maxOf { abs(it.vz) }) }
            else { pu = maxOf(pu, st.maxOf { -it.n }); mc = maxOf(mc, st.maxOf { maxOf(abs(it.my), abs(it.mz)) }) }
        }
        val beam = BeamDesigner.design(BeamInput(widthB = 230.0, overallDepthD = 450.0, effectiveSpan = 5000.0, fck = 25.0, fy = 500.0, factoredMoment = mu, factoredShear = vu))
        val col = ColumnDesigner.design(ColumnInput(b = 300.0, d = 450.0, fck = 25.0, fy = 500.0, pu = pu, mux = mc, unsupportedLength = 3200.0))
        println("[B4] beam Mu = ${"%.1f".format(mu)}, Vu = ${"%.1f".format(vu)} → ${beam.checks.size} checks; column Pu = ${"%.1f".format(pu)}, Mu = ${"%.1f".format(mc)} → ${col.checks.size} checks")
        assertTrue(beam.checks.isNotEmpty() && col.checks.isNotEmpty())
    }

    @Test
    fun kernel_tensionOnly_springs_pointMoment() {
        // X-braced single panel: one diagonal goes slack under lateral load
        val mat = Material.steel(250.0)
        val nodes = listOf(Node(1, 0.0, 0.0, 0.0), Node(2, 4.0, 0.0, 0.0), Node(3, 0.0, 0.0, 3.0), Node(4, 4.0, 0.0, 3.0))
        val col = Section.rhs(0.15, 0.15, 0.006); val br = Section.chs(0.06, 0.004)
        val members = listOf(
            Member(1, 1, 3, mat, col, hingeI = true, hingeJ = true), Member(2, 2, 4, mat, col, hingeI = true, hingeJ = true),
            Member(3, 3, 4, mat, col, hingeI = true, hingeJ = true),
            Member(4, 1, 4, mat, br, truss = true, tensionOnly = true), Member(5, 2, 3, mat, br, truss = true, tensionOnly = true),
        )
        val m = FrameModel(nodes, members, listOf(Support(1, listOf(true, true, true, false, false, false)), Support(2, listOf(true, true, true, false, false, false))),
            nodalLoads = listOf(NodalLoad(3, listOf(20.0, 0.0, 0.0, 0.0, 0.0, 0.0))), selfWeight = false, planeXZ = true)
        val r = FrameSolver.analyse(m)
        assertEquals(setOf(5), r.inactiveMembers)                  // 2→3 diagonal would be compressed
        assertEquals(20.0 * 5.0 / 4.0, r.internalForces(4, 2)[1].n, 1e-3)   // T = H / cosθ
        // spring support: cantilever tip on a spring carries k·δ
        val cant = FrameModel(listOf(Node(1, 0.0, 0.0, 0.0), Node(2, 3.0, 0.0, 0.0)), listOf(Member(1, 1, 2, mat, col)),
            listOf(Support.fixed(1), Support.spring(2, 0.0, 0.0, 500.0)), nodalLoads = listOf(NodalLoad(2, listOf(0.0, 0.0, -10.0, 0.0, 0.0, 0.0))), selfWeight = false)
        val rc = FrameSolver.analyse(cant)
        assertEquals(-500.0 * rc.nodeDisplacements[2]!![2], rc.reactions[2]!![2], 1e-9)
        assertTrue(rc.equilibriumError.all { abs(it) < 1e-9 } && rc.momentEquilibriumError.all { abs(it) < 1e-9 })
        // point moment on a simply supported beam: reactions ±M/L
        val beam = FrameModel(listOf(Node(1, 0.0, 0.0, 0.0), Node(2, 6.0, 0.0, 0.0)), listOf(Member(1, 1, 2, mat, col)),
            listOf(Support(1, listOf(true, true, true, true, false, false)), Support(2, listOf(false, true, true, false, false, false))), selfWeight = false)
        val bm = Builders.memberMoment(beam, 1, 2.0, axis = 1, moment = 30.0)
        val rb = FrameSolver.analyse(bm)
        assertEquals(30.0 / 6.0, abs(rb.reactions[1]!![2]), 1e-9)
        assertEquals(0.0, rb.reactions[1]!![2] + rb.reactions[2]!![2], 1e-9)
        assertTrue(rb.momentEquilibriumError.all { abs(it) < 1e-9 })
    }

    @Test
    fun sections_parametric() {
        val i = Section.iBeam(0.3, 0.14, 0.0124, 0.0075)
        assertEquals(2 * 0.14 * 0.0124 + (0.3 - 0.0248) * 0.0075, i.a, 1e-12)
        assertTrue(i.iw > 0 && i.iy > i.iz)
        val t = Section.tee(0.4, 0.6, 0.12, 0.25)
        val zc = (0.6 * 0.12 * 0.06 + 0.28 * 0.25 * (0.12 + 0.14)) / t.a
        assertEquals(zc, 0.0 + (0.6 * 0.12 * 0.06 + 0.28 * 0.25 * 0.26) / (0.6 * 0.12 + 0.28 * 0.25), 1e-12)
        val c = Section.circle(0.4); assertEquals(Math.PI * 0.4 * 0.4 / 4, c.a, 1e-12); assertEquals(2 * c.iy, c.j, 1e-15)
        val a = Section.angle(0.06, 0.06, 0.004)
        assertEquals(0.004 * (0.12 - 0.004), a.a, 1e-12); assertEquals(a.iy, a.iz, 1e-15)
        val tri = Section.triangle(1.0, Math.sqrt(3.0) / 2)                 // equilateral, a = 1
        assertEquals(Math.sqrt(3.0) / 80, tri.j, 2e-4)
        assertEquals(4700 * Math.sqrt(30.0) * 1000, Material.concreteAci(30.0).e, 1e-6)
    }
}
