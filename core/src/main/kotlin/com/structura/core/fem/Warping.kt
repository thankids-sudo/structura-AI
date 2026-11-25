package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Non-uniform (Vlasov) torsion of open thin-walled members: EIω·φ'''' − GIt·φ'' = 0 between the ends.
 * Warping (φ') may be restrained at either end (Member.warpI / warpJ — e.g. a fixed base or a welded end plate)
 * or free (bimoment B = 0). The warping DOF is condensed per member, so the frame keeps 6 DOF per node while the
 * member gets the exact torsional stiffness and the bimoment B(x) = −EIω·φ''(x) can be reported.
 * Warping continuity between members is not modelled (each joint is either fully restrained or free).
 * Solution basis φ = A + Bx + C·e^(−λx) + D·e^(−λ(L−x)), λ = √(GIt/EIω) — numerically stable for large λL.
 */
object Warping {
    fun active(m: Member) = (m.warpI || m.warpJ || m.warpContI || m.warpContJ) && m.section.iw > 0 && m.section.j > 0

    /** Member takes part in warping continuity (7th DOF) at an end that is not restrained. */
    fun continuous(m: Member) = active(m) && !m.truss && ((m.warpContI && !m.warpI) || (m.warpContJ && !m.warpJ))

    /**
     * Coefficients for end twists φ1, φ2 and end warping [th1] / [th2] (φ' given), or null = free warping (bimoment 0).
     */
    fun coeffsGeneral(gj: Double, eiw: Double, l: Double, phi1: Double, phi2: Double, th1: Double?, th2: Double?): DoubleArray {
        val lam = sqrt(gj / eiw)
        val e = exp(-lam * l)
        val a = arrayOf(
            doubleArrayOf(1.0, 0.0, 1.0, e),
            doubleArrayOf(1.0, l, e, 1.0),
            if (th1 != null) doubleArrayOf(0.0, 1.0, -lam, lam * e) else doubleArrayOf(0.0, 0.0, lam * lam, lam * lam * e),
            if (th2 != null) doubleArrayOf(0.0, 1.0, -lam * e, lam) else doubleArrayOf(0.0, 0.0, lam * lam * e, lam * lam),
        )
        return solve(a, doubleArrayOf(phi1, phi2, th1 ?: 0.0, th2 ?: 0.0))
    }

    /**
     * Exact 4×4 stiffness for [φ1, θ1, φ2, θ2] (θ = φ', warping), from the strain energy
     * ∫(EIω·φ''² + GIt·φ'²)dx of the exact Vlasov shape functions (Gauss integration on λL-scaled segments).
     */
    fun matrix4(m: Member, l: Double): Array<DoubleArray> {
        val gj = m.material.g * m.section.j; val eiw = m.material.e * m.section.iw
        val lam = sqrt(gj / eiw)
        val units = listOf(doubleArrayOf(1.0, 0.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        val cs = units.map { u -> coeffsGeneral(gj, eiw, l, u[0], u[2], u[1], u[3]) }
        fun d1(c: DoubleArray, x: Double) = c[1] - lam * c[2] * exp(-lam * x) + lam * c[3] * exp(-lam * (l - x))
        fun d2(c: DoubleArray, x: Double) = lam * lam * (c[2] * exp(-lam * x) + c[3] * exp(-lam * (l - x)))
        val k = Array(4) { DoubleArray(4) }
        val seg = maxOf(40, (lam * l * 6).toInt()).coerceAtMost(4000)
        val h = l / seg
        val gx = doubleArrayOf(-0.8611363115940526, -0.3399810435848563, 0.3399810435848563, 0.8611363115940526)
        val gw = doubleArrayOf(0.3478548451374538, 0.6521451548625461, 0.6521451548625461, 0.3478548451374538)
        for (s in 0 until seg) for (q in 0 until 4) {
            val x = h * (s + 0.5 + gx[q] / 2); val w = gw[q] * h / 2
            val p1 = DoubleArray(4) { d1(cs[it], x) }; val p2 = DoubleArray(4) { d2(cs[it], x) }
            for (a in 0 until 4) for (b in 0 until 4) k[a][b] += w * (eiw * p2[a] * p2[b] + gj * p1[a] * p1[b])
        }
        return k
    }

    /** Coefficients (A, B, C, D) for end twists [phi1], [phi2]. */
    fun coeffs(gj: Double, eiw: Double, l: Double, phi1: Double, phi2: Double, restrI: Boolean, restrJ: Boolean): DoubleArray {
        val lam = sqrt(gj / eiw)
        val e = exp(-lam * l)
        // rows: φ(0)=φ1, φ(L)=φ2, end i: φ'(0)=0 or φ''(0)=0, end j: φ'(L)=0 or φ''(L)=0
        val a = arrayOf(
            doubleArrayOf(1.0, 0.0, 1.0, e),
            doubleArrayOf(1.0, l, e, 1.0),
            if (restrI) doubleArrayOf(0.0, 1.0, -lam, lam * e) else doubleArrayOf(0.0, 0.0, lam * lam, lam * lam * e),
            if (restrJ) doubleArrayOf(0.0, 1.0, -lam * e, lam) else doubleArrayOf(0.0, 0.0, lam * lam * e, lam * lam),
        )
        val b = doubleArrayOf(phi1, phi2, 0.0, 0.0)
        return solve(a, b)
    }

    /** Torsional stiffness T/(φ2 − φ1) (kN·m/rad) of the member, replacing GIt/L. */
    fun stiffness(m: Member, l: Double): Double {
        val gj = m.material.g * m.section.j
        if (!active(m)) return gj / l
        val c = coeffs(gj, m.material.e * m.section.iw, l, 0.0, 1.0, m.warpI, m.warpJ)
        return gj * c[1]
    }

    class Station(val s: Double, val twist: Double, val bimoment: Double, val stVenant: Double, val warpingTorque: Double)

    /** Twist, bimoment (kN·m²), St Venant and warping torque (kN·m) along the member for end twists φ1, φ2. */
    fun stations(m: Member, l: Double, phi1: Double, phi2: Double, n: Int = 20): List<Station> {
        val gj = m.material.g * m.section.j; val eiw = m.material.e * m.section.iw
        if (!active(m)) return (0..n).map { k -> Station(l * k / n, phi1 + (phi2 - phi1) * k / n, 0.0, gj * (phi2 - phi1) / l, 0.0) }
        val c = coeffs(gj, eiw, l, phi1, phi2, m.warpI, m.warpJ)
        val lam = sqrt(gj / eiw)
        return (0..n).map { k ->
            val x = l * k / n
            val e1 = exp(-lam * x); val e2 = exp(-lam * (l - x))
            val p = c[0] + c[1] * x + c[2] * e1 + c[3] * e2
            val p1 = c[1] - lam * c[2] * e1 + lam * c[3] * e2
            val p2 = lam * lam * (c[2] * e1 + c[3] * e2)
            val p3 = lam * lam * lam * (-c[2] * e1 + c[3] * e2)
            Station(x, p, -eiw * p2, gj * p1, -eiw * p3)
        }
    }

    /** Stations for given end twists and end warping (null = free). */
    fun stationsGeneral(m: Member, l: Double, phi1: Double, phi2: Double, th1: Double?, th2: Double?, n: Int = 20): List<Station> {
        val gj = m.material.g * m.section.j; val eiw = m.material.e * m.section.iw
        val c = coeffsGeneral(gj, eiw, l, phi1, phi2, th1, th2)
        val lam = sqrt(gj / eiw)
        return (0..n).map { k ->
            val x = l * k / n
            val e1 = exp(-lam * x); val e2 = exp(-lam * (l - x))
            Station(x, c[0] + c[1] * x + c[2] * e1 + c[3] * e2, -eiw * lam * lam * (c[2] * e1 + c[3] * e2),
                gj * (c[1] - lam * c[2] * e1 + lam * c[3] * e2), -eiw * lam * lam * lam * (-c[2] * e1 + c[3] * e2))
        }
    }

    private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }; val b = b0.copyOf()
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            val t = a[c]; a[c] = a[p]; a[p] = t; val tb = b[c]; b[c] = b[p]; b[p] = tb
            for (r in c + 1 until n) { val f = a[r][c] / a[c][c]; for (k in c until n) a[r][k] -= f * a[c][k]; b[r] -= f * b[c] }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) { var s = b[r]; for (k in r + 1 until n) s -= a[r][k] * x[k]; x[r] = s / a[r][r] }
        return x
    }
}
