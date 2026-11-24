package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class UnstableModelException(message: String) : RuntimeException(message)

/** Local axis triad of a member. */
data class MemberAxes(val x: Vec3, val y: Vec3, val z: Vec3, val length: Double) {
    fun toLocal(v: Vec3) = Vec3(v dot x, v dot y, v dot z)
    fun toGlobal(v: Vec3) = x * v.x + y * v.y + z * v.z
}

/** Internal forces at a station: N tension +; My, Mz per RFEM local axes (sagging beam → +My). */
data class Station(
    val s: Double,
    val n: Double, val vy: Double, val vz: Double,
    val mt: Double, val my: Double, val mz: Double,
)

/**
 * Linear-static 3D frame analysis by the direct stiffness method
 * (Euler–Bernoulli members, 6 DOF per node, rigid joints).
 */
object FrameSolver {

    fun axes(model: FrameModel, m: Member): MemberAxes {
        val pi = model.node(m.i).pos
        val pj = model.node(m.j).pos
        val d = pj - pi
        val len = d.length
        require(len > 1e-9) { "Member ${m.id} has zero length" }
        val x = d * (1.0 / len)
        var y: Vec3
        var z: Vec3
        if (abs(x dot Vec3.Z) > 0.999) {          // vertical member
            y = Vec3.Y
            z = (x cross y).unit()
            y = (z cross x).unit()
        } else {
            y = (x cross Vec3.Z).unit()
            z = (x cross y).unit()
        }
        if (m.betaDeg != 0.0) {
            val b = Math.toRadians(m.betaDeg)
            val y2 = y * cos(b) + z * sin(b)
            val z2 = y * (-sin(b)) + z * cos(b)
            y = y2; z = z2
        }
        return MemberAxes(x, y, z, len)
    }

    /** 12×12 local stiffness matrix, DOF order (u, v, w, θx, θy, θz) at i then j. */
    fun localStiffness(m: Member, len: Double): Array<DoubleArray> {
        val e = m.material.e
        val g = m.material.g
        val s = m.section
        val l = len; val l2 = l * l; val l3 = l2 * l
        val k = Array(12) { DoubleArray(12) }
        fun set(r: Int, c: Int, v: Double) { k[r][c] = v; k[c][r] = v }

        val ea = e * s.a / l
        val gj = g * s.j / l
        val eiz = e * s.iz
        val eiy = e * s.iy

        set(0, 0, ea); set(0, 6, -ea); set(6, 6, ea)
        set(3, 3, gj); set(3, 9, -gj); set(9, 9, gj)
        // bending in local x-y plane (v, θz) — EIz
        set(1, 1, 12 * eiz / l3); set(1, 5, 6 * eiz / l2); set(1, 7, -12 * eiz / l3); set(1, 11, 6 * eiz / l2)
        set(5, 5, 4 * eiz / l); set(5, 7, -6 * eiz / l2); set(5, 11, 2 * eiz / l)
        set(7, 7, 12 * eiz / l3); set(7, 11, -6 * eiz / l2)
        set(11, 11, 4 * eiz / l)
        // bending in local x-z plane (w, θy) — EIy
        set(2, 2, 12 * eiy / l3); set(2, 4, -6 * eiy / l2); set(2, 8, -12 * eiy / l3); set(2, 10, -6 * eiy / l2)
        set(4, 4, 4 * eiy / l); set(4, 8, 6 * eiy / l2); set(4, 10, 2 * eiy / l)
        set(8, 8, 12 * eiy / l3); set(8, 10, 6 * eiy / l2)
        set(10, 10, 4 * eiy / l)
        return k
    }

    /** Total uniform load on a member in LOCAL axes (wx, wy, wz) kN/m, including self-weight. */
    fun localUdl(model: FrameModel, m: Member, ax: MemberAxes): Vec3 {
        var global = Vec3.ZERO
        var local = Vec3.ZERO
        for (ml in model.memberLoads) if (ml.member == m.id) when (ml.dir) {
            LoadDir.GLOBAL_X -> global += Vec3(ml.w, 0.0, 0.0)
            LoadDir.GLOBAL_Y -> global += Vec3(0.0, ml.w, 0.0)
            LoadDir.GLOBAL_Z -> global += Vec3(0.0, 0.0, ml.w)
            LoadDir.LOCAL_X -> local += Vec3(ml.w, 0.0, 0.0)
            LoadDir.LOCAL_Y -> local += Vec3(0.0, ml.w, 0.0)
            LoadDir.LOCAL_Z -> local += Vec3(0.0, 0.0, ml.w)
        }
        if (model.selfWeight) global += Vec3(0.0, 0.0, -m.material.gamma * m.section.a)
        return ax.toLocal(global) + local
    }

    /** Equivalent nodal loads (local) of a uniform local load over length L. */
    fun equivalentNodal(w: Vec3, l: Double): DoubleArray {
        val f = DoubleArray(12)
        f[0] = w.x * l / 2; f[6] = w.x * l / 2
        f[1] = w.y * l / 2; f[7] = w.y * l / 2
        f[5] = w.y * l * l / 12; f[11] = -w.y * l * l / 12
        f[2] = w.z * l / 2; f[8] = w.z * l / 2
        f[4] = -w.z * l * l / 12; f[10] = w.z * l * l / 12
        return f
    }

    private fun rotation(ax: MemberAxes) = arrayOf(
        doubleArrayOf(ax.x.x, ax.x.y, ax.x.z),
        doubleArrayOf(ax.y.x, ax.y.y, ax.y.z),
        doubleArrayOf(ax.z.x, ax.z.y, ax.z.z),
    )

    /** local = T · global for a 12-vector. */
    private fun toLocal12(r: Array<DoubleArray>, g: DoubleArray): DoubleArray {
        val out = DoubleArray(12)
        for (blk in 0 until 4) for (a in 0 until 3) {
            var s = 0.0
            for (b in 0 until 3) s += r[a][b] * g[blk * 3 + b]
            out[blk * 3 + a] = s
        }
        return out
    }

    /** global = Tᵀ · local for a 12-vector. */
    private fun toGlobal12(r: Array<DoubleArray>, l: DoubleArray): DoubleArray {
        val out = DoubleArray(12)
        for (blk in 0 until 4) for (a in 0 until 3) {
            var s = 0.0
            for (b in 0 until 3) s += r[b][a] * l[blk * 3 + b]
            out[blk * 3 + a] = s
        }
        return out
    }

    /** Kg = Tᵀ · Kl · T */
    private fun globalStiffness(kl: Array<DoubleArray>, r: Array<DoubleArray>): Array<DoubleArray> {
        val t = Array(12) { DoubleArray(12) }
        for (blk in 0 until 4) for (a in 0 until 3) for (b in 0 until 3) t[blk * 3 + a][blk * 3 + b] = r[a][b]
        val kt = Array(12) { DoubleArray(12) }
        for (i in 0 until 12) for (j in 0 until 12) {
            var s = 0.0
            for (k in 0 until 12) s += kl[i][k] * t[k][j]
            kt[i][j] = s
        }
        val kg = Array(12) { DoubleArray(12) }
        for (i in 0 until 12) for (j in 0 until 12) {
            var s = 0.0
            for (k in 0 until 12) s += t[k][i] * kt[k][j]
            kg[i][j] = s
        }
        return kg
    }

    fun analyse(model: FrameModel): FrameResults {
        require(model.members.isNotEmpty()) { "Model has no members" }
        require(model.supports.isNotEmpty()) { "Model has no supports" }
        val nDof = model.nodes.size * 6
        val k = Array(nDof) { DoubleArray(nDof) }
        val f = DoubleArray(nDof)

        fun dofs(m: Member): IntArray {
            val a = model.nodeIndex[m.i]!! * 6
            val b = model.nodeIndex[m.j]!! * 6
            return IntArray(12) { if (it < 6) a + it else b + it - 6 }
        }

        val cache = HashMap<Int, Triple<MemberAxes, Array<DoubleArray>, Vec3>>()
        for (m in model.members) {
            val ax = axes(model, m)
            val kl = localStiffness(m, ax.length)
            val r = rotation(ax)
            val kg = globalStiffness(kl, r)
            val w = localUdl(model, m, ax)
            cache[m.id] = Triple(ax, kl, w)
            val idx = dofs(m)
            for (a in 0 until 12) for (b in 0 until 12) k[idx[a]][idx[b]] += kg[a][b]
            val feq = toGlobal12(r, equivalentNodal(w, ax.length))
            for (a in 0 until 12) f[idx[a]] += feq[a]
        }
        for (nl in model.nodalLoads) {
            val base = (model.nodeIndex[nl.node] ?: error("Load on missing node ${nl.node}")) * 6
            for (a in 0 until 6) f[base + a] += nl.values[a]
        }

        val fixed = BooleanArray(nDof)
        for (s in model.supports) {
            val base = (model.nodeIndex[s.node] ?: error("Support on missing node ${s.node}")) * 6
            for (a in 0 until 6) if (s.restraints[a]) fixed[base + a] = true
        }
        val free = (0 until nDof).filter { !fixed[it] }.toIntArray()
        val nf = free.size

        // Reduced system, solved by Cholesky (K is SPD for a stable structure)
        val kr = Array(nf) { r -> DoubleArray(nf) { c -> k[free[r]][free[c]] } }
        val fr = DoubleArray(nf) { f[free[it]] }
        val ur = cholesky(kr, fr) { row ->
            val dof = free[row]
            val node = model.nodes[dof / 6].id
            val name = listOf("ux", "uy", "uz", "φx", "φy", "φz")[dof % 6]
            "Model is unstable — mechanism at node $node, $name. Check supports and member connectivity."
        }

        val u = DoubleArray(nDof)
        for (r in 0 until nf) u[free[r]] = ur[r]

        // Reactions R = K·u − F at restrained DOFs
        val reactions = LinkedHashMap<Int, DoubleArray>()
        for (s in model.supports) {
            val base = model.nodeIndex[s.node]!! * 6
            val rr = DoubleArray(6)
            for (a in 0 until 6) {
                if (!s.restraints[a]) continue
                var sum = 0.0
                val row = k[base + a]
                for (c in 0 until nDof) sum += row[c] * u[c]
                rr[a] = sum - f[base + a]
            }
            reactions[s.node] = rr
        }

        val nodeDisp = LinkedHashMap<Int, DoubleArray>()
        for ((idx, n) in model.nodes.withIndex()) nodeDisp[n.id] = DoubleArray(6) { u[idx * 6 + it] }

        // Member end forces (local): f = Kl·u_local − F_eq
        val endForces = LinkedHashMap<Int, DoubleArray>()
        val udl = LinkedHashMap<Int, Vec3>()
        val axesMap = LinkedHashMap<Int, MemberAxes>()
        val localDisp = LinkedHashMap<Int, DoubleArray>()
        for (m in model.members) {
            val (ax, kl, w) = cache[m.id]!!
            val r = rotation(ax)
            val ug = DoubleArray(12).also { arr -> dofs(m).forEachIndexed { a, d -> arr[a] = u[d] } }
            val ul = toLocal12(r, ug)
            val feq = equivalentNodal(w, ax.length)
            endForces[m.id] = DoubleArray(12) { i ->
                var s = 0.0
                for (c in 0 until 12) s += kl[i][c] * ul[c]
                s - feq[i]
            }
            udl[m.id] = w
            axesMap[m.id] = ax
            localDisp[m.id] = ul
        }

        val totalLoad = DoubleArray(3)
        for (m in model.members) {
            val ax = axesMap[m.id]!!
            val g = ax.toGlobal(udl[m.id]!!) * ax.length
            totalLoad[0] += g.x; totalLoad[1] += g.y; totalLoad[2] += g.z
        }
        for (nl in model.nodalLoads) for (a in 0 until 3) totalLoad[a] += nl.values[a]

        return FrameResults(model, nodeDisp, reactions, endForces, udl, axesMap, localDisp, totalLoad)
    }

    /** In-place Cholesky solve of SPD system. */
    internal fun cholesky(a: Array<DoubleArray>, b: DoubleArray, onSingular: (Int) -> String): DoubleArray {
        val n = b.size
        var maxDiag = 0.0
        for (i in 0 until n) maxDiag = maxOf(maxDiag, abs(a[i][i]))
        val tol = maxDiag * 1e-12
        for (j in 0 until n) {
            var d = a[j][j]
            val rowJ = a[j]
            for (k in 0 until j) d -= rowJ[k] * rowJ[k]
            if (d <= tol) throw UnstableModelException(onSingular(j))
            val ljj = sqrt(d)
            rowJ[j] = ljj
            for (i in j + 1 until n) {
                val rowI = a[i]
                var s = rowI[j]
                for (k in 0 until j) s -= rowI[k] * rowJ[k]
                rowI[j] = s / ljj
            }
        }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            for (k in 0 until i) s -= a[i][k] * y[k]
            y[i] = s / a[i][i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until n) s -= a[k][i] * x[k]
            x[i] = s / a[i][i]
        }
        return x
    }
}

class FrameResults(
    val model: FrameModel,
    /** Global displacements per node: ux, uy, uz (m), φx, φy, φz (rad). */
    val nodeDisplacements: Map<Int, DoubleArray>,
    /** Support reactions per node in global axes (kN, kN·m). */
    val reactions: Map<Int, DoubleArray>,
    /** Local end forces acting ON the member, 12 values (i end then j end). */
    val memberEndForces: Map<Int, DoubleArray>,
    val memberUdl: Map<Int, Vec3>,
    val memberAxes: Map<Int, MemberAxes>,
    private val memberLocalDisp: Map<Int, DoubleArray>,
    /** Total applied load ΣFx, ΣFy, ΣFz (kN) including self-weight. */
    val totalLoad: DoubleArray,
) {
    /** ΣReactions + ΣLoads per global direction — should be ≈ 0 (equilibrium check, as in RFEM). */
    val equilibriumError: DoubleArray
        get() {
            val r = DoubleArray(3)
            for (v in reactions.values) for (a in 0 until 3) r[a] += v[a]
            return DoubleArray(3) { r[it] + totalLoad[it] }
        }

    /** Internal forces at [n] + 1 equally spaced stations along the member. */
    fun internalForces(memberId: Int, n: Int = 20): List<Station> {
        val fe = memberEndForces[memberId] ?: error("No member $memberId")
        val w = memberUdl[memberId]!!
        val l = memberAxes[memberId]!!.length
        return (0..n).map { k ->
            val s = l * k / n
            Station(
                s = s,
                n = -(fe[0] + w.x * s),
                vy = -(fe[1] + w.y * s),
                vz = -(fe[2] + w.z * s),
                mt = -fe[3],
                my = -(fe[4] + s * fe[2] + w.z * s * s / 2),
                mz = -fe[5] + s * fe[1] + w.y * s * s / 2,
            )
        }
    }

    /**
     * Deformed shape of a member in global coordinates: Hermite interpolation of the end
     * displacements plus the exact fixed-end deflection due to the member's own UDL.
     */
    fun deformedShape(memberId: Int, scale: Double, n: Int = 16): List<Vec3> {
        val m = model.members.first { it.id == memberId }
        val ax = memberAxes[memberId]!!
        val ul = memberLocalDisp[memberId]!!
        val w = memberUdl[memberId]!!
        val l = ax.length
        val e = m.material.e
        val p0 = model.node(m.i).pos
        return (0..n).map { k ->
            val xi = k.toDouble() / n
            val s = xi * l
            val n1 = 1 - 3 * xi * xi + 2 * xi * xi * xi
            val n2 = l * (xi - 2 * xi * xi + xi * xi * xi)
            val n3 = 3 * xi * xi - 2 * xi * xi * xi
            val n4 = l * (-xi * xi + xi * xi * xi)
            val bubble = s * s * (l - s) * (l - s) / 24.0
            val u = ul[0] * (1 - xi) + ul[6] * xi
            val v = n1 * ul[1] + n2 * ul[5] + n3 * ul[7] + n4 * ul[11] + w.y * bubble / (e * m.section.iz)
            val wz = n1 * ul[2] - n2 * ul[4] + n3 * ul[8] - n4 * ul[10] + w.z * bubble / (e * m.section.iy)
            p0 + ax.x * s + ax.toGlobal(Vec3(u, v, wz)) * scale
        }
    }

    /** Largest translational displacement (m) and the node where it occurs. */
    fun maxDisplacement(): Pair<Int, Double> =
        nodeDisplacements.maxByOrNull { (_, d) -> Vec3(d[0], d[1], d[2]).length }!!
            .let { (id, d) -> id to Vec3(d[0], d[1], d[2]).length }

    /** Largest member deflection relative to the chord (m), checked along every member. */
    fun maxMemberDeflection(): Double = model.members.maxOf { m ->
        val pts = deformedShape(m.id, 1.0, 20)
        val a = pts.first()
        val chord = pts.last() - a
        pts.withIndex().maxOf { (k, p) -> (p - (a + chord * (k / 20.0))).length }
    }
}
