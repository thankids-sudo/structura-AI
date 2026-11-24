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

/** A concentrated force on a member in LOCAL axes at distance [a] from node i. */
data class LocalPointLoad(val f: Vec3, val a: Double)

/**
 * Linear-static 3D frame analysis by the direct stiffness method
 * (Euler–Bernoulli members, 6 DOF per node, rigid joints or moment hinges at member ends).
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
        set(1, 1, 12 * eiz / l3); set(1, 5, 6 * eiz / l2); set(1, 7, -12 * eiz / l3); set(1, 11, 6 * eiz / l2)
        set(5, 5, 4 * eiz / l); set(5, 7, -6 * eiz / l2); set(5, 11, 2 * eiz / l)
        set(7, 7, 12 * eiz / l3); set(7, 11, -6 * eiz / l2)
        set(11, 11, 4 * eiz / l)
        set(2, 2, 12 * eiy / l3); set(2, 4, -6 * eiy / l2); set(2, 8, -12 * eiy / l3); set(2, 10, -6 * eiy / l2)
        set(4, 4, 4 * eiy / l); set(4, 8, 6 * eiy / l2); set(4, 10, 2 * eiy / l)
        set(8, 8, 12 * eiy / l3); set(8, 10, 6 * eiy / l2)
        set(10, 10, 4 * eiy / l)
        return k
    }

    /** Total uniform load on a member in LOCAL axes (wx, wy, wz) kN/m, including factored self-weight. */
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
        if (model.selfWeight) global += Vec3(0.0, 0.0, -m.material.gamma * m.section.a * model.selfWeightFactor)
        return ax.toLocal(global) + local
    }

    fun localPointLoads(model: FrameModel, m: Member, ax: MemberAxes): List<LocalPointLoad> =
        model.pointLoads.filter { it.member == m.id }.map { pl ->
            require(pl.a in 0.0..ax.length) { "Point load on member ${m.id} at a = ${pl.a} m lies outside the member" }
            val f = when (pl.dir) {
                LoadDir.GLOBAL_X -> ax.toLocal(Vec3(pl.p, 0.0, 0.0))
                LoadDir.GLOBAL_Y -> ax.toLocal(Vec3(0.0, pl.p, 0.0))
                LoadDir.GLOBAL_Z -> ax.toLocal(Vec3(0.0, 0.0, pl.p))
                LoadDir.LOCAL_X -> Vec3(pl.p, 0.0, 0.0)
                LoadDir.LOCAL_Y -> Vec3(0.0, pl.p, 0.0)
                LoadDir.LOCAL_Z -> Vec3(0.0, 0.0, pl.p)
            }
            LocalPointLoad(f, pl.a)
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

    /** Equivalent nodal loads (local) of a concentrated local force at a from i (fixed-end values). */
    fun equivalentNodal(p: LocalPointLoad, l: Double): DoubleArray {
        val a = p.a; val b = l - a; val l2 = l * l; val l3 = l2 * l
        val f = DoubleArray(12)
        f[0] = p.f.x * b / l; f[6] = p.f.x * a / l
        f[1] = p.f.y * b * b * (3 * a + b) / l3; f[7] = p.f.y * a * a * (a + 3 * b) / l3
        f[5] = p.f.y * a * b * b / l2; f[11] = -p.f.y * a * a * b / l2
        f[2] = p.f.z * b * b * (3 * a + b) / l3; f[8] = p.f.z * a * a * (a + 3 * b) / l3
        f[4] = -p.f.z * a * b * b / l2; f[10] = p.f.z * a * a * b / l2
        return f
    }

    fun releasedDofs(m: Member): IntArray =
        (if (m.hingeI) intArrayOf(4, 5) else intArrayOf()) + (if (m.hingeJ) intArrayOf(10, 11) else intArrayOf())

    /** Static condensation of released DOFs (exact Gaussian elimination of those rows). */
    private fun condense(k: Array<DoubleArray>, f: DoubleArray, released: IntArray) {
        for (r in released) {
            val krr = k[r][r]
            if (krr == 0.0) continue
            val row = k[r].copyOf()
            val col = DoubleArray(12) { k[it][r] }
            val fr = f[r]
            for (i in 0 until 12) {
                f[i] -= col[i] * fr / krr
                for (j in 0 until 12) k[i][j] -= col[i] * row[j] / krr
            }
            for (i in 0 until 12) { k[r][i] = 0.0; k[i][r] = 0.0 }
            f[r] = 0.0
        }
    }

    private fun rotation(ax: MemberAxes) = arrayOf(
        doubleArrayOf(ax.x.x, ax.x.y, ax.x.z),
        doubleArrayOf(ax.y.x, ax.y.y, ax.y.z),
        doubleArrayOf(ax.z.x, ax.z.y, ax.z.z),
    )

    private fun toLocal12(r: Array<DoubleArray>, g: DoubleArray): DoubleArray {
        val out = DoubleArray(12)
        for (blk in 0 until 4) for (a in 0 until 3) {
            var s = 0.0
            for (b in 0 until 3) s += r[a][b] * g[blk * 3 + b]
            out[blk * 3 + a] = s
        }
        return out
    }

    private fun toGlobal12(r: Array<DoubleArray>, l: DoubleArray): DoubleArray {
        val out = DoubleArray(12)
        for (blk in 0 until 4) for (a in 0 until 3) {
            var s = 0.0
            for (b in 0 until 3) s += r[b][a] * l[blk * 3 + b]
            out[blk * 3 + a] = s
        }
        return out
    }

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

    private class MemberData(
        val ax: MemberAxes,
        val klFull: Array<DoubleArray>,
        val feqFull: DoubleArray,
        val kl: Array<DoubleArray>,
        val feq: DoubleArray,
        val w: Vec3,
        val points: List<LocalPointLoad>,
    )

    /** Analyse one load combination (factored loads superposed in a single solve). */
    fun analyse(model: FrameModel, combination: LoadCombination): FrameResults = analyse(model.factored(combination))

    /** Analyse a list of combinations. */
    fun analyseAll(model: FrameModel, combinations: List<LoadCombination>): List<Pair<LoadCombination, FrameResults>> =
        combinations.map { it to analyse(model, it) }

    /** Analyse the model with every load × 1.0. */
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

        val data = HashMap<Int, MemberData>()
        for (m in model.members) {
            val ax = axes(model, m)
            val klFull = localStiffness(m, ax.length)
            val w = localUdl(model, m, ax)
            val pts = localPointLoads(model, m, ax)
            val feqFull = equivalentNodal(w, ax.length)
            for (p in pts) { val e = equivalentNodal(p, ax.length); for (a in 0 until 12) feqFull[a] += e[a] }
            val kl = Array(12) { klFull[it].copyOf() }
            val feq = feqFull.copyOf()
            condense(kl, feq, releasedDofs(m))
            data[m.id] = MemberData(ax, klFull, feqFull, kl, feq, w, pts)

            val r = rotation(ax)
            val kg = globalStiffness(kl, r)
            val idx = dofs(m)
            for (a in 0 until 12) for (b in 0 until 12) k[idx[a]][idx[b]] += kg[a][b]
            val fg = toGlobal12(r, feq)
            for (a in 0 until 12) f[idx[a]] += fg[a]
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

        val kr = Array(nf) { r -> DoubleArray(nf) { c -> k[free[r]][free[c]] } }
        val fr = DoubleArray(nf) { f[free[it]] }
        val ur = cholesky(kr, fr) { row ->
            val dof = free[row]
            val node = model.nodes[dof / 6].id
            val name = listOf("ux", "uy", "uz", "φx", "φy", "φz")[dof % 6]
            "Model is unstable — mechanism at node $node, $name. Check supports, hinges and member connectivity."
        }

        val u = DoubleArray(nDof)
        for (r in 0 until nf) u[free[r]] = ur[r]

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

        val endForces = LinkedHashMap<Int, DoubleArray>()
        val udl = LinkedHashMap<Int, Vec3>()
        val axesMap = LinkedHashMap<Int, MemberAxes>()
        val localDisp = LinkedHashMap<Int, DoubleArray>()
        val points = LinkedHashMap<Int, List<LocalPointLoad>>()
        for (m in model.members) {
            val d = data[m.id]!!
            val r = rotation(d.ax)
            val ug = DoubleArray(12).also { arr -> dofs(m).forEachIndexed { a, dof -> arr[a] = u[dof] } }
            val ul = toLocal12(r, ug)
            endForces[m.id] = DoubleArray(12) { i ->
                var s = 0.0
                for (c in 0 until 12) s += d.kl[i][c] * ul[c]
                s - d.feq[i]
            }
            // member-end rotations at hinges (differ from the node rotation)
            val rel = releasedDofs(m)
            if (rel.isNotEmpty()) recoverReleased(d.klFull, d.feqFull, ul, rel)
            udl[m.id] = d.w
            axesMap[m.id] = d.ax
            localDisp[m.id] = ul
            points[m.id] = d.points
        }

        val totalLoad = DoubleArray(3)
        for (m in model.members) {
            val ax = axesMap[m.id]!!
            val g = ax.toGlobal(udl[m.id]!!) * ax.length
            totalLoad[0] += g.x; totalLoad[1] += g.y; totalLoad[2] += g.z
            for (p in points[m.id]!!) {
                val pg = ax.toGlobal(p.f)
                totalLoad[0] += pg.x; totalLoad[1] += pg.y; totalLoad[2] += pg.z
            }
        }
        for (nl in model.nodalLoads) for (a in 0 until 3) totalLoad[a] += nl.values[a]

        return FrameResults(model, nodeDisp, reactions, endForces, udl, axesMap, localDisp, totalLoad, points)
    }

    /** Solve K_rr·u_r = F_r − K_rc·u_c for the released end rotations and write them into [ul]. */
    private fun recoverReleased(k: Array<DoubleArray>, feq: DoubleArray, ul: DoubleArray, rel: IntArray) {
        val n = rel.size
        val a = Array(n) { i -> DoubleArray(n) { j -> k[rel[i]][rel[j]] } }
        val b = DoubleArray(n) { i ->
            var s = feq[rel[i]]
            for (c in 0 until 12) if (c !in rel) s -= k[rel[i]][c] * ul[c]
            s
        }
        // Gaussian elimination with partial pivoting (n ≤ 4)
        for (col in 0 until n) {
            var p = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[p][col])) p = r
            val tmp = a[col]; a[col] = a[p]; a[p] = tmp
            val tb = b[col]; b[col] = b[p]; b[p] = tb
            if (abs(a[col][col]) < 1e-30) return
            for (r in col + 1 until n) {
                val fct = a[r][col] / a[col][col]
                for (c in col until n) a[r][c] -= fct * a[col][c]
                b[r] -= fct * b[col]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (c in i + 1 until n) s -= a[i][c] * x[c]
            x[i] = s / a[i][i]
        }
        for (i in 0 until n) ul[rel[i]] = x[i]
    }

    /** In-place Cholesky solve of an SPD system. */
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
    val memberPointLoads: Map<Int, List<LocalPointLoad>> = emptyMap(),
) {
    /** ΣReactions + ΣLoads per global direction — should be ≈ 0 (equilibrium check, as in RFEM). */
    val equilibriumError: DoubleArray
        get() {
            val r = DoubleArray(3)
            for (v in reactions.values) for (a in 0 until 3) r[a] += v[a]
            return DoubleArray(3) { r[it] + totalLoad[it] }
        }

    /** Internal forces at [n] + 1 equally spaced stations, plus stations just either side of point loads. */
    fun internalForces(memberId: Int, n: Int = 20): List<Station> {
        val fe = memberEndForces[memberId] ?: error("No member $memberId")
        val w = memberUdl[memberId]!!
        val l = memberAxes[memberId]!!.length
        val pts = memberPointLoads[memberId].orEmpty()
        val ss = ((0..n).map { l * it / n } + pts.flatMap { listOf(it.a - 1e-6, it.a + 1e-6) })
            .filter { it in 0.0..l }.sorted()
        return ss.map { s ->
            var px = 0.0; var py = 0.0; var pz = 0.0; var mPy = 0.0; var mPz = 0.0
            for (p in pts) if (p.a < s) {
                px += p.f.x; py += p.f.y; pz += p.f.z
                mPy += p.f.y * (s - p.a); mPz += p.f.z * (s - p.a)
            }
            Station(
                s = s,
                n = -(fe[0] + w.x * s + px),
                vy = -(fe[1] + w.y * s + py),
                vz = -(fe[2] + w.z * s + pz),
                mt = -fe[3],
                my = -(fe[4] + s * fe[2] + w.z * s * s / 2 + mPz),
                mz = -fe[5] + s * fe[1] + w.y * s * s / 2 + mPy,
            )
        }
    }

    /**
     * Deformed shape of a member in global coordinates: Hermite interpolation of the member-end
     * displacements plus the exact fixed-end deflection due to the member's own loads.
     */
    fun deformedShape(memberId: Int, scale: Double, n: Int = 16): List<Vec3> {
        val m = model.members.first { it.id == memberId }
        val ax = memberAxes[memberId]!!
        val ul = memberLocalDisp[memberId]!!
        val w = memberUdl[memberId]!!
        val pts = memberPointLoads[memberId].orEmpty()
        val l = ax.length
        val e = m.material.e
        val eiy = e * m.section.iy
        val eiz = e * m.section.iz
        val p0 = model.node(m.i).pos
        return (0..n).map { k ->
            val xi = k.toDouble() / n
            val s = xi * l
            val n1 = 1 - 3 * xi * xi + 2 * xi * xi * xi
            val n2 = l * (xi - 2 * xi * xi + xi * xi * xi)
            val n3 = 3 * xi * xi - 2 * xi * xi * xi
            val n4 = l * (-xi * xi + xi * xi * xi)
            val bubble = s * s * (l - s) * (l - s) / 24.0
            var v = n1 * ul[1] + n2 * ul[5] + n3 * ul[7] + n4 * ul[11] + w.y * bubble / eiz
            var wz = n1 * ul[2] - n2 * ul[4] + n3 * ul[8] - n4 * ul[10] + w.z * bubble / eiy
            for (p in pts) {
                val g = fixedFixedPointDeflection(s, p.a, l)
                v += p.f.y * g / eiz
                wz += p.f.z * g / eiy
            }
            val u = ul[0] * (1 - xi) + ul[6] * xi
            p0 + ax.x * s + ax.toGlobal(Vec3(u, v, wz)) * scale
        }
    }

    /** Deflection × EI / P of a fixed–fixed beam under a point load P at a (Roark, Table 8.1 case 1d). */
    private fun fixedFixedPointDeflection(x: Double, a: Double, l: Double): Double {
        val b = l - a
        return if (x <= a) b * b * x * x * (3 * a * l - 3 * a * x - b * x) / (6 * l * l * l)
        else {
            val xr = l - x
            a * a * xr * xr * (3 * b * l - 3 * b * xr - a * xr) / (6 * l * l * l)
        }
    }

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
