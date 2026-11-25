package com.structura.core.steel

import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameResults
import com.structura.core.fem.LoadCombination
import com.structura.core.fem.Material
import com.structura.core.fem.Section
import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.Status
import kotlin.math.abs

/** Bridges the FEM model and IS 800 member checks (RFEM "steel design add-on" style). */
object SteelModelCheck {

    /** FEM section (m) from a steel section (mm). FEM local z = depth (major axis bending = FEM My). */
    fun femSection(s: SteelSection): Section {
        val shape = when (s.family) {
            SteelSection.Family.I, SteelSection.Family.WELDED_I -> "I"
            SteelSection.Family.CHANNEL -> "CHANNEL"
            SteelSection.Family.SHS -> "SHS"; SteelSection.Family.RHS -> "RHS"; SteelSection.Family.CHS -> "CHS"
        }
        val asy = when (s.family) {
            SteelSection.Family.I, SteelSection.Family.WELDED_I, SteelSection.Family.CHANNEL -> 2 * s.width * s.tf * 5.0 / 6.0
            else -> s.area - s.avZ
        }
        return Section(s.name, s.area * 1e-6, s.izz * 1e-12, s.iyy * 1e-12, s.j * 1e-12, s.width / 1000, s.depth / 1000,
            asy * 1e-6, s.avZ * 1e-6, shape, s.tf / 1000, s.tw / 1000)
    }

    /** Steel section (mm) back from a FEM section, or null if it is not a steel shape we can check. */
    fun steelSection(f: Section): SteelSection? {
        SteelLibrary.find(f.name)?.let { lib -> if (kotlin.math.abs(lib.depth / 1000 - f.h) < 1e-6) return lib }   // tabulated properties
        val h = f.h * 1000; val b = f.b * 1000; val t = f.t * 1000
        return when (f.shape) {
            "I" -> SteelSection.iSection(f.name, h, b, t, f.tw * 1000)
            "CHANNEL" -> SteelSection.channel(f.name, h, b, t, f.tw * 1000)
            "SHS", "RHS" -> if (t > 0) SteelSection.rhs(f.name, h, b, t) else null
            "CHS" -> if (t > 0) SteelSection.chs(f.name, h, t) else null
            else -> null
        }
    }

    fun isSteel(m: Material) = m.gamma in 70.0..85.0

    /** Grade from the material name ("E250", "E350"…), default E250. */
    fun grade(m: Material): SteelGrade = when {
        m.name.contains("350") -> SteelGrade.E350
        m.name.contains("310") -> SteelGrade.YST310
        else -> SteelGrade.E250
    }

    class MemberCheck(
        val member: Int, val section: String, val grade: String, val combination: String,
        val utilisation: Double, val p: Double, val mMajor: Double, val mMinor: Double, val v: Double,
        val result: SteelResult,
    ) { val ok: Boolean get() = utilisation <= 1.0 && result.isSafe }

    /**
     * Checks every steel member for every combination. Effective lengths: K·L with K = [k] about both axes
     * (member length between nodes), unbraced length for LTB = L·[lltFactor] (0 = laterally restrained).
     * Forces per combination: largest compression (or tension), largest |M| about each axis and largest |V|
     * along the member — combined conservatively even when they occur at different sections.
     */
    fun check(model: FrameModel, results: List<Pair<LoadCombination, FrameResults>>, k: Double = 1.0, lltFactor: Double = 1.0): List<MemberCheck> {
        val out = ArrayList<MemberCheck>()
        for (m in model.members) {
            if (!isSteel(m.material)) continue
            val s = steelSection(m.section) ?: continue
            val g = grade(m.material)
            val l = (model.node(m.j).pos - model.node(m.i).pos).length * 1000
            var best: MemberCheck? = null
            for ((c, r) in results) {
                val st = r.internalForces(m.id, 20)
                val comp = st.maxOf { -it.n }.coerceAtLeast(0.0)
                val tens = st.maxOf { it.n }.coerceAtLeast(0.0)
                val mMaj = st.maxOf { abs(it.my) }
                val mMin = st.maxOf { abs(it.mz) }
                val v = maxOf(st.maxOf { abs(it.vz) }, 0.0)
                val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
                val main: SteelResult = if (comp >= tens) {
                    IS800.beamColumn(s, g, comp, mMaj, mMin, k * l, k * l, l * lltFactor)
                } else {
                    val td = IS800.tension(s, g, tens, length = l)
                    val bc = IS800.bendingCapacity(s, g, l * lltFactor)
                    val mdy = s.zpy * g.fy / IS800.GM0 / 1e6
                    val u = tens / td.capacity + mMaj / bc.md + mMin / mdy
                    steps += td.steps
                    checks += td.checks.filter { it.item != "Tension" }
                    checks += DesignCheck("Tension + bending", "%.3f".format(u), "T/Td + Mz/Mdz + My/Mdy ≤ 1", if (u <= 1) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 9.3.1.3")
                    SteelResult(u, u, checks.toList(), steps.toList())
                }
                val vd = IS800.bendingCapacity(s, g, 0.0).vd
                val shear = DesignCheck("Shear", "V = ${"%.2f".format(v)} kN", "≤ Vd = ${"%.2f".format(vd)} kN", if (v <= vd) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 8.4")
                val (cls, why) = IS800.classifyBending(s, g.fy)
                val clsCheck = DesignCheck("Section class", cls.name.lowercase().replace('_', '-'), why, if (cls == SectionClass.SLENDER) Status.FAIL else Status.PASS, "IS 800:2007 Table 2")
                val res = SteelResult(main.capacity, maxOf(main.utilisation, v / vd), listOf(clsCheck) + main.checks + shear,
                    listOf("Combination ${c.name}: P = ${"%.2f".format(if (comp >= tens) comp else -tens)} kN (compression +), Mz,major = ${"%.2f".format(mMaj)}, My,minor = ${"%.2f".format(mMin)} kN·m, V = ${"%.2f".format(v)} kN; L = ${"%.0f".format(l)} mm, KL = ${"%.0f".format(k * l)} mm, LLT = ${"%.0f".format(l * lltFactor)} mm") + main.steps)
                val mc = MemberCheck(m.id, s.name, g.name, c.name, res.utilisation, if (comp >= tens) comp else -tens, mMaj, mMin, v, res)
                if (best == null || mc.utilisation > best.utilisation) best = mc
            }
            best?.let { out += it }
        }
        return out
    }
}
