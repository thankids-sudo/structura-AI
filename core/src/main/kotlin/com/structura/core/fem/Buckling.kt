package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Result of a linear (first-order elastic) global buckling analysis.
 * [lambda] is the critical load multiplier λcr of the analysed combination: the loads × λcr
 * reach elastic bifurcation. A negative λ means buckling would need the loads reversed.
 */
class BucklingResult(
    val lambda: Double,
    val combination: String,
    /** Per original member: points along the member and the normalised mode displacement there. */
    val modeShape: Map<Int, List<Pair<Vec3, Vec3>>>,
    val iterations: Int,
    val segmentsPerMember: Int,
)

/**
 * Global elastic buckling: (K + λ·Kg) φ = 0, solved for the smallest |λ| by inverse (power)
 * iteration with the Rayleigh quotient. Kg is the consistent geometric stiffness of 3D
 * Euler–Bernoulli members (Przemieniecki / McGuire, Gallagher & Ziemian, Matrix Structural
 * Analysis 2nd ed., Eq. 9.18) built from the axial forces of a first-order analysis.
 * Each member is subdivided into [segments] elements so member (local) buckling is captured.
 * Moment-hinge releases are not applied to Kg (conservative for most frames) — noted in reports.
 */
object Buckling {

    fun geometricStiffness(p: Double, l: Double, s: Section): Array<DoubleArray> {
        val k = Array(12) { DoubleArray(12) }
        fun set(r: Int, c: Int, v: Double) { k[r][c] = v; k[c][r] = v }
        val a = 6 * p / (5 * l)
        val b = p / 10
        val c = 2 * p * l / 15
        val d = p * l / 30
        val t = p * (s.iy + s.iz) / (s.a * l)
        set(1, 1, a); set(1, 5, b); set(1, 7, -a); set(1, 11, b)
        set(2, 2, a); set(2, 4, -b); set(2, 8, -a); set(2, 10, -b)
        set(3, 3, t); set(3, 9, -t)
        set(4, 4, c); set(4, 8, b); set(4, 10, -d)
        set(5, 5, c); set(5, 7, -b); set(5, 11, -d)
        set(7, 7, a); set(7, 11, -b)
        set(8, 8, a); set(8, 10, b)
        set(9, 9, t)
        set(10, 10, c)
        set(11, 11, c)
        return k
    }

    fun analyse(model: FrameModel, combination: LoadCombination = LoadCombination.ALL, segments: Int = 4): BucklingResult {
        val fm = model.factored(combination)
        val (mesh, chains) = ModelEditor.subdivide(fm, segments)
        val lin = FrameSolver.analyse(mesh)

        val nDof = mesh.nodes.size * 6
        val k = Array(nDof) { DoubleArray(nDof) }
        val kg = Array(nDof) { DoubleArray(nDof) }
        for (m in mesh.members) {
            val ax = FrameSolver.axes(mesh, m)
            val kl = FrameSolver.localStiffness(m, ax.length)
            FrameSolver.condense(kl, DoubleArray(12), FrameSolver.releasedDofs(m))
            val fe = lin.memberEndForces[m.id]!!
            val p = (-fe[0] + fe[6]) / 2.0                        // axial force, tension +
            val kgl = geometricStiffness(p, ax.length, m.section)
            val r = FrameSolver.rotation(ax)
            val kgG = FrameSolver.globalStiffness(kl, r)
            val kggG = FrameSolver.globalStiffness(kgl, r)
            val a0 = mesh.nodeIndex[m.i]!! * 6
            val b0 = mesh.nodeIndex[m.j]!! * 6
            val idx = IntArray(12) { if (it < 6) a0 + it else b0 + it - 6 }
            for (x in 0 until 12) for (y in 0 until 12) {
                k[idx[x]][idx[y]] += kgG[x][y]
                kg[idx[x]][idx[y]] += kggG[x][y]
            }
        }
        val fixed = BooleanArray(nDof)
        for (s in mesh.supports) {
            val base = mesh.nodeIndex[s.node]!! * 6
            for (a in 0 until 6) if (s.restraints[a]) fixed[base + a] = true
        }
        val free = (0 until nDof).filter { !fixed[it] }.toIntArray()
        val n = free.size
        val a = Array(n) { r -> DoubleArray(n) { c -> k[free[r]][free[c]] } }
        val b = Array(n) { r -> DoubleArray(n) { c -> -kg[free[r]][free[c]] } }
        val l = factor(a)

        fun mul(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(n) { r -> var s = 0.0; val row = m[r]; for (c in 0 until n) s += row[c] * v[c]; s }
        fun dot(x: DoubleArray, y: DoubleArray): Double { var s = 0.0; for (i in x.indices) s += x[i] * y[i]; return s }
        // A is factored in place, so keep an unfactored copy for the Rayleigh quotient
        val aFull = Array(n) { r -> DoubleArray(n) { c -> k[free[r]][free[c]] } }

        var x = DoubleArray(n) { 1.0 + 0.37 * ((it * 7919) % 13) / 13.0 }
        var lambda = Double.NaN
        var iter = 0
        while (iter < 800) {
            iter++
            val y = solve(l, mul(b, x))
            val big = y.maxOf { abs(it) }
            if (big < 1e-300) throw UnstableModelException("No axial forces in the structure — buckling is not possible under these loads.")
            for (i in y.indices) y[i] /= big
            val newLambda = dot(y, mul(aFull, y)) / dot(y, mul(b, y))
            x = y
            if (!lambda.isNaN() && abs(newLambda - lambda) <= 1e-9 * abs(newLambda)) { lambda = newLambda; break }
            lambda = newLambda
        }

        // mode shape: full displacement vector, normalised to unit max translation
        val phi = DoubleArray(nDof)
        for (r in 0 until n) phi[free[r]] = x[r]
        var maxT = 0.0
        for (node in mesh.nodes.indices) {
            val v = Vec3(phi[node * 6], phi[node * 6 + 1], phi[node * 6 + 2]).length
            if (v > maxT) maxT = v
        }
        if (maxT > 0) for (i in phi.indices) phi[i] /= maxT

        val shape = LinkedHashMap<Int, List<Pair<Vec3, Vec3>>>()
        for ((orig, chain) in chains) {
            val pts = ArrayList<Pair<Vec3, Vec3>>()
            chain.forEachIndexed { k2, mid ->
                val mm = mesh.members.first { it.id == mid }
                val ids = if (k2 == 0) listOf(mm.i, mm.j) else listOf(mm.j)
                for (nid in ids) {
                    val ix = mesh.nodeIndex[nid]!! * 6
                    pts += mesh.node(nid).pos to Vec3(phi[ix], phi[ix + 1], phi[ix + 2])
                }
            }
            shape[orig] = pts
        }
        return BucklingResult(lambda, combination.name, shape, iter, segments)
    }

    private fun factor(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        var maxDiag = 0.0
        for (i in 0 until n) maxDiag = maxOf(maxDiag, abs(a[i][i]))
        for (j in 0 until n) {
            var d = a[j][j]
            val rowJ = a[j]
            for (k in 0 until j) d -= rowJ[k] * rowJ[k]
            if (d <= maxDiag * 1e-12) throw UnstableModelException("Model is unstable — fix the supports before a buckling analysis.")
            val ljj = sqrt(d)
            rowJ[j] = ljj
            for (i in j + 1 until n) {
                val rowI = a[i]
                var s = rowI[j]
                for (k in 0 until j) s -= rowI[k] * rowJ[k]
                rowI[j] = s / ljj
            }
        }
        return a
    }

    private fun solve(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            val row = l[i]
            for (k in 0 until i) s -= row[k] * y[k]
            y[i] = s / row[i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until n) s -= l[k][i] * x[k]
            x[i] = s / l[i][i]
        }
        return x
    }
}
