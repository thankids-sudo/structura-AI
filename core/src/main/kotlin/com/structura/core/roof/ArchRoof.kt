package com.structura.core.roof

import com.structura.core.fem.Buckling
import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameSolver
import com.structura.core.fem.LateralLoads
import com.structura.core.fem.LoadCase
import com.structura.core.fem.LoadCombination
import com.structura.core.fem.Material
import com.structura.core.fem.Member
import com.structura.core.fem.NodalLoad
import com.structura.core.fem.Node
import com.structura.core.fem.Section
import com.structura.core.fem.Support
import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.Status
import com.structura.core.report.ReportDoc
import com.structura.core.report.f0
import com.structura.core.report.f1
import com.structura.core.report.f2
import com.structura.core.report.f3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Self-supporting curved (arch) roof of cold-formed trapezoidal corrugated steel sheet with a user-defined profile.
 * Per metre width: thin-wall section properties, Winter effective width of compression flanges (IS 801 Cl. 5.2.1),
 * circular two-pinned arch FEM for DL, full / half-span roof live load (IS 875-2 Table 2, curved roofs) and wind
 * normal to the surface (IS 875-3, Cpe by thirds), in-plane elastic buckling (λcr) with second-order amplification
 * 1/(1 − 1/λcr) (IS 800 Cl. 4.4.3.2), stress interaction on the effective section and service deflection.
 */
object ArchRoof {
    data class Profile(val depth: Double = 60.0, val pitch: Double = 200.0, val crest: Double = 40.0, val trough: Double = 60.0, val t: Double = 1.0) {
        val web: Double get() = (pitch - crest - trough) / 2
        val webLen: Double get() = hypot(web, depth)
    }

    class Props(val area: Double, val ybar: Double, val i: Double, val zTop: Double, val zBot: Double)   // per m width: mm²/m, mm, mm⁴/m, mm³/m

    data class Input(
        val span: Double = 12.0, val rise: Double = 3.0, val profile: Profile = Profile(),
        val fy: Double = 350.0, val e: Double = 2.0e5,
        val extraDead: Double = 0.05,                    // kN/m² on surface (fixings, insulation)
        val roofLive: Double? = null,                    // kN/m² on plan; null = IS 875-2 curved-roof rule
        val vb: Double = 44.0, val terrain: Int = 2, val height: Double = 4.0,
        val cpe: Triple<Double, Double, Double> = Triple(-0.6, -0.8, -0.5),   // windward third, centre, leeward third
        val cpi: Double = 0.2, val segments: Int = 24,
    )

    class Result(val model: FrameModel, val props: Props, val effTop: Props, val effBot: Props, val lambdaCr: Double, val maxUtil: Double,
                 val thrust: Double, val deflection: Double, val report: ReportDoc)

    /** Thin-wall properties per metre width for crest / trough widths [a] / [c] (effective widths allowed). */
    fun props(p: Profile, a: Double = p.crest, c: Double = p.trough): Props {
        val n = 1000.0 / p.pitch
        val lw = p.webLen; val t = p.t; val d = p.depth
        val ap = t * (a + c + 2 * lw)
        val yb = t * (a * d + 2 * lw * d / 2) / ap
        val ip = t * (a * (d - yb) * (d - yb) + c * yb * yb + 2 * (lw * d * d / 12 + lw * (d / 2 - yb) * (d / 2 - yb)))
        return Props(ap * n, yb, ip * n, ip * n / (d - yb), ip * n / yb)
    }

    /** Winter effective width of a flat element stiffened on both edges (IS 801 Cl. 5.2.1.1): be/t = 1.9√(E/f)(1 − 0.415/(w/t)·√(E/f)). */
    fun effectiveWidth(w: Double, t: Double, f: Double, e: Double): Double {
        val r = sqrt(e / f); val wt = w / t
        return if (wt <= 1.28 * r) w else min(w, 1.9 * t * r * (1 - 0.415 / wt * r))
    }

    /** Curved roof live load on plan (IS 875-2 Table 2): 0.75 − 0.52γ² ≥ 0.40 kN/m², γ = rise / span. */
    fun curvedLive(rise: Double, span: Double) = max(0.40, 0.75 - 0.52 * (rise / span) * (rise / span))

    fun design(inp: Input): Result {
        val p = inp.profile
        val steps = ArrayList<String>()
        val gross = props(p)
        val eTop = props(p, effectiveWidth(p.crest, p.t, inp.fy, inp.e), p.trough)
        val eBot = props(p, p.crest, effectiveWidth(p.trough, p.t, inp.fy, inp.e))
        steps += "Profile: depth ${f0(p.depth)}, pitch ${f0(p.pitch)}, crest ${f0(p.crest)}, trough ${f0(p.trough)}, web ${f1(p.webLen)} mm, t ${f2(p.t)} mm"
        steps += "Gross per m: A = ${f0(gross.area)} mm², I = ${String.format(java.util.Locale.US, "%.3e", gross.i)} mm⁴, Ztop ${f0(gross.zTop)}, Zbot ${f0(gross.zBot)} mm³ (weight ${f3(gross.area * 1e-6 * 78.5)} kN/m²)"
        steps += "Effective crest ${f1(effectiveWidth(p.crest, p.t, inp.fy, inp.e))} / trough ${f1(effectiveWidth(p.trough, p.t, inp.fy, inp.e))} mm (Winter, f = fy) → Aeff ${f0(min(eTop.area, eBot.area))} mm², Zeff ${f0(minOf(eTop.zTop, eTop.zBot, eBot.zTop, eBot.zBot))} mm³"

        // arch geometry
        val l = inp.span; val h = inp.rise
        val r = (l * l / 4 + h * h) / (2 * h)
        val half = asin((l / 2) / r)
        val n = inp.segments.coerceIn(8, 80)
        val nodes = (0..n).map { k ->
            val a = -half + 2 * half * k / n
            Node(k + 1, l / 2 + r * sin(a), 0.0, h - r + r * cos(a))
        }
        val mat = Material("Cold-formed fy ${f0(inp.fy)}", inp.e * 1000, inp.e * 1000 / 2.6, 78.5)
        val jt = gross.area * p.t * p.t / 3 * 1e-12                                  // thin-wall St Venant J per m (Σ b·t³/3)
        val sec = Section("Sheet ${f0(p.depth)}×${f2(p.t)}", gross.area * 1e-6, gross.i * 1e-12, gross.i * 1e-12 * 50, jt, 1.0, p.depth / 1000, gross.area * 1e-6, gross.area * 1e-6)
        val members = (1..n).map { Member(it, it, it + 1, mat, sec) }
        val supports = listOf(Support(1, listOf(true, true, true, false, false, false)), Support(n + 1, listOf(true, true, true, false, false, false)))
        steps += "Arch: circular, R = ${f2(r)} m, half-angle ${f1(Math.toDegrees(half))}°, ${n} segments, two-pinned, 1 m wide strip (2D)"

        // loads at nodes (tributary arc length / plan length)
        val ll = inp.roofLive ?: curvedLive(h, l)
        steps += "Roof live load ${f2(ll)} kN/m² on plan" + if (inp.roofLive == null) " (IS 875-2 Table 2: 0.75 − 0.52(h/l)² ≥ 0.40, h/l = ${f3(h / l)})" else ""
        val z = inp.height + h
        val vz = inp.vb * LateralLoads.k2(z, inp.terrain)
        val pd = maxOf(0.81, 0.7) * 0.6 * vz * vz / 1000
        steps += "Wind pd = ${f3(pd)} kN/m² at ${f1(z)} m (IS 875-3 Cl. 7.2, Kd·Ka·Kc = 0.81); Cpe thirds ${inp.cpe.first} / ${inp.cpe.second} / ${inp.cpe.third}, Cpi +${inp.cpi} (IS 875-3 Table 16 curved roofs — confirm for h/l and support height)"
        val loads = ArrayList<NodalLoad>()
        for (k in 1..n + 1) {
            val nd = nodes[k - 1]
            var ls = 0.0; var lp = 0.0
            if (k > 1) { val a = nodes[k - 2]; ls += hypot(nd.x - a.x, nd.z - a.z) / 2; lp += abs(nd.x - a.x) / 2 }
            if (k <= n) { val b = nodes[k]; ls += hypot(b.x - nd.x, b.z - nd.z) / 2; lp += abs(b.x - nd.x) / 2 }
            loads += NodalLoad(k, listOf(0.0, 0.0, -inp.extraDead * ls, 0.0, 0.0, 0.0), LoadCase.DL)
            loads += NodalLoad(k, listOf(0.0, 0.0, -ll * lp, 0.0, 0.0, 0.0), LoadCase.LL)
            if (nd.x <= l / 2 + 1e-9) loads += NodalLoad(k, listOf(0.0, 0.0, -ll * lp * (if (abs(nd.x - l / 2) < 1e-9) 0.5 else 1.0), 0.0, 0.0, 0.0), "LLH")
            // outward normal of the circle at the node
            val nx = (nd.x - l / 2) / r; val nz = (nd.z - (h - r)) / r
            val cpe = when { nd.x < l / 3 -> inp.cpe.first; nd.x <= 2 * l / 3 -> inp.cpe.second; else -> inp.cpe.third }
            val f = -(cpe - inp.cpi) * pd * ls
            loads += NodalLoad(k, listOf(f * nx, 0.0, f * nz, 0.0, 0.0, 0.0), LoadCase.WL)
        }
        val model = FrameModel(nodes, members, supports, loads, selfWeight = true, title = "Self-supported arch roof ${f1(l)} m", planeXZ = true)
        val combos = listOf(
            LoadCombination("1.5(DL+LL)", mapOf(LoadCase.DL to 1.5, LoadCase.LL to 1.5)),
            LoadCombination("1.5(DL+LL half)", mapOf(LoadCase.DL to 1.5, "LLH" to 1.5)),
            LoadCombination("1.5(DL+WL)", mapOf(LoadCase.DL to 1.5, LoadCase.WL to 1.5)),
            LoadCombination("0.9DL+1.5WL", mapOf(LoadCase.DL to 0.9, LoadCase.WL to 1.5)),
            LoadCombination("1.2(DL+LL+WL)", mapOf(LoadCase.DL to 1.2, LoadCase.LL to 1.2, LoadCase.WL to 1.2)),
        )
        val res = FrameSolver.analyseAll(model, combos)
        val aEff = min(eTop.area, eBot.area) * 1e-6
        val zEff = min(min(eTop.zTop, eTop.zBot), min(eBot.zTop, eBot.zBot)) * 1e-9
        val fd = inp.fy / 1.10 * 1000                                       // kN/m²
        val rows = ArrayList<List<String>>()
        var worst = 0.0; var worstCombo = ""; var lamMin = Double.MAX_VALUE
        val checks = ArrayList<DesignCheck>()
        for ((c, rr) in res) {
            // λcr < 0 means this combination puts the arch in tension overall (no in-plane buckling under it)
            val lam = runCatching { Buckling.analyse(model, c, 2).lambda }.getOrNull()?.let { if (it <= 0) Double.MAX_VALUE else it } ?: Double.MAX_VALUE
            lamMin = min(lamMin, lam)
            val amp = if (lam > 1.0) 1 / (1 - 1 / lam) else Double.POSITIVE_INFINITY
            var u = 0.0; var nAt = 0.0; var mAt = 0.0
            for (m in members) for (st in rr.internalForces(m.id, 4)) {
                val v = abs(st.n) / aEff / fd + abs(st.my) * amp / zEff / fd
                if (v > u) { u = v; nAt = st.n; mAt = st.my }
            }
            rows += listOf(c.name, f1(nAt), f2(mAt), if (lam == Double.MAX_VALUE) "—" else f2(lam), f2(amp), f3(u))
            if (u > worst) { worst = u; worstCombo = c.name }
        }
        val h1 = res.first().second.reactions.getValue(1)[0]
        val thrust = res.maxOf { (_, rr) -> abs(rr.reactions.getValue(1)[0]) }
        val service = FrameSolver.analyse(model, LoadCombination("DL+LL", mapOf(LoadCase.DL to 1.0, LoadCase.LL to 1.0)))
        val serviceH = FrameSolver.analyse(model, LoadCombination("DL+LL half", mapOf(LoadCase.DL to 1.0, "LLH" to 1.0)))
        val defl = max(service.nodeDisplacements.values.maxOf { abs(it[2]) }, serviceH.nodeDisplacements.values.maxOf { abs(it[2]) }) * 1000
        checks += DesignCheck("Flat width of crest", "w/t = ${f1(p.crest / p.t)}", "≤ 500; effective above ${f1(1.28 * sqrt(inp.e / inp.fy))}", if (p.crest / p.t <= 500) Status.PASS else Status.FAIL, "IS 801 Cl. 5.2.1, 5.3.1")
        checks += DesignCheck("Web depth / t", "${f1(p.webLen / p.t)}", "≤ 150 (unreinforced webs)", if (p.webLen / p.t <= 150) Status.PASS else Status.FAIL, "IS 801 Cl. 6.4")
        checks += DesignCheck("In-plane arch buckling", "λcr min = ${if (lamMin == Double.MAX_VALUE) "—" else f2(lamMin)}", "> 1 (amplification 1/(1 − 1/λcr) applied)", if (lamMin > 1.0) Status.PASS else Status.FAIL, "IS 800 Cl. 4.4.3.2")
        checks += DesignCheck("Strength N/Aeff + M·amp/Zeff", "${f3(worst)} ($worstCombo)", "≤ 1.0 with fy/γm0", if (worst <= 1.0) Status.PASS else Status.FAIL, "IS 801 Cl. 6.6 / IS 800 Cl. 9.3.1")
        checks += DesignCheck("Service deflection", "${f1(defl)} mm", "≤ span/180 = ${f1(l * 1000 / 180)} mm", if (defl <= l * 1000 / 180) Status.PASS else Status.FAIL, "IS 800 Table 6")

        val rb = ReportDoc.Builder("Self-supported corrugated arch roof", "IS 801 · IS 800:2007 · IS 875 Parts 2–3 · span ${f1(l)} m, rise ${f2(h)} m")
        rb.h("1. Data").kv(
            "Span × rise" to "${f2(l)} m × ${f2(h)} m (h/l = ${f3(h / l)}), radius ${f2(r)} m",
            "Profile (custom)" to "depth ${f0(p.depth)} · pitch ${f0(p.pitch)} · crest ${f0(p.crest)} · trough ${f0(p.trough)} · t ${f2(p.t)} mm",
            "Steel" to "fy ${f0(inp.fy)} MPa, E ${f0(inp.e)} MPa",
            "Loads" to "extra dead ${f2(inp.extraDead)} kN/m², live ${f2(ll)} kN/m² (plan), Vb ${f1(inp.vb)} m/s, terrain ${inp.terrain}",
        )
        rb.fig(archFigure(nodes, l, h))
        rb.fig(profileFigure(p))
        rb.h("2. Section and loads").steps(steps)
        rb.h("3. Analysis (1 m strip, two-pinned arch)")
        rb.p("Support thrust H = ${f2(thrust)} kN/m (max over combinations; ${f2(h1)} kN/m under 1.5(DL+LL)) — provide ties or anchor the springings for it.")
        rb.table(listOf("Combination", "N (kN/m)", "M (kN·m/m)", "λcr", "amp", "η"), rows, listOf(1.8f, 1f, 1f, 0.8f, 0.8f, 0.8f))
        rb.h("4. Checks").checks(checks)
        rb.h("5. Notes").p("Half-span live load usually governs arch bending. Seams, end fixings to the springing beam and the gable stiffening are to be designed for the thrust and wind uplift. Confirm Cpe for curved roofs (IS 875-3 Table 16) and use the manufacturer's tested section properties where available.")
        return Result(model, gross, eTop, eBot, lamMin, worst, thrust, defl, rb.build())
    }

    private fun archFigure(nodes: List<Node>, l: Double, h: Double): ReportDoc.Block.Figure {
        val ln = ArrayList<ReportDoc.FLine>()
        for (k in 1 until nodes.size) ln += ReportDoc.FLine(nodes[k - 1].x, nodes[k - 1].z, nodes[k].x, nodes[k].z, 3)
        ln += ReportDoc.FLine(0.0, 0.0, l, 0.0, 1, dashed = true)
        ln += ReportDoc.FLine(l / 2, 0.0, l / 2, h, 1, dashed = true)
        for (x in listOf(0.0, l)) { ln += ReportDoc.FLine(x - 0.3, -0.35, x + 0.3, -0.35, 2); ln += ReportDoc.FLine(x - 0.3, -0.35, x, 0.0); ln += ReportDoc.FLine(x, 0.0, x + 0.3, -0.35) }
        return ReportDoc.Block.Figure("Arch elevation — span ${f2(l)} m, rise ${f2(h)} m, pinned springings", ln,
            listOf(ReportDoc.FText(l / 2 + 0.2, h / 2, "rise ${f2(h)} m"), ReportDoc.FText(l / 2, -0.6, "span ${f2(l)} m")), 170f)
    }

    private fun profileFigure(p: Profile): ReportDoc.Block.Figure {
        val ln = ArrayList<ReportDoc.FLine>()
        var x = 0.0
        repeat(3) {
            ln += ReportDoc.FLine(x, 0.0, x + p.trough, 0.0, 2)
            ln += ReportDoc.FLine(x + p.trough, 0.0, x + p.trough + p.web, p.depth, 2)
            ln += ReportDoc.FLine(x + p.trough + p.web, p.depth, x + p.trough + p.web + p.crest, p.depth, 2)
            ln += ReportDoc.FLine(x + p.trough + p.web + p.crest, p.depth, x + p.pitch, 0.0, 2)
            x += p.pitch
        }
        return ReportDoc.Block.Figure("Custom trapezoidal profile (mm), 3 pitches", ln,
            listOf(ReportDoc.FText(p.trough + p.web, p.depth + 12, "crest ${f0(p.crest)}"), ReportDoc.FText(0.0, -14.0, "trough ${f0(p.trough)} · pitch ${f0(p.pitch)} · depth ${f0(p.depth)} · t ${f2(p.t)}")), 110f)
    }
}
