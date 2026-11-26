package com.structura.core.walls

import com.structura.core.codes.IS456
import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.SlabDesigner
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
import kotlin.math.sin

/**
 * Cantilever RCC retaining wall per metre run: Rankine active pressure with surcharge, stability per IS 456
 * Cl. 20.1 (overturning: 0.9·Mr ≥ 1.2·M(earth) + 1.4·M(surcharge)) and Cl. 20.2 (sliding: 0.9·μ·W ≥ 1.4·H),
 * base pressure within the middle third and SBC, then limit-state design of stem, heel and toe (Cl. 38, 40, 26.5).
 * With [autoSize] the base width grows (0.05 m steps) until every stability check passes.
 */
object RetainingWall {
    data class Input(
        val height: Double = 4.0,          // m, top of stem to underside of base
        val gammaSoil: Double = 18.0, val phi: Double = 30.0, val surcharge: Double = 10.0,
        val sbc: Double = 150.0, val mu: Double = 0.5,
        val fck: Double = 25.0, val fy: Double = 500.0, val cover: Double = 50.0, val bar: Double = 16.0,
        val stemTop: Double = 0.2, val stemBottom: Double = 0.0, val baseThickness: Double = 0.0,
        val baseWidth: Double = 0.0, val toe: Double = 0.0, val autoSize: Boolean = true,
    )

    class Stability(val b: Double, val t: Double, val d: Double, val tb: Double, val w: Double, val mr: Double, val moE: Double, val moS: Double,
                    val pa: Double, val ps: Double, val e: Double, val qmax: Double, val qmin: Double, val xw: Double) {
        val heel get() = b - t - tb
    }

    class Result(val st: Stability, val checks: List<DesignCheck>, val report: ReportDoc)

    fun ka(phi: Double) = (1 - sin(phi * PI / 180)) / (1 + sin(phi * PI / 180))

    internal fun stability(inp: Input, b: Double, toe: Double, d: Double, tb: Double, extraW: Double = 0.0, extraX: Double = 0.0): Stability {
        val h = inp.height; val ka = ka(inp.phi); val hs = h - d
        val pa = 0.5 * ka * inp.gammaSoil * h * h; val ps = ka * inp.surcharge * h
        val heel = b - toe - tb
        // weights (kN/m) and levers from the toe edge
        val parts = listOf(
            25 * inp.stemTop * hs to toe + inp.stemTop / 2,
            25 * 0.5 * (tb - inp.stemTop) * hs to toe + inp.stemTop + (tb - inp.stemTop) / 3,
            25 * b * d to b / 2,
            inp.gammaSoil * heel * hs to toe + tb + heel / 2,
            extraW to extraX,
        )
        val w = parts.sumOf { it.first }; val mr = parts.sumOf { it.first * it.second }
        val moE = pa * h / 3; val moS = ps * h / 2
        // bearing with the surcharge on the heel
        val wq = inp.surcharge * heel; val xq = toe + tb + heel / 2
        val r = w + wq
        val xbar = (mr + wq * xq - moE - moS) / r
        val e = b / 2 - xbar
        return Stability(b, toe, d, tb, w, mr, moE, moS, pa, ps, e, r / b * (1 + 6 * e / b), r / b * (1 - 6 * e / b), r)
    }

    fun design(inp: Input): Result {
        val h = inp.height
        val d = if (inp.baseThickness > 0) inp.baseThickness else max(0.3, Math.ceil(h / 12 / 0.05) * 0.05)
        val tb = if (inp.stemBottom > 0) inp.stemBottom else max(0.3, Math.ceil(h / 12 / 0.05) * 0.05)
        fun ok(s: Stability) = 0.9 * s.mr >= 1.2 * s.moE + 1.4 * s.moS && 0.9 * inp.mu * s.w >= 1.4 * (s.pa + s.ps) &&
            abs(s.e) <= s.b / 6 && s.qmax <= inp.sbc
        var b = if (inp.baseWidth > 0) inp.baseWidth else Math.ceil(0.4 * h / 0.05) * 0.05
        fun toeOf(b: Double) = if (inp.toe > 0) inp.toe else floor(b / 3 / 0.05) * 0.05
        var st = stability(inp, b, toeOf(b), d, tb)
        if (inp.autoSize) {
            var guard = 0
            while (!ok(st) && guard++ < 120) { b += 0.05; st = stability(inp, b, toeOf(b), d, tb) }
        }
        val ka = ka(inp.phi)
        val checks = ArrayList<DesignCheck>()
        val steps = ArrayList<String>()
        steps += "Ka = (1 − sin φ)/(1 + sin φ) = ${f3(ka)} (Rankine, φ = ${f1(inp.phi)}°, level backfill)"
        steps += "Pa = ½·Ka·γ·H² = ${f2(st.pa)} kN/m at H/3; Ps = Ka·q·H = ${f2(st.ps)} kN/m at H/2"
        steps += "Proportions: B = ${f2(st.b)} m, toe ${f2(st.t)} m, stem ${f0(inp.stemTop * 1000)}–${f0(tb * 1000)} mm, heel ${f2(st.heel)} m, base ${f0(d * 1000)} mm" + if (inp.autoSize) " (base width auto-sized)" else ""
        steps += "Σ W = ${f2(st.w)} kN/m, Mr = ${f2(st.mr)} kN·m/m; Mo = ${f2(st.moE)} (earth) + ${f2(st.moS)} (surcharge) kN·m/m"
        val otL = 0.9 * st.mr; val otR = 1.2 * st.moE + 1.4 * st.moS
        checks += DesignCheck("Overturning", "0.9·Mr = ${f1(otL)}", "≥ 1.2·Mo(earth) + 1.4·Mo(surcharge) = ${f1(otR)} (FOS ${f2(st.mr / (st.moE + st.moS))})", if (otL >= otR) Status.PASS else Status.FAIL, IS456.ref("Cl. 20.1"))
        val slL = 0.9 * inp.mu * st.w; val slR = 1.4 * (st.pa + st.ps)
        checks += DesignCheck("Sliding", "0.9·μ·W = ${f1(slL)}", "≥ 1.4·H = ${f1(slR)} kN/m${if (slL < slR) " — add a shear key / widen base" else ""}", if (slL >= slR) Status.PASS else Status.FAIL, IS456.ref("Cl. 20.2"))
        checks += DesignCheck("Eccentricity", "e = ${f3(st.e)} m", "≤ B/6 = ${f3(st.b / 6)} m (no tension)", if (abs(st.e) <= st.b / 6) Status.PASS else Status.FAIL, "Middle-third rule")
        checks += DesignCheck("Base pressure", "qmax ${f1(st.qmax)}, qmin ${f1(st.qmin)} kN/m²", "≤ SBC ${f0(inp.sbc)}, ≥ 0", if (st.qmax <= inp.sbc && st.qmin >= 0) Status.PASS else Status.FAIL, IS456.ref("Cl. 34.1"))

        // structural design (γf = 1.5), per metre
        val hs = h - d
        fun dEff(thk: Double) = thk * 1000 - inp.cover - inp.bar / 2
        val astMin = { thk: Double -> 0.0012 * 1000 * thk * 1000 }
        val aBar = PI * inp.bar * inp.bar / 4
        fun spacing(ast: Double) = min(min(300.0, 3 * 1000.0), floor(1000 * aBar / ast / 10) * 10)
        fun member(name: String, mu: Double, vu: Double, thk: Double, where: String): Pair<String, Double> {
            val dd = dEff(thk)
            val ast = max(SlabDesigner.astFor(mu, dd, inp.fck, inp.fy), astMin(thk))
            val sp = spacing(ast); val prov = 1000 * aBar / sp
            val tv = vu * 1000 / (1000 * dd); val tc = IS456.tauC(100 * prov / (1000 * dd), inp.fck)
            steps += "$name: Mu = ${f2(mu)} kN·m/m, Vu = ${f2(vu)} kN/m, d = ${f0(dd)} mm → Ast = ${f0(ast)} mm²/m → φ${f0(inp.bar)} @ ${f0(sp)} c/c ($where)"
            checks += DesignCheck("$name flexure", "Ast req ${f0(ast)}", "provided ${f0(prov)} mm²/m", if (prov >= ast) Status.PASS else Status.FAIL, IS456.ref("Cl. 38.1, 26.5.2.1"))
            checks += DesignCheck("$name shear", "τv = ${f3(tv)} MPa", "≤ τc = ${f3(tc)} MPa", if (tv <= tc) Status.PASS else Status.FAIL, IS456.ref("Cl. 40.2, Table 19"))
            return "φ${f0(inp.bar)} @ ${f0(sp)} c/c" to prov
        }
        val stemMu = 1.5 * (ka * inp.gammaSoil * hs * hs * hs / 6 + ka * inp.surcharge * hs * hs / 2)
        val stemVu = 1.5 * (ka * inp.gammaSoil * hs * hs / 2 + ka * inp.surcharge * hs)
        val stem = member("Stem", stemMu, stemVu, tb, "earth face, vertical; curtail half at mid-height")
        // pressures at toe / stem faces (service), linear
        fun q(x: Double) = st.qmax + (st.qmin - st.qmax) * x / st.b
        val heel = st.heel; val xh = st.t + tb
        val down = inp.gammaSoil * hs + inp.surcharge + 25 * d
        val q2 = q(xh)
        val heelM = 1.5 * max(0.0, down * heel * heel / 2 - (st.qmin * heel * heel / 2 + (q2 - st.qmin) * heel * heel / 3))
        val heelV = 1.5 * max(0.0, down * heel - (st.qmin + q2) / 2 * heel)
        val heelBars = member("Heel", heelM, heelV, d, "top face")
        val q1 = q(st.t)
        val toeM = 1.5 * max(0.0, q1 * st.t * st.t / 2 + (st.qmax - q1) * st.t * st.t / 3 - 25 * d * st.t * st.t / 2)
        val toeV = 1.5 * max(0.0, (st.qmax + q1) / 2 * st.t - 25 * d * st.t)
        val toeBars = member("Toe", toeM, toeV, d, "bottom face")
        val dist = astMin(tb); val distB = astMin(d)
        steps += "Distribution / temperature steel 0.12 % bD: stem ${f0(dist)} mm²/m, base ${f0(distB)} mm²/m (IS 456 Cl. 26.5.2.1); front face of stem nominal mesh"

        val rb = ReportDoc.Builder("Cantilever retaining wall — analysis and design", "IS 456:2000 · per metre run · H = ${f2(h)} m")
        rb.h("1. Data").kv(
            "Height (top of stem to underside of base)" to "${f2(h)} m",
            "Backfill" to "γ = ${f1(inp.gammaSoil)} kN/m³, φ = ${f1(inp.phi)}°, surcharge ${f1(inp.surcharge)} kN/m²",
            "Foundation" to "SBC ${f0(inp.sbc)} kN/m², μ = ${f2(inp.mu)}",
            "Materials" to "M${f0(inp.fck)}, Fe${f0(inp.fy)}, cover ${f0(inp.cover)} mm, bars φ${f0(inp.bar)}",
        )
        rb.fig(figure(inp, st, tb, d))
        rb.h("2. Stability and design").steps(steps)
        rb.h("3. Checks").checks(checks)
        rb.h("4. Reinforcement").table(listOf("Element", "Main steel", "Face", "Distribution"), listOf(
            listOf("Stem", stem.first, "earth face (vertical)", "${f0(dist)} mm²/m horizontal"),
            listOf("Heel", heelBars.first, "top", "${f0(distB)} mm²/m"),
            listOf("Toe", toeBars.first, "bottom", "${f0(distB)} mm²/m"),
        ))
        rb.h("5. Notes").p("Provide weep holes (≈ 100 mm at 1.5 m c/c) with a graded filter behind the stem; drained backfill assumed (no water pressure). Check development length of stem bars into the base (IS 456 Cl. 26.2.1).")
        return Result(st, checks, rb.build())
    }

    private fun figure(inp: Input, s: Stability, tb: Double, d: Double): ReportDoc.Block.Figure {
        val h = inp.height; val ln = ArrayList<ReportDoc.FLine>()
        fun box(pts: List<Pair<Double, Double>>) { for (k in pts.indices) { val a = pts[k]; val b = pts[(k + 1) % pts.size]; ln += ReportDoc.FLine(a.first, a.second, b.first, b.second, 3) } }
        box(listOf(0.0 to 0.0, s.b to 0.0, s.b to d, s.t + tb to d, s.t + inp.stemTop to h, s.t to h, s.t to d, 0.0 to d))
        ln += ReportDoc.FLine(s.t + tb, h, s.b + 0.6, h, 1, dashed = true)           // backfill level
        // active pressure triangle behind the heel
        val pk = ka(inp.phi) * inp.gammaSoil * h / 40
        ln += ReportDoc.FLine(s.b + 0.3, h, s.b + 0.3, 0.0); ln += ReportDoc.FLine(s.b + 0.3, 0.0, s.b + 0.3 + pk, 0.0); ln += ReportDoc.FLine(s.b + 0.3, h, s.b + 0.3 + pk, 0.0)
        val tx = listOf(
            ReportDoc.FText(s.b / 2 - 0.3, -0.35, "B = ${f2(s.b)} m"), ReportDoc.FText(s.t / 2 - 0.2, d + 0.15, "toe ${f2(s.t)}"),
            ReportDoc.FText(s.t + tb + 0.1, d + 0.15, "heel ${f2(s.heel)}"), ReportDoc.FText(s.t - 0.9, h / 2, "H = ${f2(h)} m"),
            ReportDoc.FText(s.b + 0.35, h / 2, "Ka·γ·H"),
        )
        return ReportDoc.Block.Figure("Section — stem, toe, heel, base; active pressure diagram", ln, tx, 220f)
    }
}
