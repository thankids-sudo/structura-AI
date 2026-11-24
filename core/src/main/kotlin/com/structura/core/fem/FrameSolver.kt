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

    /** Shear-deformation parameters Φ = 12EI/(G·As·L²) for bending in the x-y (Iz, Asy) and x-z (Iy, Asz) planes. */
    fun phi(m: Member, len: Double, theory: BeamTheory): Pair<Double, Double> {
        if (theory != BeamTheory.TIMOSHENKO) return 0.0 to 0.0
        val s = m.section; val e = m.material.e; val g = m.material.g
        val py = if (s.asy > 0) 12 * e * s.iz / (g * s.asy * len * len) else 0.0
        val pz = if (s.asz > 0) 12 * e * s.iy / (g * s.asz * len * len) else 0.0
        return py to pz
    }

    /**
     * 12×12 local stiffness matrix, DOF order (u, v, w, θx, θy, θz) at i then j.
     * Timoshenko theory: Przemieniecki's shear-deformable beam (Φ terms); Φ = 0 gives Euler–Bernoulli.
     */
    fun localStiffness(m: Member, len: Double, theory: BeamTheory = BeamTheory.EULER): Array<DoubleArray> {
        val e = m.material.e
        val g = m.material.g
        val s = m.section
        val l = len; val l2 = l * l; val l3 = l2 * l
        val k = Array(12) { DoubleArray(12) }
        fun set(r: Int, c: Int, v: Double) { k[r][c] = v; k[c][r] = v }

        val ea = e * s.a / l
        val gj = g * s.j / l
        val (py, pz) = phi(m, len, theory)
        val eiz = e * s.iz / (1 + py)
        val eiy = e * s.iy / (1 + pz)

        set(0, 0, ea); set(0, 6, -ea); set(6, 6, ea)
        set(3, 3, gj); set(3, 9, -gj); set(9, 9, gj)
        set(1, 1, 12 * eiz / l3); set(1, 5, 6 * eiz / l2); set(1, 7, -12 * eiz / l3); set(1, 11, 6 * eiz / l2)
        set(5, 5, (4 + py) * eiz / l); set(5, 7, -6 * eiz / l2); set(5, 11, (2 - py) * eiz / l)
        set(7, 7, 12 * eiz / l3); set(7, 11, -6 * eiz / l2)
        set(11, 11, (4 + py) * eiz / l)
        set(2, 2, 12 * eiy / l3); set(2, 4, -6 * eiy / l2); set(2, 8, -12 * eiy / l3); set(2, 10, -6 * eiy / l2)
        set(4, 4, (4 + pz) * eiy / l); set(4, 8, 6 * eiy / l2); set(4, 10, (2 - pz) * eiy / l)
        set(8, 8, 12 * eiy / l3); set(8, 10, 6 * eiy / l2)
        set(10, 10, (4 + pz) * eiy / l)
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

    /**
     * Equivalent nodal loads (local) of a concentrated local force at a from i (fixed-end values).
     * With shear deformation (Φ) the fixed-end moments become P·a·b·(b + Φ·L/2)/(L²(1+Φ)) and
     * P·a·b·(a + Φ·L/2)/(L²(1+Φ)); end shears follow from statics. Φ = 0 → classical values.
     */
    fun equivalentNodal(p: LocalPointLoad, l: Double, phiY: Double = 0.0, phiZ: Double = 0.0): DoubleArray {
        val a = p.a; val b = l - a; val l2 = l * l
        val f = DoubleArray(12)
        f[0] = p.f.x * b / l; f[6] = p.f.x * a / l
        fun fem(phi: Double): Pair<Double, Double> =
            (a * b * (b + phi * l / 2) / (l2 * (1 + phi))) to (a * b * (a + phi * l / 2) / (l2 * (1 + phi)))
        val (miY, mjY) = fem(phiY)
        f[5] = p.f.y * miY; f[11] = -p.f.y * mjY
        f[1] = p.f.y * b / l + (f[5] + f[11]) / l; f[7] = p.f.y - f[1]
        val (miZ, mjZ) = fem(phiZ)
        f[4] = -p.f.z * miZ; f[10] = p.f.z * mjZ
        f[2] = p.f.z * b / l - (f[4] + f[10]) / l; f[8] = p.f.z - f[2]
        return f
    }

    fun releasedDofs(m: Member): IntArray =
        (if (m.endI != null) intArrayOf(4, 5) else intArrayOf()) + (if (m.endJ != null) intArrayOf(10, 11) else intArrayOf())

    /**
     * Member with end hinges / rotational springs, by static condensation of internal member-end
     * rotations: each released local rotation r gets an internal DOF joined to the node by a spring
     * k (0 = hinge). Returns the condensed 12×12 stiffness and load vector, and a function that
     * recovers the member-end displacement vector from the node displacements (local).
     */
    class EndCondensation(val k: Array<DoubleArray>, val f: DoubleArray, val recover: (DoubleArray) -> DoubleArray)

    fun endCondensation(m: Member, kl: Array<DoubleArray>, feq: DoubleArray): EndCondensation {
        val rel = releasedDofs(m)
        if (rel.isEmpty()) return EndCondensation(kl, feq) { it }
        val springs = rel.map { if (it < 6) m.endI!! else m.endJ!! }
        val r = rel.size
        val n = 12 + r
        val map = IntArray(12) { c -> val k2 = rel.indexOf(c); if (k2 >= 0) 12 + k2 else c }
        val kk = Array(n) { DoubleArray(n) }
        val ff = DoubleArray(n)
        for (a in 0 until 12) {
            ff[map[a]] += feq[a]
            for (b in 0 until 12) kk[map[a]][map[b]] += kl[a][b]
        }
        rel.forEachIndexed { k2, c ->
            val ks = springs[k2]; val ii = 12 + k2
            kk[c][c] += ks; kk[ii][ii] += ks; kk[c][ii] -= ks; kk[ii][c] -= ks
        }
        val kii = Array(r) { x -> DoubleArray(r) { y -> kk[12 + x][12 + y] } }
        val inv = invertSmall(kii)
        // Kc = Knn − Kni·Kii⁻¹·Kin ; Fc = Fn − Kni·Kii⁻¹·Fi
        val kniInv = Array(12) { a -> DoubleArray(r) { y -> var s = 0.0; for (x in 0 until r) s += kk[a][12 + x] * inv[x][y]; s } }
        val kc = Array(12) { a -> DoubleArray(12) { b -> var s = kk[a][b]; for (y in 0 until r) s -= kniInv[a][y] * kk[12 + y][b]; s } }
        val fc = DoubleArray(12) { a -> var s = ff[a]; for (y in 0 until r) s -= kniInv[a][y] * ff[12 + y]; s }
        val recover = { un: DoubleArray ->
            val rhs = DoubleArray(r) { x -> var s = ff[12 + x]; for (b in 0 until 12) s -= kk[12 + x][b] * un[b]; s }
            val ui = DoubleArray(r) { x -> var s = 0.0; for (y in 0 until r) s += inv[x][y] * rhs[y]; s }
            val um = un.copyOf()
            rel.forEachIndexed { k2, c -> um[c] = ui[k2] }
            um
        }
        return EndCondensation(kc, fc, recover)
    }

    private fun invertSmall(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        val m = Array(n) { r -> DoubleArray(2 * n) { c -> if (c < n) a[r][c] else if (c - n == r) 1.0 else 0.0 } }
        for (col in 0 until n) {
            var p = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[p][col])) p = r
            val t = m[col]; m[col] = m[p]; m[p] = t
            val piv = m[col][col]
            require(abs(piv) > 1e-30) { "Member end release is singular" }
            for (c in 0 until 2 * n) m[col][c] /= piv
            for (r in 0 until n) if (r != col) {
                val f = m[r][col]
                if (f != 0.0) for (c in 0 until 2 * n) m[r][c] -= f * m[col][c]
            }
        }
        return Array(n) { r -> DoubleArray(n) { c -> m[r][n + c] } }
    }

    internal fun rotation(ax: MemberAxes) = arrayOf(
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

    internal fun globalStiffness(kl: Array<DoubleArray>, r: Array<DoubleArray>): Array<DoubleArray> {
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
        val cond: EndCondensation,
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
            val klFull = localStiffness(m, ax.length, model.beamTheory)
            val w = localUdl(model, m, ax)
            val pts = localPointLoads(model, m, ax)
            val feqFull = equivalentNodal(w, ax.length)
            val (py, pz) = phi(m, ax.length, model.beamTheory)
            for (p in pts) { val e = equivalentNodal(p, ax.length, py, pz); for (a in 0 until 12) feqFull[a] += e[a] }
            val cond = endCondensation(m, klFull, feqFull)
            data[m.id] = MemberData(ax, klFull, feqFull, cond, w, pts)
            val kl = cond.k
            val feq = cond.f

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
            // member-end displacements (rotations at hinges / springs differ from the node rotation)
            val ul = d.cond.recover(toLocal12(r, ug))
            endForces[m.id] = DoubleArray(12) { i ->
                var s = 0.0
                for (c in 0 until 12) s += d.klFull[i][c] * ul[c]
                s - d.feqFull[i]
            }
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
            if (model.beamTheory == BeamTheory.TIMOSHENKO) {
                // shear deflection of the fixed-ended member under its own loads (Timoshenko)
                val g = m.material.g
                val shearBubble = s * (l - s) / 2.0
                if (m.section.asy > 0) v += w.y * shearBubble / (g * m.section.asy)
                if (m.section.asz > 0) wz += w.z * shearBubble / (g * m.section.asz)
                for (p in pts) {
                    val lin = if (s <= p.a) (l - p.a) / l * s else p.a / l * (l - s)
                    if (m.section.asy > 0) v += p.f.y * lin / (g * m.section.asy)
                    if (m.section.asz > 0) wz += p.f.z * lin / (g * m.section.asz)
                }
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
