package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * STAAD-style floor load generation: an area pressure on a rectangular bay is distributed to the
 * beams around it, and stored as ordinary (partial / trapezoidal) member loads.
 *
 *  • TWO_WAY — 45° yield lines from the corners (IS 456 Annex D / classic slab practice):
 *    every edge gets q·h(s) with h(s) = min(s, L − s, lx/2) → triangles on the short beams and
 *    trapezoids on the long beams.
 *  • ONE_WAY — the slab spans the short direction lx: each long beam gets the uniform q·lx/2.
 *  • AUTO — one-way when ly/lx > 2, otherwise two-way.
 *
 * Edges may consist of several collinear members (after splitting / panel meshing); the load is
 * cut into matching pieces. The total transferred always equals q × bay area.
 */
object FloorLoad {

    enum class Mode(val label: String) { AUTO("Auto (ly/lx)"), TWO_WAY("Two-way 45°"), ONE_WAY("One-way (short span)") }

    class Result(val model: FrameModel, val bays: Int, val total: Double, val warnings: List<String>)

    private const val TOL = 1e-6

    /** Applies pressure [q] (kN/m², negative = downward along global Z) to one rectangular bay given by 4 corner nodes. */
    fun applyToBay(model: FrameModel, corners: List<Int>, q: Double, case: String, mode: Mode = Mode.AUTO): Result {
        require(corners.size == 4 && corners.distinct().size == 4) { "Pick the 4 corner nodes of the bay" }
        val p = corners.map { model.node(it).pos }
        val e = (0 until 4).map { p[(it + 1) % 4] - p[it] }
        for (k in 0 until 4) {
            val c = abs(e[k].unit() dot e[(k + 1) % 4].unit())
            require(c < 1e-3) { "The 4 corners must form a rectangle (taken in order around the bay)" }
        }
        val lens = e.map { it.length }
        val lx = min(lens[0], lens[1])
        val ly = max(lens[0], lens[1])
        val m = when (mode) { Mode.AUTO -> if (ly / lx > 2.0) Mode.ONE_WAY else Mode.TWO_WAY; else -> mode }
        val warnings = ArrayList<String>()
        val loads = ArrayList<MemberLoad>()
        var total = 0.0
        // long edges carry one-way load; for a square bay edges 0 and 2 are taken as "long"
        fun longEdge(k: Int) = if (abs(lx - ly) < 1e-9) k % 2 == 0 else abs(lens[k] - ly) < 1e-9
        for (k in 0 until 4) {
            val l = lens[k]
            // intensity profile along this edge as breakpoints (s from the edge start, w)
            val profile: List<Pair<Double, Double>> = when (m) {
                Mode.ONE_WAY -> if (longEdge(k)) listOf(0.0 to q * lx / 2, l to q * lx / 2) else emptyList()
                else -> {
                    val h = lx / 2
                    if (l - 2 * h > 1e-9) listOf(0.0 to 0.0, h to q * h, l - h to q * h, l to 0.0)
                    else listOf(0.0 to 0.0, l / 2 to q * l / 2, l to 0.0)
                }
            }
            if (profile.isEmpty()) continue
            var edgeTotal = 0.0
            for (i in 1 until profile.size) edgeTotal += (profile[i].second + profile[i - 1].second) / 2 * (profile[i].first - profile[i - 1].first)
            val placed = placeOnEdge(model, corners[k], corners[(k + 1) % 4], profile, case, loads)
            if (placed < l - 1e-6) warnings += "Edge N${corners[k]}–N${corners[(k + 1) % 4]}: only ${"%.2f".format(placed)} of ${"%.2f".format(l)} m has beams — that part of the load is not applied."
            total += edgeTotal * (placed / l).coerceAtMost(1.0)
        }
        return Result(model.copy(memberLoads = model.memberLoads + loads), 1, total, warnings)
    }

    /**
     * Finds the members lying on the straight edge a→b and adds the piecewise-linear [profile]
     * (positions from a) as member loads in global Z. Returns the edge length covered by members.
     */
    private fun placeOnEdge(model: FrameModel, a: Int, b: Int, profile: List<Pair<Double, Double>>, case: String, out: MutableList<MemberLoad>): Double {
        val pa = model.node(a).pos
        val dir = model.node(b).pos - pa
        val l = dir.length
        val u = dir.unit()
        fun param(n: Int): Double? {
            val d = model.node(n).pos - pa
            val t = d dot u
            val off = (d - u * t).length
            return if (off < 1e-4 && t > -1e-6 && t < l + 1e-6) t else null
        }
        fun w(s: Double): Double {
            for (i in 1 until profile.size) if (s <= profile[i].first + TOL) {
                val (s0, w0) = profile[i - 1]; val (s1, w1) = profile[i]
                return if (s1 - s0 < TOL) w1 else w0 + (w1 - w0) * (s - s0) / (s1 - s0)
            }
            return profile.last().second
        }
        var covered = 0.0
        for (mem in model.members) {
            val ti = param(mem.i) ?: continue
            val tj = param(mem.j) ?: continue
            val t0 = min(ti, tj); val t1 = max(ti, tj)
            if (t1 - t0 < 1e-9) continue
            covered += t1 - t0
            val cuts = (listOf(t0, t1) + profile.map { it.first }.filter { it > t0 + TOL && it < t1 - TOL }).sorted()
            for (k in 1 until cuts.size) {
                val u0 = cuts[k - 1]; val u1 = cuts[k]
                val w0 = w(u0); val w1 = w(u1)
                if (abs(w0) < 1e-12 && abs(w1) < 1e-12) continue
                // member coordinate from its node i
                val (sa, sb, wa, wb) = if (tj >= ti) listOf(u0 - ti, u1 - ti, w0, w1) else listOf(ti - u1, ti - u0, w1, w0)
                out += MemberLoad(mem.id, LoadDir.GLOBAL_Z, wa, case, sa, sb, wb)
            }
        }
        return min(covered, l)
    }

    /**
     * Applies the pressure to every rectangular bay of the horizontal level at height [z]: bays are the
     * cells of the grid of node X/Y coordinates on that level whose 4 corners exist and whose edges
     * carry beams. Works for any orthogonal grid (as built by the frame wizard or drawn on a snap grid).
     */
    fun applyToLevel(model: FrameModel, z: Double, q: Double, case: String, mode: Mode = Mode.AUTO): Result {
        val level = model.nodes.filter { abs(it.z - z) < 1e-4 }
        val xs = level.map { it.x }.distinctBy { Math.round(it * 1e4) }.sorted()
        val ys = level.map { it.y }.distinctBy { Math.round(it * 1e4) }.sorted()
        fun nodeAt(x: Double, y: Double) = level.firstOrNull { abs(it.x - x) < 1e-4 && abs(it.y - y) < 1e-4 }?.id
        fun hasBeam(a: Int, b: Int): Boolean {
            val pa = model.node(a).pos; val pb = model.node(b).pos
            val u = (pb - pa).unit(); val l = (pb - pa).length
            var cov = 0.0
            for (m in model.members) {
                val ts = listOf(m.i, m.j).map { n ->
                    val d = model.node(n).pos - pa; val t = d dot u
                    if ((d - u * t).length < 1e-4 && t > -1e-6 && t < l + 1e-6) t else null
                }
                if (ts.all { it != null }) cov += abs(ts[0]!! - ts[1]!!)
            }
            return cov > l - 1e-6
        }
        var cur = model
        var bays = 0
        var total = 0.0
        val warnings = ArrayList<String>()
        for (j in 0 until ys.size - 1) for (i in 0 until xs.size - 1) {
            val c = listOf(nodeAt(xs[i], ys[j]), nodeAt(xs[i + 1], ys[j]), nodeAt(xs[i + 1], ys[j + 1]), nodeAt(xs[i], ys[j + 1]))
            if (c.any { it == null }) continue
            val ids = c.map { it!! }
            if (!(0 until 4).all { hasBeam(ids[it], ids[(it + 1) % 4]) }) continue
            val r = applyToBay(cur, ids, q, case, mode)
            cur = r.model; bays++; total += r.total; warnings += r.warnings
        }
        if (bays == 0) warnings += "No rectangular bays enclosed by beams were found at Z = ${"%.3f".format(z)} m."
        return Result(cur, bays, total, warnings)
    }
}
