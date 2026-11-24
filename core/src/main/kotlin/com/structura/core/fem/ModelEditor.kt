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
        )
        return removeOrphans(mm)
    }

    /** Deletes a node with every member connected to it, its support and its loads. */
    fun deleteNode(m: FrameModel, id: Int): FrameModel {
        val gone = m.members.filter { it.i == id || it.j == id }.map { it.id }.toSet()
        return m.copy(
            nodes = m.nodes.filter { it.id != id },
            members = m.members.filter { it.id !in gone },
            supports = m.supports.filter { it.node != id },
            nodalLoads = m.nodalLoads.filter { it.node != id },
            memberLoads = m.memberLoads.filter { it.member !in gone },
            pointLoads = m.pointLoads.filter { it.member !in gone },
        )
    }

    private fun removeOrphans(m: FrameModel): FrameModel {
        val used = m.members.flatMap { listOf(it.i, it.j) }.toSet() +
            m.supports.map { it.node } + m.nodalLoads.map { it.node }
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
        val first = mem.copy(j = nid, hingeJ = false)
        val second = mem.copy(id = id2, i = nid, hingeI = false)
        val la = len * t
        val udl = m1.memberLoads.filter { it.member == id }.map { it.copy(member = id2) }
        val points = m1.pointLoads.map { p ->
            if (p.member != id) p
            else if (p.a <= la) p
            else p.copy(member = id2, a = p.a - la)
        }
        return m1.copy(
            members = m1.members.flatMap { if (it.id == id) listOf(first, second) else listOf(it) },
            memberLoads = m1.memberLoads + udl,
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

    /** Quick model checks shown before analysis (like RFEM's "plausibility check"). */
    fun check(m: FrameModel): List<String> = buildList {
        if (m.nodes.isEmpty()) add("Model has no nodes.")
        if (m.members.isEmpty()) add("Model has no members.")
        if (m.supports.isEmpty()) add("No supports — add at least one fixed or pinned support.")
        val connected = m.members.flatMap { listOf(it.i, it.j) }.toSet()
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
