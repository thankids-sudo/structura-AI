package com.structura.core.fem

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Self-contained HTML analysis report (opens in any browser; Print ▸ Save as PDF gives an A4 report).
 * Black-and-white, serif, textbook style.
 */
object HtmlReport {

    fun build(
        model: FrameModel,
        shown: Pair<LoadCombination, FrameResults>?,
        ulsEnvelope: List<MemberEnvelope>,
        combinations: List<LoadCombination>,
        buckling: BucklingResult? = null,
        project: String = model.title,
        modal: ModalResult? = null,
        seismic: Seismic.Result? = null,
        steel: List<com.structura.core.steel.SteelModelCheck.MemberCheck> = emptyList(),
    ): String = buildString {
        fun f(v: Double, d: Int = 2) = String.format(Locale.US, "%.${d}f", v)
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun table(head: List<String>, rows: List<List<String>>) {
            append("<table><thead><tr>")
            head.forEach { append("<th>").append(esc(it)).append("</th>") }
            append("</tr></thead><tbody>")
            rows.forEach { r -> append("<tr>"); r.forEach { append("<td>").append(esc(it)).append("</td>") }; append("</tr>") }
            append("</tbody></table>")
        }

        append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>Structura AI — ").append(esc(project)).append("</title><style>")
        append(
            "body{font-family:Georgia,'Times New Roman',serif;color:#111;max-width:900px;margin:24px auto;padding:0 16px;line-height:1.45}" +
                "h1{font-size:24px;margin-bottom:4px}h2{font-size:18px;border-bottom:1px solid #444;padding-bottom:3px;margin-top:28px}" +
                "table{border-collapse:collapse;width:100%;font-size:13px;margin:8px 0}th,td{border:1px solid #555;padding:3px 6px;text-align:right}" +
                "th{background:#eee}td:first-child,th:first-child{text-align:left}.note{border-left:4px solid #333;background:#f4f4f4;padding:8px 12px;font-size:13px}" +
                ".meta{font-size:13px}svg{border:1px solid #555;background:#fff;width:100%;height:auto}.ok{font-weight:bold}" +
                "@media print{body{margin:0;max-width:none}h2{page-break-after:avoid}table{page-break-inside:avoid}}"
        )
        append("</style></head><body>")
        append("<h1>Structura AI — Structural Analysis Report</h1>")
        append("<div class=\"meta\"><b>Project:</b> ").append(esc(project)).append("<br><b>Report date:</b> ")
            .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm"))).append("<br>")
        append("<b>Solver:</b> linear-elastic 3D frame, direct stiffness method, ${esc(model.beamTheory.label)} members (6 DOF/node), ")
        append("moment hinges and rotational end springs by static condensation, uniform and concentrated member loads, nodal loads, self-weight. ")
        append("Load combinations per IS 456:2000 Table 18 / IS 875 (Part 5).</div>")
        append("<p class=\"note\"><b>PRELIMINARY ENGINEERING ANALYSIS.</b> Results are for checking and design development; ")
        append("verify input data, loads (IS 875, IS 1893) and member design before use for construction. ")
        append("The buckling multiplier is a first-order elastic estimate, not a design resistance.</p>")

        val shownRes = shown?.second
        append("<h2>1. Frame model</h2>")
        append(svg(model, shownRes))
        append("<p class=\"meta\">Isometric view. Global Z up. ")
        if (shownRes != null) append("Deformed shape (magnified) for ${esc(shown!!.first.name)} drawn bold.")
        append("</p>")

        append("<h2>2. Nodes and supports</h2>")
        table(listOf("Node", "X (m)", "Y (m)", "Z (m)", "Support"), model.nodes.map { n ->
            listOf("N${n.id}", f(n.x, 3), f(n.y, 3), f(n.z, 3), ModelEditor.supportType(model, n.id).name.lowercase().replace('_', ' ').let { if (it == "none") "free" else it })
        })

        append("<h2>3. Materials and sections</h2>")
        val mats = model.members.map { it.material }.distinct()
        table(listOf("Material", "E (MPa)", "G (MPa)", "γ (kN/m³)"), mats.map { listOf(it.name, f(it.e / 1000, 0), f(it.g / 1000, 0), f(it.gamma, 1)) })
        val secs = model.members.map { it.section }.distinct()
        table(listOf("Section", "Shape", "A (cm²)", "Iy (cm⁴)", "Iz (cm⁴)", "J (cm⁴)", "Asy (cm²)", "Asz (cm²)"), secs.map {
            listOf(it.name, it.shape, f(it.a * 1e4, 2), f(it.iy * 1e8, 1), f(it.iz * 1e8, 1), f(it.j * 1e8, 1), f(it.asy * 1e4, 2), f(it.asz * 1e4, 2))
        })
        append("<p class=\"meta\">Hollow-section properties are computed from nominal dimensions with sharp corners. ")
        append("FRP values are illustrative unless replaced with the manufacturer's tested data.</p>")

        append("<h2>3a. Members</h2>")
        table(listOf("Member", "Node i", "Node j", "L (m)", "Section", "Material", "A (cm²)", "Iy (cm⁴)", "Iz (cm⁴)", "End conditions"), model.members.map { m ->
            val l = (model.node(m.j).pos - model.node(m.i).pos).length
            listOf(
                "M${m.id}", "N${m.i}", "N${m.j}", f(l, 3), m.section.name, m.material.name,
                f(m.section.a * 1e4, 1), f(m.section.iy * 1e8, 0), f(m.section.iz * 1e8, 0),
                listOfNotNull(
                    m.endI?.let { k -> if (k == 0.0) "hinge i" else "spring i ${f(k, 0)} kN·m/rad" },
                    m.endJ?.let { k -> if (k == 0.0) "hinge j" else "spring j ${f(k, 0)} kN·m/rad" },
                    if (m.offsetI > 0) "offset i ${f(m.offsetI, 3)} m" else null,
                    if (m.offsetJ > 0) "offset j ${f(m.offsetJ, 3)} m" else null,
                ).joinToString(", ").ifEmpty { "rigid" },
            )
        })

        append("<h2>4. Loads (characteristic)</h2>")
        append("<p class=\"meta\">Self-weight: ").append(if (model.selfWeight) "included in DL (γ·A)" else "not included").append("</p>")
        if (model.nodalLoads.isNotEmpty()) {
            append("<b>Nodal loads</b>")
            table(listOf("Case", "Node", "Fx (kN)", "Fy (kN)", "Fz (kN)"), model.nodalLoads.map {
                listOf(it.case, "N${it.node}", f(it.values[0]), f(it.values[1]), f(it.values[2]))
            })
        }
        if (model.memberLoads.isNotEmpty()) {
            append("<b>Distributed member loads</b>")
            table(listOf("Case", "Member", "Direction", "w at start (kN/m)", "w at end (kN/m)", "from a (m)", "to b (m)"), model.memberLoads.map {
                listOf(it.case, "M${it.member}", it.dir.name.lowercase().replace('_', ' '), f(it.w), f(it.w2 ?: it.w),
                    it.a?.let { v -> f(v, 3) } ?: "0", it.b?.let { v -> f(v, 3) } ?: "L")
            })
        }
        if (model.pointLoads.isNotEmpty()) {
            append("<b>Concentrated member loads</b>")
            table(listOf("Case", "Member", "Direction", "P (kN)", "a from i (m)"), model.pointLoads.map {
                listOf(it.case, "M${it.member}", it.dir.name.lowercase().replace('_', ' '), f(it.p), f(it.a, 3))
            })
        }

        if (model.panels.isNotEmpty()) {
            append("<h2>4a. Surface panels (Q4 flat shell)</h2>")
            append("<p class=\"meta\">Membrane + Reissner–Mindlin plate, 2×2 integration with 1-point transverse shear and t²/h² stabilisation. ")
            append("One element per panel — refine with Panel mesh. Results at the centroid in panel axes.</p>")
            table(listOf("Panel", "Nodes", "t (mm)", "E (MPa)", "G (MPa)", "ν", "γ (kN/m³)", "Pressure (kN/m²)", "Case"), model.panels.map {
                listOf("P${it.id}", it.nodes.joinToString("-") { n -> "N$n" }, f(it.t * 1000, 0), f(it.e / 1000, 0), f(it.g / 1000, 0),
                    f(it.nu, 2), f(it.gamma, 1), f(it.pressure), it.case)
            })
        }

        append("<h2>5. Load combinations</h2>")
        table(listOf("Combination", "Factors"), combinations.map { c ->
            listOf(c.name, c.factors.entries.joinToString("  ") { (k, v) -> "$k × ${f(v)}" })
        })

        if (shownRes != null) {
            val (combo, r) = shown!!
            append("<h2>6. Results — ${esc(combo.name)}</h2>")
            val (nid, d) = r.maxDisplacement()
            val eq = r.equilibriumError
            append("<p>Maximum joint displacement: <b>${f(d * 1000, 3)} mm</b> at node N$nid. ")
            append("Maximum member deflection from chord: <b>${f(r.maxMemberDeflection() * 1000, 3)} mm</b>.<br>")
            append("Equilibrium check: ΣF + ΣR = (${f(eq[0], 4)}, ${f(eq[1], 4)}, ${f(eq[2], 4)}) kN ")
            append(if (eq.all { abs(it) < 1e-3 * (1 + abs(r.totalLoad[2])) }) "<span class=\"ok\">✔ satisfied</span>" else "<b>✘ check model</b>")
            append("</p><b>Support reactions</b>")
            table(listOf("Node", "Rx (kN)", "Ry (kN)", "Rz (kN)", "Mx (kN·m)", "My (kN·m)", "Mz (kN·m)"), r.reactions.map { (n, v) ->
                listOf("N$n") + v.map { f(it) }
            })
            append("<b>Node displacements</b>")
            table(listOf("Node", "ux (mm)", "uy (mm)", "uz (mm)", "φx (mrad)", "φy (mrad)", "φz (mrad)"), r.nodeDisplacements.map { (n, v) ->
                listOf("N$n") + v.map { f(it * 1000, 3) }
            })
            if (r.panelResults.isNotEmpty()) {
                append("<b>Panel results (centroid)</b>")
                table(listOf("Panel", "Area (m²)", "Mean normal defl. (mm)", "Nx", "Ny", "Nxy (kN/m)", "Mx", "My", "Mxy (kN·m/m)", "Max face stress (MPa)"),
                    r.panelResults.values.map {
                        listOf("P${it.id}", f(it.area, 3), f(it.meanNormalDeflection * 1000, 3), f(it.nx), f(it.ny), f(it.nxy),
                            f(it.mx), f(it.my), f(it.mxy), f(it.maxFaceStress, 3))
                    })
            }
            append("<b>Member end forces (local axes; N tension +, sagging My +)</b>")
            table(listOf("Member", "x (m)", "N (kN)", "Vy (kN)", "Vz (kN)", "Mt (kN·m)", "My (kN·m)", "Mz (kN·m)"), model.members.flatMap { m ->
                val st = r.internalForces(m.id, 2)
                listOf(st.first(), st.last()).map { s -> listOf("M${m.id}", f(s.s, 3), f(s.n), f(s.vy), f(s.vz), f(s.mt), f(s.my), f(s.mz)) }
            })
        }

        if (ulsEnvelope.isNotEmpty()) {
            append("<h2>7. Member envelope — IS 456 limit-state combinations</h2>")
            table(listOf("Member", "L (m)", "N at |N|max (kN)", "|Vz|max (kN)", "My,max + (kN·m)", "My,min − (kN·m)"), ulsEnvelope.map {
                listOf("M${it.member}", f(it.length, 3), f(it.nAbsMax), f(it.vzAbsMax), f(it.myMax), f(it.myMin))
            })
        }

        if (buckling != null) {
            append("<h2>8. Global buckling</h2>")
            append("<p>First elastic buckling multiplier <b>λcr = ${f(buckling.lambda, 4)}</b> for ${esc(buckling.combination)} ")
            append("(${buckling.segmentsPerMember} elements per member, ${buckling.iterations} iterations). ")
            if (buckling.lambda > 0 && buckling.lambda < 10) append("λcr &lt; 10: second-order (P-Δ) effects should be considered (IS 800 Cl. 4.4.2). ")
            append("This is not a design factor of safety.</p>")
        }

        if (modal != null && modal.modes.isNotEmpty()) {
            append("<h2>9. Modal analysis</h2>")
            append("<p>Consistent mass, ${modal.segmentsPerMember} elements per member; mass source: ${esc(modal.massSource)}. ")
            append("Total participating mass X ${f(modal.totalMass[0], 3)} t, Y ${f(modal.totalMass[1], 3)} t, Z ${f(modal.totalMass[2], 3)} t.</p>")
            table(listOf("Mode", "f (Hz)", "T (s)", "ω (rad/s)", "Mx (%)", "My (%)", "Mz (%)"), modal.modes.map {
                listOf("${it.number}", f(it.frequency, 3), f(it.period, 4), f(it.omega, 2),
                    f(it.participation[0] * 100, 1), f(it.participation[1] * 100, 1), f(it.participation[2] * 100, 1))
            })
            val cum = modal.cumulative
            append("<p>Cumulative effective mass: X ${f(cum[0] * 100, 1)} %, Y ${f(cum[1] * 100, 1)} %, Z ${f(cum[2] * 100, 1)} %. ")
            append("IS 1893 (Part 1):2016 Cl. 7.7.5.2 requires enough modes for at least 90 % in each direction considered.</p>")
        }

        if (seismic != null) {
            val sp = seismic.params
            append("<h2>10. Response spectrum analysis — IS 1893 (Part 1):2016</h2>")
            append("<p>Z = ${f(sp.z, 2)} (Table 3), I = ${f(sp.importance, 1)} (Table 8), R = ${f(sp.r, 1)} (Table 9), soil type ${sp.soil.name}, ")
            append("5 % damping; Ah = (Z/2)(Sa/g)/(R/I) (Cl. 6.4.2); CQC combination (Cl. 7.7.5.4); seismic weight DL + ${f(sp.llFactor * 100, 0)} % LL (Table 10). ")
            append("Height h = ${f(seismic.height, 2)} m. Each direction analysed separately.</p>")
            for (d in listOf(seismic.x, seismic.y)) {
                append("<b>${esc(d.name)}</b>")
                table(listOf("Mode", "T (s)", "Sa/g", "Ah", "Mass (%)", "Modal VB (kN)"), d.rows.map {
                    listOf("${it.mode}", f(it.period, 3), f(it.saG, 3), f(it.ah, 4), f(it.massRatio * 100, 1), f(it.baseShear, 2))
                })
                append("<p>W = ${f(d.weight, 1)} kN; Σ mass ${f(d.cumulativeMass * 100, 1)} %. VB (CQC) = ${f(d.baseShearRs, 2)} kN; ")
                append("Ta = ${f(d.ta, 3)} s (Cl. 7.6.2) → VB̄ = Ah·W = ${f(d.baseShearStatic, 2)} kN; scale ${f(d.scale, 3)} (Cl. 7.7.3) → ")
                append("<b>design VB = ${f(d.baseShear, 2)} kN</b>.</p>")
                if (d.drifts.isNotEmpty()) table(listOf("Level Z (m)", "Storey height (m)", "Drift ratio", "Limit 0.004 (Cl. 7.11.1)"), d.drifts.map {
                    listOf(f(it.z, 3), f(it.height, 3), f(it.drift, 5), if (it.ok) "✔ OK" else "✘ exceeds")
                })
            }
        }

        if (steel.isNotEmpty()) {
            append("<h2>11. Steel member checks — IS 800:2007</h2>")
            append("<p>Each steel member checked for every ULS combination; governing combination shown. Largest axial force, moments and shear along the member ")
            append("are combined (conservative). Effective length KL = L, LLT = L unless stated. Clauses: classification Table 2; tension Cl. 6; compression Cl. 7.1.2; ")
            append("bending Cl. 8.2 with LTB Cl. 8.2.2 / Annex E; shear Cl. 8.4; combined Cl. 9.3.1 / 9.3.2.2.</p>")
            table(listOf("Member", "Section", "Grade", "Governing", "P (kN, +C)", "Mz,maj (kN·m)", "My,min (kN·m)", "V (kN)", "η", "Status"), steel.map {
                listOf("M${it.member}", esc(it.section), it.grade, esc(it.combination), f(it.p), f(it.mMajor), f(it.mMinor), f(it.v), f(it.utilisation, 3), if (it.ok) "✔ OK" else "✘ REVISE")
            })
            steel.maxByOrNull { it.utilisation }?.let { g ->
                append("<p><b>Critical member M${g.member} (${esc(g.section)}), η = ${f(g.utilisation, 3)}:</b></p>")
                table(listOf("Check", "Value", "Requirement", "Status", "Clause"), g.result.checks.map {
                    listOf(esc(it.item), esc(it.value), esc(it.requirement), it.status.name, esc(it.clause))
                })
            }
        }

        append("<p class=\"meta\" style=\"margin-top:32px\">Generated by Structura AI · sign convention as RFEM: member local x from i to j, ")
        append("local z downward on beams (sagging My positive).</p>")
        append("</body></html>")
    }

    /** Isometric SVG of the model; deformed shape overlaid when results are given. */
    fun svg(model: FrameModel, r: FrameResults?, w: Int = 860, h: Int = 460): String {
        if (model.nodes.isEmpty()) return ""
        val cy = cos(Math.toRadians(-35.0)); val sy = sin(Math.toRadians(-35.0))
        val cp = cos(Math.toRadians(25.0)); val sp = sin(Math.toRadians(25.0))
        fun proj(p: Vec3): Pair<Double, Double> {
            val sx = p.x * cy + p.y * sy
            val depth = -p.x * sy + p.y * cy
            return sx to (p.z * cp + depth * sp)
        }
        val pts = model.nodes.map { proj(it.pos) }
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val s = 0.85 * minOf(w / max(maxX - minX, 1e-6), h / max(maxY - minY, 1e-6))
        val ox = w / 2.0 - (minX + maxX) / 2 * s
        val oy = h / 2.0 + (minY + maxY) / 2 * s
        fun xy(p: Vec3): String { val (a, b) = proj(p); return String.format(Locale.US, "%.1f,%.1f", ox + a * s, oy - b * s) }
        val sb = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $w $h\" font-family=\"Georgia,serif\" font-size=\"11\">")
        for (p in model.panels) {
            val poly = p.nodes.joinToString(" ") { xy(model.node(it).pos) }
            sb.append("<polygon points=\"$poly\" fill=\"#ddd\" fill-opacity=\"0.5\" stroke=\"#999\" stroke-width=\"0.8\"/>")
        }
        for (m in model.members) {
            val a = model.node(m.i).pos; val b = model.node(m.j).pos
            sb.append("<line x1=\"${xy(a).substringBefore(',')}\" y1=\"${xy(a).substringAfter(',')}\" x2=\"${xy(b).substringBefore(',')}\" y2=\"${xy(b).substringAfter(',')}\" stroke=\"#666\" stroke-width=\"1.5\"/>")
            val mid = (a + b) * 0.5
            sb.append("<text x=\"${xy(mid).substringBefore(',')}\" y=\"${xy(mid).substringAfter(',')}\" fill=\"#333\">M${m.id}</text>")
        }
        for (n in model.nodes) {
            val (x, y) = xy(n.pos).split(',')
            val sup = model.supports.any { it.node == n.id }
            sb.append(if (sup) "<rect x=\"${x.toDouble() - 5}\" y=\"${y.toDouble() - 5}\" width=\"10\" height=\"10\" fill=\"none\" stroke=\"#000\"/>" else "<circle cx=\"$x\" cy=\"$y\" r=\"2.5\"/>")
            sb.append("<text x=\"${x.toDouble() + 5}\" y=\"${y.toDouble() + 12}\" fill=\"#000\">N${n.id}</text>")
        }
        if (r != null) {
            val maxD = max(r.maxDisplacement().second, r.maxMemberDeflection())
            if (maxD > 1e-12) {
                val xs = model.nodes.map { it.x }; val ys = model.nodes.map { it.y }; val zs = model.nodes.map { it.z }
                val size = maxOf(xs.max() - xs.min(), ys.max() - ys.min(), zs.max() - zs.min()).coerceAtLeast(1.0)
                val k = 0.08 * size / maxD
                for (m in model.members) {
                    val poly = r.deformedShape(m.id, k, 12).joinToString(" ") { xy(it) }
                    sb.append("<polyline points=\"$poly\" fill=\"none\" stroke=\"#000\" stroke-width=\"2.2\"/>")
                }
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }
}
