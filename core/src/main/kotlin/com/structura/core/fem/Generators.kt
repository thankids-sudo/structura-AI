package com.structura.core.fem

/**
 * Model generators — the quick-start templates (like RFEM's "structure wizards").
 * All lengths in m, loads in kN/m (positive value = downward gravity load).
 * Load cases: [beamUdl] → DL (finishes, walls), [liveUdl] and [pointLoadMid] → LL, [lateral] → WL.
 */
object Generators {

    /** Continuous beam along global X: pinned at the first support, rollers elsewhere. */
    fun continuousBeam(
        spans: List<Double>,
        section: Section,
        material: Material,
        udl: Double,
        selfWeight: Boolean = true,
        liveUdl: Double = 0.0,
        pointLoadMid: Double = 0.0,
    ): FrameModel {
        require(spans.isNotEmpty() && spans.all { it > 0 }) { "Enter at least one span > 0" }
        val nodes = ArrayList<Node>()
        var x = 0.0
        nodes += Node(1, 0.0, 0.0, 0.0)
        spans.forEachIndexed { k, l -> x += l; nodes += Node(k + 2, x, 0.0, 0.0) }
        val members = spans.indices.map { k -> Member(k + 1, k + 1, k + 2, material, section) }
        val supports = nodes.mapIndexed { k, n -> if (k == 0) Support.pinned(n.id) else Support.rollerX(n.id) }
        val loads = gravity(members.map { it.id }, udl, liveUdl)
        val points = if (pointLoadMid != 0.0) members.mapIndexed { k, m -> PointLoad(m.id, LoadDir.GLOBAL_Z, -pointLoadMid, spans[k] / 2, LoadCase.LL) } else emptyList()
        return FrameModel(nodes, members, supports, memberLoads = loads, selfWeight = selfWeight,
            title = "Continuous beam ${spans.joinToString(" + ") { "%.2f".format(it) }} m", pointLoads = points)
    }

    /**
     * Plane portal frame in the global X–Z plane with fixed bases.
     * [lateral] is a horizontal load (kN) in +X at the left node of every floor.
     */
    fun portalFrame(
        bays: List<Double>,
        storeys: List<Double>,
        column: Section,
        beam: Section,
        material: Material,
        beamUdl: Double,
        lateral: Double = 0.0,
        selfWeight: Boolean = true,
        liveUdl: Double = 0.0,
        pointLoadMid: Double = 0.0,
        hingedBeams: Boolean = false,
    ): FrameModel = spaceFrame(bays, emptyList(), storeys, column, beam, material, beamUdl, lateral, selfWeight, liveUdl, pointLoadMid, hingedBeams)
        .copy(title = "Portal frame ${bays.size} bay × ${storeys.size} storey")

    /**
     * Space frame: bays along X, bays along Y (empty list = plane frame), storeys along Z, fixed bases.
     * Columns get the [column] section, beams the [beam] section (depth along local z).
     */
    fun spaceFrame(
        baysX: List<Double>,
        baysY: List<Double>,
        storeys: List<Double>,
        column: Section,
        beam: Section,
        material: Material,
        beamUdl: Double,
        lateralX: Double = 0.0,
        selfWeight: Boolean = true,
        liveUdl: Double = 0.0,
        pointLoadMid: Double = 0.0,
        hingedBeams: Boolean = false,
    ): FrameModel {
        require(baysX.isNotEmpty() && storeys.isNotEmpty()) { "Enter at least one bay and one storey" }
        val xs = cumulative(baysX)
        val ys = cumulative(baysY)
        val zs = cumulative(storeys)
        val nodes = ArrayList<Node>()
        val id = HashMap<Triple<Int, Int, Int>, Int>()
        for (k in zs.indices) for (j in ys.indices) for (i in xs.indices) {
            val nid = nodes.size + 1
            nodes += Node(nid, xs[i], ys[j], zs[k])
            id[Triple(i, j, k)] = nid
        }
        val members = ArrayList<Member>()
        val beamIds = ArrayList<Int>()
        fun add(a: Int, b: Int, s: Section, isBeam: Boolean) {
            val mid = members.size + 1
            members += Member(mid, a, b, material, s, hingeI = isBeam && hingedBeams, hingeJ = isBeam && hingedBeams)
            if (isBeam) beamIds += mid
        }
        for (k in 1 until zs.size) for (j in ys.indices) for (i in xs.indices)
            add(id[Triple(i, j, k - 1)]!!, id[Triple(i, j, k)]!!, column, false)
        for (k in 1 until zs.size) {
            for (j in ys.indices) for (i in 1 until xs.size) add(id[Triple(i - 1, j, k)]!!, id[Triple(i, j, k)]!!, beam, true)
            for (j in 1 until ys.size) for (i in xs.indices) add(id[Triple(i, j - 1, k)]!!, id[Triple(i, j, k)]!!, beam, true)
        }
        val supports = ys.indices.flatMap { j -> xs.indices.map { i -> Support.fixed(id[Triple(i, j, 0)]!!) } }
        val memberLoads = gravity(beamIds, beamUdl, liveUdl)
        val nodalLoads = if (lateralX != 0.0) (1 until zs.size).map { k ->
            NodalLoad(id[Triple(0, 0, k)]!!, listOf(lateralX, 0.0, 0.0, 0.0, 0.0, 0.0), LoadCase.WL)
        } else emptyList()
        val beamLen = { mid: Int -> members[mid - 1].let { m -> (nodes[m.j - 1].pos - nodes[m.i - 1].pos).length } }
        val points = if (pointLoadMid != 0.0) beamIds.map { PointLoad(it, LoadDir.GLOBAL_Z, -pointLoadMid, beamLen(it) / 2, LoadCase.LL) } else emptyList()
        return FrameModel(nodes, members, supports, nodalLoads, memberLoads, selfWeight,
            title = "Space frame ${baysX.size}×${baysY.size.coerceAtLeast(0)} bays, ${storeys.size} storeys", pointLoads = points)
    }

    private fun gravity(ids: List<Int>, dl: Double, ll: Double): List<MemberLoad> =
        (if (dl != 0.0) ids.map { MemberLoad(it, LoadDir.GLOBAL_Z, -dl, LoadCase.DL) } else emptyList()) +
            (if (ll != 0.0) ids.map { MemberLoad(it, LoadDir.GLOBAL_Z, -ll, LoadCase.LL) } else emptyList())

    private fun cumulative(spans: List<Double>): List<Double> {
        val out = arrayListOf(0.0)
        var s = 0.0
        for (v in spans) { s += v; out += s }
        return out
    }
}
