package com.structura.core.steel

import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.Status
import com.structura.core.steel.SteelSection.Family
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

/** Steel grade (IS 2062): yield fy and ultimate fu, MPa. */
data class SteelGrade(val name: String, val fy: Double, val fu: Double) {
    companion object {
        val E250 = SteelGrade("E250 (IS 2062)", 250.0, 410.0)
        val E350 = SteelGrade("E350 (IS 2062)", 350.0, 490.0)
        val YST310 = SteelGrade("YSt 310 tubes (IS 4923)", 310.0, 450.0)
        val all = listOf(E250, E350, YST310)
    }
}

enum class SectionClass { PLASTIC, COMPACT, SEMI_COMPACT, SLENDER }

/** One IS 800 result: governing capacity, utilisation and the clause-referenced check sheet. */
class SteelResult(val capacity: Double, val utilisation: Double, val checks: List<DesignCheck>, val steps: List<String>) {
    val isSafe: Boolean get() = checks.none { it.status == Status.FAIL }
}

/**
 * IS 800:2007 limit state design of steel members and connections.
 * γm0 = 1.10, γm1 = 1.25 (Table 5); E = 2×10⁵ MPa (Cl. 2.2.4.1).
 */
object IS800 {
    const val E = 2.0e5
    const val GM0 = 1.10
    const val GM1 = 1.25
    const val GMB = 1.25

    private fun ref(c: String) = "IS 800:2007 $c"
    private fun f1(v: Double) = "%.1f".format(v)
    private fun f2(v: Double) = "%.2f".format(v)
    private fun f3(v: Double) = "%.3f".format(v)

    fun eps(fy: Double) = sqrt(250.0 / fy)

    // ------------------------------------------------------------ classification (Table 2)
    fun classifyBending(s: SteelSection, fy: Double): Pair<SectionClass, String> {
        val e = eps(fy)
        fun cls(r: Double, p: Double, c: Double, sc: Double) = when { r <= p * e -> SectionClass.PLASTIC; r <= c * e -> SectionClass.COMPACT; r <= sc * e -> SectionClass.SEMI_COMPACT; else -> SectionClass.SLENDER }
        return when (s.family) {
            Family.I, Family.WELDED_I, Family.CHANNEL -> {
                val b = if (s.family == Family.CHANNEL) s.width else s.width / 2
                val rf = b / s.tf
                val rw = (s.depth - 2 * s.tf) / s.tw
                val cf = if (s.family == Family.WELDED_I) cls(rf, 8.4, 9.4, 13.6) else cls(rf, 9.4, 10.5, 15.7)
                val cw = cls(rw, 84.0, 105.0, 126.0)
                maxOf(cf, cw) to "flange b/tf = ${f2(rf)}, web d/tw = ${f2(rw)}, ε = ${f3(e)}"
            }
            Family.SHS, Family.RHS -> {
                val rf = (s.width - 3 * s.tf) / s.tf
                val rw = (s.depth - 3 * s.tw) / s.tw
                maxOf(cls(rf, 29.3, 33.5, 42.0), cls(rw, 84.0, 105.0, 126.0)) to "flange b/t = ${f2(rf)}, web d/t = ${f2(rw)}, ε = ${f3(e)}"
            }
            Family.CHS -> {
                val r = s.depth / s.tf
                (when { r <= 42 * e * e -> SectionClass.PLASTIC; r <= 52 * e * e -> SectionClass.COMPACT; r <= 146 * e * e -> SectionClass.SEMI_COMPACT; else -> SectionClass.SLENDER }) to "D/t = ${f2(r)}, ε² = ${f3(e * e)}"
            }
        }
    }

    /** Axial compression: web/wall slenderness limits for semi-compact (Table 2). */
    fun slenderInCompression(s: SteelSection, fy: Double): Boolean {
        val e = eps(fy)
        return when (s.family) {
            Family.I, Family.WELDED_I, Family.CHANNEL -> (s.depth - 2 * s.tf) / s.tw > 42 * e || (if (s.family == Family.CHANNEL) s.width else s.width / 2) / s.tf > 15.7 * e
            Family.SHS, Family.RHS -> (s.width - 3 * s.tf) / s.tf > 42 * e
            Family.CHS -> s.depth / s.tf > 88 * e * e
        }
    }

    // ------------------------------------------------------------ compression (Cl. 7.1.2, Table 10)
    /** Imperfection factor α for buckling about z (major) and y (minor). */
    fun alpha(s: SteelSection): Pair<Double, Double> = when (s.family) {
        Family.I -> if (s.depth / s.width > 1.2) 0.21 to 0.34 else 0.34 to 0.49
        Family.WELDED_I -> 0.34 to 0.49
        Family.SHS, Family.RHS, Family.CHS -> 0.21 to 0.21
        Family.CHANNEL -> 0.49 to 0.49
    }

    /** Design compressive stress fcd (MPa) for slenderness KL/r, Cl. 7.1.2.1. */
    fun fcd(klr: Double, fy: Double, alpha: Double): Double {
        val lam = sqrt(fy * klr * klr / (PI * PI * E))
        val phi = 0.5 * (1 + alpha * (lam - 0.2) + lam * lam)
        return minOf((fy / GM0) / (phi + sqrt(phi * phi - lam * lam)), fy / GM0)
    }

    fun nonDimSlenderness(klr: Double, fy: Double) = sqrt(fy * klr * klr / (PI * PI * E))

    fun compression(s: SteelSection, g: SteelGrade, p: Double, klz: Double, kly: Double): SteelResult {
        val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
        val (az, ay) = alpha(s)
        val sz = klz / s.rz; val sy = kly / s.ry
        val fz = fcd(sz, g.fy, az); val fyy = fcd(sy, g.fy, ay)
        val pdz = s.area * fz / 1e3; val pdy = s.area * fyy / 1e3
        val pd = minOf(pdz, pdy)
        steps += "KL/r: z–z ${f1(sz)} (α ${az}), y–y ${f1(sy)} (α ${ay}) → fcd = ${f1(fz)} / ${f1(fyy)} MPa (Cl. 7.1.2.1, Table 10)"
        steps += "Pd = A·fcd = ${f1(s.area)} × ${f1(minOf(fz, fyy))} = ${f1(pd)} kN"
        if (slenderInCompression(s, g.fy)) checks += DesignCheck("Section class (axial)", "slender", "effective area method (Cl. 7.3.2) not covered — choose a stockier section", Status.FAIL, ref("Table 2"))
        checks += DesignCheck("Slenderness KL/r", f1(maxOf(sz, sy)), "≤ 180 (members carrying dead + imposed load)", if (maxOf(sz, sy) <= 180) Status.PASS else Status.FAIL, ref("Table 3"))
        checks += DesignCheck("Axial compression", "P = ${f1(p)} kN", "≤ Pd = ${f1(pd)} kN", if (p <= pd) Status.PASS else Status.FAIL, ref("Cl. 7.1.2"))
        return SteelResult(pd, p / pd, checks, steps)
    }

    // ------------------------------------------------------------ tension (Cl. 6)
    fun tension(s: SteelSection, g: SteelGrade, t: Double, holes: Int = 0, holeDia: Double = 18.0, holeThickness: Double = s.tf, length: Double = 3000.0): SteelResult {
        val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
        val tdg = s.area * g.fy / GM0 / 1e3
        val an = s.area - holes * holeDia * holeThickness
        val tdn = 0.9 * an * g.fu / GM1 / 1e3
        val td = minOf(tdg, tdn)
        steps += "Tdg = Ag·fy/γm0 = ${f1(tdg)} kN (Cl. 6.2); An = ${f1(an)} mm² → Tdn = 0.9·An·fu/γm1 = ${f1(tdn)} kN (Cl. 6.3.1)"
        val lr = length / minOf(s.rz, s.ry)
        checks += DesignCheck("Slenderness L/r", f1(lr), "≤ 400 (tension member)", if (lr <= 400) Status.PASS else Status.FAIL, ref("Table 3"))
        checks += DesignCheck("Tension", "T = ${f1(t)} kN", "≤ Td = ${f1(td)} kN", if (t <= td) Status.PASS else Status.FAIL, ref("Cl. 6.1–6.3"))
        checks += DesignCheck("Block shear", "not computed", "check at the end connection (Cl. 6.4)", Status.INFO, ref("Cl. 6.4"))
        return SteelResult(td, t / td, checks, steps)
    }

    // ------------------------------------------------------------ bending (Cl. 8)
    class BendingCapacity(val md: Double, val mdSupported: Double, val vd: Double, val chiLT: Double, val lambdaLT: Double, val cls: SectionClass, val mcr: Double)

    /** Design bending strength about z (kN·m) for an unbraced length [llt] (mm; 0 = laterally supported). */
    fun bendingCapacity(s: SteelSection, g: SteelGrade, llt: Double, cantilever: Boolean = false): BendingCapacity {
        val (cls, _) = classifyBending(s, g.fy)
        val betaB = if (cls == SectionClass.PLASTIC || cls == SectionClass.COMPACT) 1.0 else s.zez / s.zpz
        val limit = (if (cantilever) 1.5 else 1.2) * s.zez * g.fy / GM0
        val mdSup = minOf(betaB * s.zpz * g.fy / GM0, limit) / 1e6
        val vd = s.avZ * g.fy / (sqrt(3.0) * GM0) / 1e3
        val open = s.family == Family.I || s.family == Family.WELDED_I || s.family == Family.CHANNEL
        if (llt <= 0 || !open) return BendingCapacity(mdSup, mdSup, vd, 1.0, 0.0, cls, Double.POSITIVE_INFINITY)
        val hf = s.depth - s.tf
        val mcr = PI * PI * E * s.iyy * hf / (2 * llt * llt) * sqrt(1 + ((llt / s.ry) / (hf / s.tf)).pow(2) / 20)
        val lamLT = sqrt(betaB * s.zpz * g.fy / mcr)
        val aLT = if (s.family == Family.WELDED_I) 0.49 else 0.21
        val phi = 0.5 * (1 + aLT * (lamLT - 0.2) + lamLT * lamLT)
        val chi = minOf(1.0 / (phi + sqrt(phi * phi - lamLT * lamLT)), 1.0)
        val md = minOf(betaB * s.zpz * chi * g.fy / GM0 / 1e6, mdSup)
        return BendingCapacity(md, mdSup, vd, chi, lamLT, cls, mcr / 1e6)
    }

    /**
     * Beam check: factored moment [mu] (kN·m), shear [vu] (kN), span [span] (mm), unbraced length [llt] (mm, 0 = fully
     * restrained), service UDL [wService] (kN/m) for deflection with limit span/[deflLimit].
     */
    fun beam(s: SteelSection, g: SteelGrade, mu: Double, vu: Double, span: Double, llt: Double, wService: Double = 0.0,
             deflLimit: Double = 300.0, cantilever: Boolean = false): SteelResult {
        val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
        val (cls, why) = classifyBending(s, g.fy)
        checks += DesignCheck("Section class", cls.name.lowercase().replace('_', '-'), why, if (cls == SectionClass.SLENDER) Status.FAIL else Status.PASS, ref("Table 2"))
        val bc = bendingCapacity(s, g, llt, cantilever)
        steps += "Laterally supported Md = βb·Zp·fy/γm0 ≤ ${if (cantilever) "1.5" else "1.2"}·Ze·fy/γm0 = ${f2(bc.mdSupported)} kN·m (Cl. 8.2.1.2)"
        if (llt > 0 && bc.mcr.isFinite()) steps += "LTB: Mcr = ${f2(bc.mcr)} kN·m (Annex E simplified), λLT = ${f3(bc.lambdaLT)}, χLT = ${f3(bc.chiLT)} → Md = ${f2(bc.md)} kN·m (Cl. 8.2.2)"
        // shear
        val vd = bc.vd
        steps += "Vd = Av·fy/(√3·γm0) = ${f1(s.avZ)} × ${f1(g.fy)}/(√3 × 1.10) = ${f2(vd)} kN (Cl. 8.4)"
        checks += DesignCheck("Shear", "V = ${f2(vu)} kN", "≤ Vd = ${f2(vd)} kN", if (vu <= vd) Status.PASS else Status.FAIL, ref("Cl. 8.4.1"))
        var md = bc.md
        if (vu > 0.6 * vd) {
            val beta = (2 * vu / vd - 1).pow(2)
            val mfd = if (s.family == Family.I || s.family == Family.WELDED_I || s.family == Family.CHANNEL) s.width * s.tf * (s.depth - s.tf) * g.fy / GM0 / 1e6 else 0.0
            md = maxOf(md - beta * (md - mfd), 0.0)
            steps += "High shear (V > 0.6Vd): β = ${f3(beta)}, Mfd = ${f2(mfd)} → Mdv = ${f2(md)} kN·m (Cl. 9.2.2)"
        }
        checks += DesignCheck("Bending", "M = ${f2(mu)} kN·m", "≤ Md = ${f2(md)} kN·m", if (mu <= md) Status.PASS else Status.FAIL,
            ref(if (llt > 0) "Cl. 8.2.2" else "Cl. 8.2.1"))
        if (s.family == Family.I || s.family == Family.WELDED_I || s.family == Family.CHANNEL) {
            val dw = (s.depth - 2 * s.tf) / s.tw
            checks += DesignCheck("Web shear buckling", "d/tw = ${f1(dw)}", "≤ 67ε = ${f1(67 * eps(g.fy))} (no stiffeners needed)",
                if (dw <= 67 * eps(g.fy)) Status.PASS else Status.FAIL, ref("Cl. 8.4.2.1"))
        }
        if (wService > 0) {
            val l = span
            val delta = if (cantilever) wService * l.pow(4) / (8 * E * s.izz) else 5 * wService * l.pow(4) / (384 * E * s.izz)
            val allow = l / (if (cantilever) deflLimit / 2 else deflLimit)
            checks += DesignCheck("Deflection (service)", "${f2(delta)} mm", "≤ span/${if (cantilever) (deflLimit / 2).toInt() else deflLimit.toInt()} = ${f2(allow)} mm",
                if (delta <= allow) Status.PASS else Status.FAIL, ref("Cl. 5.6.1, Table 6"))
        }
        return SteelResult(md, maxOf(mu / md, vu / vd), checks, steps)
    }

    // ------------------------------------------------------------ combined (Cl. 9.3)
    fun beamColumn(s: SteelSection, g: SteelGrade, p: Double, mz: Double, my: Double, klz: Double, kly: Double, llt: Double,
                   cmz: Double = 0.9, cmy: Double = 0.9, cmLT: Double = 0.9): SteelResult {
        val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
        val (az, ay) = alpha(s)
        val lz = nonDimSlenderness(klz / s.rz, g.fy); val ly = nonDimSlenderness(kly / s.ry, g.fy)
        val pdz = s.area * fcd(klz / s.rz, g.fy, az) / 1e3
        val pdy = s.area * fcd(kly / s.ry, g.fy, ay) / 1e3
        val bc = bendingCapacity(s, g, llt)
        val mdz = bc.md
        val (cls, _) = classifyBending(s, g.fy)
        val mdy = (if (cls == SectionClass.PLASTIC || cls == SectionClass.COMPACT) s.zpy else s.zey) * g.fy / GM0 / 1e6
        val nd = s.area * g.fy / GM0 / 1e3
        val ny = p / pdy; val nz = p / pdz
        val ky = minOf(1 + (ly - 0.2) * ny, 1 + 0.8 * ny)
        val kz = minOf(1 + (lz - 0.2) * nz, 1 + 0.8 * nz)
        val klt = maxOf(1 - 0.1 * bc.lambdaLT * ny / (cmLT - 0.25), 1 - 0.1 * ny / (cmLT - 0.25))
        val sec = p / nd + mz / bc.mdSupported + my / mdy
        val e1 = p / pdy + ky * cmy * my / mdy + klt * mz / mdz
        val e2 = p / pdz + 0.6 * ky * cmy * my / mdy + kz * cmz * mz / mdz
        steps += "Pdz = ${f1(pdz)}, Pdy = ${f1(pdy)}, Nd = ${f1(nd)} kN; Mdz = ${f2(mdz)} (LTB), Mdy = ${f2(mdy)} kN·m"
        steps += "Ky = ${f3(ky)}, Kz = ${f3(kz)}, KLT = ${f3(klt)} (Cl. 9.3.2.2)"
        checks += DesignCheck("Section strength", f3(sec), "N/Nd + Mz/Mdz + My/Mdy ≤ 1", if (sec <= 1) Status.PASS else Status.FAIL, ref("Cl. 9.3.1.3"))
        checks += DesignCheck("Member buckling (a)", f3(e1), "P/Pdy + Ky·Cmy·My/Mdy + KLT·Mz/Mdz ≤ 1", if (e1 <= 1) Status.PASS else Status.FAIL, ref("Cl. 9.3.2.2"))
        checks += DesignCheck("Member buckling (b)", f3(e2), "P/Pdz + 0.6·Ky·Cmy·My/Mdy + Kz·Cmz·Mz/Mdz ≤ 1", if (e2 <= 1) Status.PASS else Status.FAIL, ref("Cl. 9.3.2.2"))
        if (slenderInCompression(s, g.fy)) checks += DesignCheck("Section class (axial)", "slender", "not covered", Status.FAIL, ref("Table 2"))
        val u = maxOf(sec, e1, e2)
        return SteelResult(u, u, checks, steps)
    }

    // ------------------------------------------------------------ bolts (Cl. 10.3)
    data class Bolt(val dia: Double, val fub: Double, val grade: String) {
        val asb: Double get() = PI * dia * dia / 4
        val anb: Double get() = 0.78 * asb
        val holeDia: Double get() = dia + if (dia <= 14) 1.0 else if (dia <= 24) 2.0 else 3.0
        companion object {
            fun g46(d: Double) = Bolt(d, 400.0, "4.6")
            fun g88(d: Double) = Bolt(d, 800.0, "8.8")
        }
    }

    /** Bearing-type bolt group in shear: force [v] kN, plate thickness [t], [nn] shear planes through threads, [ns] through shank. */
    fun boltGroup(bolt: Bolt, plateFu: Double, t: Double, v: Double, e: Double, pitch: Double, nn: Int = 1, ns: Int = 0): SteelResult {
        val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
        val d0 = bolt.holeDia
        val vdsb = bolt.fub * (nn * bolt.anb + ns * bolt.asb) / (sqrt(3.0) * GMB) / 1e3
        val kb = minOf(e / (3 * d0), pitch / (3 * d0) - 0.25, bolt.fub / plateFu, 1.0)
        val vdpb = 2.5 * kb * bolt.dia * t * plateFu / GMB / 1e3
        val value = minOf(vdsb, vdpb)
        val n = maxOf(ceil(v / value).toInt(), 2)
        steps += "M${bolt.dia.toInt()} grade ${bolt.grade}: Vdsb = fub(nn·Anb + ns·Asb)/(√3·γmb) = ${f2(vdsb)} kN (Cl. 10.3.3)"
        steps += "kb = ${f3(kb)} → Vdpb = 2.5·kb·d·t·fu/γmb = ${f2(vdpb)} kN (Cl. 10.3.4); bolt value ${f2(value)} kN → $n bolts for ${f1(v)} kN"
        checks += DesignCheck("Pitch", "${f1(pitch)} mm", "≥ 2.5d = ${f1(2.5 * bolt.dia)} mm", if (pitch >= 2.5 * bolt.dia) Status.PASS else Status.FAIL, ref("Cl. 10.2.2"))
        checks += DesignCheck("End / edge distance", "${f1(e)} mm", "≥ 1.5·d0 = ${f1(1.5 * d0)} mm (machine-cut edge)", if (e >= 1.5 * d0) Status.PASS else Status.FAIL, ref("Cl. 10.2.4.2"))
        checks += DesignCheck("Bolt group", "$n bolts × ${f2(value)} kN = ${f1(n * value)} kN", "≥ V = ${f1(v)} kN", Status.PASS, ref("Cl. 10.3.2"))
        if (n > 1 && (n - 1) * pitch > 15 * bolt.dia) checks += DesignCheck("Long joint", "lj = ${f1((n - 1) * pitch)} mm", "> 15d → apply βlj (Cl. 10.3.3.1)", Status.INFO, ref("Cl. 10.3.3.1"))
        return SteelResult(value, v / (n * value), checks, steps)
    }

    // ------------------------------------------------------------ fillet welds (Cl. 10.5)
    fun filletWeld(size: Double, force: Double, fu: Double, thinner: Double, thicker: Double, shop: Boolean = true): SteelResult {
        val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
        val gmw = if (shop) 1.25 else 1.5
        val fwd = fu / (sqrt(3.0) * gmw)
        val perMm = 0.7 * size * fwd / 1e3           // kN per mm of effective length
        val leff = force / perMm
        val overall = ceil((leff + 2 * size) / 5) * 5
        steps += "fwd = fu/(√3·γmw) = ${f1(fwd)} MPa (Cl. 10.5.7.1.1); throat 0.7s = ${f1(0.7 * size)} mm → ${f3(perMm)} kN/mm"
        steps += "Effective length ${f1(leff)} mm + 2s end returns → provide ${f1(overall)} mm (Cl. 10.5.4.1)"
        val sMin = when { thicker <= 10 -> 3.0; thicker <= 20 -> 5.0; thicker <= 32 -> 6.0; else -> 8.0 }
        checks += DesignCheck("Minimum weld size", "${f1(size)} mm", "≥ ${f1(sMin)} mm for thicker part ${f1(thicker)} mm", if (size >= sMin) Status.PASS else Status.FAIL, ref("Table 21"))
        checks += DesignCheck("Maximum weld size", "${f1(size)} mm", "≤ thinner part − 1.5 = ${f1(thinner - 1.5)} mm (square edge)", if (size <= thinner - 1.5) Status.PASS else Status.FAIL, ref("Cl. 10.5.8.1"))
        checks += DesignCheck("Effective length", "${f1(leff)} mm", "≥ 4s = ${f1(4 * size)} mm", if (leff >= 4 * size) Status.PASS else Status.INFO, ref("Cl. 10.5.4.1"))
        return SteelResult(overall, force / (perMm * (overall - 2 * size)), checks, steps)
    }
}
