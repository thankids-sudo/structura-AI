package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.max

/**
 * "AI result check": rule-based engineering review of a model and its results, the way a senior engineer
 * would sanity-check a STAAD / RFEM run — load path, equilibrium, serviceability, unusual proportions and
 * missing load cases — with a plain-language explanation and the code clause for each finding.
 */
object ResultCheck {
    enum class Level { OK, INFO, WARN, ERROR }

    data class Finding(val level: Level, val topic: String, val message: String, val clause: String = "", val member: Int? = null, val node: Int? = null)

    class Review(val findings: List<Finding>, val summary: List<String>) {
        val errors get() = findings.count { it.level == Level.ERROR }
        val warnings get() = findings.count { it.level == Level.WARN }
    }

    private fun isVertical(model: FrameModel, m: Member): Boolean {
        val d = model.node(m.j).pos - model.node(m.i).pos
        return abs(d.z) > 0.7 * d.length
    }

    private fun isHorizontal(model: FrameModel, m: Member): Boolean {
        val d = model.node(m.j).pos - model.node(m.i).pos
        return abs(d.z) < 0.1 * d.length
    }

    private fun isConcrete(m: Member) = m.material.gamma in 23.0..26.0

    /** Max deflection (m) of [memberId] relative to the chord of its displaced ends. */
    fun relativeDeflection(r: FrameResults, memberId: Int): Double {
        val pts = r.deformedShape(memberId, 1.0, 20)
        val a = pts.first(); val b = pts.last(); val ch = b - a
        val len = ch.length
        if (len < 1e-12) return 0.0
        val u = ch * (1.0 / len)
        return pts.maxOf { p -> val v = p - a; (v - u * v.dot(u)).length }
    }

    fun review(model: FrameModel, results: List<Pair<LoadCombination, FrameResults>>): Review {
        val f = ArrayList<Finding>()
        val summary = ArrayList<String>()

        // ---- model sanity
        if (model.supports.isEmpty()) f += Finding(Level.ERROR, "Supports", "The model has no supports — it cannot carry load to the ground.")
        val used = model.members.flatMap { listOf(it.i, it.j) }.toSet() + model.panels.flatMap { it.nodes }.toSet()
        model.nodes.filter { it.id !in used }.forEach {
            f += Finding(Level.WARN, "Free node", "Node ${it.id} is not connected to any member or panel — delete it or connect it.", node = it.id)
        }
        if (!model.selfWeight) f += Finding(Level.WARN, "Self-weight", "Self-weight is off: member dead load is missing unless you added it by hand.", "IS 875 Part 1")
        val cases = model.loadCases
        if (LoadCase.LL !in cases) f += Finding(Level.INFO, "Imposed load", "No live (LL) load case — floors and roofs normally carry imposed load.", "IS 875 Part 2, Table 1")
        val zs = model.nodes.map { it.z }
        val tall = (zs.maxOrNull() ?: 0.0) - (zs.minOrNull() ?: 0.0) > 3.5
        if (tall && LoadCase.WL !in cases && LoadCase.EX !in cases && LoadCase.EY !in cases) f += Finding(Level.WARN, "Lateral load",
            "Building frame without any lateral (wind / seismic) case. Every building must be checked for IS 875 Part 3 wind and IS 1893 earthquake — use the seismic card in Studio.",
            "IS 875-3; IS 1893-1 Cl. 6.3")
        val names = results.map { it.first }
        if (LoadCase.WL in cases && names.none { (it.factors[LoadCase.WL] ?: 0.0) != 0.0 })
            f += Finding(Level.ERROR, "Combinations", "A lateral case exists but no combination includes it.", "IS 456 Table 18")
        if (LoadCase.LL in cases && names.none { (it.factors[LoadCase.LL] ?: 0.0) != 0.0 })
            f += Finding(Level.ERROR, "Combinations", "A live-load case exists but no combination includes it.", "IS 456 Table 18")
        if (LoadCase.WL in cases && names.none { (it.factors[LoadCase.DL] ?: 0.0) in 0.85..0.95 })
            f += Finding(Level.WARN, "Combinations", "No 0.9 DL ± 1.5 WL combination — uplift / overturning with minimum gravity is not checked.", "IS 456 Table 18; IS 1893 Cl. 6.3.1.2")

        // ---- proportions
        for (m in model.members) {
            val l = (model.node(m.j).pos - model.node(m.i).pos).length
            val s = m.section
            if (isHorizontal(model, m) && isConcrete(m) && s.h > 0) {
                val ratio = l / s.h
                if (ratio > 20) f += Finding(Level.WARN, "Beam depth", "M${m.id}: span/depth = ${"%.1f".format(ratio)} (> 20). Deflection control is doubtful — deepen the beam or check Cl. 23.2.1 with modification factors.", "IS 456 Cl. 23.2.1", member = m.id)
                if (s.b < 0.2 - 1e-9) f += Finding(Level.INFO, "Beam width", "M${m.id}: width ${(s.b * 1000).toInt()} mm < 200 mm (IS 13920 minimum for earthquake-resistant frames).", "IS 13920 Cl. 6.1.3", member = m.id)
            }
            if (isVertical(model, m) && isConcrete(m) && s.b > 0) {
                val least = minOf(s.b, s.h)
                if (l / least > 12) f += Finding(Level.INFO, "Slender column", "M${m.id}: lex/D ≈ ${"%.1f".format(l / least)} > 12 — design as slender (additional moments) or brace it.", "IS 456 Cl. 25.1.2, 39.7", member = m.id)
                if (least < 0.3 - 1e-9 && tall) f += Finding(Level.INFO, "Column size", "M${m.id}: least dimension ${(least * 1000).toInt()} mm < 300 mm (IS 13920).", "IS 13920 Cl. 7.1.2", member = m.id)
            }
        }

        if (results.isEmpty()) return Review(f, listOf("No results yet — run the analysis for the result checks."))

        // ---- equilibrium & load path
        for ((c, r) in results) {
            val tot = r.totalLoad.map { abs(it) }.max().coerceAtLeast(1e-9)
            val err = r.equilibriumError.map { abs(it) }.max()
            if (err > 1e-3 * tot + 1e-6) f += Finding(Level.ERROR, "Equilibrium", "${c.name}: Σ reactions differ from Σ loads by ${"%.3f".format(err)} kN — check for mechanisms or very soft springs.", "Statics")
        }
        val gravity = results.firstOrNull { (c, _) -> (c.factors[LoadCase.WL] ?: 0.0) == 0.0 } ?: results.first()
        run {
            val (c, r) = gravity
            val fz = -r.totalLoad[2]
            summary += "Load path (${c.name}): total vertical load ${"%.1f".format(fz)} kN reaches ${r.reactions.size} supports."
            val shares = r.reactions.mapValues { it.value[2] }
            val maxS = shares.maxByOrNull { it.value }
            if (maxS != null && fz > 0) summary += "Largest support reaction ${"%.1f".format(maxS.value)} kN at node ${maxS.key} (${"%.0f".format(100 * maxS.value / fz)} % of the total)."
            shares.filter { it.value < -1e-3 }.forEach { (n, v) ->
                f += Finding(Level.WARN, "Uplift", "Node $n pulls the support upward (${"%.1f".format(v)} kN) under ${c.name} — foundation needs hold-down / weight.", "IS 456 Cl. 34", node = n)
            }
            // columns in tension under gravity
            for (m in model.members.filter { isVertical(model, it) }) {
                val st = r.internalForces(m.id, 4)
                val nMax = st.maxOf { it.n }
                if (nMax > 1.0) f += Finding(Level.WARN, "Load path", "Column M${m.id} is in tension (${"%.1f".format(nMax)} kN) under gravity — it is hanging from above; check the load path or a missing support.", member = m.id)
            }
            // beams with large axial force
            for (m in model.members.filter { isHorizontal(model, it) }) {
                val st = r.internalForces(m.id, 8)
                val nAbs = st.maxOf { abs(it.n) }; val vAbs = st.maxOf { abs(it.vz) + abs(it.vy) }
                if (nAbs > 10 && nAbs > vAbs) f += Finding(Level.INFO, "Beam axial force", "Beam M${m.id} carries ${"%.1f".format(nAbs)} kN axial (more than its shear) — it acts as a tie/strut; design for P + M.", "IS 456 Cl. 39.6", member = m.id)
            }
            // idle members
            for (m in model.members) {
                val idle = results.all { (_, rr) -> rr.internalForces(m.id, 4).all { abs(it.n) + abs(it.vy) + abs(it.vz) + abs(it.my) + abs(it.mz) < 1e-3 } }
                if (idle) f += Finding(Level.INFO, "Idle member", "M${m.id} carries no force in any combination — redundant, unloaded or disconnected.", member = m.id)
            }
        }

        // ---- serviceability: beam deflection (service or ÷ 1.5) and storey drift
        val service = results.firstOrNull { (c, _) -> c.factors.values.all { it <= 1.0 + 1e-9 } }
        val (defCombo, defRes) = service ?: gravity
        val scale = if (service != null) 1.0 else 1.0 / 1.5
        var worst: Pair<Int, Double>? = null
        for (m in model.members.filter { isHorizontal(model, it) }) {
            val l = (model.node(m.j).pos - model.node(m.i).pos).length
            val d = relativeDeflection(defRes, m.id) * scale
            if (worst == null || d / l > worst.second) worst = m.id to d / l
            if (d > l / 250) f += Finding(Level.WARN, "Deflection", "M${m.id}: deflection ${"%.1f".format(d * 1000)} mm > span/250 = ${"%.1f".format(l * 1000 / 250)} mm (${defCombo.name}${if (service == null) " ÷ 1.5" else ""}).", "IS 456 Cl. 23.2(a); IS 800 Table 6", member = m.id)
        }
        worst?.let { summary += "Stiffest-to-softest beams: worst deflection span/${"%.0f".format(1 / it.second.coerceAtLeast(1e-9))} at M${it.first} (limit span/250)." }
        for ((c, r) in results.filter { (c, _) -> (c.factors[LoadCase.WL] ?: 0.0) != 0.0 }) {
            for (m in model.members.filter { isVertical(model, it) }) {
                val h = abs(model.node(m.j).z - model.node(m.i).z)
                val di = r.nodeDisplacements[m.i] ?: continue; val dj = r.nodeDisplacements[m.j] ?: continue
                val drift = max(abs(dj[0] - di[0]), abs(dj[1] - di[1]))
                val fac = c.factors.values.maxOf { abs(it) }.coerceAtLeast(1.0)
                if (drift / fac > 0.004 * h) f += Finding(Level.WARN, "Storey drift", "M${m.id}: drift ${"%.1f".format(drift / fac * 1000)} mm > 0.004 h = ${"%.1f".format(4 * h)} mm under ${c.name} (unfactored).", "IS 1893-1 Cl. 7.11.1", member = m.id)
            }
        }
        // near-mechanism
        val size = (model.nodes.maxOf { it.x } - model.nodes.minOf { it.x }) + (model.nodes.maxOf { it.y } - model.nodes.minOf { it.y }) + (zs.max() - zs.min())
        for ((c, r) in results) {
            val (n, d) = r.maxDisplacement()
            if (d > size / 50) f += Finding(Level.ERROR, "Stability", "${c.name}: node $n moves ${"%.0f".format(d * 1000)} mm — the structure is close to a mechanism (missing restraint, hinges or bracing).", node = n)
        }

        if (f.none { it.level == Level.ERROR || it.level == Level.WARN }) summary += "No errors or warnings — the model behaves as a sound structure. Proceed to member design."
        else summary += "${f.count { it.level == Level.ERROR }} error(s), ${f.count { it.level == Level.WARN }} warning(s) — review before design."
        return Review(f.sortedBy { -it.level.ordinal }, summary)
    }
}
