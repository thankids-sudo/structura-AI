package com.structura.core.rcc

/** Plain-text design note for sharing (WhatsApp / e-mail) or pasting into a report. */
object BeamReport {
    fun text(r: BeamResult): String = buildString {
        appendLine("STRUCTURA AI — RCC BEAM DESIGN (IS 456:2000, Limit State Method)")
        appendLine("Result: " + if (r.isSafe) "SECTION SAFE" else "REVISE SECTION")
        appendLine("Effective depth d = ${"%.1f".format(r.effectiveDepth)} mm; Mu,lim = ${"%.2f".format(r.muLim)} kN·m")
        appendLine("Bottom steel: ${r.tensionBars} (${"%.0f".format(r.tensionBars.area)} mm²)")
        appendLine("Top steel: " + (r.compressionBars?.let { "$it (${"%.0f".format(it.area)} mm²)" } ?: "2 hanger bars (nominal)"))
        r.stirrupSpacing?.let { appendLine("Stirrups: @ ${it.toInt()} mm c/c") }
        appendLine()
        appendLine("CHECK SHEET")
        r.checks.forEachIndexed { i, c ->
            appendLine("${i + 1}. [${c.status}] ${c.item}: ${c.value} — ${c.requirement} (${c.clause})")
        }
        appendLine()
        appendLine("CALCULATION STEPS")
        r.steps.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
    }
}
