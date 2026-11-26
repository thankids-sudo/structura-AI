package com.structura.core.fem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOpsTest {
    private fun portal(): FrameModel {
        val mat = Material.concrete(25.0); val col = Section.rect(0.3, 0.45); val bm = Section.rect(0.3, 0.5)
        val nodes = listOf(Node(1, 0.0, 0.0, 0.0), Node(2, 0.0, 0.0, 3.0), Node(3, 5.0, 0.0, 3.0), Node(4, 5.0, 0.0, 0.0))
        val members = listOf(Member(1, 1, 2, mat, col), Member(2, 2, 3, mat, bm), Member(3, 4, 3, mat, col))
        return FrameModel(nodes, members, listOf(Support.fixed(1), Support.fixed(4)),
            memberLoads = listOf(MemberLoad(2, LoadDir.GLOBAL_Z, -20.0, LoadCase.DL), MemberLoad(2, LoadDir.GLOBAL_Z, -10.0, LoadCase.LL)))
    }

    @Test
    fun copy_bayRepeat_sharesColumnLine() {
        val m = portal()
        // copy the beam and right column 5 m along X twice: shared nodes merge
        val (c, last) = ModelOps.copy(m, ModelOps.Sel(members = setOf(2, 3)), Vec3(5.0, 0.0, 0.0), 2)
        assertEquals(3 + 4, c.members.size)
        assertEquals(4 + 4, c.nodes.size)
        assertEquals(4, c.supports.size)
        assertEquals(2 + 4, c.memberLoads.size)
        assertEquals(2, last.members.size)
        val res = FrameSolver.analyseAll(c, LoadCombination.forModel(c.loadCases))
        assertTrue(res.isNotEmpty())
    }

    @Test
    fun mirror_move_rotate_merge() {
        val m = portal()
        val (mi, _) = ModelOps.mirror(m, ModelOps.Sel(members = setOf(1, 2, 3)), 'X', 5.0, keep = true)
        assertEquals(6, mi.members.size); assertEquals(6, mi.nodes.size)          // shares the column line at x = 5
        val mv = ModelOps.move(m, ModelOps.Sel(nodes = setOf(2, 3)), Vec3(0.0, 0.0, 0.5))
        assertEquals(3.5, mv.node(3).z, 1e-12)
        val (rt, _) = ModelOps.rotate(m, ModelOps.Sel(members = setOf(1, 2, 3)), 90.0, 0.0, 0.0, keep = true)
        assertEquals(6, rt.members.size); assertEquals(7, rt.nodes.size)          // shares the left column
        val (ap, s) = ModelOps.append(m, m, Vec3(5.0, 0.0, 0.0))
        assertEquals(6, ap.members.size); assertEquals(3, s.members.size)
        val sub = ModelOps.subdivide(m, setOf(2), 4)
        assertEquals(6, sub.members.size)
        assertEquals(setOf(1, 3), ModelOps.columns(m)); assertEquals(setOf(2), ModelOps.beams(m))
    }

    @Test
    fun rccModelDesign_beamsAndColumns() {
        val m = portal()
        val res = FrameSolver.analyseAll(m, LoadCombination.forModel(m.loadCases))
        val d = RccModelDesign.design(m, res)
        assertEquals(3, d.size)
        assertEquals(2, d.count { it.kind == "Column" })
        d.forEach { println(it.summary) }
        assertTrue(d.first { it.kind == "Beam" }.mu > 30)
    }
}
