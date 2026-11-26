package com.structura.core.report

import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.Status

/**
 * Platform-neutral design report: the engines build it, the app renders it on screen and to an A4 PDF.
 * Figures are line drawings in model units (m), scaled to fit; y is up.
 */
class ReportDoc(val title: String, val subtitle: String, val blocks: List<Block>) {
    val isSafe: Boolean get() = blocks.filterIsInstance<Block.Checks>().all { b -> b.checks.none { it.status == Status.FAIL } }

    sealed class Block {
        class Heading(val text: String) : Block()
        class Para(val text: String) : Block()
        class KeyValues(val rows: List<Pair<String, String>>) : Block()
        class Table(val header: List<String>, val rows: List<List<String>>, val weights: List<Float>? = null) : Block()
        class Checks(val checks: List<DesignCheck>) : Block()
        class Steps(val lines: List<String>) : Block()
        class Figure(val caption: String, val lines: List<FLine>, val texts: List<FText> = emptyList(), val heightPt: Float = 210f) : Block()
    }

    /** weight 1 = thin, 2 = medium, 3 = heavy; dashed for hidden / centre lines; hatch fills nothing (B&W line drawing). */
    data class FLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val weight: Int = 1, val dashed: Boolean = false)
    data class FText(val x: Double, val y: Double, val text: String, val sizePt: Float = 7f)

    class Builder(private val title: String, private val subtitle: String) {
        private val b = ArrayList<Block>()
        fun h(t: String) = apply { b += Block.Heading(t) }
        fun p(t: String) = apply { b += Block.Para(t) }
        fun kv(vararg r: Pair<String, String>) = apply { b += Block.KeyValues(r.toList()) }
        fun kv(r: List<Pair<String, String>>) = apply { b += Block.KeyValues(r) }
        fun table(header: List<String>, rows: List<List<String>>, weights: List<Float>? = null) = apply { b += Block.Table(header, rows, weights) }
        fun checks(c: List<DesignCheck>) = apply { if (c.isNotEmpty()) b += Block.Checks(c) }
        fun steps(s: List<String>) = apply { if (s.isNotEmpty()) b += Block.Steps(s) }
        fun fig(f: Block.Figure) = apply { b += f }
        fun build() = ReportDoc(title, subtitle, b.toList())
    }

    /** Plain-text rendering (share / clipboard / tests). */
    fun text(): String = buildString {
        appendLine(title); appendLine(subtitle); appendLine()
        for (bl in blocks) when (bl) {
            is Block.Heading -> { appendLine(); appendLine(bl.text.uppercase()) }
            is Block.Para -> appendLine(bl.text)
            is Block.KeyValues -> bl.rows.forEach { appendLine("${it.first}: ${it.second}") }
            is Block.Table -> { appendLine(bl.header.joinToString(" | ")); bl.rows.forEach { appendLine(it.joinToString(" | ")) } }
            is Block.Checks -> bl.checks.forEach { appendLine("${it.item}: ${it.value} ${it.requirement} [${it.status}] ${it.clause}") }
            is Block.Steps -> bl.lines.forEach { appendLine("• $it") }
            is Block.Figure -> appendLine("[Figure: ${bl.caption}]")
        }
    }
}

internal fun f0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)
internal fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
internal fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
internal fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
