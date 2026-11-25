package com.structura.core.rcc

import kotlin.math.ceil
import kotlin.math.max

/** One row of a bar bending schedule. Lengths in mm, weights in kg. */
data class BbsRow(
    val member: String,
    val mark: String,
    val description: String,
    val dia: Double,
    val count: Int,
    val shape: String,
    val cutLength: Double,
    val formula: String,
) {
    /** Unit weight d²/162 kg/m (IS 1786 nominal mass). */
    val unitWeight: Double get() = dia * dia / 162.0
    val totalLength: Double get() = count * cutLength / 1000.0
    val weight: Double get() = totalLength * unitWeight
}

/**
 * Bar bending schedules (IS 2502 practice): 90° bend deduction 2φ, 135° deduction 3φ, hook / anchorage
 * extension 10φ for stirrups and ties, 12φ legs for main bars. Formulas are listed per row so a
 * colleague can check them. Cutting lengths are rounded up to 10 mm.
 */
object BarBendingSchedule {
    private fun r10(v: Double) = ceil(v / 10) * 10

    fun beam(inp: BeamInput, res: BeamResult, supportWidth: Double = 230.0, name: String = "Beam"): List<BbsRow> {
        val cantilever = inp.support == com.structura.core.codes.IS456.Support.CANTILEVER
        val clear = if (cantilever) inp.effectiveSpan else inp.effectiveSpan - supportWidth
        val lt = if (cantilever) supportWidth + clear else clear + 2 * supportWidth
        val c = inp.clearCover
        val rows = ArrayList<BbsRow>()
        val m = res.tensionBars
        val leg = 12 * m.dia
        val mainLen = r10(lt - 2 * c + 2 * leg - 2 * 2 * m.dia)
        rows += BbsRow(name, "A", if (cantilever) "Main top bars (tension)" else "Main bottom bars", m.dia, m.count, "⌐¬ (90° legs both ends)",
            mainLen, "L − 2c + 2×12φ − 2×2φ = ${lt.toInt()} − ${(2 * c).toInt()} + ${(2 * leg).toInt()} − ${(4 * m.dia).toInt()}")
        val top = res.compressionBars ?: BarArrangement(2, inp.compBarDia)
        val topLen = r10(lt - 2 * c + 2 * 12 * top.dia - 2 * 2 * top.dia)
        rows += BbsRow(name, "B", if (res.compressionBars != null) "Compression bars" else "Hanger bars", top.dia, top.count, "⌐¬ (90° legs both ends)",
            topLen, "L − 2c + 2×12φ − 2×2φ")
        val sv = res.stirrupSpacing ?: 150.0
        val a = inp.widthB - 2 * c - inp.stirrupDia     // c/c of stirrup legs
        val bb = inp.overallDepthD - 2 * c - inp.stirrupDia
        val stirLen = r10(2 * (a + bb) + 2 * 10 * inp.stirrupDia - 3 * 2 * inp.stirrupDia - 2 * 3 * inp.stirrupDia)
        val zone = if (cantilever) lt - supportWidth - 50 - c else lt - 2 * supportWidth - 100
        val n = (ceil(zone / sv) + 1).toInt()
        rows += BbsRow(name, "C", "${inp.stirrupLegs}-legged stirrups @ ${sv.toInt()} c/c", inp.stirrupDia, n, "▭ with 135° hooks",
            stirLen, "2(a + b) + 2×10φ − 3×2φ − 2×3φ, a = ${a.toInt()}, b = ${bb.toInt()}")
        return rows
    }

    fun slab(inp: SlabInput, res: SlabResult, name: String = "Slab"): List<BbsRow> {
        val lx = minOf(inp.lx, inp.ly) * 1000; val ly = maxOf(inp.lx, inp.ly) * 1000
        val c = inp.clearCover
        val rows = ArrayList<BbsRow>()
        val nx = ceil(ly / res.spacingX).toInt() + 1
        rows += BbsRow(name, "S1", "Short-span bars @ ${res.spacingX.toInt()} c/c", inp.barDia, nx, "— straight (hooks/cranks as detailed)",
            r10(lx + 2 * 10 * inp.barDia - 2 * c), "lx − 2c + 2×10φ anchorage")
        val dia2 = if (inp.type == SlabType.TWO_WAY_SS) inp.barDia else inp.distBarDia
        val ny = ceil(lx / res.spacingY).toInt() + 1
        rows += BbsRow(name, "S2", if (inp.type == SlabType.TWO_WAY_SS) "Long-span bars @ ${res.spacingY.toInt()} c/c" else "Distribution bars @ ${res.spacingY.toInt()} c/c",
            dia2, ny, "— straight", r10(ly + 2 * 10 * dia2 - 2 * c), "ly − 2c + 2×10φ")
        return rows
    }

    fun footing(inp: FootingInput, res: FootingResult, name: String = "Footing"): List<BbsRow> {
        val c = inp.clearCover
        val leg = max(res.depth - 2 * c - 2 * inp.barDia, 0.0)
        return listOf(
            BbsRow(name, "F1", "Bars parallel to L", inp.barDia, res.barsL, "⊔ (legs up at both ends)",
                r10(res.lengthL - 2 * c + 2 * leg - 2 * 2 * inp.barDia), "L − 2c + 2×leg − 2×2φ, leg = ${leg.toInt()}"),
            BbsRow(name, "F2", "Bars parallel to B (${res.centralBandBars} in central band)", inp.barDia, res.barsB, "⊔ (legs up at both ends)",
                r10(res.widthB - 2 * c + 2 * leg - 2 * 2 * inp.barDia), "B − 2c + 2×leg − 2×2φ"),
        )
    }

    fun column(inp: ColumnInput, res: ColumnResult, height: Double = inp.unsupportedLength, lap: Double = 50.0, name: String = "Column"): List<BbsRow> {
        val nBars = 2 * inp.nb + 2 * inp.nd - 4
        val c = inp.clearCover
        val a = inp.b - 2 * c - inp.tieDia; val bb = inp.d - 2 * c - inp.tieDia
        val nTies = (ceil(height / res.tiePitch) + 1).toInt()
        return listOf(
            BbsRow(name, "K1", "Main bars (lap ${lap.toInt()}φ at top)", inp.barDia, nBars, "| straight", r10(height + lap * inp.barDia), "storey height + ${lap.toInt()}φ lap"),
            BbsRow(name, "K2", "Peripheral ties @ ${res.tiePitch.toInt()} c/c", inp.tieDia, nTies, "▭ with 135° hooks",
                r10(2 * (a + bb) + 2 * 10 * inp.tieDia - 3 * 2 * inp.tieDia - 2 * 3 * inp.tieDia), "2(a + b) + 2×10φ − 3×2φ − 2×3φ"),
        )
    }

    fun csv(rows: List<BbsRow>): String = buildString {
        appendLine("Structura AI — Bar Bending Schedule (IS 2502 practice; unit weight d²/162 kg/m)")
        appendLine("Member,Mark,Description,Dia (mm),No.,Shape,Cut length (mm),Total length (m),Unit wt (kg/m),Weight (kg),Formula")
        for (r in rows) appendLine(listOf(r.member, r.mark, r.description, r.dia.toInt(), r.count, r.shape, r.cutLength.toInt(),
            "%.2f".format(r.totalLength), "%.3f".format(r.unitWeight), "%.2f".format(r.weight), r.formula).joinToString(",") { "\"$it\"" })
        val byDia = rows.groupBy { it.dia.toInt() }.toSortedMap()
        appendLine()
        appendLine("Summary by diameter")
        for ((d, rs) in byDia) appendLine("\"φ$d\",\"${"%.2f".format(rs.sumOf { it.totalLength })} m\",\"${"%.2f".format(rs.sumOf { it.weight })} kg\"")
        appendLine("\"Total\",,\"${"%.2f".format(rows.sumOf { it.weight })} kg\"")
    }
}
