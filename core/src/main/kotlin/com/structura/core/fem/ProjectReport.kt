package com.structura.core.fem

import com.structura.core.rcc.FoundationDesign
import com.structura.core.rcc.Status
import com.structura.core.report.ReportDoc
import com.structura.core.steel.SteelModelCheck

/**
 * One report for the whole project: model, loads and analysis ([FemReportDoc]) followed by the design of every
 * member (beam schedule with sagging / hogging steel and stirrups, column schedule, roles), the footing schedule,
 * the clause-wise checks of each element, and the validation list.
 */
object ProjectReport {
    private fun f0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    fun build(
        model: FrameModel, results: List<Pair<LoadCombination, FrameResults>>, rcc: List<RccModelDesign.MemberDesign>,
        steel: List<SteelModelCheck.MemberCheck>, footings: List<FoundationDesign.Result>, validation: Validation.Report,
        project: String = model.title, engineer: String = "",
    ): ReportDoc {
        val base = FemReportDoc.build(model, results, steel, ResultCheck.review(model, results))
        val rb = ReportDoc.Builder("", "")
        val beams = rcc.filter { it.nBot > 0 || it.kind != "Column" && it.kind != "Raking" && it.nb == 0 && !it.circular }
        val cols = rcc.filter { it.kind == "Column" || it.kind == "Raking" }
        rb.h("Design summary")
        rb.kv(
            "Project" to project, "Engineer" to engineer.ifEmpty { "—" },
            "Members designed" to "${rcc.size} RCC (${beams.size} beams, ${cols.size} columns) + ${steel.size} steel",
            "Footings" to "${footings.size} (${footings.groupBy { it.type.label }.entries.joinToString { "${it.value.size} ${it.key.lowercase()}" }})",
            "Result" to "${validation.errors} error(s), ${validation.warnings} warning(s)" + if (validation.errors == 0) " — design satisfies the checks" else " — see the validation list",
            "Codes" to "IS 456:2000, IS 800:2007, IS 875 (Parts 1–3, 5), IS 1893 (Part 1), IS 13920:2016",
        )
        if (beams.isNotEmpty()) {
            rb.h("Beam schedule (IS 456 Cl. 38, 40, 23.2, 26)")
            rb.table(listOf("Member", "Type", "b × D (mm)", "M+ / M− (kN·m)", "Vu (kN)", "Bottom", "Top", "Stirrups", "η", ""),
                beams.sortedBy { it.member }.map { d ->
                    listOf("M${d.member}", d.kind, "${f0(d.b)} × ${f0(d.d)}", "${f1(d.mu)} / ${f1(d.muHog)}", f1(d.vu), d.bottom, d.top, d.stirrups, f2(d.utilisation), if (d.ok) "✔" else "✘")
                }, listOf(0.7f, 1.1f, 1f, 1.1f, 0.7f, 1f, 1f, 1f, 0.5f, 0.3f))
        }
        if (cols.isNotEmpty()) {
            rb.h("Column schedule (IS 456 Cl. 25, 26.5.3, 39)")
            rb.table(listOf("Member", "Section (mm)", "Pu (kN)", "Mux / Muy (kN·m)", "Bars", "Interaction", ""),
                cols.sortedBy { it.member }.map { d ->
                    listOf("M${d.member}", if (d.circular) "Ø ${f0(d.d)}" else "${f0(d.b)} × ${f0(d.d)}", f0(d.pu), "${f1(d.mux)} / ${f1(d.muy)}", d.bottom, f2(d.utilisation), if (d.ok) "✔" else "✘")
                }, listOf(0.7f, 1f, 0.8f, 1.1f, 1.8f, 0.8f, 0.3f))
        }
        if (steel.isNotEmpty()) {
            rb.h("Steel members (IS 800:2007)")
            rb.table(listOf("Member", "Section", "Grade", "Governing combination", "η", ""),
                steel.sortedBy { it.member }.map { listOf("M${it.member}", it.section, it.grade, it.combination, f2(it.utilisation), if (it.ok) "✔" else "✘") })
        }
        if (footings.isNotEmpty()) {
            rb.h("Footing schedule (IS 456 Cl. 34)")
            rb.table(listOf("Node", "Type", "L × B (m)", "D (mm)", "Pu (kN)", "Bottom ∥ L", "Bottom ∥ B", "Top", "Concrete (m³)", ""),
                footings.sortedBy { it.node }.map { f ->
                    listOf("N${f.node}" + (f.partner?.let { "+N$it" } ?: ""), f.type.label, "${f2(f.l)} × ${f2(f.b)}", if (f.edge < f.depth) "${f0(f.depth)}/${f0(f.edge)}" else f0(f.depth),
                        f0(f.pu), f.barsL, f.barsB, f.barsTop.ifEmpty { "—" }, f2(f.concrete), if (f.ok) "✔" else "✘")
                }, listOf(0.8f, 1.1f, 1f, 0.8f, 0.7f, 1.2f, 1.2f, 0.8f, 0.8f, 0.3f))
            rb.p("Total footing concrete ${f2(footings.sumOf { it.concrete })} m³. Service loads are taken as factored reactions / 1.5; " +
                "confirm the safe bearing capacity from the soil report and add the weight of backfill over the footing where it is significant.")
        }
        rb.h("Element checks")
        for (d in rcc.sortedBy { it.member }) { rb.p("M${d.member} — ${d.summary}"); rb.checks(d.checks) }
        for (s in steel.sortedBy { it.member }) { rb.p("M${s.member} — steel ${s.section} ${s.grade}, η ${f2(s.utilisation)} (${s.combination})"); rb.checks(s.result.checks) }
        for (f in footings.sortedBy { it.node }) { rb.p("Footing N${f.node} — ${f.summary}"); rb.steps(f.steps); rb.checks(f.checks) }
        rb.h("Validation")
        rb.table(listOf("", "Topic", "Finding", "Clause"), validation.items.map { v ->
            listOf(when (v.level) { Validation.Level.ERROR -> "✘"; Validation.Level.WARN -> "⚠"; Validation.Level.INFO -> "ℹ"; Validation.Level.OK -> "✔" }, v.topic, v.message, v.clause)
        }, listOf(0.3f, 1.2f, 3.5f, 1.2f))
        rb.p("Prepared with Structura AI. The designer remains responsible for verifying inputs, load assumptions, soil data and detailing before construction.")
        val extra = rb.build()
        return ReportDoc("Structural design report — $project", "Analysis and design of all members and foundations · IS 456 / IS 800 / IS 1893 / IS 13920",
            base.blocks + extra.blocks)
    }

    @Suppress("unused") private val keep = Status.PASS
}
