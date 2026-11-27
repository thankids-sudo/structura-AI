package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.round

/**
 * Immutable editing operations for the 3D Studio (each returns a new [FrameModel], so undo/redo
 * is simply a list of snapshots). Node and member ids are never reused within a model.
 */
object ModelEditor {

    const val MERGE_TOL = 1e-4   // m — a new node this close to an existing one reuses it

    fun empty(title: String = "New model") =
        FrameModel(emptyList(), emptyList(), emptyList(), selfWeight = true, title = title)

    fun snap(v: Double, step: Double): Double = if (step > 0) round(v / step) * step else v

    fun nextNodeId(m: FrameModel) = (m.nodes.maxOfOrNull { it.id } ?: 0) + 1
    fun nextMemberId(m: FrameModel) = (m.members.maxOfOrNull { it.id } ?: 0) + 1

    fun findNode(m: FrameModel, p: Vec3, tol: Double = MERGE_TOL): Node? =
        m.nodes.firstOrNull { (it.pos - p).length <= tol }

    /** Adds a node (or returns the existing one at the same point). */
    fun addNode(m: FrameModel, p: Vec3): Pair<FrameModel, Int> {
        findNode(m, p)?.let { return m to it.id }
        val id = nextNodeId(m)
        return m.copy(nodes = m.nodes + Node(id, p.x, p.y, p.z)) to id
    }

    fun moveNode(m: FrameModel, id: Int, p: Vec3): FrameModel =
        m.copy(nodes = m.nodes.map { if (it.id == id) Node(id, p.x, p.y, p.z) else it })

    /** Adds a member between two existing nodes; refuses duplicates and zero length. */
    fun addMember(m: FrameModel, i: Int, j: Int, material: Material, section: Section): Pair<FrameModel, Int?> {
        if (i == j) return m to null
        if (m.members.any { (it.i == i && it.j == j) || (it.i == j && it.j == i) }) return m to null
        val id = nextMemberId(m)
        return m.copy(members = m.members + Member(id, i, j, material, section)) to id
    }

    fun updateMember(m: FrameModel, id: Int, f: (Member) -> Member): FrameModel =
        m.copy(members = m.members.map { if (it.id == id) f(it) else it })

    enum class SupportType { FIXED, PINNED, ROLLER_X, NONE }

    fun setSupport(m: FrameModel, node: Int, type: SupportType): FrameModel {
        val others = m.supports.filter { it.node != node }
        val s = when (type) {
            SupportType.FIXED -> Support.fixed(node)
            SupportType.PINNED -> Support.pinned(node)
            SupportType.ROLLER_X -> Support.rollerX(node)
            SupportType.NONE -> null
        }
        return m.copy(supports = if (s == null) others else others + s)
    }

    fun supportType(m: FrameModel, node: Int): SupportType {
        val s = m.supports.firstOrNull { it.node == node } ?: return SupportType.NONE
        return when (s.restraints) {
            Support.fixed(node).restraints -> SupportType.FIXED
            Support.pinned(node).restraints -> SupportType.PINNED
            Support.rollerX(node).restraints -> SupportType.ROLLER_X
            else -> SupportType.FIXED
        }
    }

    fun addNodalLoad(m: FrameModel, node: Int, f: Vec3, case: String): FrameModel =
        m.copy(nodalLoads = m.nodalLoads + NodalLoad(node, listOf(f.x, f.y, f.z, 0.0, 0.0, 0.0), case))

    fun addMemberUdl(m: FrameModel, member: Int, dir: LoadDir, w: Double, case: String): FrameModel =
        m.copy(memberLoads = m.memberLoads + MemberLoad(member, dir, w, case))

    fun addPointLoad(m: FrameModel, member: Int, dir: LoadDir, p: Double, a: Double, case: String): FrameModel =
        m.copy(pointLoads = m.pointLoads + PointLoad(member, dir, p, a, case))

    fun clearLoadsAtNode(m: FrameModel, node: Int) = m.copy(nodalLoads = m.nodalLoads.filter { it.node != node })

    fun clearLoadsOnMember(m: FrameModel, member: Int) = m.copy(
        memberLoads = m.memberLoads.filter { it.member != member },
        pointLoads = m.pointLoads.filter { it.member != member },
    )

    /** Deletes a member and its loads; nodes left without members and without supports/loads are removed too. */
    fun deleteMember(m: FrameModel, id: Int): FrameModel {
        val mm = m.copy(
            members = m.members.filter { it.id != id },
            memberLoads = m.memberLoads.filter { it.member != id },
            pointLoads = m.pointLoads.filter { it.member != id },
            roles = m.roles - id,
        )
        return removeOrphans(mm)
    }

    /** Deletes a node with every member connected to it, its support and its loads. */
    fun deleteNode(m: FrameModel, id: Int): FrameModel {
        val gone = m.members.filter { it.i == id || it.j == id }.map { it.id }.toSet()
        return m.copy(
            panels = m.panels.filter { id !in it.nodes },
            nodes = m.nodes.filter { it.id != id },
            members = m.members.filter { it.id !in gone },
            supports = m.supports.filter { it.node != id },
            footings = m.footings - id,
            roles = m.roles - gone,
            nodalLoads = m.nodalLoads.filter { it.node != id },
            memberLoads = m.memberLoads.filter { it.member !in gone },
            pointLoads = m.pointLoads.filter { it.member !in gone },
        )
    }

    private fun removeOrphans(m: FrameModel): FrameModel {
        val used = m.members.flatMap { listOf(it.i, it.j) }.toSet() +
            m.supports.map { it.node } + m.nodalLoads.map { it.node } + m.panels.flatMap { it.nodes }
        return m.copy(nodes = m.nodes.filter { it.id in used })
    }

    /**
     * Splits a member at fraction [t] (0..1) from node i. UDLs are copied to both parts, point loads
     * move to the part that contains them, hinges stay at the original ends.
     * Returns the new model and the new node id.
     */
    fun splitMember(m: FrameModel, id: Int, t: Double = 0.5): Pair<FrameModel, Int> {
        require(t > 0.0 && t < 1.0)
        val mem = m.members.first { it.id == id }
        val a = m.node(mem.i).pos
        val b = m.node(mem.j).pos
        val len = (b - a).length
        val (m1, nid) = addNode(m, a + (b - a) * t)
        val id2 = nextMemberId(m1)
        val la = len * t
        require(la > mem.offsetI && len - la > mem.offsetJ) { "Split point lies inside a rigid end offset" }
        val first = mem.copy(j = nid, hingeJ = false, springJ = null, offsetJ = 0.0)
        val second = mem.copy(id = id2, i = nid, hingeI = false, springI = null, offsetI = 0.0)
        // distributed loads: full uniform loads copy to both parts; partial / trapezoidal loads are clipped
        val split = m1.memberLoads.filter { it.member == id }.flatMap { ml ->
            if (ml.isUniformFull) return@flatMap listOf(ml, ml.copy(member = id2))
            val a = (ml.a ?: 0.0).coerceIn(0.0, len); val b = (ml.b ?: len).coerceIn(a, len)
            val w1 = ml.w; val w2 = ml.w2 ?: ml.w
            fun at(s: Double) = if (b - a < 1e-12) w1 else w1 + (w2 - w1) * (s - a) / (b - a)
            buildList {
                if (a < la) add(ml.copy(a = a, b = minOf(b, la), w = w1, w2 = at(minOf(b, la))))
                if (b > la) add(ml.copy(member = id2, a = maxOf(a, la) - la, b = b - la, w = at(maxOf(a, la)), w2 = w2))
            }
        }
        val points = m1.pointLoads.map { p ->
            if (p.member != id) p
            else if (p.a <= la) p
            else p.copy(member = id2, a = p.a - la)
        }
        return m1.copy(
            members = m1.members.flatMap { if (it.id == id) listOf(first, second) else listOf(it) },
            memberLoads = m1.memberLoads.filter { it.member != id } + split,
            pointLoads = points,
        ) to nid
    }

    /**
     * Mesh refinement for analyses that need internal nodes (buckling, mode shapes):
     * every member is divided into [n] equal segments. Returns the refined model and, per original
     * member, the ordered list of refined member ids.
     */
    fun subdivide(m: FrameModel, n: Int): Pair<FrameModel, Map<Int, List<Int>>> {
        if (n <= 1) return m to m.members.associate { it.id to listOf(it.id) }
        var cur = m
        val map = LinkedHashMap<Int, List<Int>>()
        for (orig in m.members) {
            val chain = arrayListOf(orig.id)
            // split the last piece repeatedly: piece k gets fraction 1/(n-k)
            for (k in 0 until n - 1) {
                val last = chain.last()
                val (next, _) = splitMember(cur, last, 1.0 / (n - k))
                cur = next
                chain += cur.members.maxOf { it.id }
            }
            map[orig.id] = chain
        }
        return cur to map
    }

    fun nextPanelId(m: FrameModel) = (m.panels.maxOfOrNull { it.id } ?: 0) + 1

    /** Adds a quadrilateral panel on 4 existing nodes (in order around the panel). */
    fun addPanel(m: FrameModel, nodes: List<Int>, proto: Panel): Pair<FrameModel, Int> {
        require(nodes.size == 4 && nodes.distinct().size == 4) { "Pick 4 different nodes around the panel" }
        val id = nextPanelId(m)
        return m.copy(panels = m.panels + proto.copy(id = id, nodes = nodes)) to id
    }

    fun updatePanel(m: FrameModel, id: Int, f: (Panel) -> Panel) = m.copy(panels = m.panels.map { if (it.id == id) f(it) else it })

    fun deletePanel(m: FrameModel, id: Int) = removeOrphans(m.copy(panels = m.panels.filter { it.id != id }))

    /**
     * Panel mesh: replaces a panel by nx × ny panels on a bilinear grid of new nodes, and splits any member
     * lying along a panel edge at the new edge nodes so beams and slab stay connected.
     */
    fun meshPanel(m: FrameModel, id: Int, nx: Int, ny: Int): FrameModel {
        require(nx >= 1 && ny >= 1)
        val p = m.panels.first { it.id == id }
        val c = p.nodes.map { m.node(it).pos }
        fun at(u: Double, v: Double) = c[0] * ((1 - u) * (1 - v)) + c[1] * (u * (1 - v)) + c[2] * (u * v) + c[3] * ((1 - u) * v)
        var cur = m
        // split edge members first so their new nodes are reused by the grid
        val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)
        for ((k, e) in edges.withIndex()) {
            val na = p.nodes[e.first]; val nb = p.nodes[e.second]
            val div = if (k % 2 == 0) nx else ny
            var mem = cur.members.firstOrNull { (it.i == na && it.j == nb) || (it.i == nb && it.j == na) } ?: continue
            val forward = mem.i == na
            for (q in 1 until div) {
                // split the remaining piece so the next node lands at q/div along the edge from na
                val remainingFrac = 1.0 / (div - q + 1)
                val t = if (forward) remainingFrac else 1 - remainingFrac
                val (next, _) = splitMember(cur, mem.id, t)
                cur = next
                val newId = cur.members.maxOf { it.id }
                mem = if (forward) cur.members.first { it.id == newId } else cur.members.first { it.id == mem.id }
            }
        }
        val grid = Array(ny + 1) { IntArray(nx + 1) }
        for (j in 0..ny) for (i in 0..nx) {
            val (mm, nid) = addNode(cur, at(i.toDouble() / nx, j.toDouble() / ny)); cur = mm; grid[j][i] = nid
        }
        var nextId = nextPanelId(cur)
        val newPanels = ArrayList<Panel>()
        for (j in 0 until ny) for (i in 0 until nx) {
            newPanels += p.copy(id = nextId++, nodes = listOf(grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i]))
        }
        return cur.copy(panels = cur.panels.filter { it.id != id } + newPanels)
    }

    /** Quick model checks shown before analysis (like RFEM's "plausibility check"). */
    fun check(m: FrameModel): List<String> = buildList {
        if (m.nodes.isEmpty()) add("Model has no nodes.")
        if (m.members.isEmpty() && m.panels.isEmpty()) add("Model has no members.")
        if (m.supports.isEmpty()) add("No supports — add at least one fixed or pinned support.")
        val connected = m.members.flatMap { listOf(it.i, it.j) }.toSet() + m.panels.flatMap { it.nodes }
        m.nodes.filter { it.id !in connected }.forEach { add("Node ${it.id} is not connected to any member.") }
        for (a in m.nodes.indices) for (b in a + 1 until m.nodes.size) {
            if ((m.nodes[a].pos - m.nodes[b].pos).length < MERGE_TOL)
                add("Nodes ${m.nodes[a].id} and ${m.nodes[b].id} coincide.")
        }
        m.members.filter { mem -> (m.node(mem.i).pos - m.node(mem.j).pos).length < MERGE_TOL }
            .forEach { add("Member ${it.id} has zero length.") }
        if (m.nodalLoads.isEmpty() && m.memberLoads.isEmpty() && m.pointLoads.isEmpty() && !m.selfWeight)
            add("No loads applied.")
        m.nodalLoads.filter { l -> l.values.all { abs(it) < 1e-12 } }.forEach { add("Zero nodal load at node ${it.node}.") }
    }
}
