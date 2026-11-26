package com.structura.core.fem

import com.structura.core.report.ReportDoc
import com.structura.core.steel.SteelModelCheck
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Frame analysis report (for the PDF export from the Workbench): data, isometric sketch, static check, reactions, envelope, steel, AI review. */
object FemReportDoc {
    private fun f(v: Double, d: Int = 2) = String.format(java.util.Locale.US, "%.${d}f", v)
    private fun e(v: Double) = String.format(java.util.Locale.US, "%.1e", v)

    fun build(model: FrameModel, results: List<Pair<LoadCombination, FrameResults>>, steel: List<SteelModelCheck.MemberCheck> = emptyList(),
              review: ResultCheck.Review? = null): ReportDoc {
        val rb = ReportDoc.Builder("Frame analysis — ${model.title}", "Direct stiffness 3D frame · IS 456 Table 18 / IS 1893 combinations · IS 800")
        rb.h("1. Model").kv(
            "Nodes / members / supports" to "${model.nodes.size} / ${model.members.size} / ${model.supports.size}",
            "Beam theory" to model.beamTheory.label, "Self-weight" to if (model.selfWeight) "included (factor ${f(model.selfWeightFactor)})" else "not included",
            "Load cases" to model.loadCases.joinToString(), "Combinations" to results.joinToString { it.first.name },
            "Materials" to model.members.map { it.material.name }.distinct().joinToString(),
            "Sections" to model.members.map { it.section.name }.distinct().take(12).joinToString(),
        )
        // isometric sketch
        val c = cos(Math.toRadians(30.0)); val s = sin(Math.toRadians(30.0))
        fun px(n: Node) = (n.x - n.y) * c
        fun py(n: Node) = n.z + (n.x + n.y) * s
        val lines = model.members.map { m -> val a = model.node(m.i); val b = model.node(m.j); ReportDoc.FLine(px(a), py(a), px(b), py(b), 2) }
        val sup = model.supports.flatMap { sp -> val n = model.node(sp.node); val x = px(n); val y = py(n)
            listOf(ReportDoc.FLine(x - 0.25, y - 0.3, x + 0.25, y - 0.3, 2), ReportDoc.FLine(x - 0.25, y - 0.3, x, y), ReportDoc.FLine(x, y, x + 0.25, y - 0.3)) }
        rb.fig(ReportDoc.Block.Figure("Isometric view of the model (supports shown as triangles)", lines + sup, emptyList(), 230f))
        if (results.isEmpty()) return rb.p("Not analysed.").build()
        rb.h("2. Static check (all combinations)")
        rb.table(listOf("Combination", "ΣFz applied (kN)", "Residual ΣF (kN)", "Residual ΣM (kN·m)", "Max disp. (mm)"), results.map { (cb, r) ->
            listOf(cb.name, f(r.totalLoad[2]), e(r.equilibriumError.maxOf { abs(it) }), e(r.momentEquilibriumError.maxOf { abs(it) }), f(r.maxDisplacement().second * 1000, 1))
        }, listOf(1.8f, 1f, 1f, 1f, 0.9f))
        val (c0, r0) = results.first()
        rb.h("3. Support reactions — ${c0.name}")
        rb.table(listOf("Node", "Rx (kN)", "Ry (kN)", "Rz (kN)", "Mx", "My", "Mz (kN·m)"), r0.reactions.entries.sortedBy { it.key }.map { (n, v) ->
            listOf("N$n") + v.map { f(it) } }, listOf(0.8f, 1f, 1f, 1f, 1f, 1f, 1f))
        rb.h("4. Member force envelope (all combinations; 30 largest moments)")
        val env = FemReport.envelope(results.map { it.second })
        rb.table(listOf("Member", "L (m)", "N (kN)", "|Vz| max (kN)", "My + (kN·m)", "My − (kN·m)"), env.sortedByDescending { maxOf(it.myMax, -it.myMin) }.take(30).map {
            listOf("M${it.member}", f(it.length), f(it.nAbsMax), f(it.vzAbsMax), f(it.myMax), f(it.myMin)) })
        if (steel.isNotEmpty()) {
            rb.h("5. Steel members — IS 800:2007")
            rb.table(listOf("Member", "Section", "Governing", "η", "Status"), steel.sortedByDescending { it.utilisation }.map {
                listOf("M${it.member}", it.section, it.combination, f(it.utilisation, 3), if (it.ok) "OK" else "REVISE") }, listOf(0.7f, 1.3f, 1.8f, 0.6f, 0.7f))
            steel.maxByOrNull { it.utilisation }?.let { rb.p("Critical member M${it.member} (${it.section}):"); rb.checks(it.result.checks) }
        }
        review?.let { rv ->
            rb.h("${if (steel.isNotEmpty()) 6 else 5}. AI result check")
            rv.summary.forEach { rb.p(it) }
            if (rv.findings.isNotEmpty()) rb.table(listOf("Level", "Topic", "Finding", "Clause"), rv.findings.map { listOf(it.level.name, it.topic, it.message, it.clause) }, listOf(0.6f, 1f, 3f, 1.1f))
        }
        return rb.build()
    }
}
