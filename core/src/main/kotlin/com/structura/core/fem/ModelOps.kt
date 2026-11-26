package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Operations on a selection of nodes and members (STAAD-style translational repeat, move, mirror, rotate,
 * property assignment). Every function returns a new [FrameModel]; coincident nodes are merged afterwards so
 * copied bays connect to the existing structure.
 */
object ModelOps {
    data class Sel(val nodes: Set<Int> = emptySet(), val members: Set<Int> = emptySet()) {
        val isEmpty get() = nodes.isEmpty() && members.isEmpty()
        val count get() = nodes.size + members.size
    }

    /** All nodes touched by the selection (selected nodes + ends of selected members). */
    fun nodesOf(m: FrameModel, s: Sel): Set<Int> = s.nodes + m.members.filter { it.id in s.members }.flatMap { listOf(it.i, it.j) }

    private fun transform(m: FrameModel, s: Sel, f: (Vec3) -> Vec3): FrameModel {
        val ids = nodesOf(m, s)
        return m.copy(nodes = m.nodes.map { if (it.id in ids) f(it.pos).let { p -> Node(it.id, p.x, p.y, p.z) } else it })
    }

    fun move(m: FrameModel, s: Sel, d: Vec3): FrameModel = merge(transform(m, s) { it + d })

    /**
     * Copies the selection [times] times, each copy offset by [f] applied repeatedly to the previous copy's points.
     * Loads on copied nodes / members are copied too when [withLoads]. Returns the model and the last copy's selection.
     */
    fun copyWith(m: FrameModel, s: Sel, times: Int, withLoads: Boolean = true, withSupports: Boolean = true, f: (Vec3, Int) -> Vec3): Pair<FrameModel, Sel> {
        var model = m
        val srcNodes = nodesOf(m, s)
        val srcMembers = m.members.filter { it.id in s.members }
        var last = Sel()
        for (k in 1..times.coerceIn(1, 100)) {
            val map = HashMap<Int, Int>()
            for (id in srcNodes) {
                val p = f(m.node(id).pos, k)
                val (mm, nid) = ModelEditor.addNode(model, p); model = mm; map[id] = nid
            }
            val newMembers = ArrayList<Int>()
            for (mem in srcMembers) {
                val i = map.getValue(mem.i); val j = map.getValue(mem.j)
                if (i == j || model.members.any { (it.i == i && it.j == j) || (it.i == j && it.j == i) }) continue
                val id = ModelEditor.nextMemberId(model)
                model = model.copy(members = model.members + mem.copy(id = id, i = i, j = j))
                newMembers += id
                if (withLoads) model = model.copy(
                    memberLoads = model.memberLoads + m.memberLoads.filter { it.member == mem.id }.map { it.copy(member = id) },
                    pointLoads = model.pointLoads + m.pointLoads.filter { it.member == mem.id }.map { it.copy(member = id) },
                )
            }
            if (withLoads) model = model.copy(nodalLoads = model.nodalLoads + m.nodalLoads.filter { it.node in s.nodes }.map { it.copy(node = map.getValue(it.node)) })
            if (withSupports) for (sp in m.supports.filter { it.node in srcNodes }) {
                val n = map.getValue(sp.node)
                if (model.supports.none { it.node == n }) model = model.copy(supports = model.supports + sp.copy(node = n))
            }
            last = Sel(s.nodes.map { map.getValue(it) }.toSet(), newMembers.toSet())
        }
        return merge(model) to last
    }

    fun copy(m: FrameModel, s: Sel, d: Vec3, times: Int = 1, withLoads: Boolean = true) =
        copyWith(m, s, times, withLoads) { p, k -> p + d * k.toDouble() }

    /** Mirror about the plane x = [at] (axis 'X') or y = [at] (axis 'Y') or z = [at] ('Z'); [keep] = copy, else move. */
    fun mirror(m: FrameModel, s: Sel, axis: Char, at: Double, keep: Boolean = true): Pair<FrameModel, Sel> {
        val f: (Vec3) -> Vec3 = when (axis.uppercaseChar()) {
            'X' -> { p -> Vec3(2 * at - p.x, p.y, p.z) }
            'Y' -> { p -> Vec3(p.x, 2 * at - p.y, p.z) }
            else -> { p -> Vec3(p.x, p.y, 2 * at - p.z) }
        }
        return if (keep) copyWith(m, s, 1) { p, _ -> f(p) } else merge(transform(m, s, f)) to s
    }

    /** Rotation about the vertical axis through ([cx], [cy]) by [deg] (anticlockwise seen from above). */
    fun rotate(m: FrameModel, s: Sel, deg: Double, cx: Double, cy: Double, keep: Boolean = false, times: Int = 1): Pair<FrameModel, Sel> {
        fun rot(p: Vec3, k: Int): Vec3 {
            val a = Math.toRadians(deg * k); val dx = p.x - cx; val dy = p.y - cy
            return Vec3(cx + dx * cos(a) - dy * sin(a), cy + dx * sin(a) + dy * cos(a), p.z)
        }
        return if (keep) copyWith(m, s, times) { p, k -> rot(p, k) } else merge(transform(m, s) { rot(it, 1) }) to s
    }

    fun delete(m: FrameModel, s: Sel): FrameModel {
        var model = m
        for (id in s.members) if (model.members.any { it.id == id }) model = ModelEditor.deleteMember(model, id)
        for (id in s.nodes) if (model.nodes.any { it.id == id }) model = ModelEditor.deleteNode(model, id)
        return model
    }

    /** Merges nodes closer than [tol]; members become connected, duplicates and zero-length members are dropped. */
    fun merge(m: FrameModel, tol: Double = ModelEditor.MERGE_TOL): FrameModel {
        val keep = ArrayList<Node>(); val map = HashMap<Int, Int>()
        for (n in m.nodes) {
            val hit = keep.firstOrNull { (it.pos - n.pos).length <= tol }
            if (hit == null) { keep += n; map[n.id] = n.id } else map[n.id] = hit.id
        }
        if (keep.size == m.nodes.size) return m
        val seen = HashSet<Pair<Int, Int>>()
        val members = m.members.map { it.copy(i = map.getValue(it.i), j = map.getValue(it.j)) }.filter { mem ->
            val key = minOf(mem.i, mem.j) to maxOf(mem.i, mem.j)
            mem.i != mem.j && seen.add(key)
        }
        val alive = members.map { it.id }.toSet()
        return m.copy(
            nodes = keep, members = members,
            supports = m.supports.map { it.copy(node = map.getValue(it.node)) }.distinctBy { it.node },
            nodalLoads = m.nodalLoads.map { it.copy(node = map.getValue(it.node)) },
            memberLoads = m.memberLoads.filter { it.member in alive }, pointLoads = m.pointLoads.filter { it.member in alive },
            panels = m.panels.map { p -> p.copy(nodes = p.nodes.map { map.getValue(it) }) },
        )
    }

    fun setSection(m: FrameModel, members: Set<Int>, section: Section, material: Material? = null) =
        m.copy(members = m.members.map { if (it.id in members) it.copy(section = section, material = material ?: it.material) else it })

    fun setReleases(m: FrameModel, members: Set<Int>, hingeI: Boolean, hingeJ: Boolean, truss: Boolean = false) =
        m.copy(members = m.members.map { if (it.id in members) it.copy(hingeI = hingeI, hingeJ = hingeJ, truss = truss) else it })

    fun setSupports(m: FrameModel, nodes: Set<Int>, type: ModelEditor.SupportType) = nodes.fold(m) { acc, n -> ModelEditor.setSupport(acc, n, type) }

    fun addNodalLoads(m: FrameModel, nodes: Set<Int>, f: Vec3, case: String) = nodes.fold(m) { acc, n -> ModelEditor.addNodalLoad(acc, n, f, case) }

    fun addUdls(m: FrameModel, members: Set<Int>, w: Double, case: String, dir: LoadDir = LoadDir.GLOBAL_Z) =
        members.fold(m) { acc, id -> ModelEditor.addMemberUdl(acc, id, dir, w, case) }

    fun clearLoads(m: FrameModel, s: Sel) = m.copy(
        nodalLoads = m.nodalLoads.filter { it.node !in s.nodes }, memberLoads = m.memberLoads.filter { it.member !in s.members },
        pointLoads = m.pointLoads.filter { it.member !in s.members },
    )

    /** Splits each selected member into [parts] equal parts. */
    fun subdivide(m: FrameModel, members: Set<Int>, parts: Int): FrameModel {
        var model = m
        for (id in members) {
            var cur = id
            for (k in parts downTo 2) {
                if (model.members.none { it.id == cur }) break
                val (mm, nid) = ModelEditor.splitMember(model, cur, 1.0 / k); model = mm
                cur = model.members.first { it.i == nid && it.id != cur }.id
            }
        }
        return model
    }

    /**
     * Appends [add] to [base], shifted by [offset]; ids are renumbered after the base's, coincident nodes merged.
     * Returns the model and the selection of the appended part.
     */
    fun append(base: FrameModel, add: FrameModel, offset: Vec3): Pair<FrameModel, Sel> {
        val n0 = ModelEditor.nextNodeId(base) - 1; val m0 = ModelEditor.nextMemberId(base) - 1
        val nodes = add.nodes.map { Node(it.id + n0, it.x + offset.x, it.y + offset.y, it.z + offset.z) }
        val members = add.members.map { it.copy(id = it.id + m0, i = it.i + n0, j = it.j + n0) }
        val joined = base.copy(
            nodes = base.nodes + nodes, members = base.members + members,
            supports = base.supports + add.supports.map { it.copy(node = it.node + n0) },
            nodalLoads = base.nodalLoads + add.nodalLoads.map { it.copy(node = it.node + n0) },
            memberLoads = base.memberLoads + add.memberLoads.map { it.copy(member = it.member + m0) },
            pointLoads = base.pointLoads + add.pointLoads.map { it.copy(member = it.member + m0) },
            planeXZ = base.planeXZ && add.planeXZ && base.nodes.isEmpty(),
        )
        val merged = merge(joined)
        val alive = merged.members.map { it.id }.toSet(); val aliveN = merged.nodes.map { it.id }.toSet()
        return merged to Sel(nodes.map { it.id }.filter { it in aliveN }.toSet(), members.map { it.id }.filter { it in alive }.toSet())
    }

    // ---- selection helpers
    private fun dirZ(m: FrameModel, mem: Member): Double {
        val d = m.node(mem.j).pos - m.node(mem.i).pos
        return abs(d.z) / d.length.coerceAtLeast(1e-12)
    }
    fun columns(m: FrameModel) = m.members.filter { dirZ(m, it) > 0.9 }.map { it.id }.toSet()
    fun beams(m: FrameModel) = m.members.filter { dirZ(m, it) < 0.1 }.map { it.id }.toSet()
    fun braces(m: FrameModel) = m.members.map { it.id }.toSet() - columns(m) - beams(m)
    fun atLevel(m: FrameModel, z: Double, tol: Double = 0.01): Sel {
        val ns = m.nodes.filter { abs(it.z - z) <= tol }.map { it.id }.toSet()
        return Sel(ns, m.members.filter { it.i in ns && it.j in ns }.map { it.id }.toSet())
    }
    fun similar(m: FrameModel, member: Int): Set<Int> {
        val ref = m.members.first { it.id == member }
        return m.members.filter { it.section.name == ref.section.name && it.material.name == ref.material.name }.map { it.id }.toSet()
    }
    fun all(m: FrameModel) = Sel(m.nodes.map { it.id }.toSet(), m.members.map { it.id }.toSet())
    fun invert(m: FrameModel, s: Sel) = Sel(m.nodes.map { it.id }.toSet() - s.nodes, m.members.map { it.id }.toSet() - s.members)

    /** Centroid of the selection's nodes (for mirror / rotate defaults). */
    fun centroid(m: FrameModel, s: Sel): Vec3 {
        val ps = nodesOf(m, s).map { m.node(it).pos }
        if (ps.isEmpty()) return Vec3(0.0, 0.0, 0.0)
        return ps.reduce { a, b -> a + b } * (1.0 / ps.size)
    }
}
