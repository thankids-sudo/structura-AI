package com.structura.core.walls

import com.structura.core.codes.IS456
import com.structura.core.fem.LateralLoads
import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.Status
import com.structura.core.report.ReportDoc
import com.structura.core.report.f0
import com.structura.core.report.f1
import com.structura.core.report.f2
import com.structura.core.report.f3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Wall types other than retaining walls: load-bearing masonry (IS 1905), RCC wall (IS 456 Cl. 32), free-standing compound wall. */
object Walls {

    // ------------------------------------------------------------------ masonry (IS 1905:1987)
    data class Masonry(
        val height: Double = 3.0, val thickness: Double = 230.0, val load: Double = 60.0, val ecc: Double = 0.0,
        val fb: Double = 0.50,                 // basic compressive stress, MPa (IS 1905 Table 8 for the unit strength and mortar)
        val restraint: Int = 0,                // 0: lateral + rotational at both ends (0.75H); 1: lateral only (1.0H); 2: free top (2.0H)
        val kp: Double = 1.0,                  // shape modification factor (Table 10)
        val selfWeight: Double = 19.2,         // kN/m³ incl. plaster
    )

    private val SR = doubleArrayOf(6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 27.0)
    private val KS = doubleArrayOf(1.00, 0.95, 0.89, 0.84, 0.78, 0.73, 0.67, 0.62, 0.56, 0.51, 0.45, 0.43)

    /** Stress reduction factor for slenderness, axial loading (e/t ≤ 1/24) — IS 1905 Table 9, linear in between. */
    fun ksSlender(sr: Double): Double = if (sr <= 6) 1.0 else if (sr >= 27) 0.43 else IS456.interpolate(SR, KS, sr)

    fun masonry(m: Masonry): ReportDoc {
        val t = m.thickness
        val hef = m.height * when (m.restraint) { 0 -> 0.75; 1 -> 1.0; else -> 2.0 }
        val sr = hef * 1000 / t
        val ks = ksSlender(sr)
        val area = t / 1000 * 1.0
        val ka = if (area < 0.2) 0.7 + 1.5 * area else 1.0
        val fc = m.fb * ks * ka * m.kp
        val w = m.load + m.selfWeight * t / 1000 * m.height / 2         // at mid-height of the storey
        val et = m.ecc / t
        val sigma = w / t                                                // kN/m / mm = MPa
        val sigMax = sigma * (1 + 6 * et)
        val checks = ArrayList<DesignCheck>()
        checks += DesignCheck("Slenderness ratio", "SR = hef/t = ${f1(sr)}", "≤ 27 (cement / cement-lime mortar)", if (sr <= 27) Status.PASS else Status.FAIL, "IS 1905 Cl. 4.7")
        val allow = if (et <= 1.0 / 24) fc else 1.25 * fc
        checks += DesignCheck("Compressive stress", if (et <= 1.0 / 24) "σ = ${f3(sigma)} MPa" else "σmax = ${f3(sigMax)} MPa (e/t ${f3(et)})",
            "≤ ${f3(allow)} MPa${if (et > 1.0 / 24) " (fc + 25 % for eccentric loading)" else ""}", if ((if (et <= 1.0 / 24) sigma else sigMax) <= allow) Status.PASS else Status.FAIL, "IS 1905 Cl. 5.4, Table 9")
        if (et > 1.0 / 6) checks += DesignCheck("Eccentricity", "e/t = ${f3(et)}", "≤ 1/6 for no tension", Status.FAIL, "IS 1905 Cl. 5.5")
        val rb = ReportDoc.Builder("Load-bearing masonry wall", "IS 1905:1987 · per metre length")
        rb.h("1. Data").kv("Storey height" to "${f2(m.height)} m", "Thickness" to "${f0(t)} mm", "Load at top" to "${f1(m.load)} kN/m, e = ${f0(m.ecc)} mm",
            "Basic compressive stress fb" to "${f3(m.fb)} MPa (IS 1905 Table 8 — for the brick strength and mortar used)",
            "End restraint" to listOf("lateral + rotational both ends (hef = 0.75H)", "lateral only (hef = H)", "free top (hef = 2H)")[m.restraint])
        rb.fig(wallFigure(m.height, t / 1000, "Masonry wall, ${f0(t)} mm"))
        rb.h("2. Design").steps(listOf(
            "Effective height hef = ${f2(hef)} m (IS 1905 Table 4); SR = ${f1(sr)} → ks = ${f3(ks)} (Table 9, e/t ≤ 1/24 column)",
            "Area factor ka = ${f3(ka)} (Cl. 5.4.1.3: 0.7 + 1.5A for A < 0.2 m²); shape factor kp = ${f2(m.kp)} (Table 10)",
            "Permissible fc = fb·ks·ka·kp = ${f3(fc)} MPa",
            "Load at mid-height = ${f1(m.load)} + self-weight ${f1(w - m.load)} = ${f1(w)} kN/m → σ = ${f3(sigma)} MPa",
        ))
        rb.h("3. Checks").checks(checks)
        rb.p("For e/t > 1/24 confirm ks from the other columns of IS 1905 Table 9; fb must be taken from Table 8 for the specified unit strength and mortar mix.")
        return rb.build()
    }

    // ------------------------------------------------------------------ RCC wall (IS 456 Cl. 32)
    data class RccWall(val height: Double = 3.5, val thickness: Double = 200.0, val pu: Double = 800.0, val ecc: Double = 0.0,
                       val fck: Double = 25.0, val fy: Double = 500.0, val restrained: Boolean = true, val bar: Double = 10.0)

    fun rccWall(w: RccWall): ReportDoc {
        val t = w.thickness
        val hwe = w.height * 1000 * (if (w.restrained) 0.75 else 1.0)
        val ea = hwe * hwe / (2500 * t)
        val e = max(w.ecc, 0.05 * t)
        val puw = 0.3 * (t - 1.2 * e - 2 * ea) * w.fck                   // N/mm per mm = kN/m
        val vMin = (if (w.fy >= 415) 0.0012 else 0.0015) * 1000 * t
        val hMin = (if (w.fy >= 415) 0.0020 else 0.0025) * 1000 * t
        val aBar = PI * w.bar * w.bar / 4
        fun sp(a: Double) = min(min(3 * t, 450.0), floor(1000 * aBar / a / 10) * 10)
        val checks = listOf(
            DesignCheck("Slenderness", "Hwe/t = ${f1(hwe / t)}", "≤ 30", if (hwe / t <= 30) Status.PASS else Status.FAIL, IS456.ref("Cl. 32.2.3")),
            DesignCheck("Axial capacity (braced wall)", "Pu = ${f1(w.pu)} kN/m", "≤ Puw = 0.3(t − 1.2e − 2ea)fck = ${f1(puw)} kN/m", if (w.pu <= puw) Status.PASS else Status.FAIL, IS456.ref("Cl. 32.2.5")),
            DesignCheck("Minimum vertical steel", "${f0(vMin)} mm²/m", "φ${f0(w.bar)} @ ${f0(sp(vMin))} c/c (both faces if t > 200)", Status.INFO, IS456.ref("Cl. 32.5(a)")),
            DesignCheck("Minimum horizontal steel", "${f0(hMin)} mm²/m", "φ${f0(w.bar)} @ ${f0(sp(hMin))} c/c, spacing ≤ 3t, 450", Status.INFO, IS456.ref("Cl. 32.5(c)")),
        )
        val rb = ReportDoc.Builder("RCC wall — axial load", "IS 456:2000 Cl. 32 · per metre length")
        rb.h("1. Data").kv("Height" to "${f2(w.height)} m (${if (w.restrained) "restrained against rotation at both ends, Hwe = 0.75H" else "not restrained, Hwe = H"})",
            "Thickness" to "${f0(t)} mm", "Design load" to "Pu = ${f1(w.pu)} kN/m, e = ${f0(w.ecc)} mm", "Materials" to "M${f0(w.fck)}, Fe${f0(w.fy)}")
        rb.fig(wallFigure(w.height, t / 1000, "RCC wall, ${f0(t)} mm"))
        rb.h("2. Design").steps(listOf(
            "Hwe = ${f0(hwe)} mm (Cl. 32.2.4); additional eccentricity ea = Hwe²/(2500t) = ${f1(ea)} mm (Cl. 32.2.5)",
            "e = max(e, 0.05t) = ${f1(e)} mm (Cl. 32.2.2) → Puw = ${f1(puw)} kN/m",
        ))
        rb.h("3. Checks").checks(checks)
        return rb.build()
    }

    // ------------------------------------------------------------------ compound / boundary wall
    data class Compound(
        val height: Double = 2.0, val thickness: Double = 230.0, val gamma: Double = 19.2,
        val vb: Double = 44.0, val terrain: Int = 2, val cf: Double = 1.2,
        val footingWidth: Double = 0.9, val footingDepth: Double = 0.9, val footingThk: Double = 0.3,
        val sbc: Double = 100.0, val mu: Double = 0.5, val gammaSoil: Double = 18.0, val ft: Double = 0.07,
    )

    fun compound(c: Compound): ReportDoc {
        val vz = c.vb * LateralLoads.k2(c.height, c.terrain)
        val pd = max(0.81, 0.7) * 0.6 * vz * vz / 1000
        val f = c.cf * pd * c.height                                       // kN/m
        val mBase = f * c.height / 2
        val t = c.thickness / 1000
        val wWall = c.gamma * t * c.height
        val sigC = wWall / (t * 1000); val sigB = mBase / (t * t / 6) / 1000
        val tension = sigB - sigC
        // whole wall on its strip footing, overturning about the footing toe
        val wFoot = 25 * c.footingWidth * c.footingThk
        val wSoil = c.gammaSoil * (c.footingWidth - t) * (c.footingDepth - c.footingThk)
        val wTot = wWall + wFoot + wSoil
        val mo = f * (c.height / 2 + c.footingDepth)
        val mr = wTot * c.footingWidth / 2
        val e = mo / wTot
        val qmax = wTot / c.footingWidth * (1 + 6 * e / c.footingWidth); val qmin = wTot / c.footingWidth * (1 - 6 * e / c.footingWidth)
        val checks = listOf(
            DesignCheck("Masonry tension at base", "σt = ${f3(tension)} MPa", "≤ ${f3(c.ft)} MPa (flexural tension, cement mortar)", if (tension <= c.ft) Status.PASS else Status.FAIL, "IS 1905 Cl. 5.4.3"),
            DesignCheck("Overturning", "0.9·Mr = ${f2(0.9 * mr)}", "≥ 1.4·Mo = ${f2(1.4 * mo)} kN·m/m", if (0.9 * mr >= 1.4 * mo) Status.PASS else Status.FAIL, IS456.ref("Cl. 20.1")),
            DesignCheck("Sliding", "0.9·μ·W = ${f2(0.9 * c.mu * wTot)}", "≥ 1.4·F = ${f2(1.4 * f)} kN/m", if (0.9 * c.mu * wTot >= 1.4 * f) Status.PASS else Status.FAIL, IS456.ref("Cl. 20.2")),
            DesignCheck("Base pressure", "qmax ${f1(qmax)}, qmin ${f1(qmin)} kN/m²", "≤ SBC ${f0(c.sbc)}, ≥ 0", if (qmax <= c.sbc && qmin >= 0) Status.PASS else Status.FAIL, IS456.ref("Cl. 34.1")),
        )
        val rb = ReportDoc.Builder("Compound (boundary) wall — wind stability", "IS 875-3 · IS 1905 · IS 456 Cl. 20 · per metre length")
        rb.h("1. Data").kv("Wall" to "${f2(c.height)} m high, ${f0(c.thickness)} mm, γ ${f1(c.gamma)} kN/m³", "Wind" to "Vb ${f1(c.vb)} m/s, terrain ${c.terrain}, Cf ${f2(c.cf)}",
            "Footing" to "${f2(c.footingWidth)} m wide, ${f2(c.footingThk)} m thick, founded ${f2(c.footingDepth)} m deep", "Soil" to "SBC ${f0(c.sbc)} kN/m², μ ${f2(c.mu)}")
        rb.fig(wallFigure(c.height, t, "Compound wall on strip footing", c.footingWidth, c.footingThk, c.footingDepth))
        rb.h("2. Design").steps(listOf(
            "Vz = ${f2(vz)} m/s, pd = ${f3(pd)} kN/m² (IS 875-3 Cl. 6.3, 7.2); F = Cf·pd·h = ${f2(f)} kN/m at h/2 (Table 24 free-standing walls — confirm Cf for the length/height ratio)",
            "Base of wall: M = ${f2(mBase)} kN·m/m; σ = W/A ± M/Z = ${f3(sigC)} ± ${f3(sigB)} MPa",
            "Whole wall: W = ${f1(wTot)} kN/m, Mo = ${f2(mo)} kN·m/m about the footing base, e = ${f3(e)} m",
        ))
        rb.h("3. Checks").checks(checks)
        rb.p("Piers at 3 m c/c or a thicker wall reduce base tension; provide expansion joints at 15–20 m.")
        return rb.build()
    }

    private fun wallFigure(h: Double, t: Double, caption: String, fw: Double = 0.0, ft: Double = 0.0, fd: Double = 0.0): ReportDoc.Block.Figure {
        val ln = ArrayList<ReportDoc.FLine>()
        val x0 = 0.0
        ln += ReportDoc.FLine(x0, 0.0, x0, h, 3); ln += ReportDoc.FLine(x0 + t, 0.0, x0 + t, h, 3); ln += ReportDoc.FLine(x0, h, x0 + t, h, 3)
        if (fw > 0) {
            val l = x0 + t / 2 - fw / 2
            ln += ReportDoc.FLine(x0, 0.0, x0, -(fd - ft), 3); ln += ReportDoc.FLine(x0 + t, 0.0, x0 + t, -(fd - ft), 3)
            ln += ReportDoc.FLine(l, -(fd - ft), l + fw, -(fd - ft), 3); ln += ReportDoc.FLine(l, -fd, l + fw, -fd, 3)
            ln += ReportDoc.FLine(l, -fd, l, -(fd - ft), 3); ln += ReportDoc.FLine(l + fw, -fd, l + fw, -(fd - ft), 3)
            ln += ReportDoc.FLine(l - 0.4, 0.0, l + fw + 0.4, 0.0, 1, dashed = true)
            for (k in 0..4) ln += ReportDoc.FLine(-0.9, h * (0.2 + 0.15 * k), -0.2, h * (0.2 + 0.15 * k))
        } else {
            ln += ReportDoc.FLine(x0 - 0.6, 0.0, x0 + t + 0.6, 0.0, 2); ln += ReportDoc.FLine(x0 - 0.6, h, x0 + t + 0.6, h, 2)
        }
        return ReportDoc.Block.Figure(caption, ln, listOf(ReportDoc.FText(x0 + t + 0.15, h / 2, "h = ${f2(h)} m"), ReportDoc.FText(x0 - 0.1, h + 0.12, "t = ${f0(t * 1000)} mm")), 190f)
    }

    internal fun absd(v: Double) = abs(v)
}
