package com.structura.core.rcc

import com.structura.core.codes.IS456
import com.structura.core.codes.IS456.Support
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Input for limit-state design of a rectangular RCC beam (IS 456:2000, Section 5 & Annex G).
 * Lengths in mm, moments in kN·m, forces in kN, stresses in MPa.
 */
data class BeamInput(
    val widthB: Double,                 // b
    val overallDepthD: Double,          // D
    val clearCover: Double = 25.0,      // nominal cover to stirrups, Cl. 26.4.2
    val mainBarDia: Double = 16.0,      // tension bar φ
    val compBarDia: Double = 12.0,      // compression bar φ (used only if doubly reinforced)
    val stirrupDia: Double = 8.0,
    val stirrupLegs: Int = 2,
    val effectiveSpan: Double,          // l (mm), Cl. 22.2
    val support: Support = Support.SIMPLY_SUPPORTED,
    val fck: Double = 20.0,
    val fy: Double = 500.0,
    val fyStirrup: Double = 415.0,
    val factoredMoment: Double,         // Mu, kN·m
    val factoredShear: Double,          // Vu, kN
    val maxAggregate: Double = 20.0,
)

enum class Status { PASS, FAIL, INFO }

/** One line of the design check sheet — always carries its clause. */
data class DesignCheck(
    val item: String,
    val value: String,
    val requirement: String,
    val status: Status,
    val clause: String,
)

data class BarArrangement(val count: Int, val dia: Double) {
    val area: Double get() = count * PI * dia * dia / 4.0
    override fun toString() = "$count-${dia.toInt()} φ"
}

data class BeamResult(
    val effectiveDepth: Double,
    val xuMax: Double,
    val muLim: Double,                  // kN·m
    val doublyReinforced: Boolean,
    val astRequired: Double,            // mm²
    val ascRequired: Double,            // mm²
    val tensionBars: BarArrangement,
    val compressionBars: BarArrangement?,
    val stirrupSpacing: Double?,        // mm c/c, null if section fails shear
    val checks: List<DesignCheck>,
    val steps: List<String>,
) {
    val isSafe: Boolean get() = checks.none { it.status == Status.FAIL }
}

object BeamDesigner {

    private fun barArea(dia: Double) = PI * dia * dia / 4.0
    private fun f1(v: Double) = "%.1f".format(v)
    private fun f2(v: Double) = "%.2f".format(v)
    private fun f3(v: Double) = "%.3f".format(v)

    /** Factored moment & shear for a simply supported beam under factored UDL wu (kN/m) over span l (m). */
    fun simplySupportedUdl(wu: Double, spanM: Double) = Pair(wu * spanM * spanM / 8.0, wu * spanM / 2.0)

    /** Factored moment & shear for a cantilever under factored UDL wu (kN/m) over length l (m). */
    fun cantileverUdl(wu: Double, spanM: Double) = Pair(wu * spanM * spanM / 2.0, wu * spanM)

    fun design(inp: BeamInput): BeamResult {
        require(inp.widthB > 0 && inp.overallDepthD > 0) { "Section dimensions must be positive" }
        require(inp.factoredMoment >= 0 && inp.factoredShear >= 0) { "Mu and Vu must be ≥ 0" }

        val checks = mutableListOf<DesignCheck>()
        val steps = mutableListOf<String>()
        val b = inp.widthB
        val fck = inp.fck
        val fy = inp.fy
        val mu = inp.factoredMoment * 1e6       // N·mm
        val vu = inp.factoredShear * 1e3        // N

        // ---------------------------------------------------------- Geometry
        val d = inp.overallDepthD - inp.clearCover - inp.stirrupDia - inp.mainBarDia / 2.0
        val dDash = inp.clearCover + inp.stirrupDia + inp.compBarDia / 2.0
        steps += "d = D − cover − φst − φ/2 = ${f1(inp.overallDepthD)} − ${f1(inp.clearCover)} − ${f1(inp.stirrupDia)} − ${f1(inp.mainBarDia / 2)} = ${f1(d)} mm"

        checks += DesignCheck(
            "Concrete grade", "M${fck.toInt()}", "≥ M20 for RCC (moderate exposure)",
            if (fck >= 20) Status.PASS else Status.FAIL, IS456.ref("Cl. 6.1.2, Table 5"),
        )
        checks += DesignCheck(
            "Nominal cover", "${f1(inp.clearCover)} mm", "≥ 25 mm beams (moderate), ≥ main bar φ",
            if (inp.clearCover >= max(25.0, inp.mainBarDia)) Status.PASS else Status.FAIL,
            IS456.ref("Cl. 26.4.1, 26.4.2, Table 16"),
        )

        // ---------------------------------------------------------- Flexure
        val xuMaxByD = IS456.xuMaxByD(fy)
        val xuMax = xuMaxByD * d
        val muLim = 0.36 * fck * b * xuMax * (d - 0.42 * xuMax)
        steps += "xu,max/d = ${f3(xuMaxByD)} (Fe ${fy.toInt()}) → xu,max = ${f1(xuMax)} mm"
        steps += "Mu,lim = 0.36·fck·b·xu,max·(d − 0.42·xu,max) = ${f2(muLim / 1e6)} kN·m"

        val doubly = mu > muLim
        val astReq: Double
        var ascReq = 0.0
        if (!doubly) {
            val r = 4.6 * mu / (fck * b * d * d)
            astReq = 0.5 * fck / fy * (1 - sqrt(1 - r)) * b * d
            steps += "Mu ≤ Mu,lim → singly reinforced."
            steps += "Ast = 0.5·fck/fy·[1 − √(1 − 4.6·Mu/(fck·b·d²))]·b·d = ${f1(astReq)} mm²"
            checks += DesignCheck(
                "Flexure (Mu vs Mu,lim)", "${f2(mu / 1e6)} kN·m", "≤ ${f2(muLim / 1e6)} kN·m → singly",
                Status.PASS, IS456.ref("Annex G-1.1"),
            )
        } else {
            val mu2 = mu - muLim
            val ast1 = muLim / (0.87 * fy * (d - 0.42 * xuMax))
            val esc = IS456.ECU * (xuMax - dDash) / xuMax
            val fsc = IS456.steelStress(esc, fy)
            val fcc = 0.446 * fck
            ascReq = mu2 / ((fsc - fcc) * (d - dDash))
            val ast2 = ascReq * (fsc - fcc) / (0.87 * fy)
            astReq = ast1 + ast2
            steps += "Mu > Mu,lim → doubly reinforced; Mu2 = Mu − Mu,lim = ${f2(mu2 / 1e6)} kN·m"
            steps += "d′ = ${f1(dDash)} mm; εsc = 0.0035·(xu,max − d′)/xu,max = ${"%.5f".format(esc)} → fsc = ${f1(fsc)} MPa (Fig. 23)"
            steps += "Asc = Mu2 / [(fsc − 0.446·fck)(d − d′)] = ${f1(ascReq)} mm²"
            steps += "Ast = Mu,lim/[0.87·fy·(d − 0.42·xu,max)] + Asc·(fsc − 0.446·fck)/(0.87·fy) = ${f1(ast1)} + ${f1(ast2)} = ${f1(astReq)} mm²"
            checks += DesignCheck(
                "Flexure (Mu vs Mu,lim)", "${f2(mu / 1e6)} kN·m", "> ${f2(muLim / 1e6)} kN·m → doubly",
                Status.INFO, IS456.ref("Annex G-1.2"),
            )
            if (dDash / d > 0.2) {
                checks += DesignCheck(
                    "Compression steel depth d′/d", f3(dDash / d), "keep ≤ 0.20 for effective compression steel",
                    Status.INFO, IS456.ref("Annex G-1.2"),
                )
            }
        }

        // Minimum / maximum tension steel
        val astMin = 0.85 * b * d / fy
        val astMax = 0.04 * b * inp.overallDepthD
        val astDesign = max(astReq, astMin)
        if (astReq < astMin) steps += "Ast,min = 0.85·b·d/fy = ${f1(astMin)} mm² governs."

        val nT = max(2, ceil(astDesign / barArea(inp.mainBarDia)).toInt())
        val tension = BarArrangement(nT, inp.mainBarDia)
        checks += DesignCheck(
            "Tension steel provided", "$tension = ${f1(tension.area)} mm²", "≥ ${f1(astDesign)} mm²",
            Status.PASS, IS456.ref("Annex G-1.1"),
        )
        checks += DesignCheck(
            "Minimum tension steel", "${f1(tension.area)} mm²", "≥ 0.85·b·d/fy = ${f1(astMin)} mm²",
            if (tension.area >= astMin) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.1.1(a)"),
        )
        checks += DesignCheck(
            "Maximum tension steel", "${f1(tension.area)} mm²", "≤ 0.04·b·D = ${f1(astMax)} mm²",
            if (tension.area <= astMax) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.1.1(b)"),
        )

        var compression: BarArrangement? = null
        if (doubly) {
            val nC = max(2, ceil(ascReq / barArea(inp.compBarDia)).toInt())
            compression = BarArrangement(nC, inp.compBarDia)
            checks += DesignCheck(
                "Compression steel provided", "$compression = ${f1(compression.area)} mm²", "≥ ${f1(ascReq)} mm²",
                Status.PASS, IS456.ref("Annex G-1.2"),
            )
            checks += DesignCheck(
                "Maximum compression steel", "${f1(compression.area)} mm²", "≤ 0.04·b·D = ${f1(astMax)} mm²",
                if (compression.area <= astMax) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.1.2"),
            )
        }

        // Bar spacing in one layer — Cl. 26.3.2(a)
        val minClear = max(inp.mainBarDia, inp.maxAggregate + 5.0)
        val clearWidth = b - 2 * (inp.clearCover + inp.stirrupDia)
        val clearSpacing = if (nT > 1) (clearWidth - nT * inp.mainBarDia) / (nT - 1) else clearWidth
        checks += DesignCheck(
            "Clear spacing of bars (single layer)", "${f1(clearSpacing)} mm",
            "≥ max(φ, agg + 5) = ${f1(minClear)} mm",
            if (clearSpacing >= minClear) Status.PASS else Status.FAIL,
            IS456.ref("Cl. 26.3.2(a)") + if (clearSpacing < minClear) " — use 2 layers and recompute d" else "",
        )

        // ---------------------------------------------------------- Shear
        val pt = 100.0 * tension.area / (b * d)
        val tauV = vu / (b * d)
        val tauC = IS456.tauC(pt, fck)
        val tauCMax = IS456.tauCMax(fck)
        steps += "τv = Vu/(b·d) = ${f3(tauV)} MPa; pt = ${f3(pt)} % → τc = ${f3(tauC)} MPa (Table 19); τc,max = ${f2(tauCMax)} MPa (Table 20)"

        val shearOk = tauV <= tauCMax
        checks += DesignCheck(
            "Shear stress vs τc,max", "${f3(tauV)} MPa", "≤ ${f2(tauCMax)} MPa",
            if (shearOk) Status.PASS else Status.FAIL,
            IS456.ref("Cl. 40.2.3, Table 20") + if (!shearOk) " — increase section" else "",
        )

        var sv: Double? = null
        if (shearOk) {
            val fyv = min(inp.fyStirrup, 415.0)   // Cl. 40.4(a): fy not to be taken > 415 MPa
            val asv = inp.stirrupLegs * barArea(inp.stirrupDia)
            val svMaxCode = min(0.75 * d, 300.0)
            val svMin = 0.87 * fyv * asv / (0.4 * b)
            val svReq: Double
            if (tauV <= tauC) {
                svReq = svMin
                steps += "τv ≤ τc → minimum shear reinforcement: sv ≤ 0.87·fy·Asv/(0.4·b) = ${f1(svMin)} mm"
                checks += DesignCheck(
                    "Shear reinforcement", "τv ≤ τc", "Minimum stirrups",
                    Status.PASS, IS456.ref("Cl. 26.5.1.6, 40.3"),
                )
            } else {
                val vus = vu - tauC * b * d
                val svDesign = 0.87 * fyv * asv * d / vus
                svReq = min(svDesign, svMin)
                steps += "Vus = Vu − τc·b·d = ${f2(vus / 1e3)} kN; sv = 0.87·fy·Asv·d/Vus = ${f1(svDesign)} mm"
                checks += DesignCheck(
                    "Shear reinforcement", "Vus = ${f2(vus / 1e3)} kN", "Vertical stirrups designed",
                    Status.PASS, IS456.ref("Cl. 40.4(a)"),
                )
            }
            val svAdopt = floor(min(svReq, svMaxCode) / 5.0) * 5.0   // round down to 5 mm
            sv = svAdopt
            checks += DesignCheck(
                "Stirrup spacing",
                "${inp.stirrupLegs}L-${inp.stirrupDia.toInt()} φ @ ${svAdopt.toInt()} c/c",
                "≤ min(0.75·d, 300) = ${f1(svMaxCode)} mm",
                if (svAdopt in 50.0..svMaxCode) Status.PASS else Status.FAIL,
                IS456.ref("Cl. 26.5.1.5"),
            )
        }

        // ---------------------------------------------------------- Deflection
        val basic = IS456.basicSpanDepth(inp.support, inp.effectiveSpan)
        val fs = 0.58 * fy * astDesign / tension.area
        val kt = IS456.kt(pt, fs)
        val pc = compression?.let { 100.0 * it.area / (b * d) } ?: 0.0
        val kc = IS456.kc(pc)
        val allowed = basic * kt * kc
        val actual = inp.effectiveSpan / d
        steps += "l/d allowed = ${f1(basic)} × kt ${f3(kt)} × kc ${f3(kc)} = ${f2(allowed)}; actual l/d = ${f2(actual)} (fs = ${f1(fs)} MPa)"
        checks += DesignCheck(
            "Deflection (span/effective depth)", f2(actual), "≤ ${f2(allowed)}",
            if (actual <= allowed) Status.PASS else Status.FAIL,
            IS456.ref("Cl. 23.2.1, Fig. 4 & 5"),
        )

        return BeamResult(
            effectiveDepth = d,
            xuMax = xuMax,
            muLim = muLim / 1e6,
            doublyReinforced = doubly,
            astRequired = astReq,
            ascRequired = ascReq,
            tensionBars = tension,
            compressionBars = compression,
            stirrupSpacing = sv,
            checks = checks,
            steps = steps,
        )
    }
}
