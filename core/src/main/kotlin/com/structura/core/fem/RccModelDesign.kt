package com.structura.core.fem

import com.structura.core.rcc.BeamDesigner
import com.structura.core.rcc.BeamInput
import com.structura.core.rcc.ColumnDesigner
import com.structura.core.rcc.ColumnInput
import kotlin.math.abs
import kotlin.math.max

/**
 * Whole-model member design from the analysis: every concrete member with a rectangular section is designed
 * from its envelope over the IS 456 combinations — vertical members as columns (IS 456 Cl. 39.6 biaxial,
 * Cl. 25 slenderness, bars chosen from 4 up to 20 bars of φ12–φ25), the rest as beams (Cl. 38.1 flexure on
 * the larger |My|, Cl. 40 shear). Steel members are left to [com.structura.core.steel.SteelModelCheck].
 */
object RccModelDesign {
    class MemberDesign(
        val member: Int, val kind: String, val utilisation: Double, val ok: Boolean, val summary: String,
        val b: Double, val d: Double, val span: Double, val mu: Double, val vu: Double, val pu: Double, val mux: Double, val muy: Double,
    )

    fun fck(mat: Material): Double? = if (mat.name.startsWith("M")) mat.name.drop(1).takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() else null
    fun isConcrete(m: Member) = fck(m.material) != null && m.section.b > 0 && m.section.h > 0 && m.section.shape == "RECT"

    fun design(model: FrameModel, results: List<Pair<LoadCombination, FrameResults>>, fy: Double = 500.0): List<MemberDesign> {
        val out = ArrayList<MemberDesign>()
        for (mem in model.members.filter { isConcrete(it) }) {
            var my = 0.0; var mz = 0.0; var vz = 0.0; var vy = 0.0; var nc = 0.0
            for ((_, r) in results) {
                val st = runCatching { r.internalForces(mem.id, 12) }.getOrNull() ?: continue
                for (s in st) { my = max(my, abs(s.my)); mz = max(mz, abs(s.mz)); vz = max(vz, abs(s.vz)); vy = max(vy, abs(s.vy)); nc = max(nc, abs(s.n)) }
            }
            val a = model.node(mem.i).pos; val bb = model.node(mem.j).pos
            val len = (bb - a).length
            val vertical = abs(bb.z - a.z) / len.coerceAtLeast(1e-9) > 0.9
            val b = mem.section.b * 1000; val d = mem.section.h * 1000
            val fc = fck(mem.material)!!
            if (vertical) {
                var best: Pair<ColumnInput, com.structura.core.rcc.ColumnResult>? = null
                search@ for (dia in listOf(12.0, 16.0, 20.0, 25.0)) for (n in 2..6) {
                    val inp = ColumnInput(b = b, d = d, barDia = dia, nb = n, nd = n, fck = fc, fy = fy, pu = max(nc, 1.0), mux = my, muy = mz,
                        unsupportedLength = len * 1000)
                    val res = runCatching { ColumnDesigner.design(inp) }.getOrNull() ?: continue
                    best = inp to res
                    if (res.isSafe) break@search
                }
                val (inp, res) = best ?: continue
                val bars = 2 * inp.nb + 2 * inp.nd - 4
                out += MemberDesign(mem.id, "Column", res.interaction, res.isSafe,
                    "Column ${b.toInt()}×${d.toInt()}: Pu ${"%.0f".format(nc)} kN, Mux ${"%.1f".format(my)}, Muy ${"%.1f".format(mz)} kN·m → $bars-φ${inp.barDia.toInt()}, ties φ8 @ ${res.tiePitch.toInt()} · interaction ${"%.2f".format(res.interaction)} ${if (res.isSafe) "✔" else "✘"}",
                    b, d, len, 0.0, 0.0, nc, my, mz)
            } else {
                val inp = BeamInput(widthB = b, overallDepthD = d, effectiveSpan = len * 1000, fck = fc, fy = fy, factoredMoment = max(my, 0.1), factoredShear = max(vz, 0.1))
                val res = runCatching { BeamDesigner.design(inp) }.getOrNull() ?: continue
                val u = if (res.muLim > 0) my / res.muLim else 0.0
                out += MemberDesign(mem.id, "Beam", u, res.isSafe,
                    "Beam ${b.toInt()}×${d.toInt()}: Mu ${"%.1f".format(my)} kN·m, Vu ${"%.1f".format(vz)} kN → ${res.tensionBars}" +
                        (res.compressionBars?.let { " + $it top" } ?: "") + (res.stirrupSpacing?.let { ", φ8 @ ${it.toInt()}" } ?: ", shear fails") +
                        " · Mu/Mu,lim ${"%.2f".format(u)} ${if (res.isSafe) "✔" else "✘"}",
                    b, d, len, my, vz, 0.0, 0.0, 0.0)
            }
        }
        return out
    }
}
