package com.structura.core.rcc

import com.structura.core.codes.IS456
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Circular RCC column (IS 456:2000): strain compatibility with the Cl. 38.1 assumptions (parabolic-rectangular block
 * 0.446 fck, εcu = 0.0035, 0.002 at 3D/7 from the most compressed fibre when the axis is outside the section, Cl. 39.1),
 * bars equally spaced on a circle, elastic–plastic steel fy/1.15. Biaxial moments combine into the resultant (axisymmetric).
 * Slenderness (Cl. 25.1.2, 39.7.1), minimum eccentricity (Cl. 25.4), 6 bars minimum and 0.8–4 % (Cl. 26.5.3.1),
 * lateral ties / helix (Cl. 26.5.3.2).
 */
object CircularColumn {
    class Result(val nBars: Int, val dia: Double, val p: Double, val interaction: Double, val muCap: Double, val muDesign: Double,
                 val puMax: Double, val tiePitch: Double, val checks: List<DesignCheck>) {
        val isSafe get() = checks.none { it.status == Status.FAIL }
    }

    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    /** Axial force (kN, compression +) and moment (kN·m) capacity for neutral-axis depth [xu] (mm, may exceed D). */
    fun capacity(dMm: Double, n: Int, dia: Double, cover: Double, fck: Double, fy: Double, xu: Double): Pair<Double, Double> {
        val r = dMm / 2
        val strips = 120
        var p = 0.0; var m = 0.0
        val eTop: Double; val curv: Double                         // strain at the top fibre and per mm
        if (xu <= dMm) { eTop = 0.0035; curv = 0.0035 / xu }
        else { // Cl. 39.1(b): 0.002 at 3D/7 from the most compressed fibre
            eTop = 0.002 * xu / (xu - 3 * dMm / 7); curv = eTop / xu
        }
        fun eps(y: Double) = eTop - curv * y                     // y from the top fibre
        fun fc(e: Double): Double = when {
            e <= 0 -> 0.0
            e >= 0.002 -> 0.446 * fck
            else -> 0.446 * fck * (2 * e / 0.002 - (e / 0.002) * (e / 0.002))
        }
        for (k in 0 until strips) {
            val y = dMm * (k + 0.5) / strips
            val w = 2 * sqrt(max(0.0, r * r - (r - y) * (r - y)))
            val f = fc(eps(y)) * w * dMm / strips
            p += f; m += f * (r - y)
        }
        val rb = r - cover - dia / 2
        val ab = PI * dia * dia / 4
        for (k in 0 until n) {
            val th = 2 * PI * k / n
            val y = r - rb * cos(th)
            val e = eps(y)
            val fs = (e * 2.0e5).coerceIn(-0.87 * fy, 0.87 * fy) - fc(e)   // net of displaced concrete
            p += fs * ab; m += fs * ab * (r - y)
        }
        return p / 1000 to m / 1e6
    }

    /** Moment capacity at axial load [pu] (kN), by bisection on the neutral-axis depth. */
    fun muAt(dMm: Double, n: Int, dia: Double, cover: Double, fck: Double, fy: Double, pu: Double): Double {
        var lo = 1.0; var hi = 20 * dMm
        if (capacity(dMm, n, dia, cover, fck, fy, hi).first < pu) return 0.0
        repeat(80) {
            val mid = (lo + hi) / 2
            if (capacity(dMm, n, dia, cover, fck, fy, mid).first < pu) lo = mid else hi = mid
        }
        return capacity(dMm, n, dia, cover, fck, fy, (lo + hi) / 2).second
    }

    fun design(dMm: Double, n: Int, dia: Double, fck: Double, fy: Double, pu: Double, mux: Double, muy: Double, lUnsupported: Double,
               effFactor: Double = 1.0, cover: Double = 40.0, tieDia: Double = 8.0): Result {
        val checks = ArrayList<DesignCheck>()
        val ag = PI * dMm * dMm / 4
        val asc = n * PI * dia * dia / 4
        val p = 100 * asc / ag
        checks += DesignCheck("Longitudinal steel", "${f2(p)} %", "0.8 % ≤ p ≤ 4 %", when { p < 0.8 -> Status.FAIL; p <= 4.0 -> Status.PASS; p <= 6.0 -> Status.INFO; else -> Status.FAIL }, IS456.ref("Cl. 26.5.3.1(a),(b)"))
        checks += DesignCheck("Number of bars", "$n", "≥ 6 (circular)", if (n >= 6) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.3.1(c)"))
        checks += DesignCheck("Bar diameter", "${dia.toInt()} mm", "≥ 12 mm", if (dia >= 12) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.3.1(d)"))
        val le = effFactor * lUnsupported
        val slender = le / dMm > 12
        checks += DesignCheck("Slenderness", "le/D = ${f1(le / dMm)}", if (slender) "> 12: slender, additional moment" else "≤ 12: short", Status.INFO, IS456.ref("Cl. 25.1.2"))
        checks += DesignCheck("Unsupported length", "${f1(lUnsupported / dMm)} D", "≤ 60 D", if (lUnsupported <= 60 * dMm) Status.PASS else Status.FAIL, IS456.ref("Cl. 25.3.1"))
        val emin = max(lUnsupported / 500 + dMm / 30, 20.0)
        val ma = if (slender) pu * dMm / 2000 * (le / dMm) * (le / dMm) / 1000 else 0.0
        val mRes = sqrt(mux * mux + muy * muy)
        val mu = max(mRes, pu * emin / 1000) + ma
        checks += DesignCheck("Minimum eccentricity", "emin = ${f1(emin)} mm → ${f1(pu * emin / 1000)} kN·m", "design moment ≥ Pu·emin", Status.INFO, IS456.ref("Cl. 25.4"))
        if (slender) checks += DesignCheck("Additional moment", "Ma = ${f1(ma)} kN·m", "Pu·D/2000·(le/D)²", Status.INFO, IS456.ref("Cl. 39.7.1"))
        val puMax = (0.4 * fck * (ag - asc) + 0.67 * fy * asc) / 1000
        checks += DesignCheck("Axial capacity", "Pu ${f1(pu)} kN", "≤ 0.4fck·Ac + 0.67fy·Asc = ${f1(puMax)} kN", if (pu <= puMax) Status.PASS else Status.FAIL, IS456.ref("Cl. 39.3"))
        val cap = if (pu <= puMax) muAt(dMm, n, dia, cover, fck, fy, pu) else 0.0
        val ratio = if (cap > 0) mu / cap else 99.0
        checks += DesignCheck("Moment capacity at Pu", "Mu ${f1(mu)} kN·m (resultant of Mux, Muy)", "≤ Mu,cap ${f1(cap)} kN·m (strain compatibility)", if (ratio <= 1.0) Status.PASS else Status.FAIL, IS456.ref("Cl. 39.5, 38.1"))
        val pitch = min(dMm, min(16 * dia, 300.0))
        checks += DesignCheck("Lateral ties", "φ${tieDia.toInt()} @ ${pitch.toInt()} mm", "φ ≥ max(φ/4, 6); pitch ≤ min(D, 16φ, 300)", if (tieDia >= max(dia / 4, 6.0)) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.3.2(c)"))
        return Result(n, dia, p, ratio, cap, mu, puMax, pitch, checks)
    }
}
