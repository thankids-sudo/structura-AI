package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Geometry generators from the STAAD workflow (CEE 330L): translational repeat with link steps,
 * parametric Fink roof truss and Pratt bridge truss, and point moments on members.
 */
object Builders {

    /**
     * STAAD "Translational Repeat": copies the selected [members] (and [nodes]) [spacings].size times along the
     * unit direction [dir] at cumulative spacings. Copies landing on existing nodes are merged (1 mm). With
     * [link], every copied node is joined to its previous copy by a new member of [linkSection] / [linkMaterial]
     * (defaults: those of the first selected member) — columns when repeating up, beams / cantilevers sideways.
     */
    fun translationalRepeat(
        model: FrameModel, members: Set<Int>, nodes: Set<Int> = emptySet(), dir: Vec3, spacings: List<Double>,
        link: Boolean = false, linkSection: Section? = null, linkMaterial: Material? = null,
    ): FrameModel {
        require(spacings.isNotEmpty() && spacings.all { it > 0 }) { "Give at least one positive spacing" }
        val sel = model.members.filter { it.id in members }
        val selNodes = (nodes + sel.flatMap { listOf(it.i, it.j) }).toSortedSet()
        require(selNodes.isNotEmpty()) { "Select members or nodes to repeat" }
        val u = dir.unit()
        val ns = model.nodes.toMutableList(); val ms = model.members.toMutableList()
        var nextN = ModelEditor.nextNodeId(model); var nextM = ModelEditor.nextMemberId(model)
        fun findOrAdd(p: Vec3): Int {
            ns.firstOrNull { (it.pos - p).length < 1e-3 }?.let { return it.id }
            ns += Node(nextN, p.x, p.y, p.z); return nextN++
        }
        fun exists(a: Int, b: Int) = ms.any { (it.i == a && it.j == b) || (it.i == b && it.j == a) }
        val ref = sel.firstOrNull() ?: model.members.firstOrNull()
        val lSec = linkSection ?: ref?.section ?: error("No section for link members")
        val lMat = linkMaterial ?: ref?.material ?: error("No material for link members")
        var prev = selNodes.associateWith { it }
        var offset = 0.0
        for (s in spacings) {
            offset += s
            val cur = selNodes.associateWith { id -> findOrAdd(model.node(id).pos + u * offset) }
            for (m in sel) {
                val a = cur.getValue(m.i); val b = cur.getValue(m.j)
                if (a != b && !exists(a, b)) ms += m.copy(id = nextM++, i = a, j = b)
            }
            if (link) for (id in selNodes) {
                val a = prev.getValue(id); val b = cur.getValue(id)
                if (a != b && !exists(a, b)) ms += Member(nextM++, a, b, lMat, lSec)
            }
            prev = cur
        }
        return model.copy(nodes = ns, members = ms)
    }

    /**
     * Fink (W) roof truss in the XZ plane: bottom chord at thirds, top chord quarter points, ridge at mid-span.
     * Truss members, pinned support at the left heel and roller at the right, [panelLoad] (kN, downward, case [case])
     * at the three top-chord panel points. Plane (2D) model.
     */
    fun finkTruss(span: Double, rise: Double, section: Section, material: Material, panelLoad: Double = 0.0,
                  case: String = LoadCase.LL, selfWeight: Boolean = true): FrameModel {
        val nodes = listOf(
            Node(1, 0.0, 0.0, 0.0), Node(2, span / 3, 0.0, 0.0), Node(3, 2 * span / 3, 0.0, 0.0), Node(4, span, 0.0, 0.0),
            Node(5, span / 4, 0.0, rise / 2), Node(6, span / 2, 0.0, rise), Node(7, 3 * span / 4, 0.0, rise / 2),
        )
        val pairs = listOf(1 to 5, 5 to 6, 6 to 7, 7 to 4, 1 to 2, 2 to 3, 3 to 4, 2 to 5, 2 to 6, 3 to 6, 3 to 7)
        val members = pairs.mapIndexed { k, (a, b) -> Member(k + 1, a, b, material, section, truss = true) }
        val supports = listOf(Support(1, listOf(true, true, true, false, false, false)), Support(4, listOf(false, false, true, false, false, false)))
        val loads = if (panelLoad != 0.0) listOf(5, 6, 7).map { NodalLoad(it, listOf(0.0, 0.0, -panelLoad, 0.0, 0.0, 0.0), case) } else emptyList()
        return FrameModel(nodes, members, supports, loads, selfWeight = selfWeight,
            title = "Fink truss ${"%.1f".format(span)} m × ${"%.2f".format(rise)} m", planeXZ = true)
    }

    /**
     * Pratt bridge truss in the XZ plane: [panels] (even) bays of the bottom chord, inclined end posts,
     * verticals, diagonals sloping down towards mid-span (tension under gravity). [panelLoad] (kN) at every
     * interior bottom-chord node. Pinned left, roller right; truss members; plane model.
     */
    fun prattTruss(span: Double, height: Double, panels: Int, chord: Section, web: Section, material: Material,
                   panelLoad: Double = 0.0, case: String = LoadCase.LL, selfWeight: Boolean = true): FrameModel {
        require(panels >= 2 && panels % 2 == 0) { "Number of panels must be even" }
        val p = span / panels
        val nodes = ArrayList<Node>()
        for (i in 0..panels) nodes += Node(i + 1, i * p, 0.0, 0.0)                     // bottom 1 … n+1
        for (i in 1 until panels) nodes += Node(panels + 1 + i, i * p, 0.0, height)    // top n+2 … 2n
        fun b(i: Int) = i + 1
        fun t(i: Int) = panels + 1 + i
        val pairs = ArrayList<Triple<Int, Int, Section>>()
        for (i in 0 until panels) pairs += Triple(b(i), b(i + 1), chord)
        for (i in 1 until panels - 1) pairs += Triple(t(i), t(i + 1), chord)
        pairs += Triple(b(0), t(1), chord); pairs += Triple(t(panels - 1), b(panels), chord)       // end posts
        for (i in 1 until panels) pairs += Triple(b(i), t(i), web)                                 // verticals
        for (i in 1 until panels / 2) pairs += Triple(t(i), b(i + 1), web)                         // diagonals, left half
        for (i in panels / 2 + 1 until panels) pairs += Triple(t(i), b(i - 1), web)                // right half
        val members = pairs.mapIndexed { k, (a, c, s) -> Member(k + 1, a, c, material, s, truss = true) }
        val supports = listOf(Support(b(0), listOf(true, true, true, false, false, false)), Support(b(panels), listOf(false, false, true, false, false, false)))
        val loads = if (panelLoad != 0.0) (1 until panels).map { NodalLoad(b(it), listOf(0.0, 0.0, -panelLoad, 0.0, 0.0, 0.0), case) } else emptyList()
        return FrameModel(nodes, members, supports, loads, selfWeight = selfWeight,
            title = "Pratt truss $panels × ${"%.2f".format(p)} m, h ${"%.2f".format(height)} m", planeXZ = true)
    }

    /**
     * Concentrated moment [moment] (kN·m) about global axis [axis] (0 = X, 1 = Y, 2 = Z) at distance [a] (m)
     * from node i of [member]: the member is split there and the moment applied at the new node (exact).
     */
    fun memberMoment(model: FrameModel, member: Int, a: Double, axis: Int, moment: Double, case: String = LoadCase.LL): FrameModel {
        val m = model.members.first { it.id == member }
        val len = (model.node(m.j).pos - model.node(m.i).pos).length
        val v = MutableList(6) { 0.0 }.also { it[3 + axis] = moment }
        val target: Int; val out: FrameModel
        when {
            a <= 1e-9 -> { target = m.i; out = model }
            a >= len - 1e-9 -> { target = m.j; out = model }
            else -> { val (mm, nid) = ModelEditor.splitMember(model, member, a / len); target = nid; out = mm }
        }
        return out.copy(nodalLoads = out.nodalLoads + NodalLoad(target, v, case))
    }

    /** Node lying at (x, z) in a plane model (for tests / templates). */
    fun nodeAt(model: FrameModel, x: Double, z: Double) = model.nodes.first { abs(it.x - x) < 1e-6 && abs(it.z - z) < 1e-6 && abs(it.y) < 1e-6 }.id

    internal fun dist(a: Node, b: Node) = hypot(hypot(a.x - b.x, a.y - b.y), a.z - b.z)
}
