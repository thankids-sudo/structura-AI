package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Results of one Q4 panel at its centroid (local panel axes). */
class PanelResult(
    val id: Int,
    val area: Double,                 // m²
    val meanNormalDeflection: Double, // m, along the panel normal
    val nx: Double, val ny: Double, val nxy: Double,   // membrane forces kN/m
    val mx: Double, val my: Double, val mxy: Double,   // moments kN·m/m
    val maxFaceStress: Double,        // MPa, largest |σ| of σx, σy, τxy on top / bottom faces
)

/**
 * Four-node flat shell element (Q4): bilinear membrane (2×2 Gauss) + Reissner–Mindlin plate with
 * selective reduced integration — bending 2×2, transverse shear 1-point plus a small full-shear
 * stabilisation scaled by t²/h² against hourglass modes — and a small drilling stiffness.
 * Checked against Timoshenko's square-plate deflections (simply supported 0.00406 qa⁴/D,
 * clamped 0.00126 qa⁴/D) within ≈2 % on an 8 × 8 mesh.
 * Flat geometry assumed (a warped panel is projected onto its mean plane).
 */
object Shell {
    private val GP = doubleArrayOf(-1 / sqrt(3.0), 1 / sqrt(3.0))
    private val XI = doubleArrayOf(-1.0, 1.0, 1.0, -1.0)
    private val ETA = doubleArrayOf(-1.0, -1.0, 1.0, 1.0)
    const val SHEAR_STAB = 0.1
    const val DRILL = 1e-3

    class Frame(val x: Vec3, val y: Vec3, val z: Vec3, val xy: Array<DoubleArray>, val origin: Vec3)

    fun frame(model: FrameModel, p: Panel): Frame {
        val pts = p.nodes.map { model.node(it).pos }
        val z = ((pts[2] - pts[0]) cross (pts[3] - pts[1])).unit()
        val e1 = pts[1] - pts[0]
        val x = (e1 - z * (e1 dot z)).unit()
        val y = z cross x
        val xy = Array(4) { k -> val d = pts[k] - pts[0]; doubleArrayOf(d dot x, d dot y) }
        return Frame(x, y, z, xy, pts[0])
    }

    private class Gauss(val n: DoubleArray, val dx: DoubleArray, val dy: DoubleArray, val detJ: Double)

    private fun shape(xy: Array<DoubleArray>, xi: Double, eta: Double): Gauss {
        val n = DoubleArray(4) { 0.25 * (1 + XI[it] * xi) * (1 + ETA[it] * eta) }
        val dxi = DoubleArray(4) { 0.25 * XI[it] * (1 + ETA[it] * eta) }
        val deta = DoubleArray(4) { 0.25 * ETA[it] * (1 + XI[it] * xi) }
        var j11 = 0.0; var j12 = 0.0; var j21 = 0.0; var j22 = 0.0
        for (k in 0 until 4) {
            j11 += dxi[k] * xy[k][0]; j12 += dxi[k] * xy[k][1]
            j21 += deta[k] * xy[k][0]; j22 += deta[k] * xy[k][1]
        }
        val det = j11 * j22 - j12 * j21
        require(det > 1e-12) { "Panel is degenerate or its nodes are not in order around the panel" }
        val dx = DoubleArray(4) { (j22 * dxi[it] - j12 * deta[it]) / det }
        val dy = DoubleArray(4) { (-j21 * dxi[it] + j11 * deta[it]) / det }
        return Gauss(n, dx, dy, det)
    }

    private fun addBtDB(k: Array<DoubleArray>, b: Array<DoubleArray>, d: Array<DoubleArray>, w: Double) {
        val rows = b.size
        val db = Array(rows) { r -> DoubleArray(24) { c -> var s = 0.0; for (t in 0 until rows) s += d[r][t] * b[t][c]; s } }
        for (i in 0 until 24) for (j in 0 until 24) {
            var s = 0.0
            for (r in 0 until rows) s += b[r][i] * db[r][j]
            k[i][j] += s * w
        }
    }

    private fun bMembrane(g: Gauss) = Array(3) { DoubleArray(24) }.also { b ->
        for (k in 0 until 4) {
            b[0][6 * k] = g.dx[k]; b[1][6 * k + 1] = g.dy[k]
            b[2][6 * k] = g.dy[k]; b[2][6 * k + 1] = g.dx[k]
        }
    }

    private fun bBending(g: Gauss) = Array(3) { DoubleArray(24) }.also { b ->
        for (k in 0 until 4) {
            b[0][6 * k + 4] = g.dx[k]
            b[1][6 * k + 3] = -g.dy[k]
            b[2][6 * k + 4] = g.dy[k]; b[2][6 * k + 3] = -g.dx[k]
        }
    }

    private fun bShear(g: Gauss) = Array(2) { DoubleArray(24) }.also { b ->
        for (k in 0 until 4) {
            b[0][6 * k + 2] = g.dx[k]; b[0][6 * k + 4] = g.n[k]
            b[1][6 * k + 2] = g.dy[k]; b[1][6 * k + 3] = -g.n[k]
        }
    }

    private fun dMem(p: Panel): Array<DoubleArray> {
        val c = p.e * p.t / (1 - p.nu * p.nu)
        return arrayOf(doubleArrayOf(c, c * p.nu, 0.0), doubleArrayOf(c * p.nu, c, 0.0), doubleArrayOf(0.0, 0.0, c * (1 - p.nu) / 2))
    }

    private fun dBend(p: Panel): Array<DoubleArray> {
        val c = p.e * p.t * p.t * p.t / (12 * (1 - p.nu * p.nu))
        return arrayOf(doubleArrayOf(c, c * p.nu, 0.0), doubleArrayOf(c * p.nu, c, 0.0), doubleArrayOf(0.0, 0.0, c * (1 - p.nu) / 2))
    }

    /** Local 24×24 stiffness (DOF per node u, v, w, θx, θy, θz in panel axes). */
    fun localStiffness(f: Frame, p: Panel): Array<DoubleArray> {
        val k = Array(24) { DoubleArray(24) }
        val dm = dMem(p); val db = dBend(p)
        val gs = 5.0 / 6 * p.g * p.t
        val h = max((Vec3(f.xy[2][0] - f.xy[0][0], f.xy[2][1] - f.xy[0][1], 0.0)).length,
            (Vec3(f.xy[3][0] - f.xy[1][0], f.xy[3][1] - f.xy[1][1], 0.0)).length)
        val stab = SHEAR_STAB * min(1.0, p.t * p.t / (h * h))
        val dsFull = arrayOf(doubleArrayOf(gs * stab, 0.0), doubleArrayOf(0.0, gs * stab))
        var area = 0.0
        for (xi in GP) for (eta in GP) {
            val g = shape(f.xy, xi, eta)
            addBtDB(k, bMembrane(g), dm, g.detJ)
            addBtDB(k, bBending(g), db, g.detJ)
            addBtDB(k, bShear(g), dsFull, g.detJ)
            area += g.detJ
        }
        val g0 = shape(f.xy, 0.0, 0.0)
        val dsRed = arrayOf(doubleArrayOf(gs * (1 - stab), 0.0), doubleArrayOf(0.0, gs * (1 - stab)))
        addBtDB(k, bShear(g0), dsRed, 4 * g0.detJ)
        val kd = DRILL * p.e * p.t * area
        for (n in 0 until 4) k[6 * n + 5][6 * n + 5] += kd
        return k
    }

    fun area(f: Frame): Double { var a = 0.0; for (xi in GP) for (eta in GP) a += shape(f.xy, xi, eta).detJ; return a }

    private fun rotation(f: Frame) = arrayOf(
        doubleArrayOf(f.x.x, f.x.y, f.x.z), doubleArrayOf(f.y.x, f.y.y, f.y.z), doubleArrayOf(f.z.x, f.z.y, f.z.z),
    )

    /** local = T · global for a 24-vector. */
    fun toLocal(f: Frame, g: DoubleArray): DoubleArray {
        val r = rotation(f)
        return DoubleArray(24) { i -> val blk = i / 3; val a = i % 3; var s = 0.0; for (b in 0 until 3) s += r[a][b] * g[blk * 3 + b]; s }
    }

    fun toGlobal(f: Frame, l: DoubleArray): DoubleArray {
        val r = rotation(f)
        return DoubleArray(24) { i -> val blk = i / 3; val a = i % 3; var s = 0.0; for (b in 0 until 3) s += r[b][a] * l[blk * 3 + b]; s }
    }

    /** Global 24×24 stiffness. */
    fun globalStiffness(model: FrameModel, p: Panel): Array<DoubleArray> {
        val f = frame(model, p)
        val kl = localStiffness(f, p)
        val r = rotation(f)
        // Kg = Tᵀ Kl T with block-diagonal T (8 blocks of 3)
        val kt = Array(24) { i -> DoubleArray(24) { j -> val bj = j / 3; val bjj = j % 3; var s = 0.0; for (t in 0 until 3) s += kl[i][bj * 3 + t] * r[t][bjj]; s } }
        return Array(24) { i -> DoubleArray(24) { j -> val bi = i / 3; val bii = i % 3; var s = 0.0; for (t in 0 until 3) s += r[t][bii] * kt[bi * 3 + t][j]; s } }
    }

    /** Global equivalent nodal loads: normal pressure + self-weight (γ·t, global −Z, × factor). */
    fun loadVector(model: FrameModel, p: Panel): DoubleArray {
        val f = frame(model, p)
        val out = DoubleArray(24)
        val sw = if (model.selfWeight) p.gamma * p.t * model.selfWeightFactor else 0.0
        for (xi in GP) for (eta in GP) {
            val g = shape(f.xy, xi, eta)
            for (k in 0 until 4) {
                val w = g.n[k] * g.detJ
                val pr = f.z * (p.pressure * w)
                out[6 * k] += pr.x; out[6 * k + 1] += pr.y; out[6 * k + 2] += pr.z - sw * w
            }
        }
        return out
    }

    /** Total load resultant of the panel (global), for the equilibrium check. */
    fun resultant(model: FrameModel, p: Panel): Vec3 {
        val v = loadVector(model, p)
        var r = Vec3.ZERO
        for (k in 0 until 4) r += Vec3(v[6 * k], v[6 * k + 1], v[6 * k + 2])
        return r
    }

    fun result(model: FrameModel, p: Panel, uGlobal: DoubleArray): PanelResult {
        val f = frame(model, p)
        val ul = toLocal(f, uGlobal)
        val g = shape(f.xy, 0.0, 0.0)
        fun mul(d: Array<DoubleArray>, b: Array<DoubleArray>): DoubleArray {
            val strain = DoubleArray(b.size) { r -> var s = 0.0; for (c in 0 until 24) s += b[r][c] * ul[c]; s }
            return DoubleArray(d.size) { r -> var s = 0.0; for (c in strain.indices) s += d[r][c] * strain[c]; s }
        }
        val n = mul(dMem(p), bMembrane(g))
        val m = mul(dBend(p), bBending(g))
        var maxS = 0.0
        for (sign in listOf(-1.0, 1.0)) for (c in 0 until 3) {
            val s = n[c] / p.t + sign * 6 * m[c] / (p.t * p.t)
            maxS = max(maxS, abs(s))
        }
        val meanW = (0 until 4).sumOf { ul[6 * it + 2] } / 4
        return PanelResult(p.id, area(f), meanW, n[0], n[1], n[2], m[0], m[1], m[2], maxS / 1000.0)
    }
}
