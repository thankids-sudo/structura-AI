package com.structura.core.rcc

import com.structura.core.codes.IS456
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Isolated pad footing under an axially loaded column. Loads kN, lengths mm, SBC kN/m². */
data class FootingInput(
    val serviceLoad: Double,          // P (kN, unfactored)
    val factoredLoad: Double = 1.5 * serviceLoad,
    val colB: Double = 300.0,         // column width (mm)
    val colD: Double = 450.0,         // column depth (mm)
    val sbc: Double = 200.0,          // safe bearing capacity (kN/m²)
    val fck: Double = 25.0,
    val fy: Double = 500.0,
    val barDia: Double = 12.0,
    val clearCover: Double = 50.0,
    val selfWeightAllowance: Double = 0.10,
    /** Service moments (kN·m): [momentL] varies the pressure along L (bending about the B-axis), [momentB] along B. */
    val momentL: Double = 0.0,
    val momentB: Double = 0.0,
    val factoredMomentL: Double = 1.5 * momentL,
    val factoredMomentB: Double = 1.5 * momentB,
)

data class FootingResult(
    val lengthL: Double, val widthB: Double, val depth: Double, val d: Double,
    val qu: Double,                    // factored net upward pressure kN/m²
    val astL: Double, val astB: Double,    // total steel along L (bars parallel to L) / along B, mm²
    val barsL: Int, val barsB: Int, val spacingL: Double, val spacingB: Double,
    val centralBandBars: Int,
    val qMaxService: Double = 0.0, val qMinService: Double = 0.0,   // kN/m² (gross, with self-weight allowance)
    val checks: List<DesignCheck>, val steps: List<String>,
) {
    val isSafe: Boolean get() = checks.none { it.status == Status.FAIL }
}

/**
 * IS 456:2000 Cl. 34 — plan area from SBC, bending at the column face (Cl. 34.2.3.1), one-way shear at d from the
 * face and punching shear at d/2 (Cl. 34.2.4, 31.6.3), central band for rectangular footings (Cl. 34.3.1(c)),
 * minimum steel (Cl. 34.5.1 / 26.5.2.1), development length (Cl. 26.2.1).
 */
object FootingDesigner {
    private fun f1(v: Double) = "%.1f".format(v)
    private fun f2(v: Double) = "%.2f".format(v)

    /** Design bond stress for deformed bars in tension, Cl. 26.2.1.1 (+60 %). */
    fun tauBd(fck: Double): Double = 1.6 * when {
        fck >= 40 -> 1.9; fck >= 35 -> 1.7; fck >= 30 -> 1.5; fck >= 25 -> 1.4; fck >= 20 -> 1.2; else -> 1.0
    }

    fun design(inp: FootingInput): FootingResult {
        val checks = ArrayList<DesignCheck>()
        val steps = ArrayList<String>()
        val area = (1 + inp.selfWeightAllowance) * inp.serviceLoad / inp.sbc     // m²
        val diff = (inp.colD - inp.colB) / 1000
        var bw = (-diff + sqrt(diff * diff + 4 * area)) / 2
        bw = ceil(bw * 20) / 20                                // round up to 50 mm
        val lw = ceil((bw + diff) * 20) / 20
        steps += "Area = ${f2(1 + inp.selfWeightAllowance)} × ${f1(inp.serviceLoad)} / ${f1(inp.sbc)} = ${f2(area)} m² → L × B = ${f2(lw)} × ${f2(bw)} m (equal projections)"
        val ps = inp.serviceLoad * (1 + inp.selfWeightAllowance)
        fun qPair(l: Double, b: Double): Pair<Double, Double> {
            val q0 = ps / (l * b); val dq = 6 * kotlin.math.abs(inp.momentL) / (b * l * l) + 6 * kotlin.math.abs(inp.momentB) / (l * b * b)
            return (q0 + dq) to (q0 - dq)
        }
        val hasMoment = inp.momentL != 0.0 || inp.momentB != 0.0
        var lw0 = lw; var bw0 = bw; var guard = 0
        if (hasMoment) {
            steps += "Column moments (service): ML = ${f2(inp.momentL)} kN·m (pressure varies along L), MB = ${f2(inp.momentB)} kN·m (along B); " +
                "eL = ${f1(1000 * inp.momentL / ps)} mm, eB = ${f1(1000 * inp.momentB / ps)} mm"
            // enlarge until qmax ≤ SBC and the base is fully in compression (resultant within the kern)
            while (guard++ < 400) {
                val (qmx, qmn) = qPair(lw0, bw0)
                if (qmx <= inp.sbc + 1e-6 && qmn >= -1e-9) break
                if (kotlin.math.abs(inp.momentL) / lw0 >= kotlin.math.abs(inp.momentB) / bw0) lw0 += 0.05 else bw0 += 0.05
            }
            lw0 = ceil(lw0 * 20 - 1e-6) / 20; bw0 = ceil(bw0 * 20 - 1e-6) / 20
            steps += "Enlarged for eccentricity: L × B = ${f2(lw0)} × ${f2(bw0)} m (qmax ≤ SBC, qmin ≥ 0)"
        }
        val lwF = lw0; val bwF = bw0
        val qu = inp.factoredLoad / (lwF * bwF)
        val (qmaxS, qminS) = qPair(lwF, bwF)
        checks += DesignCheck("Bearing pressure (service)", "qmax ${f1(qmaxS)} kN/m²", "≤ SBC ${f1(inp.sbc)} kN/m²", if (qmaxS <= inp.sbc + 1e-6) Status.PASS else Status.FAIL, IS456.ref("Cl. 34.1"))
        if (hasMoment) checks += DesignCheck("No tension under base", "qmin ${f1(qminS)} kN/m²", "≥ 0 (e within kern: eL/L + eB/B ≤ 1/6)",
            if (qminS >= -1e-6) Status.PASS else Status.FAIL, IS456.ref("Cl. 34.1, 34.4"))
        // factored pressure gradients (kN/m² per m) from the factored moments
        val kL = 12 * inp.factoredMomentL / (bwF * lwF * lwF * lwF)
        val kB = 12 * inp.factoredMomentB / (lwF * bwF * bwF * bwF)
        steps += "Net factored upward pressure qu = ${f1(inp.factoredLoad)} / ${f2(lwF * bwF)} = ${f2(qu)} kN/m²" +
            if (hasMoment) "; edge values ${f2(qu + kL.let { kotlin.math.abs(it) } * lwF / 2 + kotlin.math.abs(kB) * bwF / 2)} / ${f2(qu - kotlin.math.abs(kL) * lwF / 2 - kotlin.math.abs(kB) * bwF / 2)} kN/m² (Mu = ${f2(inp.factoredMomentL)}, ${f2(inp.factoredMomentB)} kN·m)" else ""

        val L = lwF * 1000; val B = bwF * 1000
        val cl = (L - inp.colD) / 2; val cb = (B - inp.colB) / 2      // cantilevers (mm)
        // cantilever on the more heavily loaded side: ∫ (qu + |k|·s)(s − c) ds × width, s from the centre (m)
        fun cantMoment(width: Double, half: Double, c: Double, k: Double): Double {
            val a = half - c; val kk = kotlin.math.abs(k)
            return width * (qu * a * a / 2 + kk * (a * a * a / 3 + c * a * a / 2))
        }
        fun cantShear(width: Double, half: Double, from: Double, k: Double): Double {
            if (from >= half) return 0.0
            val kk = kotlin.math.abs(k)
            return width * (qu * (half - from) + kk * (half * half - from * from) / 2)
        }
        val muL = cantMoment(bwF, lwF / 2, inp.colD / 2000, kL)          // kN·m, bars parallel to L
        val muB = cantMoment(lwF, bwF / 2, inp.colB / 2000, kB)
        steps += "Moments at the column face: Mu (bars ∥ L) = ${f2(muL)} kN·m over B; Mu (bars ∥ B) = ${f2(muB)} kN·m over L"

        val beta = min(inp.colB, inp.colD) / max(inp.colB, inp.colD)
        val tauPunchMax = min(0.5 + beta, 1.0) * 0.25 * sqrt(inp.fck)
        var d = 150.0
        var ok = false
        var astL = 0.0; var astB = 0.0
        while (d <= 2000 && !ok) {
            val xuMax = IS456.xuMaxByD(inp.fy) * d
            val mlimL = 0.36 * inp.fck * B * xuMax * (d - 0.42 * xuMax) / 1e6
            val mlimB = 0.36 * inp.fck * L * xuMax * (d - 0.42 * xuMax) / 1e6
            if (muL > mlimL || muB > mlimB) { d += 25; continue }
            fun ast(mu: Double, width: Double): Double {
                val r = 4.6 * mu * 1e6 / (inp.fck * width * d * d)
                return 0.5 * inp.fck / inp.fy * (1 - sqrt(1 - r)) * width * d
            }
            val dTot = d + inp.clearCover + inp.barDia
            astL = max(ast(muL, B), 0.0012 * B * dTot)
            astB = max(ast(muB, L), 0.0012 * L * dTot)
            // one-way shear at d from the face (both directions)
            val vL = cantShear(bwF, lwF / 2, (inp.colD / 2 + d) / 1000, kL)
            val vB = cantShear(lwF, bwF / 2, (inp.colB / 2 + d) / 1000, kB)
            val tvL = vL * 1e3 / (B * d); val tvB = vB * 1e3 / (L * d)
            val tcL = IS456.tauC(100 * astL / (B * d), inp.fck); val tcB = IS456.tauC(100 * astB / (L * d), inp.fck)
            // punching at d/2
            val b0 = 2 * ((inp.colB + d) + (inp.colD + d))
            val vp = qu * (lwF * bwF - (inp.colB + d) * (inp.colD + d) / 1e6)
            val tp = vp * 1e3 / (b0 * d)
            if (tvL <= tcL && tvB <= tcB && tp <= tauPunchMax) {
                ok = true
                checks += DesignCheck("One-way shear (bars ∥ L)", "τv ${"%.3f".format(tvL)} MPa", "≤ τc ${"%.3f".format(tcL)} MPa", Status.PASS, IS456.ref("Cl. 34.2.4.1(a), Table 19"))
                checks += DesignCheck("One-way shear (bars ∥ B)", "τv ${"%.3f".format(tvB)} MPa", "≤ τc ${"%.3f".format(tcB)} MPa", Status.PASS, IS456.ref("Cl. 34.2.4.1(a), Table 19"))
                checks += DesignCheck("Punching shear at d/2", "τv ${"%.3f".format(tp)} MPa", "≤ ks·0.25√fck = ${"%.3f".format(tauPunchMax)} MPa", Status.PASS, IS456.ref("Cl. 31.6.3.1, 34.2.4.1(b)"))
                checks += DesignCheck("Flexure depth", "d = ${f1(d)} mm", "Mu ≤ Mu,lim in both directions", Status.PASS, IS456.ref("Annex G-1.1"))
            } else d += 25
        }
        if (!ok) checks += DesignCheck("Depth", "> 2000 mm", "no depth up to 2 m satisfies shear and flexure", Status.FAIL, IS456.ref("Cl. 34"))
        val depth = ceil((d + inp.clearCover + inp.barDia) / 25) * 25
        steps += "Effective depth d = ${f1(d)} mm (iterated in 25 mm steps for flexure, one-way and punching shear) → overall D = ${f1(depth)} mm"
        checks += DesignCheck("Minimum edge depth", "${f1(depth)} mm", "≥ 150 mm", if (depth >= 150) Status.PASS else Status.FAIL, IS456.ref("Cl. 34.1.2"))

        val a = PI * inp.barDia * inp.barDia / 4
        val sLim = min(3 * d, 300.0)
        // bars for the area, and at least enough for the spacing limit (Cl. 26.3.3(b))
        val nL = maxOf(ceil(astL / a).toInt(), ceil((B - 2 * inp.clearCover - inp.barDia) / sLim).toInt() + 1, 2)
        var nB = maxOf(ceil(astB / a).toInt(), ceil((L - 2 * inp.clearCover - inp.barDia) / sLim).toInt() + 1, 2)
        // central band (bars ∥ B, i.e. in the short direction) for rectangular footings
        val ratio = L / B
        val band = if (ratio > 1.0) ceil(nB * 2 / (ratio + 1)).toInt() else nB
        val sL = floor((B - 2 * inp.clearCover - inp.barDia) / (nL - 1) / 5) * 5
        val sB = floor((L - 2 * inp.clearCover - inp.barDia) / (nB - 1) / 5) * 5
        val sMax = min(3 * d, 300.0)
        checks += DesignCheck("Bar spacing", "${sL.toInt()} / ${sB.toInt()} mm", "≤ min(3d, 300) = ${f1(sMax)} mm", if (sL <= sMax && sB <= sMax) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.3.3(b)"))
        if (ratio > 1.0) steps += "Central band of width B: ${band} of ${nB} short-direction bars (2/(β+1), β = L/B = ${f2(ratio)}) — Cl. 34.3.1(c)"
        // development length
        val ld = inp.barDia * 0.87 * inp.fy / (4 * tauBd(inp.fck))
        val avail = min(cl, cb) - inp.clearCover
        // a 90° bend at the bar end adds 8φ (Cl. 26.2.2.1(b): 4φ per 45°) plus the straight vertical leg
        val withBend = avail + 8 * inp.barDia + (depth - 2 * inp.clearCover - 2 * inp.barDia).coerceAtLeast(0.0)
        checks += if (avail >= ld) DesignCheck("Development length", "available ${f1(avail)} mm (straight)", "≥ Ld = φ·σs/(4·τbd) = ${f1(ld)} mm", Status.PASS, IS456.ref("Cl. 26.2.1, 34.2.4.3"))
        else DesignCheck("Development length", "straight ${f1(avail)} + 90° bend 8φ + leg = ${f1(withBend)} mm", "≥ Ld ${f1(ld)} mm — bend the bars up at the ends",
            if (withBend >= ld) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.2.1, 26.2.2.1(b), 34.2.4.3"))
        steps += "Bars ∥ L: ${nL}-${inp.barDia.toInt()} φ (${f1(astL)} mm² req.); bars ∥ B: ${nB}-${inp.barDia.toInt()} φ (${f1(astB)} mm² req.)"
        if (hasMoment) checks += DesignCheck("Punching with moment", "average qu used", "moment transfer by shear not added — check Cl. 31.6.2.2 if Mu is large", Status.INFO, IS456.ref("Cl. 31.6.2.2"))
        return FootingResult(L, B, depth, d, qu, astL, astB, nL, nB, sL, sB, band, qmaxS, qminS, checks, steps)
    }
}
