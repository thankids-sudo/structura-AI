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
 * Linear-static 3D frame + shell analysis by the direct stiffness method: Euler–Bernoulli or Timoshenko
 * members (6 DOF per node), hinges / rotational springs and rigid offsets at member ends, uniform,
 * partial, trapezoidal and concentrated member loads, and Q4 flat-shell panels ([Shell]).
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
        val gj = Warping.stiffness(m, l)
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
        if (m.truss) intArrayOf(4, 5, 9, 10, 11)
        else (if (m.endI != null) intArrayOf(4, 5) else intArrayOf()) + (if (m.endJ != null) intArrayOf(10, 11) else intArrayOf())

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
        val springs = rel.map { if (m.truss) 0.0 else if (it < 6) m.endI!! else m.endJ!! }
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

    /** Local direction vector of a load of magnitude [v] in direction [dir]. */
    fun localVector(dir: LoadDir, v: Double, ax: MemberAxes): Vec3 = when (dir) {
        LoadDir.GLOBAL_X -> ax.toLocal(Vec3(v, 0.0, 0.0))
        LoadDir.GLOBAL_Y -> ax.toLocal(Vec3(0.0, v, 0.0))
        LoadDir.GLOBAL_Z -> ax.toLocal(Vec3(0.0, 0.0, v))
        LoadDir.LOCAL_X -> Vec3(v, 0.0, 0.0)
        LoadDir.LOCAL_Y -> Vec3(0.0, v, 0.0)
        LoadDir.LOCAL_Z -> Vec3(0.0, 0.0, v)
    }

    /** All loads on a member in local axes: self-weight, distributed (uniform / partial / trapezoidal) and concentrated. */
    fun memberLoads(model: FrameModel, m: Member, ax: MemberAxes): MemberLoads {
        val l = ax.length
        val dist = ArrayList<LocalDistLoad>()
        if (model.selfWeight && model.selfWeightFactor != 0.0) {
            val w = ax.toLocal(Vec3(0.0, 0.0, -m.material.gamma * m.section.a * model.selfWeightFactor))
            dist += LocalDistLoad(0.0, l, w, w)
        }
        for (ml in model.memberLoads) if (ml.member == m.id) {
            val a = (ml.a ?: 0.0).coerceIn(0.0, l)
            val b = (ml.b ?: l).coerceIn(a, l)
            if (b - a < 1e-9) continue
            dist += LocalDistLoad(a, b, localVector(ml.dir, ml.w, ax), localVector(ml.dir, ml.w2 ?: ml.w, ax))
        }
        val pts = model.pointLoads.filter { it.member == m.id }.map { pl ->
            require(pl.a in 0.0..l) { "Point load on member ${m.id} at a = ${pl.a} m lies outside the member" }
            LocalPointLoad(localVector(pl.dir, pl.p, ax), pl.a)
        }
        return MemberLoads(dist, pts)
    }

    /** Offset transformation u_flexible-end = T·u_node (local), rigid arms along the member axis. */
    fun offsetTransform(m: Member): Array<DoubleArray> {
        val t = Array(12) { r -> DoubleArray(12) { c -> if (r == c) 1.0 else 0.0 } }
        t[1][5] = m.offsetI; t[2][4] = -m.offsetI
        t[7][11] = -m.offsetJ; t[8][10] = m.offsetJ
        return t
    }

    private fun flexLength(m: Member, l: Double): Double {
        val lf = l - m.offsetI - m.offsetJ
        require(m.offsetI >= 0 && m.offsetJ >= 0 && lf > 0.05 * l) { "Member ${m.id}: rigid end offsets leave no flexible length" }
        return lf
    }

    /**
     * Equivalent loads of a member: fixed-end forces of the flexible part (flexible-end coordinates) and
     * the loads standing on the rigid arms, transferred to the nodes (node-level, local axes).
     * Distributed loads are integrated exactly (4-point Gauss of the point-load fixed-end forces).
     */
    fun memberEquivalent(m: Member, l: Double, loads: MemberLoads, theory: BeamTheory): Pair<DoubleArray, DoubleArray> {
        val oi = m.offsetI; val oj = m.offsetJ
        val lf = flexLength(m, l)
        val (py, pz) = phi(m, lf, theory)
        val flex = DoubleArray(12)
        val arm = DoubleArray(12)
        fun pointAt(f: Vec3, s: Double) {
            when {
                s < oi -> { arm[0] += f.x; arm[1] += f.y; arm[2] += f.z; val mm = Vec3(s, 0.0, 0.0) cross f; arm[3] += mm.x; arm[4] += mm.y; arm[5] += mm.z }
                s > l - oj -> { arm[6] += f.x; arm[7] += f.y; arm[8] += f.z; val mm = Vec3(s - l, 0.0, 0.0) cross f; arm[9] += mm.x; arm[10] += mm.y; arm[11] += mm.z }
                else -> { val e = equivalentNodal(LocalPointLoad(f, s - oi), lf, py, pz); for (k in 0 until 12) flex[k] += e[k] }
            }
        }
        for (d in loads.dist) {
            val cuts = listOf(d.a, d.b, oi, l - oj).filter { it >= d.a && it <= d.b }.sorted().distinct()
            for (k in 1 until cuts.size) gauss(cuts[k - 1], cuts[k]) { s, w -> pointAt(d.at(s) * w, s) }
        }
        for (p in loads.points) pointAt(p.f, p.a)
        return flex to arm
    }

    private class MemberData(
        val ax: MemberAxes,
        val kNode: Array<DoubleArray>,
        val fNode: DoubleArray,
        val cond: EndCondensation,
        val tOff: Array<DoubleArray>,
        val loads: MemberLoads,
    )

    /** Member stiffness in global axes (theory, springs/hinges and rigid offsets included). */
    fun memberGlobalStiffness(model: FrameModel, m: Member): Array<DoubleArray> {
        val ax = axes(model, m)
        val lf = flexLength(m, ax.length)
        val cond = endCondensation(m, localStiffness(m, lf, model.beamTheory), DoubleArray(12))
        return globalStiffness(tTkt(offsetTransform(m), cond.k), rotation(ax))
    }

    private fun tTkt(t: Array<DoubleArray>, k: Array<DoubleArray>): Array<DoubleArray> {
        val kt = Array(12) { i -> DoubleArray(12) { j -> var s = 0.0; for (q in 0 until 12) s += k[i][q] * t[q][j]; s } }
        return Array(12) { i -> DoubleArray(12) { j -> var s = 0.0; for (q in 0 until 12) s += t[q][i] * kt[q][j]; s } }
    }

    private fun tTf(t: Array<DoubleArray>, f: DoubleArray) = DoubleArray(12) { i -> var s = 0.0; for (q in 0 until 12) s += t[q][i] * f[q]; s }
    private fun tf(t: Array<DoubleArray>, u: DoubleArray) = DoubleArray(12) { i -> var s = 0.0; for (q in 0 until 12) s += t[i][q] * u[q]; s }

    /** Global → support-axes transformation for inclination angles (deg) about X, then Y, then Z: T = (Rz·Ry·Rx)ᵀ. */
    fun inclinationT(deg: List<Double>): Array<DoubleArray> {
        val (ax, ay, az) = deg.map { Math.toRadians(it) }
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, kotlin.math.cos(ax), -kotlin.math.sin(ax)), doubleArrayOf(0.0, kotlin.math.sin(ax), kotlin.math.cos(ax)))
        val ry = arrayOf(doubleArrayOf(kotlin.math.cos(ay), 0.0, kotlin.math.sin(ay)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-kotlin.math.sin(ay), 0.0, kotlin.math.cos(ay)))
        val rz = arrayOf(doubleArrayOf(kotlin.math.cos(az), -kotlin.math.sin(az), 0.0), doubleArrayOf(kotlin.math.sin(az), kotlin.math.cos(az), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { a[i][it] * b[it][j] } } }
        val r = mul(rz, mul(ry, rx))
        return Array(3) { i -> DoubleArray(3) { j -> r[j][i] } }
    }

    /** Analyse one load combination (factored loads superposed in a single solve). */
    fun analyse(model: FrameModel, combination: LoadCombination): FrameResults = analyse(model.factored(combination))

    /** Analyse a list of combinations. */
    fun analyseAll(model: FrameModel, combinations: List<LoadCombination>): List<Pair<LoadCombination, FrameResults>> =
        combinations.map { it to analyse(model, it) }

    /**
     * Analyse the model with every load × 1.0. Tension-only members are handled iteratively: a member found in
     * compression is switched off (stiffness × 10⁻⁹) and the model re-solved; a switched-off member that would
     * lengthen is switched back on (max 30 cycles).
     */
    fun analyse(model: FrameModel): FrameResults {
        if (model.members.none { it.tensionOnly }) return analyseLinear(model)
        var off = emptySet<Int>()
        repeat(30) {
            val trial = model.copy(members = model.members.map { m ->
                if (m.id in off) m.copy(section = m.section.copy(a = m.section.a * 1e-9, iy = m.section.iy * 1e-9, iz = m.section.iz * 1e-9, j = m.section.j * 1e-9)) else m
            })
            val r = analyseLinear(trial)
            val next = HashSet<Int>()
            for (m in model.members.filter { it.tensionOnly }) {
                if (m.id in off) {
                    val e = (model.node(m.j).pos - model.node(m.i).pos).unit()
                    val di = r.nodeDisplacements[m.i]!!; val dj = r.nodeDisplacements[m.j]!!
                    val elong = (dj[0] - di[0]) * e.x + (dj[1] - di[1]) * e.y + (dj[2] - di[2]) * e.z
                    if (elong <= 1e-12) next += m.id
                } else if (r.internalForces(m.id, 2).minOf { it.n } < -1e-6) next += m.id
            }
            if (next == off) return r.withInactive(off)
            off = next
        }
        throw IllegalStateException("Tension-only iteration did not converge — check bracing layout")
    }

    private fun analyseLinear(model: FrameModel): FrameResults {
        require(model.members.isNotEmpty() || model.panels.isNotEmpty()) { "Model has no members or panels" }
        require(model.supports.isNotEmpty()) { "Model has no supports" }
        // 7th (warping) DOF at nodes where members are marked "warping continuous"
        val contMembers = model.members.filter { Warping.continuous(it) }
        val wIndex = LinkedHashMap<Int, Int>(); val wRef = HashMap<Int, Vec3>()
        for (m in contMembers) {
            val x = (model.node(m.j).pos - model.node(m.i).pos).unit()
            if (m.warpContI && !m.warpI && m.i !in wIndex) { wIndex[m.i] = model.nodes.size * 6 + wIndex.size; wRef[m.i] = x }
            if (m.warpContJ && !m.warpJ && m.j !in wIndex) { wIndex[m.j] = model.nodes.size * 6 + wIndex.size; wRef[m.j] = x }
        }
        val nDof = model.nodes.size * 6 + wIndex.size
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
            val lf = flexLength(m, ax.length)
            val loads = memberLoads(model, m, ax)
            val (feqFlex, fArm) = memberEquivalent(m, ax.length, loads, model.beamTheory)
            val cond = endCondensation(m, localStiffness(m, lf, model.beamTheory), feqFlex)
            val tOff = offsetTransform(m)
            val kNode = tTkt(tOff, cond.k)
            val fNode = tTf(tOff, cond.f).also { v -> for (q in 0 until 12) v[q] += fArm[q] }
            data[m.id] = MemberData(ax, kNode, fNode, cond, tOff, loads)

            val r = rotation(ax)
            val kg = globalStiffness(kNode, r)
            val idx = dofs(m)
            for (a in 0 until 12) for (b in 0 until 12) k[idx[a]][idx[b]] += kg[a][b]
            val fg = toGlobal12(r, fNode)
            for (a in 0 until 12) f[idx[a]] += fg[a]
        }
        // replace the condensed torsion of warping-continuous members by the exact [φ, θ] matrix
        for (m in contMembers) {
            val ax = axes(model, m); val lf = flexLength(m, ax.length); val e = ax.x
            val kt = Warping.stiffness(m, lf)
            val ri = model.nodeIndex[m.i]!! * 6 + 3; val rj = model.nodeIndex[m.j]!! * 6 + 3
            val ev = doubleArrayOf(e.x, e.y, e.z)
            for (a in 0 until 3) for (b in 0 until 3) {
                val v = kt * ev[a] * ev[b]
                k[ri + a][ri + b] -= v; k[rj + a][rj + b] -= v; k[ri + a][rj + b] += v; k[rj + a][ri + b] += v
            }
            val k4 = Warping.matrix4(m, lf)
            val stI = if (m.warpI) 0 else if (m.warpContI) 1 else 2          // 0 restrained, 1 continuous, 2 free
            val stJ = if (m.warpJ) 0 else if (m.warpContJ) 1 else 2
            // condense free θ, drop restrained θ
            val keep = (0 until 4).filter { it == 0 || it == 2 || (it == 1 && stI == 1) || (it == 3 && stJ == 1) }
            val cond = (0 until 4).filter { (it == 1 && stI == 2) || (it == 3 && stJ == 2) }
            val kc = Array(4) { a -> DoubleArray(4) { b -> k4[a][b] } }
            for (c in cond) for (a in keep) for (b in keep) kc[a][b] -= k4[a][c] * k4[c][b] / k4[c][c]
            if (cond.size == 2) {   // both free: exact 2-step condensation (second pivot updated)
                val c1 = cond[0]; val c2 = cond[1]
                val k1 = Array(4) { a -> DoubleArray(4) { b -> k4[a][b] - k4[a][c1] * k4[c1][b] / k4[c1][c1] } }
                for (a in keep) for (b in keep) kc[a][b] = k1[a][b] - k1[a][c2] * k1[c2][b] / k1[c2][c2]
            }
            fun map(local: Int): List<Pair<Int, Double>> = when (local) {
                0 -> (0 until 3).map { ri + it to ev[it] }
                2 -> (0 until 3).map { rj + it to ev[it] }
                // θ = dφ/dx is invariant under member reversal (φ and x both change sign), so no sign flip
                1 -> listOf(wIndex[m.i]!! to 1.0)
                else -> listOf(wIndex[m.j]!! to 1.0)
            }
            for (a in keep) for (b in keep) for ((ga, ca) in map(a)) for ((gb, cb) in map(b)) k[ga][gb] += kc[a][b] * ca * cb
        }
        for (p in model.panels) {
            val kp = Shell.globalStiffness(model, p)
            val fp = Shell.loadVector(model, p)
            val idx = IntArray(24) { (model.nodeIndex[p.nodes[it / 6]] ?: error("Panel ${p.id}: missing node")) * 6 + it % 6 }
            for (a in 0 until 24) { f[idx[a]] += fp[a]; for (b in 0 until 24) k[idx[a]][idx[b]] += kp[a][b] }
        }
        for (nl in model.nodalLoads) {
            val base = (model.nodeIndex[nl.node] ?: error("Load on missing node ${nl.node}")) * 6
            for (a in 0 until 6) f[base + a] += nl.values[a]
        }

        // applied moment about the origin (for the 6-component static check)
        val totalMoment = DoubleArray(3)
        for ((idx, n) in model.nodes.withIndex()) {
            val b = idx * 6
            totalMoment[0] += n.y * f[b + 2] - n.z * f[b + 1] + f[b + 3]
            totalMoment[1] += n.z * f[b] - n.x * f[b + 2] + f[b + 4]
            totalMoment[2] += n.x * f[b + 1] - n.y * f[b] + f[b + 5]
        }

        // inclined supports: node DOFs expressed in the support axes (u_local = Tᵀ… with T = Rᵀ per 3×3 block)
        val incl = HashMap<Int, Array<DoubleArray>>()      // node index → T (3×3, global → local)
        for (s in model.supports) {
            val ang = s.inclination ?: continue
            if (ang.all { abs(it) < 1e-12 }) continue
            val t = inclinationT(ang)
            val idx = model.nodeIndex[s.node]!!
            incl[idx] = t
            for (blk in 0 until 2) {
                val r0 = idx * 6 + blk * 3
                for (c in 0 until nDof) {              // rows ← T·K
                    val v = DoubleArray(3) { a -> var x = 0.0; for (q in 0 until 3) x += t[a][q] * k[r0 + q][c]; x }
                    for (a in 0 until 3) k[r0 + a][c] = v[a]
                }
                for (r in 0 until nDof) {              // cols ← K·Tᵀ
                    val v = DoubleArray(3) { a -> var x = 0.0; for (q in 0 until 3) x += k[r][r0 + q] * t[a][q]; x }
                    for (a in 0 until 3) k[r][r0 + a] = v[a]
                }
                val fv = DoubleArray(3) { a -> var x = 0.0; for (q in 0 until 3) x += t[a][q] * f[r0 + q]; x }
                for (a in 0 until 3) f[r0 + a] = fv[a]
            }
        }

        val fixed = BooleanArray(nDof)
        for (s in model.supports) {
            val base = (model.nodeIndex[s.node] ?: error("Support on missing node ${s.node}")) * 6
            for (a in 0 until 6) if (s.restraints[a]) fixed[base + a] = true
            s.springs?.let { ks -> for (a in 0 until 6) if (!s.restraints[a] && ks[a] > 0) k[base + a][base + a] += ks[a] }
        }
        // 2D plane frame: out-of-plane DOFs held (not reported as reactions)
        if (model.planeXZ) for (i in model.nodes.indices) { fixed[i * 6 + 1] = true; fixed[i * 6 + 3] = true; fixed[i * 6 + 5] = true }
        // rotations without stiffness (nodes joined only by truss / hinged members) are held, as in STAAD
        val maxDiag = (0 until nDof).maxOfOrNull { abs(k[it][it]) } ?: 1.0
        for (d in 0 until model.nodes.size * 6) if (d % 6 >= 3 && !fixed[d] && abs(k[d][d]) <= 1e-12 * maxDiag && abs(f[d]) < 1e-12) fixed[d] = true
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
                if (!s.restraints[a]) {
                    val ks = s.springs?.getOrNull(a) ?: 0.0
                    if (ks > 0) rr[a] = -ks * u[base + a]
                    continue
                }
                var sum = 0.0
                val row = k[base + a]
                for (c in 0 until nDof) sum += row[c] * u[c]
                rr[a] = sum - f[base + a]
            }
            incl[model.nodeIndex[s.node]!!]?.let { t ->       // back to global: Tᵀ·r
                for (blk in 0 until 2) {
                    val v = DoubleArray(3) { a -> var x = 0.0; for (q in 0 until 3) x += t[q][a] * rr[blk * 3 + q]; x }
                    for (a in 0 until 3) rr[blk * 3 + a] = v[a]
                }
            }
            reactions[s.node] = rr
        }
        for ((idx, t) in incl) for (blk in 0 until 2) {
            val r0 = idx * 6 + blk * 3
            val v = DoubleArray(3) { a -> var x = 0.0; for (q in 0 until 3) x += t[q][a] * u[r0 + q]; x }
            for (a in 0 until 3) u[r0 + a] = v[a]
        }

        val nodeDisp = LinkedHashMap<Int, DoubleArray>()
        for ((idx, n) in model.nodes.withIndex()) nodeDisp[n.id] = DoubleArray(6) { u[idx * 6 + it] }

        val endForces = LinkedHashMap<Int, DoubleArray>()
        val axesMap = LinkedHashMap<Int, MemberAxes>()
        val nodeLocal = LinkedHashMap<Int, DoubleArray>()
        val flexDisp = LinkedHashMap<Int, DoubleArray>()
        val loadsMap = LinkedHashMap<Int, MemberLoads>()
        val warpEnds = HashMap<Int, Pair<Double?, Double?>>()
        val totalLoad = DoubleArray(3)
        for (m in model.members) {
            val d = data[m.id]!!
            val r = rotation(d.ax)
            val ug = DoubleArray(12).also { arr -> dofs(m).forEachIndexed { a, dof -> arr[a] = u[dof] } }
            val ul = toLocal12(r, ug)
            endForces[m.id] = DoubleArray(12) { i ->
                var s = 0.0
                for (c in 0 until 12) s += d.kNode[i][c] * ul[c]
                s - d.fNode[i]
            }
            nodeLocal[m.id] = ul
            flexDisp[m.id] = d.cond.recover(tf(d.tOff, ul))
            if (Warping.continuous(m)) {
                val e = d.ax.x
                fun rot(n: Int) = model.nodeIndex[n]!! * 6 + 3
                val phi1 = e.x * u[rot(m.i)] + e.y * u[rot(m.i) + 1] + e.z * u[rot(m.i) + 2]
                val phi2 = e.x * u[rot(m.j)] + e.y * u[rot(m.j) + 1] + e.z * u[rot(m.j) + 2]
                fun th(n: Int, restr: Boolean, cont: Boolean): Double? = when {
                    restr -> 0.0
                    cont -> u[wIndex[n]!!]
                    else -> null
                }
                val t1 = th(m.i, m.warpI, m.warpContI); val t2 = th(m.j, m.warpJ, m.warpContJ)
                val lf = flexLength(m, d.ax.length)
                val c = Warping.coeffsGeneral(m.material.g * m.section.j, m.material.e * m.section.iw, lf, phi1, phi2, t1, t2)
                val torque = m.material.g * m.section.j * c[1]
                endForces[m.id]!![3] = -torque; endForces[m.id]!![9] = torque
                warpEnds[m.id] = t1 to t2
            }
            axesMap[m.id] = d.ax
            loadsMap[m.id] = d.loads
            var tot = Vec3.ZERO
            for (dl in d.loads.dist) gauss(dl.a, dl.b) { s, w -> tot += dl.at(s) * w }
            for (p in d.loads.points) tot += p.f
            val g = d.ax.toGlobal(tot)
            totalLoad[0] += g.x; totalLoad[1] += g.y; totalLoad[2] += g.z
        }
        val panelResults = LinkedHashMap<Int, PanelResult>()
        for (p in model.panels) {
            val ug = DoubleArray(24) { u[model.nodeIndex[p.nodes[it / 6]]!! * 6 + it % 6] }
            panelResults[p.id] = Shell.result(model, p, ug)
            val rs = Shell.resultant(model, p)
            totalLoad[0] += rs.x; totalLoad[1] += rs.y; totalLoad[2] += rs.z
        }
        for (nl in model.nodalLoads) for (a in 0 until 3) totalLoad[a] += nl.values[a]

        return FrameResults(model, nodeDisp, reactions, endForces, axesMap, nodeLocal, flexDisp, totalLoad, loadsMap, panelResults, totalMoment,
            warpingEnds = warpEnds, warpingDofs = wIndex.mapValues { u[it.value] })
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

/** Linearly varying distributed load on [a, b] in local axes (kN/m); positions measured from node i. */
data class LocalDistLoad(val a: Double, val b: Double, val w1: Vec3, val w2: Vec3) {
    fun at(s: Double): Vec3 = if (b - a < 1e-12) w1 else w1 + (w2 - w1) * ((s - a) / (b - a))
}

class MemberLoads(val dist: List<LocalDistLoad>, val points: List<LocalPointLoad>)

private val G4X = doubleArrayOf(-0.8611363115940526, -0.3399810435848563, 0.3399810435848563, 0.8611363115940526)
private val G4W = doubleArrayOf(0.3478548451374538, 0.6521451548625461, 0.6521451548625461, 0.3478548451374538)

/** 4-point Gauss–Legendre over [a, b]: calls f(s, weight). Exact for polynomials up to degree 7. */
inline fun gauss(a: Double, b: Double, f: (Double, Double) -> Unit) {
    if (b - a <= 0) return
    val h = (b - a) / 2; val c = (a + b) / 2
    for (k in 0 until 4) f(c + h * gaussX(k), h * gaussW(k))
}

fun gaussX(k: Int) = G4X[k]
fun gaussW(k: Int) = G4W[k]

class FrameResults(
    val model: FrameModel,
    /** Global displacements per node: ux, uy, uz (m), φx, φy, φz (rad). */
    val nodeDisplacements: Map<Int, DoubleArray>,
    /** Support reactions per node in global axes (kN, kN·m). */
    val reactions: Map<Int, DoubleArray>,
    /** Local end forces acting ON the member at its NODES (rigid arms included), 12 values (i end then j end). */
    val memberEndForces: Map<Int, DoubleArray>,
    val memberAxes: Map<Int, MemberAxes>,
    private val memberNodeLocalDisp: Map<Int, DoubleArray>,
    private val memberFlexDisp: Map<Int, DoubleArray>,
    /** Total applied load ΣFx, ΣFy, ΣFz (kN) including self-weight. */
    val totalLoad: DoubleArray,
    val memberLoads: Map<Int, MemberLoads> = emptyMap(),
    val panelResults: Map<Int, PanelResult> = emptyMap(),
    /** Total applied moment about the global origin ΣMx, ΣMy, ΣMz (kN·m). */
    val totalMoment: DoubleArray = DoubleArray(3),
    /** Tension-only members switched off in the final iteration. */
    val inactiveMembers: Set<Int> = emptySet(),
    /** End warping θ = φ' of warping-continuous members (null = free end). */
    val warpingEnds: Map<Int, Pair<Double?, Double?>> = emptyMap(),
    /** Nodal warping DOF values (rad/m) of the 7th DOF, by node id. */
    val warpingDofs: Map<Int, Double> = emptyMap(),
) {
    internal fun withInactive(off: Set<Int>) = FrameResults(model, nodeDisplacements, reactions, memberEndForces, memberAxes,
        memberNodeLocalDisp, memberFlexDisp, totalLoad, memberLoads, panelResults, totalMoment, off, warpingEnds, warpingDofs)

    /** Non-uniform torsion along a member: twist, bimoment B (kN·m²), St Venant and warping torque (see [Warping]). */
    fun warping(memberId: Int, n: Int = 20): List<Warping.Station> {
        val m = model.members.first { it.id == memberId }
        val flex = memberFlexDisp[memberId] ?: error("No member $memberId")
        val l = memberAxes[memberId]!!.length - m.offsetI - m.offsetJ
        warpingEnds[memberId]?.let { (t1, t2) -> return Warping.stationsGeneral(m, l, flex[3], flex[9], t1, t2, n) }
        return Warping.stations(m, l, flex[3], flex[9], n)
    }

    /** Σ(r × R + M_R) + ΣM_applied about the origin — should be ≈ 0. */
    val momentEquilibriumError: DoubleArray
        get() {
            val m = totalMoment.copyOf()
            for ((n, r) in reactions) {
                val p = model.node(n)
                m[0] += p.y * r[2] - p.z * r[1] + r[3]
                m[1] += p.z * r[0] - p.x * r[2] + r[4]
                m[2] += p.x * r[1] - p.y * r[0] + r[5]
            }
            return m
        }

    /** STAAD-style STATIC CHECK: applied loads, reactions and residuals for all six components. */
    fun staticCheck(): String = buildString {
        val ff = equilibriumError; val mm = momentEquilibriumError
        val rf = DoubleArray(3); val rm = DoubleArray(3)
        for ((n, r) in reactions) {
            val p = model.node(n)
            for (a in 0 until 3) rf[a] += r[a]
            rm[0] += p.y * r[2] - p.z * r[1] + r[3]; rm[1] += p.z * r[0] - p.x * r[2] + r[4]; rm[2] += p.x * r[1] - p.y * r[0] + r[5]
        }
        appendLine("STATIC CHECK (about origin)        X            Y            Z")
        appendLine(String.format(java.util.Locale.US, "Applied force (kN)     %12.4f %12.4f %12.4f", totalLoad[0], totalLoad[1], totalLoad[2]))
        appendLine(String.format(java.util.Locale.US, "Reaction force (kN)    %12.4f %12.4f %12.4f", rf[0], rf[1], rf[2]))
        appendLine(String.format(java.util.Locale.US, "Residual force         %12.3e %12.3e %12.3e", ff[0], ff[1], ff[2]))
        appendLine(String.format(java.util.Locale.US, "Applied moment (kN·m)  %12.4f %12.4f %12.4f", totalMoment[0], totalMoment[1], totalMoment[2]))
        appendLine(String.format(java.util.Locale.US, "Reaction moment (kN·m) %12.4f %12.4f %12.4f", rm[0], rm[1], rm[2]))
        appendLine(String.format(java.util.Locale.US, "Residual moment        %12.3e %12.3e %12.3e", mm[0], mm[1], mm[2]))
    }

    /** ΣReactions + ΣLoads per global direction — should be ≈ 0 (equilibrium check, as in RFEM). */
    val equilibriumError: DoubleArray
        get() {
            val r = DoubleArray(3)
            for (v in reactions.values) for (a in 0 until 3) r[a] += v[a]
            return DoubleArray(3) { r[it] + totalLoad[it] }
        }

    /**
     * Internal forces at [n] + 1 equally spaced stations along the full member (node i → node j), plus
     * stations either side of point loads, load-segment ends and rigid-offset faces. Exact by statics.
     */
    fun internalForces(memberId: Int, n: Int = 20): List<Station> {
        val fe = memberEndForces[memberId] ?: error("No member $memberId")
        val l = memberAxes[memberId]!!.length
        val ld = memberLoads[memberId] ?: MemberLoads(emptyList(), emptyList())
        val mem = model.members.first { it.id == memberId }
        val extra = ld.points.flatMap { listOf(it.a - 1e-6, it.a + 1e-6) } +
            ld.dist.flatMap { listOf(it.a, it.b) } + listOf(mem.offsetI, l - mem.offsetJ)
        val ss = ((0..n).map { l * it / n } + extra).filter { it in 0.0..l }.sorted()
            .fold(ArrayList<Double>()) { acc, v -> if (acc.isEmpty() || v - acc.last() > 1e-9) acc.add(v); acc }
        val fi = Vec3(fe[0], fe[1], fe[2])
        val mi = Vec3(fe[3], fe[4], fe[5])
        return ss.map { s ->
            var force = fi
            var mom = mi + (Vec3(-s, 0.0, 0.0) cross fi)
            for (d in ld.dist) {
                val hi = minOf(d.b, s)
                gauss(d.a, hi) { t, w ->
                    val q = d.at(t) * w
                    force += q
                    mom += Vec3(t - s, 0.0, 0.0) cross q
                }
            }
            for (p in ld.points) if (p.a < s) { force += p.f; mom += Vec3(p.a - s, 0.0, 0.0) cross p.f }
            Station(s, -force.x, -force.y, -force.z, -mom.x, -mom.y, -mom.z)
        }
    }

    /**
     * Deformed shape of a member in global coordinates: rigid arms move with their node; the flexible
     * part uses Hermite interpolation of its end displacements plus the exact fixed-end deflection due
     * to its own loads (bending, and shear for Timoshenko) by integration of the point-load solution.
     */
    fun deformedShape(memberId: Int, scale: Double, n: Int = 16): List<Vec3> {
        val m = model.members.first { it.id == memberId }
        val ax = memberAxes[memberId]!!
        val un = memberNodeLocalDisp[memberId]!!
        val uf = memberFlexDisp[memberId]!!
        val ld = memberLoads[memberId] ?: MemberLoads(emptyList(), emptyList())
        val l = ax.length
        val oi = m.offsetI; val oj = m.offsetJ
        val lf = l - oi - oj
        val e = m.material.e
        val eiy = e * m.section.iy
        val eiz = e * m.section.iz
        val tim = model.beamTheory == BeamTheory.TIMOSHENKO
        val gay = m.material.g * m.section.asy
        val gaz = m.material.g * m.section.asz
        val p0 = model.node(m.i).pos
        fun green(x: Double, a: Double): Double {           // fixed–fixed bending deflection × EI / P
            val b = lf - a
            return if (x <= a) b * b * x * x * (3 * a * lf - 3 * a * x - b * x) / (6 * lf * lf * lf)
            else { val xr = lf - x; a * a * xr * xr * (3 * b * lf - 3 * b * xr - a * xr) / (6 * lf * lf * lf) }
        }
        fun greenShear(x: Double, a: Double) = if (x <= a) (lf - a) / lf * x else a / lf * (lf - x)
        return (0..n).map { k ->
            val s = l * k / n
            val loc: Vec3 = when {
                s < oi - 1e-12 -> Vec3(un[0], un[1] + s * un[5], un[2] - s * un[4])
                s > l - oj + 1e-12 -> { val r = s - l; Vec3(un[6], un[7] + r * un[11], un[8] - r * un[10]) }
                else -> {
                    val x = (s - oi).coerceIn(0.0, lf)
                    val xi = x / lf
                    val n1 = 1 - 3 * xi * xi + 2 * xi * xi * xi
                    val n2 = lf * (xi - 2 * xi * xi + xi * xi * xi)
                    val n3 = 3 * xi * xi - 2 * xi * xi * xi
                    val n4 = lf * (-xi * xi + xi * xi * xi)
                    var v = n1 * uf[1] + n2 * uf[5] + n3 * uf[7] + n4 * uf[11]
                    var w = n1 * uf[2] - n2 * uf[4] + n3 * uf[8] - n4 * uf[10]
                    fun addPoint(f: Vec3, a: Double) {
                        val g = green(x, a)
                        v += f.y * g / eiz; w += f.z * g / eiy
                        if (tim) {
                            val gs = greenShear(x, a)
                            if (gay > 0) v += f.y * gs / gay
                            if (gaz > 0) w += f.z * gs / gaz
                        }
                    }
                    for (d in ld.dist) {
                        val a0 = maxOf(d.a, oi) - oi; val b0 = minOf(d.b, l - oj) - oi
                        if (b0 <= a0) continue
                        val cuts = listOf(a0, b0, x).filter { it in a0..b0 }.sorted()
                        for (q in 1 until cuts.size) gauss(cuts[q - 1], cuts[q]) { t, wt -> addPoint(d.at(t + oi) * wt, t) }
                    }
                    for (p in ld.points) if (p.a in oi..(l - oj)) addPoint(p.f, p.a - oi)
                    Vec3(uf[0] * (1 - xi) + uf[6] * xi, v, w)
                }
            }
            p0 + ax.x * s + ax.toGlobal(loc) * scale
        }
    }

    fun maxDisplacement(): Pair<Int, Double> =
        nodeDisplacements.maxByOrNull { (_, d) -> Vec3(d[0], d[1], d[2]).length }!!
            .let { (id, d) -> id to Vec3(d[0], d[1], d[2]).length }

    /** Largest member deflection relative to the chord (m), checked along every member. */
    fun maxMemberDeflection(): Double = model.members.maxOfOrNull { m ->
        val pts = deformedShape(m.id, 1.0, 20)
        val a = pts.first()
        val chord = pts.last() - a
        pts.withIndex().maxOf { (k, p) -> (p - (a + chord * (k / 20.0))).length }
    } ?: 0.0
}
