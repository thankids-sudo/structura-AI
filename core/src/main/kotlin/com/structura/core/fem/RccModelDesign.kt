package com.structura.core.fem

import com.structura.core.codes.IS456
import com.structura.core.rcc.BeamDesigner
import com.structura.core.rcc.BeamInput
import com.structura.core.rcc.ColumnDesigner
import com.structura.core.rcc.ColumnInput
import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.Status
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Whole-model member design (IS 456:2000, IS 13920:2016 where seismic cases exist) from the analysis envelope.
 *
 * Beams (|dz| < 0.1L): sagging (+My, bottom bars) and hogging (−My, top bars) designed separately (Cl. 38.1,
 * Annex G), shear from the largest Vz (Cl. 40), deflection by l/d with the support condition taken from the
 * connectivity — cantilever / simply supported / continuous (Cl. 23.2.1), development length (Cl. 26.2.1),
 * side-face steel for D > 750 mm (Cl. 26.5.1.3), ductile proportions b ≥ 200 mm, b/D ≥ 0.3 (IS 13920 Cl. 6.1).
 * Columns (|dz| > 0.9L): Pu = largest compression, Mux / Muy from the envelope, biaxial (Cl. 39.6), slenderness
 * (Cl. 25), minimum eccentricity (Cl. 25.4), steel 0.8–4 % (Cl. 26.5.3.1); bars chosen from 4 to 20 bars of φ12–φ25.
 * Members neither vertical nor horizontal (raking) are designed as beam-columns with the column routine.
 */
object RccModelDesign {
    class MemberDesign(
        val member: Int, val kind: String, val utilisation: Double, val ok: Boolean, val summary: String,
        val b: Double, val d: Double, val span: Double, val mu: Double, val vu: Double, val pu: Double, val mux: Double, val muy: Double,
        val checks: List<DesignCheck> = emptyList(), val muHog: Double = 0.0, val support: String = "",
        val bottom: String = "", val top: String = "", val stirrups: String = "",
    )

    /** Envelope over combinations: +My, −My, |Mz|, |Vz|, |Vy|, max compression, max tension, |T|. */
    class Envelope(val myPos: Double, val myNeg: Double, val mz: Double, val vz: Double, val vy: Double, val comp: Double, val tens: Double, val mt: Double,
                   val govMy: String, val govV: String, val govN: String)

    fun envelope(memberId: Int, results: List<Pair<LoadCombination, FrameResults>>): Envelope {
        var myP = 0.0; var myN = 0.0; var mz = 0.0; var vz = 0.0; var vy = 0.0; var c = 0.0; var t = 0.0; var mt = 0.0
        var gM = ""; var gV = ""; var gN = ""
        for ((combo, r) in results) {
            val st = runCatching { r.internalForces(memberId, 16) }.getOrNull() ?: continue
            for (s in st) {
                if (s.my > myP) { myP = s.my; gM = combo.name }
                if (-s.my > myN) { myN = -s.my; if (myN > myP) gM = combo.name }
                mz = max(mz, abs(s.mz))
                if (abs(s.vz) > vz) { vz = abs(s.vz); gV = combo.name }
                vy = max(vy, abs(s.vy))
                if (-s.n > c) { c = -s.n; gN = combo.name }
                t = max(t, s.n); mt = max(mt, abs(s.mt))
            }
        }
        return Envelope(myP, myN, mz, vz, vy, c, t, mt, gM, gV, gN)
    }

    fun fck(mat: Material): Double? = if (mat.name.startsWith("M")) mat.name.drop(1).takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() else null
    fun isConcrete(m: Member) = fck(m.material) != null && m.section.b > 0 && m.section.h > 0 && m.section.shape == "RECT"

    /** Design bond stress τbd for deformed bars in tension (Cl. 26.2.1.1, +60 %). */
    fun tauBd(fck: Double) = 1.6 * when { fck >= 40 -> 1.9; fck >= 35 -> 1.7; fck >= 30 -> 1.5; fck >= 25 -> 1.4; else -> 1.2 }

    /** Support condition of a beam from its connectivity (for the l/d basic value). */
    fun beamSupport(model: FrameModel, mem: Member): IS456.Support {
        fun degree(n: Int) = model.members.count { it.id != mem.id && (it.i == n || it.j == n) } + (if (model.supports.any { it.node == n }) 1 else 0)
        val di = degree(mem.i); val dj = degree(mem.j)
        fun beamAt(n: Int) = model.members.any { o ->
            o.id != mem.id && (o.i == n || o.j == n) &&
                abs(model.node(o.i).z - model.node(o.j).z) < 0.1 * (model.node(o.j).pos - model.node(o.i).pos).length
        }
        return when {
            di == 0 || dj == 0 -> IS456.Support.CANTILEVER
            mem.hingeI || mem.hingeJ -> IS456.Support.SIMPLY_SUPPORTED
            beamAt(mem.i) || beamAt(mem.j) -> IS456.Support.CONTINUOUS
            else -> IS456.Support.SIMPLY_SUPPORTED
        }
    }

    fun design(model: FrameModel, results: List<Pair<LoadCombination, FrameResults>>, fy: Double = 500.0): List<MemberDesign> {
        val seismic = results.any { (c, _) -> c.factors.keys.any { it == LoadCase.EX || it == LoadCase.EY } }
        val out = ArrayList<MemberDesign>()
        for (mem in model.members.filter { isConcrete(it) }) runCatching { out += designMember(model, mem, envelope(mem.id, results), fy, seismic) }
        return out
    }

    fun designMember(model: FrameModel, mem: Member, e: Envelope, fy: Double = 500.0, seismic: Boolean = false): MemberDesign {
        val a = model.node(mem.i).pos; val bb = model.node(mem.j).pos
        val len = (bb - a).length
        val dz = abs(bb.z - a.z) / len.coerceAtLeast(1e-9)
        val b = mem.section.b * 1000; val d = mem.section.h * 1000
        val fc = fck(mem.material)!!
        val checks = ArrayList<DesignCheck>()
        if (dz >= 0.1) {
            // column / raking member: My (local, depth h) ↔ Mux (depth d), Mz ↔ Muy (depth b)
            val mux = max(e.myPos, e.myNeg); val muy = e.mz
            var best: Pair<ColumnInput, com.structura.core.rcc.ColumnResult>? = null
            search@ for (dia in listOf(12.0, 16.0, 20.0, 25.0)) for (n in 2..6) {
                val inp = ColumnInput(b = b, d = d, barDia = dia, nb = n, nd = n, fck = fc, fy = fy, pu = max(e.comp, 1.0), mux = mux, muy = muy,
                    unsupportedLength = len * 1000, tieDia = if (dia >= 25) 8.0 else 8.0)
                val res = runCatching { ColumnDesigner.design(inp) }.getOrNull() ?: continue
                best = inp to res
                if (res.isSafe) break@search
            }
            val (inp, res) = best ?: error("column design failed")
            checks += res.checks
            if (e.tens > 1.0) checks += DesignCheck("Axial tension", "${f1(e.tens)} kN", "column in tension in some combination — check tension lap / anchorage", Status.INFO, IS456.ref("Cl. 26.2.5"))
            if (seismic) {
                checks += DesignCheck("Ductile column size", "least ${minOf(b, d).toInt()} mm", "≥ 300 mm (IS 13920 Cl. 7.1.2)", if (minOf(b, d) >= 300) Status.PASS else Status.FAIL, "IS 13920:2016 Cl. 7.1.2")
                checks += DesignCheck("Strong column – weak beam", "ΣMc vs ΣMb", "ΣMc ≥ 1.4 ΣMb at each joint — verify with the joint moments", Status.INFO, "IS 13920:2016 Cl. 7.2.1")
            }
            val bars = 2 * inp.nb + 2 * inp.nd - 4
            val ok = checks.none { it.status == Status.FAIL }
            val txt = "$bars-φ${inp.barDia.toInt()}, ties φ${inp.tieDia.toInt()} @ ${res.tiePitch.toInt()}"
            return MemberDesign(mem.id, if (dz > 0.9) "Column" else "Raking", res.interaction, ok,
                "Column ${b.toInt()}×${d.toInt()} · Pu ${f0(e.comp)} kN, Mux ${f1(mux)}, Muy ${f1(muy)} kN·m → $txt · ${f2(res.interaction)} ${if (ok) "✔" else "✘"}",
                b, d, len, 0.0, 0.0, e.comp, mux, muy, checks, support = "", bottom = txt)
        }
        // ---- beam
        val sup = beamSupport(model, mem)
        val spanMm = len * 1000
        fun beam(mu: Double, vu: Double) = BeamDesigner.design(BeamInput(widthB = b, overallDepthD = d, effectiveSpan = spanMm, support = sup,
            fck = fc, fy = fy, factoredMoment = max(mu, 0.5), factoredShear = max(vu, 0.5)))
        val sag = beam(if (sup == IS456.Support.CANTILEVER) 0.5 else e.myPos, e.vz)
        val hog = if (e.myNeg > 0.5) beam(e.myNeg, e.vz) else null
        sag.checks.forEach { c -> checks += c.copy(item = (if (sup == IS456.Support.CANTILEVER) "" else "Sagging · ") + c.item) }
        hog?.checks?.filter { !it.item.startsWith("Deflection") && !it.item.startsWith("Concrete grade") && !it.item.startsWith("Nominal cover") }
            ?.forEach { c -> checks += c.copy(item = "Hogging · " + c.item) }
        val dia = 16.0
        val ld = dia * 0.87 * fy / (4 * tauBd(fc))
        checks += DesignCheck("Development length Ld", "${f0(ld)} mm (φ16)", "anchor bars ≥ Ld beyond the critical section; at simple supports M1/V + L0 ≥ Ld",
            Status.INFO, IS456.ref("Cl. 26.2.1, 26.2.3.3"))
        if (d > 750) checks += DesignCheck("Side-face reinforcement", "D = ${d.toInt()} mm", "0.1 % of web area on each face, spacing ≤ 300 mm",
            Status.INFO, IS456.ref("Cl. 26.5.1.3"))
        if (e.mt > 0.5) checks += DesignCheck("Torsion", "Tu = ${f1(e.mt)} kN·m", "design for Ve = Vu + 1.6Tu/b and Me1 = Mu + Mt (Cl. 41) in the beam designer",
            Status.INFO, IS456.ref("Cl. 41.3, 41.4"))
        if (seismic) {
            checks += DesignCheck("Ductile beam width", "b = ${b.toInt()} mm, b/D = ${f2(b / d)}", "b ≥ 200 mm, b/D ≥ 0.3",
                if (b >= 200 && b / d >= 0.3) Status.PASS else Status.FAIL, "IS 13920:2016 Cl. 6.1.1, 6.1.2")
            checks += DesignCheck("Ductile top / bottom steel", "bottom ≥ ½ top at the joint face", "positive steel at a joint face ≥ 50 % of the negative steel",
                Status.INFO, "IS 13920:2016 Cl. 6.2.3")
        }
        val ok = checks.none { it.status == Status.FAIL }
        val u = max(if (sag.muLim > 0) e.myPos / sag.muLim else 0.0, if (hog != null && hog.muLim > 0) e.myNeg / hog.muLim else 0.0)
        val stir = (listOfNotNull(sag.stirrupSpacing, hog?.stirrupSpacing).minOrNull())?.let { "φ8 @ ${it.toInt()}" } ?: "shear fails"
        val bottom = if (sup == IS456.Support.CANTILEVER) "nominal 2-φ12" else sag.tensionBars.toString()
        val top = hog?.tensionBars?.toString() ?: "2-12 φ (hanger)"
        return MemberDesign(mem.id, "Beam", u, ok,
            "Beam ${b.toInt()}×${d.toInt()} (${sup.label.lowercase()}) · M+ ${f1(e.myPos)} / M− ${f1(e.myNeg)} kN·m, V ${f1(e.vz)} kN → bottom $bottom, top $top, $stir ${if (ok) "✔" else "✘"}",
            b, d, len, e.myPos, e.vz, 0.0, 0.0, 0.0, checks, e.myNeg, sup.label, bottom, top, stir)
    }

    private fun f0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
