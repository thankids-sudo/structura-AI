package com.structura.core.drawing

import com.structura.core.codes.IS456.Support
import com.structura.core.rcc.BeamInput
import com.structura.core.rcc.BeamResult
import kotlin.math.min

/**
 * Reinforcement detail drawing of a designed rectangular beam:
 *  • Longitudinal elevation  — UCS "ELEVATION"   (origin: soffit at the outer face of the left support)
 *  • Cross-section A-A       — UCS "SECTION_A-A" (origin: bottom-left corner of the section)
 *  • General notes           — UCS "NOTES"
 * All geometry is at true size in millimetres (1 drawing unit = 1 mm).
 */
object BeamDetailer {

    data class Options(
        val supportWidth: Double = 230.0,   // wall / column width at each support
        val supportDepth: Double = 450.0,   // depth of the support shown below the beam
        val firstStirrup: Double = 50.0,    // first stirrup from the face of support
    )

    fun detail(inp: BeamInput, res: BeamResult, opt: Options = Options()): Drawing {
        val dwg = Drawing("RCC beam ${inp.widthB.toInt()} × ${inp.overallDepthD.toInt()}")
        val b = inp.widthB
        val dd = inp.overallDepthD
        val cv = inp.clearCover
        val fst = inp.stirrupDia
        val sw = opt.supportWidth
        val cantilever = inp.support == Support.CANTILEVER

        // Tension face: bottom for simply supported / continuous (span), top for a cantilever.
        val main = res.tensionBars
        val other = res.compressionBars
        val otherDia = other?.dia ?: inp.compBarDia
        val otherCount = other?.count ?: 2
        val otherLabel = if (other != null) "${other.count}-${other.dia.toInt()} φ" else "2-${inp.compBarDia.toInt()} φ (hanger)"
        val mainLabel = "${main.count}-${main.dia.toInt()} φ"
        val bottomDia = if (cantilever) otherDia else main.dia
        val topDia = if (cantilever) main.dia else otherDia
        val bottomCount = if (cantilever) otherCount else main.count
        val topCount = if (cantilever) main.count else otherCount
        val bottomLabel = if (cantilever) otherLabel else mainLabel
        val topLabel = if (cantilever) mainLabel else otherLabel

        val clear = if (cantilever) inp.effectiveSpan else inp.effectiveSpan - sw
        val lt = if (cantilever) sw + clear else clear + 2 * sw
        val txt = (lt / 60.0).coerceIn(60.0, 150.0)

        // ------------------------------------------------------------ ELEVATION
        val el = dwg.view("LONGITUDINAL ELEVATION", Ucs("ELEVATION", P2(0.0, 0.0)))
        el.rect(0.0, 0.0, lt, dd, Layer.CONCRETE)
        el.rect(0.0, -opt.supportDepth, sw, opt.supportDepth, Layer.CONCRETE)
        el.hatchRect(0.0, -opt.supportDepth, sw, opt.supportDepth, 60.0)
        if (!cantilever) {
            el.rect(lt - sw, -opt.supportDepth, sw, opt.supportDepth, Layer.CONCRETE)
            el.hatchRect(lt - sw, -opt.supportDepth, sw, opt.supportDepth, 60.0)
        } else {
            // fixed end: wall continues above the beam as well
            el.rect(0.0, dd, sw, opt.supportDepth, Layer.CONCRETE)
            el.hatchRect(0.0, dd, sw, opt.supportDepth, 60.0)
        }

        val yBot = cv + fst + bottomDia / 2
        val yTop = dd - cv - fst - topDia / 2
        val xEnd = lt - cv
        val bendB = min(12 * bottomDia, yTop - yBot)
        val bendT = min(12 * topDia, yTop - yBot)
        el.line(cv, yBot, xEnd, yBot, Layer.REBAR)
        el.line(cv, yBot, cv, yBot + bendB, Layer.REBAR)
        if (!cantilever) el.line(xEnd, yBot, xEnd, yBot + bendB, Layer.REBAR)
        el.line(cv, yTop, xEnd, yTop, Layer.REBAR)
        el.line(cv, yTop, cv, yTop - bendT, Layer.REBAR)
        el.line(xEnd, yTop, xEnd, yTop - bendT, Layer.REBAR)

        // Stirrups from the support face(s), first at 50 mm
        val sv = res.stirrupSpacing ?: 150.0
        val xs0 = sw + opt.firstStirrup
        val xs1 = if (cantilever) lt - cv - opt.firstStirrup else lt - sw - opt.firstStirrup
        val stirrupX = ArrayList<Double>()
        var x = xs0
        while (x <= xs1 + 1e-6) { stirrupX += x; x += sv }
        if (stirrupX.isNotEmpty() && xs1 - stirrupX.last() > 1.0) stirrupX += xs1
        for (sx in stirrupX) el.line(sx, cv, sx, dd - cv, Layer.STIRRUP)

        // Section mark A-A at the critical section
        val xa = if (cantilever) sw + clear / 4 else lt / 2
        el.line(xa, dd + txt * 0.8, xa, dd + txt * 2.2, Layer.CENTER)
        el.line(xa, -txt * 0.8, xa, -txt * 2.2, Layer.CENTER)
        el.text(xa + txt * 0.4, dd + txt * 1.4, txt, "A")
        el.text(xa + txt * 0.4, -txt * 2.0, txt, "A")

        // Bar callouts with leaders
        val xl = if (cantilever) sw + clear * 0.55 else lt * 0.3
        el.line(xl, yTop, xl + txt, dd + txt * 2.5, Layer.TEXT)
        el.text(xl + txt * 1.2, dd + txt * 2.5, txt, "$topLabel TOP")
        el.line(xl, yBot, xl + txt, -opt.supportDepth - txt * 0.5, Layer.TEXT)
        el.text(xl + txt * 1.2, -opt.supportDepth - txt * 0.8, txt, "$bottomLabel BOTTOM")
        val stirrupLabel = "${inp.stirrupLegs}L-${fst.toInt()} φ @ ${sv.toInt()} c/c"
        el.text(xs0, dd / 2 - txt / 2, txt * 0.8, stirrupLabel)

        // Dimensions
        val yDim = -opt.supportDepth - txt * 3.5
        el.dimH(sw, if (cantilever) lt else lt - sw, -opt.supportDepth, yDim + opt.supportDepth, txt,
            (if (cantilever) "Cantilever " else "Clear span ") + "%.0f".format(clear))
        el.dimH(0.0, lt, -opt.supportDepth, yDim + opt.supportDepth - txt * 2.5, txt, "Overall %.0f".format(lt))
        el.dimV(0.0, dd, lt, txt * 2.0, txt)
        el.text(lt / 2, yDim - txt * 5, txt * 1.2, "LONGITUDINAL ELEVATION  (UCS: ELEVATION)", centered = true)

        // ------------------------------------------------------------ SECTION A-A
        val sx0 = lt + 1000.0
        val sec = dwg.view("SECTION A-A", Ucs("SECTION_A-A", P2(sx0, 0.0)))
        val t2 = (b / 8.0).coerceIn(25.0, 60.0)
        sec.rect(0.0, 0.0, b, dd, Layer.CONCRETE)
        sec.rect(cv, cv, b - 2 * cv, dd - 2 * cv, Layer.STIRRUP)
        // 135° hooks at the top-left corner (Cl. 26.5.1.6 / SP 34)
        val hk = min(8 * fst, 75.0)
        sec.line(cv, dd - cv, cv + hk * 0.7071, dd - cv - hk * 0.7071, Layer.STIRRUP)
        sec.line(cv + fst * 1.5, dd - cv, cv + fst * 1.5 + hk * 0.7071, dd - cv - hk * 0.7071, Layer.STIRRUP)

        fun barRow(n: Int, dia: Double, y: Double) {
            val xa0 = cv + fst + dia / 2
            val xa1 = b - cv - fst - dia / 2
            for (k in 0 until n) {
                val bx = if (n == 1) b / 2 else xa0 + (xa1 - xa0) * k / (n - 1)
                sec.circle(bx, y, dia / 2, Layer.REBAR, filled = true)
            }
        }
        barRow(bottomCount, bottomDia, cv + fst + bottomDia / 2)
        barRow(topCount, topDia, dd - cv - fst - topDia / 2)

        sec.dimH(0.0, b, 0.0, -t2 * 2.5, t2)
        sec.dimV(0.0, dd, 0.0, -t2 * 2.5, t2)
        sec.line(b - cv - fst, dd - cv - fst - topDia / 2, b + t2 * 2, dd - t2, Layer.TEXT)
        sec.text(b + t2 * 2.2, dd - t2 * 1.3, t2, "$topLabel TOP")
        sec.line(b - cv - fst, cv + fst + bottomDia / 2, b + t2 * 2, t2 * 1.5, Layer.TEXT)
        sec.text(b + t2 * 2.2, t2 * 1.2, t2, "$bottomLabel BOTTOM")
        sec.line(b - cv, dd / 2, b + t2 * 2, dd / 2, Layer.TEXT)
        sec.text(b + t2 * 2.2, dd / 2 - t2 * 0.4, t2, stirrupLabel)
        sec.text(cv, -t2 * 6.0, t2 * 0.9, "Clear cover ${cv.toInt()} mm (IS 456 Cl. 26.4)")
        sec.text(b / 2, -t2 * 8.5, t2 * 1.2, "SECTION A-A  (UCS: SECTION_A-A)", centered = true)

        // ------------------------------------------------------------ NOTES
        val notes = dwg.view("NOTES", Ucs("NOTES", P2(0.0, yDim - txt * 10)))
        val lines = listOf(
            "NOTES:",
            "1. All dimensions are in mm. Drawing units: 1 unit = 1 mm (true size).",
            "2. Concrete M${inp.fck.toInt()}, reinforcement Fe ${inp.fy.toInt()}, stirrups Fe ${inp.fyStirrup.toInt()} (IS 1786).",
            "3. Clear cover to stirrups ${cv.toInt()} mm — IS 456:2000 Cl. 26.4.2, Table 16.",
            "4. Stirrups: ${inp.stirrupLegs}-legged ${fst.toInt()} φ @ ${sv.toInt()} c/c, first at ${opt.firstStirrup.toInt()} mm from face of support; 135° hooks.",
            "5. Bars shown with 12φ bends; provide development length Ld per IS 456 Cl. 26.2.1 and SP 34.",
            "6. Design: Mu = ${"%.1f".format(inp.factoredMoment)} kN·m, Vu = ${"%.1f".format(inp.factoredShear)} kN, " +
                (if (res.isSafe) "all checks satisfied." else "SECTION NEEDS REVISION — see check sheet."),
            "7. Named UCS in this file: ELEVATION, SECTION_A-A, NOTES (AutoCAD: UCS > Named).",
            "Generated by Structura AI",
        )
        lines.forEachIndexed { k, s -> notes.text(0.0, -k * txt * 1.6, if (k == 0) txt * 1.1 else txt * 0.85, s) }
        return dwg
    }
}
