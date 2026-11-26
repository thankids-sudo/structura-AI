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
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Counterfort retaining wall (economical above ≈ 6 m): stability per metre run as for the cantilever wall
 * (IS 456 Cl. 20.1, 20.2) including the counterfort weight; stem and heel slabs continuous between counterforts
 * (moments wl²/12 at the counterforts, wl²/16 at mid-span), toe as a cantilever, counterfort as a tapering T-beam
 * cantilever from the base (lever arm measured normal to the inclined back face), horizontal ties (stem → counterfort)
 * and vertical ties (heel → counterfort).
 */
object CounterfortWall {
    data class Input(
        val height: Double = 8.0, val spacing: Double = 3.0, val counterfort: Double = 0.4,
        val stem: Double = 0.0, val base: Double = 0.0, val baseWidth: Double = 0.0, val toe: Double = 0.0,
        val gammaSoil: Double = 18.0, val phi: Double = 30.0, val surcharge: Double = 10.0, val sbc: Double = 200.0, val mu: Double = 0.5,
        val fck: Double = 25.0, val fy: Double = 500.0, val cover: Double = 50.0, val bar: Double = 16.0,
    )

    class Result(val b: Double, val checks: List<DesignCheck>, val report: ReportDoc)

    fun design(input: Input): Result {
        val h = input.height
        val d = if (input.base > 0) input.base else max(0.4, ceil(h / 16 / 0.05) * 0.05)
        // stem thickness: given, or the least (25 mm steps, ≥ 200) for which the slab shear at the counterfort face passes (Cl. 40.2)
        val inp = if (input.stem > 0) input else {
            val ka0 = RetainingWall.ka(input.phi)
            val p0 = 1.5 * ka0 * (input.gammaSoil * (h - d) + input.surcharge)
            val v0 = p0 * (input.spacing - input.counterfort) / 2
            var t = 0.2
            while (t < 0.6) {
                val dd = t * 1000 - input.cover - input.bar / 2
                val ast = max(SlabDesigner.astFor(p0 * input.spacing * input.spacing / 12, dd, input.fck, input.fy), 0.0012 * 1e6 * t)
                if (v0 * 1000 / (1000 * dd) <= IS456.tauC(100 * ast / (1000 * dd), input.fck)) break
                t += 0.025
            }
            input.copy(stem = t)
        }
        val ri = RetainingWall.Input(height = h, gammaSoil = inp.gammaSoil, phi = inp.phi, surcharge = inp.surcharge, sbc = inp.sbc, mu = inp.mu,
            fck = inp.fck, fy = inp.fy, cover = inp.cover, bar = inp.bar, stemTop = inp.stem)
        val hs = h - d
        fun cfW(b: Double, toe: Double): Pair<Double, Double> {
            val heel = b - toe - inp.stem
            return 25 * inp.counterfort * 0.5 * heel * hs / inp.spacing to toe + inp.stem + heel / 3
        }
        fun toeOf(b: Double) = if (inp.toe > 0) inp.toe else floor(b / 4 / 0.05) * 0.05
        fun st(b: Double): RetainingWall.Stability { val (w, x) = cfW(b, toeOf(b)); return RetainingWall.stability(ri, b, toeOf(b), d, inp.stem, w, x) }
        fun ok(s: RetainingWall.Stability) = 0.9 * s.mr >= 1.2 * s.moE + 1.4 * s.moS && 0.9 * inp.mu * s.w >= 1.4 * (s.pa + s.ps) && abs(s.e) <= s.b / 6 && s.qmax <= inp.sbc
        var b = if (inp.baseWidth > 0) inp.baseWidth else ceil(0.5 * h / 0.05) * 0.05
        var s = st(b)
        if (inp.baseWidth <= 0) { var g = 0; while (!ok(s) && g++ < 160) { b += 0.05; s = st(b) } }

        val ka = RetainingWall.ka(inp.phi)
        val l = inp.spacing
        val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
        steps += "Ka = ${f3(ka)}; Pa = ${f2(s.pa)}, Ps = ${f2(s.ps)} kN/m; counterforts ${f0(inp.counterfort * 1000)} mm thick at ${f2(l)} m c/c (weight ${f2(cfW(b, toeOf(b)).first)} kN/m run)"
        steps += "B = ${f2(s.b)} m (${if (inp.baseWidth <= 0) "auto" else "given"}), toe ${f2(s.t)} m, stem ${f0(inp.stem * 1000)} mm, heel ${f2(s.heel)} m, base ${f0(d * 1000)} mm"
        val otL = 0.9 * s.mr; val otR = 1.2 * s.moE + 1.4 * s.moS
        checks += DesignCheck("Overturning", "0.9·Mr = ${f1(otL)}", "≥ ${f1(otR)} kN·m/m", if (otL >= otR) Status.PASS else Status.FAIL, IS456.ref("Cl. 20.1"))
        val slL = 0.9 * inp.mu * s.w; val slR = 1.4 * (s.pa + s.ps)
        checks += DesignCheck("Sliding", "0.9·μ·W = ${f1(slL)}", "≥ 1.4·H = ${f1(slR)} kN/m", if (slL >= slR) Status.PASS else Status.FAIL, IS456.ref("Cl. 20.2"))
        checks += DesignCheck("Base pressure", "qmax ${f1(s.qmax)}, qmin ${f1(s.qmin)} kN/m²", "≤ ${f0(inp.sbc)}, ≥ 0; e = ${f3(s.e)} ≤ B/6", if (s.qmax <= inp.sbc && s.qmin >= 0 && abs(s.e) <= s.b / 6) Status.PASS else Status.FAIL, IS456.ref("Cl. 34.1"))

        val aBar = PI * inp.bar * inp.bar / 4
        fun slab(name: String, mu: Double, thk: Double, face: String): String {
            val dd = thk * 1000 - inp.cover - inp.bar / 2
            val ast = max(SlabDesigner.astFor(mu, dd, inp.fck, inp.fy), 0.0012 * 1000 * thk * 1000)
            val sp = min(300.0, floor(1000 * aBar / ast / 10) * 10)
            steps += "$name: Mu = ${f2(mu)} kN·m/m, d = ${f0(dd)} → Ast ${f0(ast)} mm²/m → φ${f0(inp.bar)} @ ${f0(sp)} ($face)"
            checks += DesignCheck("$name", "Ast ${f0(ast)} mm²/m", "φ${f0(inp.bar)} @ ${f0(sp)} c/c", Status.PASS, IS456.ref("Cl. 38.1, 26.5.2.1"))
            return "φ${f0(inp.bar)} @ ${f0(sp)}"
        }
        // stem: horizontal continuous slab, pressure at the base strip (and mid-height for curtailment)
        val pBase = 1.5 * ka * (inp.gammaSoil * hs + inp.surcharge)
        val pMid = 1.5 * ka * (inp.gammaSoil * hs / 2 + inp.surcharge)
        val stemSup = slab("Stem at counterforts (base strip)", pBase * l * l / 12, inp.stem, "earth face, horizontal")
        val stemMid = slab("Stem mid-span (base strip)", pBase * l * l / 16, inp.stem, "front face, horizontal")
        slab("Stem at counterforts (mid-height)", pMid * l * l / 12, inp.stem, "earth face, upper half")
        val vStem = pBase * (l - inp.counterfort) / 2
        val dStem = inp.stem * 1000 - inp.cover - inp.bar / 2
        val astSup = max(SlabDesigner.astFor(pBase * l * l / 12, dStem, inp.fck, inp.fy), 0.0012 * 1e6 * inp.stem)
        val tvS = vStem * 1000 / (1000 * dStem); val tcS = IS456.tauC(100 * astSup / (1000 * dStem), inp.fck)
        checks += DesignCheck("Stem shear", "τv = ${f3(tvS)} MPa (stem ${f0(inp.stem * 1000)} mm${if (input.stem > 0) "" else ", auto"})", "≤ τc = ${f3(tcS)} MPa", if (tvS <= tcS) Status.PASS else Status.FAIL, IS456.ref("Cl. 40.2"))
        // heel: continuous slab, worst strip at the heel end (largest net downward load)
        val qHeelEnd = s.qmin
        val down = inp.gammaSoil * hs + inp.surcharge + 25 * d
        val wHeel = 1.5 * max(0.0, down - qHeelEnd)
        val heelSup = slab("Heel at counterforts", wHeel * l * l / 12, d, "top face")
        val heelMid = slab("Heel mid-span", wHeel * l * l / 16, d, "bottom face")
        // toe: cantilever
        fun q(x: Double) = s.qmax + (s.qmin - s.qmax) * x / s.b
        val q1 = q(s.t)
        val toeM = 1.5 * max(0.0, q1 * s.t * s.t / 2 + (s.qmax - q1) * s.t * s.t / 3 - 25 * d * s.t * s.t / 2)
        val toe = slab("Toe (cantilever)", toeM, d, "bottom face")
        // counterfort
        val heel = s.heel
        val muCf = 1.5 * l * (ka * inp.gammaSoil * hs * hs * hs / 6 + ka * inp.surcharge * hs * hs / 2)
        val theta = atan(hs / (heel + inp.stem))
        val dCf = ((heel + inp.stem) * 1000 - inp.cover - inp.bar) * sin(theta)
        val astCf = muCf * 1e6 / (0.87 * inp.fy * 0.9 * dCf)
        val muLim = (if (inp.fy >= 500) 0.133 else 0.138) * inp.fck * inp.counterfort * 1000 * dCf * dCf / 1e6
        val nCf = ceil(astCf / aBar).toInt()
        steps += "Counterfort: Mu = 1.5·l·(Ka·γ·h³/6 + Ka·q·h²/2) = ${f1(muCf)} kN·m; back face at ${f1(Math.toDegrees(theta))}°, d normal to it = ${f0(dCf)} mm; z ≈ 0.9d (T-beam, stem flange) → Ast = ${f0(astCf)} mm² = $nCf φ${f0(inp.bar)} along the inclined face"
        checks += DesignCheck("Counterfort depth", "Mu = ${f1(muCf)} kN·m", "≤ Mu,lim (rectangular web) = ${f1(muLim)} kN·m", if (muCf <= muLim) Status.PASS else Status.FAIL, IS456.ref("Annex G-1.1"))
        val tH = pBase * l                 // kN per m height, stem → counterfort
        val tV = wHeel * l                 // kN per m length, heel → counterfort
        val asH = tH * 1000 / (0.87 * inp.fy); val asV = tV * 1000 / (0.87 * inp.fy)
        steps += "Horizontal ties (stem → counterfort): T = 1.5·p·l = ${f1(tH)} kN/m height → ${f0(asH)} mm²/m (two legs); vertical ties (heel → counterfort): ${f1(tV)} kN/m → ${f0(asV)} mm²/m (two legs)"

        val rb = ReportDoc.Builder("Counterfort retaining wall — analysis and design", "IS 456:2000 · H = ${f2(h)} m, counterforts at ${f2(l)} m c/c")
        rb.h("1. Data").kv("Height" to "${f2(h)} m", "Counterforts" to "${f0(inp.counterfort * 1000)} mm at ${f2(l)} m c/c", "Stem / base" to "${f0(inp.stem * 1000)} / ${f0(d * 1000)} mm",
            "Backfill" to "γ ${f1(inp.gammaSoil)}, φ ${f1(inp.phi)}°, q ${f1(inp.surcharge)} kN/m²", "Foundation" to "SBC ${f0(inp.sbc)} kN/m², μ ${f2(inp.mu)}", "Materials" to "M${f0(inp.fck)}, Fe${f0(inp.fy)}")
        rb.fig(section(s, inp.stem, d, h))
        rb.fig(plan(s, inp.stem, inp.counterfort, l))
        rb.h("2. Stability and design").steps(steps)
        rb.h("3. Checks").checks(checks)
        rb.h("4. Reinforcement").table(listOf("Element", "Bars", "Face"), listOf(
            listOf("Stem at counterforts", stemSup, "earth face, horizontal"), listOf("Stem mid-span", stemMid, "front face, horizontal"),
            listOf("Heel at counterforts", heelSup, "top"), listOf("Heel mid-span", heelMid, "bottom"), listOf("Toe", toe, "bottom"),
            listOf("Counterfort main", "$nCf φ${f0(inp.bar)}", "inclined back face"),
            listOf("Ties", "${f0(asH)} / ${f0(asV)} mm²/m", "horizontal / vertical, two-legged"),
        ))
        return Result(s.b, checks, rb.build())
    }

    private fun section(s: RetainingWall.Stability, tb: Double, d: Double, h: Double): ReportDoc.Block.Figure {
        val ln = ArrayList<ReportDoc.FLine>()
        val pts = listOf(0.0 to 0.0, s.b to 0.0, s.b to d, s.t + tb to d, s.t + tb to h, s.t to h, s.t to d, 0.0 to d)
        for (k in pts.indices) { val a = pts[k]; val c = pts[(k + 1) % pts.size]; ln += ReportDoc.FLine(a.first, a.second, c.first, c.second, 3) }
        ln += ReportDoc.FLine(s.t + tb, h, s.b, d, 1, dashed = true)        // counterfort outline (behind the section)
        return ReportDoc.Block.Figure("Section — counterfort shown dashed", ln, listOf(
            ReportDoc.FText(s.b / 2 - 0.3, -0.35, "B = ${f2(s.b)} m"), ReportDoc.FText(s.t - 1.0, h / 2, "H = ${f2(h)} m"), ReportDoc.FText(s.t + tb + 0.2, h * 0.55, "counterfort")), 220f)
    }

    private fun plan(s: RetainingWall.Stability, tb: Double, tc: Double, l: Double): ReportDoc.Block.Figure {
        val ln = ArrayList<ReportDoc.FLine>()
        val len = 3 * l
        ln += ReportDoc.FLine(0.0, 0.0, len, 0.0, 3); ln += ReportDoc.FLine(0.0, tb, len, tb, 3)
        ln += ReportDoc.FLine(0.0, -s.t, len, -s.t, 1, dashed = true); ln += ReportDoc.FLine(0.0, s.b - s.t, len, s.b - s.t, 1, dashed = true)
        var x = l / 2
        while (x < len) { ln += ReportDoc.FLine(x - tc / 2, tb, x - tc / 2, s.b - s.t, 2); ln += ReportDoc.FLine(x + tc / 2, tb, x + tc / 2, s.b - s.t, 2); x += l }
        return ReportDoc.Block.Figure("Plan — stem, counterforts at ${f2(l)} m c/c, base outline dashed", ln,
            listOf(ReportDoc.FText(l / 2 + 0.1, s.b - s.t + 0.15, "${f0(tc * 1000)} mm"), ReportDoc.FText(0.0, -s.t - 0.35, "front of wall")), 160f)
    }
}
