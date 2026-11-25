package com.structura.core.fem

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/** One natural mode of vibration. */
class Mode(
    val number: Int,
    val omega: Double,                 // rad/s
    /** Effective modal mass as a fraction of the total participating mass in X, Y, Z. */
    val participation: DoubleArray,
    /** Per original member: points and normalised mode displacement (unit max translation). */
    val shape: Map<Int, List<Pair<Vec3, Vec3>>>,
) {
    val frequency: Double get() = omega / (2 * PI)       // Hz
    val period: Double get() = 1.0 / frequency           // s
}

class ModalResult(
    val modes: List<Mode>,
    val totalMass: DoubleArray,        // t (kN·s²/m) participating in X, Y, Z
    val massSource: String,
    val segmentsPerMember: Int,
) {
    /** Cumulative effective mass fraction of the computed modes per direction (IS 1893 asks ≥ 90 %). */
    val cumulative: DoubleArray get() = DoubleArray(3) { d -> modes.sumOf { it.participation[d] } }
}

/**
 * Free-vibration analysis K·φ = ω²·M·φ with consistent member mass matrices (Przemieniecki),
 * solved for the lowest modes by subspace iteration with Rayleigh–Ritz (Bathe, FE Procedures §11.6).
 *
 * Mass sources (IS 1893 (Part 1):2016 Cl. 7.3.1 / Table 10 approach): self-weight, plus gravity
 * loads of the chosen cases converted to mass (|w|/g on members, |Fz|/g at nodes) with factors,
 * e.g. DL × 1.0 + LL × 0.25 (imposed load ≤ 3 kN/m²) or LL × 0.5 (> 3 kN/m²).
 */
object Modal {
    const val G = 9.81

    fun analyse(model: FrameModel, nModes: Int = 6, llFactor: Double = 0.25, segments: Int = 4): ModalResult {
        val plain = model.copy(members = model.members.map { it.copy(offsetI = 0.0, offsetJ = 0.0) })
        val (mesh, chains) = ModelEditor.subdivide(plain, segments)
        val nDof = mesh.nodes.size * 6
        val k = Array(nDof) { DoubleArray(nDof) }
        val m = Array(nDof) { DoubleArray(nDof) }

        fun factor(case: String) = when (case) { LoadCase.DL -> 1.0; LoadCase.LL -> llFactor; else -> 0.0 }

        for (mem in mesh.members) {
            val ax = FrameSolver.axes(mesh, mem)
            val l = ax.length
            // mass per length (t/m): self-weight + gravity member loads converted to mass
            var mu = if (mesh.selfWeight) mem.material.gamma * mem.section.a / G else 0.0
            for (ml in mesh.memberLoads) if (ml.member == mem.id && ml.dir == LoadDir.GLOBAL_Z) {
                val a = (ml.a ?: 0.0).coerceIn(0.0, l); val b = (ml.b ?: l).coerceIn(a, l)
                mu += abs((ml.w + (ml.w2 ?: ml.w)) / 2) * (b - a) / l * factor(ml.case) / G   // spread over the member
            }
            val ml = consistentMass(mu, l, (mem.section.iy + mem.section.iz) / mem.section.a)
            // concentrated member loads → lumped at the nearer end of the segment
            for (pl in mesh.pointLoads) if (pl.member == mem.id && pl.dir == LoadDir.GLOBAL_Z) {
                val mass = abs(pl.p) * factor(pl.case) / G
                val base = if (pl.a <= l / 2) 0 else 6
                for (t in 0 until 3) ml[base + t][base + t] += mass
            }
            val r = FrameSolver.rotation(ax)
            val kg = FrameSolver.memberGlobalStiffness(mesh, mem)
            val mg = FrameSolver.globalStiffness(ml, r)
            val a0 = mesh.nodeIndex[mem.i]!! * 6
            val b0 = mesh.nodeIndex[mem.j]!! * 6
            val idx = IntArray(12) { if (it < 6) a0 + it else b0 + it - 6 }
            for (x in 0 until 12) for (y in 0 until 12) { k[idx[x]][idx[y]] += kg[x][y]; m[idx[x]][idx[y]] += mg[x][y] }
        }
        for (p in mesh.panels) {
            val kp = Shell.globalStiffness(mesh, p)
            val idx = IntArray(24) { mesh.nodeIndex[p.nodes[it / 6]]!! * 6 + it % 6 }
            for (x in 0 until 24) for (y in 0 until 24) k[idx[x]][idx[y]] += kp[x][y]
            val area = Shell.area(Shell.frame(mesh, p))
            val mass = ((if (mesh.selfWeight) p.gamma * p.t else 0.0) + abs(p.pressure) * factor(p.case)) * area / G / 4
            for (n in 0 until 4) for (t in 0 until 3) m[idx[6 * n + t]][idx[6 * n + t]] += mass
        }
        for (nl in mesh.nodalLoads) {
            val mass = abs(nl.values[2]) * factor(nl.case) / G
            val base = mesh.nodeIndex[nl.node]!! * 6
            for (t in 0 until 3) m[base + t][base + t] += mass
        }

        val fixed = BooleanArray(nDof)
        for (s in mesh.supports) {
            val base = mesh.nodeIndex[s.node]!! * 6
            for (a in 0 until 6) if (s.restraints[a]) fixed[base + a] = true
        }
        val free = (0 until nDof).filter { !fixed[it] }.toIntArray()
        val n = free.size
        val kr = Array(n) { r -> DoubleArray(n) { c -> k[free[r]][free[c]] } }
        val mr = Array(n) { r -> DoubleArray(n) { c -> m[free[r]][free[c]] } }
        val lk = cholesky(Array(n) { kr[it].copyOf() }) ?: throw UnstableModelException("Model is unstable — fix supports before modal analysis.")

        val p = minOf(n, maxOf(2 * nModes, nModes + 8))
        val q = minOf(nModes, n)
        // starting vectors: diagonal-mass weighted unit vectors (Bathe's choice)
        var x = Array(n) { r -> DoubleArray(p) { c -> if (c == 0) mr[r][r] else if ((r % p) == c) 1.0 else 0.0 } }
        var lambdas = DoubleArray(p)
        var vecs: Array<DoubleArray> = x
        for (iter in 0 until 60) {
            // Y = K⁻¹ M X
            val y = Array(n) { DoubleArray(p) }
            for (c in 0 until p) {
                val mx = DoubleArray(n) { r -> var s = 0.0; for (t in 0 until n) s += mr[r][t] * x[t][c]; s }
                val sol = solve(lk, mx)
                for (r in 0 until n) y[r][c] = sol[r]
            }
            // reduced matrices
            val ky = proj(kr, y); val my = proj(mr, y)
            val (ev, qv) = generalizedEigen(ky, my) ?: break
            val newX = Array(n) { r -> DoubleArray(p) { c -> var s = 0.0; for (t in 0 until p) s += y[r][t] * qv[t][c]; s } }
            val conv = (0 until q).all { abs(ev[it] - lambdas[it]) <= 1e-8 * abs(ev[it]) }
            lambdas = ev; x = newX; vecs = newX
            if (conv && iter > 2) break
        }

        // participation
        val dirs = (0 until 3).map { d -> DoubleArray(n) { r -> if (free[r] % 6 == d) 1.0 else 0.0 } }
        val total = DoubleArray(3) { d -> val v = dirs[d]; quad(mr, v, v) }
        val modes = (0 until q).map { c ->
            val phi = DoubleArray(n) { vecs[it][c] }
            val gm = quad(mr, phi, phi)
            val part = DoubleArray(3) { d ->
                if (total[d] <= 0) 0.0 else { val l = quad(mr, phi, dirs[d]); l * l / gm / total[d] }
            }
            val full = DoubleArray(nDof)
            for (r in 0 until n) full[free[r]] = phi[r]
            var maxT = 0.0
            for (node in mesh.nodes.indices) maxT = maxOf(maxT, Vec3(full[node * 6], full[node * 6 + 1], full[node * 6 + 2]).length)
            if (maxT > 0) for (i in full.indices) full[i] /= maxT
            Mode(c + 1, sqrt(maxOf(lambdas[c], 0.0)), part, shapeOf(mesh, chains, full))
        }
        val src = "self-weight" + (if (model.memberLoads.isNotEmpty() || model.nodalLoads.isNotEmpty() || model.pointLoads.isNotEmpty())
            " + DL × 1.0 + LL × ${"%.2f".format(llFactor)} (gravity loads / g)" else "")
        return ModalResult(modes, total, src, segments)
    }

    internal fun shapeOf(mesh: FrameModel, chains: Map<Int, List<Int>>, full: DoubleArray): Map<Int, List<Pair<Vec3, Vec3>>> {
        val shape = LinkedHashMap<Int, List<Pair<Vec3, Vec3>>>()
        for ((orig, chain) in chains) {
            val pts = ArrayList<Pair<Vec3, Vec3>>()
            chain.forEachIndexed { k2, mid ->
                val mm = mesh.members.first { it.id == mid }
                for (nid in if (k2 == 0) listOf(mm.i, mm.j) else listOf(mm.j)) {
                    val ix = mesh.nodeIndex[nid]!! * 6
                    pts += mesh.node(nid).pos to Vec3(full[ix], full[ix + 1], full[ix + 2])
                }
            }
            shape[orig] = pts
        }
        return shape
    }

    /** Consistent mass matrix (local) of a uniform member: μ = mass/length, rg² = Ip/A (torsion). */
    fun consistentMass(mu: Double, l: Double, rg2: Double): Array<DoubleArray> {
        val k = Array(12) { DoubleArray(12) }
        fun set(r: Int, c: Int, v: Double) { k[r][c] = v; k[c][r] = v }
        val c = mu * l / 420.0
        val l2 = l * l
        set(0, 0, 140 * c); set(0, 6, 70 * c); set(6, 6, 140 * c)
        val t = mu * l * rg2 / 6
        set(3, 3, 2 * t); set(3, 9, t); set(9, 9, 2 * t)
        // v, θz
        set(1, 1, 156 * c); set(1, 5, 22 * l * c); set(1, 7, 54 * c); set(1, 11, -13 * l * c)
        set(5, 5, 4 * l2 * c); set(5, 7, 13 * l * c); set(5, 11, -3 * l2 * c)
        set(7, 7, 156 * c); set(7, 11, -22 * l * c); set(11, 11, 4 * l2 * c)
        // w, θy
        set(2, 2, 156 * c); set(2, 4, -22 * l * c); set(2, 8, 54 * c); set(2, 10, 13 * l * c)
        set(4, 4, 4 * l2 * c); set(4, 8, -13 * l * c); set(4, 10, -3 * l2 * c)
        set(8, 8, 156 * c); set(8, 10, 22 * l * c); set(10, 10, 4 * l2 * c)
        return k
    }

    // ---------------------------------------------------------------- small linear algebra
    private fun quad(a: Array<DoubleArray>, x: DoubleArray, y: DoubleArray): Double {
        var s = 0.0
        for (r in a.indices) { var t = 0.0; val row = a[r]; for (c in row.indices) t += row[c] * y[c]; s += x[r] * t }
        return s
    }

    private fun proj(a: Array<DoubleArray>, y: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size; val p = y[0].size
        val ay = Array(n) { r -> DoubleArray(p) { c -> var s = 0.0; for (t in 0 until n) s += a[r][t] * y[t][c]; s } }
        return Array(p) { i -> DoubleArray(p) { j -> var s = 0.0; for (t in 0 until n) s += y[t][i] * ay[t][j]; s } }
    }

    internal fun cholesky(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        var maxDiag = 0.0
        for (i in 0 until n) maxDiag = maxOf(maxDiag, abs(a[i][i]))
        for (j in 0 until n) {
            var d = a[j][j]
            for (k in 0 until j) d -= a[j][k] * a[j][k]
            if (d <= maxDiag * 1e-13) return null
            val ljj = sqrt(d)
            a[j][j] = ljj
            for (i in j + 1 until n) {
                var s = a[i][j]
                for (k in 0 until j) s -= a[i][k] * a[j][k]
                a[i][j] = s / ljj
            }
        }
        for (i in 0 until n) for (j in i + 1 until n) a[i][j] = 0.0
        return a
    }

    internal fun solve(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val y = DoubleArray(n)
        for (i in 0 until n) { var s = b[i]; for (k in 0 until i) s -= l[i][k] * y[k]; y[i] = s / l[i][i] }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) { var s = y[i]; for (k in i + 1 until n) s -= l[k][i] * x[k]; x[i] = s / l[i][i] }
        return x
    }

    /** Small symmetric-definite generalized eigenproblem A q = λ B q, eigenvalues ascending, B-orthonormal q. */
    internal fun generalizedEigen(a: Array<DoubleArray>, b: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>>? {
        val p = a.size
        val lb = cholesky(Array(p) { b[it].copyOf() }) ?: return null
        // C = L⁻¹ A L⁻ᵀ
        val linvA = Array(p) { DoubleArray(p) }
        for (c in 0 until p) { val col = forward(lb, DoubleArray(p) { a[it][c] }); for (r in 0 until p) linvA[r][c] = col[r] }
        val cm = Array(p) { DoubleArray(p) }
        for (r in 0 until p) { val row = forward(lb, linvA[r].copyOf()); for (c in 0 until p) cm[r][c] = row[c] }
        for (r in 0 until p) for (c in r + 1 until p) { val v = (cm[r][c] + cm[c][r]) / 2; cm[r][c] = v; cm[c][r] = v }
        val (ev, vz) = jacobi(cm)
        val order = ev.indices.sortedBy { ev[it] }
        val q = Array(p) { DoubleArray(p) }
        for ((k2, idx) in order.withIndex()) {
            val z = DoubleArray(p) { vz[it][idx] }
            val qq = backward(lb, z)                      // q = L⁻ᵀ z
            for (r in 0 until p) q[r][k2] = qq[r]
        }
        return DoubleArray(p) { ev[order[it]] } to q
    }

    private fun forward(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size; val y = DoubleArray(n)
        for (i in 0 until n) { var s = b[i]; for (k in 0 until i) s -= l[i][k] * y[k]; y[i] = s / l[i][i] }
        return y
    }

    private fun backward(l: Array<DoubleArray>, y: DoubleArray): DoubleArray {
        val n = y.size; val x = DoubleArray(n)
        for (i in n - 1 downTo 0) { var s = y[i]; for (k in i + 1 until n) s -= l[k][i] * x[k]; x[i] = s / l[i][i] }
        return x
    }

    /** Cyclic Jacobi for a small symmetric matrix: eigenvalues and eigenvector columns. */
    private fun jacobi(a0: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = a0.size
        val a = Array(n) { a0[it].copyOf() }
        val v = Array(n) { r -> DoubleArray(n) { c -> if (r == c) 1.0 else 0.0 } }
        for (sweep in 0 until 100) {
            var off = 0.0
            for (r in 0 until n) for (c in r + 1 until n) off += a[r][c] * a[r][c]
            if (off < 1e-24) break
            for (pp in 0 until n) for (qq in pp + 1 until n) {
                if (abs(a[pp][qq]) < 1e-300) continue
                val theta = (a[qq][qq] - a[pp][pp]) / (2 * a[pp][qq])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until n) {
                    val akp = a[k][pp]; val akq = a[k][qq]
                    a[k][pp] = c * akp - s * akq; a[k][qq] = s * akp + c * akq
                }
                for (k in 0 until n) {
                    val apk = a[pp][k]; val aqk = a[qq][k]
                    a[pp][k] = c * apk - s * aqk; a[qq][k] = s * apk + c * aqk
                }
                for (k in 0 until n) {
                    val vkp = v[k][pp]; val vkq = v[k][qq]
                    v[k][pp] = c * vkp - s * vkq; v[k][qq] = s * vkp + c * vkq
                }
            }
        }
        return DoubleArray(n) { a[it][it] } to v
    }
}
