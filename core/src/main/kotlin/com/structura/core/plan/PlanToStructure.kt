package com.structura.core.plan

import com.structura.core.fem.FloorLoad
import com.structura.core.fem.FrameModel
import com.structura.core.fem.LoadCase
import com.structura.core.fem.LoadDir
import com.structura.core.fem.Material
import com.structura.core.fem.Member
import com.structura.core.fem.MemberLoad
import com.structura.core.fem.Node
import com.structura.core.fem.Section
import com.structura.core.fem.Support
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A column found on plan (metres). [b] along X, [d] along Y after snapping to the grid. */
data class PlanColumn(val id: Int, val x: Double, val y: Double, val b: Double, val d: Double, val gx: Int, val gy: Int, val layer: String, val circular: Boolean = false) {
    val label: String get() = "${PlanLayout.letter(gy)}${gx + 1}"
}

data class PlanBeam(val a: Int, val b: Int) {             // column ids
    fun key() = if (a < b) a to b else b to a
}

/** Recognised structural layout: grid lines X (numbered 1, 2, …) and Y (lettered A, B, …), columns and beams. */
class PlanLayout(
    val gridX: List<Double>, val gridY: List<Double>,
    val columns: List<PlanColumn>, val beams: List<PlanBeam>,
    val notes: List<String>,
) {
    fun column(id: Int) = columns.first { it.id == id }
    fun span(bm: PlanBeam): Double { val p = column(bm.a); val q = column(bm.b); return hypot(q.x - p.x, q.y - p.y) }

    /** Layout without the [removed] column ids; beams re-derived along the grid lines. */
    fun without(removed: Set<Int>, maxSpan: Double = PlanRecognizer.MAX_SPAN): PlanLayout {
        val cols = columns.filter { it.id !in removed }
        return PlanLayout(gridX, gridY, cols, PlanRecognizer.beamsFor(cols, maxSpan), notes)
    }

    companion object {
        fun letter(k: Int): String {
            // A … Z, then AA, AB … (I and O skipped as in drawing practice)
            val abc = "ABCDEFGHJKLMNPQRSTUVWXYZ"
            return if (k < abc.length) abc[k].toString() else abc[k / abc.length - 1].toString() + abc[k % abc.length]
        }
    }
}

/**
 * Rule-based "AI" plan reader. Columns = closed rectangles (or circles) 150 – 1500 mm; if any layer name contains
 * COL, only those layers are used. Column centres are clustered into grid lines (tolerance 300 mm), snapped,
 * and beams are proposed between consecutive columns on each grid line (span ≤ [MAX_SPAN]).
 */
object PlanRecognizer {
    const val MAX_SPAN = 10.0
    private const val MIN_SIZE = 0.15
    private const val MAX_SIZE = 1.5

    private data class Cand(val x: Double, val y: Double, val b: Double, val d: Double, val layer: String, val circ: Boolean)

    fun recognise(plan: DxfPlan, gridTol: Double = 0.3, maxSpan: Double = MAX_SPAN): PlanLayout {
        val notes = ArrayList<String>()
        val cands = ArrayList<Cand>()
        for (e in plan.entities) {
            when (e) {
                is PlanEntity.Poly -> rect(e)?.let { (cx, cy, w, h) -> cands += Cand(cx, cy, w, h, e.layer, false) }
                is PlanEntity.Circle -> if (2 * e.r in MIN_SIZE..MAX_SIZE) cands += Cand(e.cx, e.cy, 2 * e.r, 2 * e.r, e.layer, true)
                else -> {}
            }
        }
        val colLayers = cands.map { it.layer }.distinct().filter { it.uppercase().contains("COL") }
        val use = if (colLayers.isNotEmpty()) {
            notes += "Columns taken from layer(s): ${colLayers.joinToString()}"
            cands.filter { it.layer in colLayers }
        } else {
            if (cands.isNotEmpty()) notes += "No layer named *COL* — every closed rectangle 150–1500 mm treated as a column; delete false ones by tapping"
            cands
        }
        // de-duplicate (hatch outlines, double polylines)
        val uniq = ArrayList<Cand>()
        for (c in use.sortedByDescending { it.b * it.d }) if (uniq.none { hypot(it.x - c.x, it.y - c.y) < 0.1 }) uniq += c
        if (uniq.isEmpty()) {
            notes += "No columns recognised — draw columns as closed polylines (LWPOLYLINE) on a layer named COLUMN"
            return PlanLayout(emptyList(), emptyList(), emptyList(), emptyList(), notes + plan.warnings)
        }
        // grid by clustering, optionally refined by GRID / AXIS lines
        val axisLines = plan.entities.filterIsInstance<PlanEntity.Line>().filter { l ->
            val u = l.layer.uppercase(); u.contains("GRID") || u.contains("AXIS") || u.contains("CENTRE") || u.contains("CENTER")
        }
        val vx = axisLines.filter { abs(it.x1 - it.x2) < 1e-3 && abs(it.y1 - it.y2) > 1.0 }.map { it.x1 }
        val hy = axisLines.filter { abs(it.y1 - it.y2) < 1e-3 && abs(it.x1 - it.x2) > 1.0 }.map { it.y1 }
        val gx = cluster(uniq.map { it.x }, gridTol).map { m -> vx.minByOrNull { abs(it - m) }?.takeIf { abs(it - m) < gridTol } ?: m }
        val gy = cluster(uniq.map { it.y }, gridTol).map { m -> hy.minByOrNull { abs(it - m) }?.takeIf { abs(it - m) < gridTol } ?: m }
        if (axisLines.isNotEmpty()) notes += "Grid snapped to ${vx.size + hy.size} axis lines on GRID/AXIS layers"
        val x0 = gx.min(); val y0 = gy.min()
        val cols = uniq.mapIndexed { k, c ->
            val ix = gx.indices.minBy { abs(gx[it] - c.x) }; val iy = gy.indices.minBy { abs(gy[it] - c.y) }
            PlanColumn(k + 1, gx[ix] - x0, gy[iy] - y0, c.b, c.d, ix, iy, c.layer, c.circ)
        }.groupBy { it.gx to it.gy }.map { it.value.first() }       // one column per grid intersection
            .sortedWith(compareBy({ it.gy }, { it.gx })).mapIndexed { k, c -> c.copy(id = k + 1) }
        notes += "${cols.size} columns on a ${gx.size} × ${gy.size} grid; origin moved to grid ${LayoutLabels.corner(cols)}"
        val gridXs = gx.map { it - x0 }; val gridYs = gy.map { it - y0 }
        val bays = (gridXs.zipWithNext { a, b -> b - a } + gridYs.zipWithNext { a, b -> b - a })
        if (bays.any { it > maxSpan }) notes += "Some bays exceed ${maxSpan} m — no beam proposed there (add an intermediate column or use a transfer girder)"
        return PlanLayout(gridXs, gridYs, cols, beamsFor(cols, maxSpan), notes + plan.warnings)
    }

    /** Beams between consecutive columns on the same grid line. */
    fun beamsFor(cols: List<PlanColumn>, maxSpan: Double): List<PlanBeam> {
        val out = ArrayList<PlanBeam>()
        for ((_, line) in cols.groupBy { it.gy }) line.sortedBy { it.x }.zipWithNext { a, b -> if (b.x - a.x <= maxSpan + 1e-6) out += PlanBeam(a.id, b.id) }
        for ((_, line) in cols.groupBy { it.gx }) line.sortedBy { it.y }.zipWithNext { a, b -> if (b.y - a.y <= maxSpan + 1e-6) out += PlanBeam(a.id, b.id) }
        return out
    }

    /** Centre and axis-aligned size of a closed rectangle (4 corners, optional repeated closing point); rotated rectangles use their own sides. */
    private fun rect(p: PlanEntity.Poly): List<Double>? {
        var pts = p.pts
        if (pts.size == 5 && hypot(pts[0].first - pts[4].first, pts[0].second - pts[4].second) < 1e-6) pts = pts.take(4)
        else if (!p.closed) return null
        if (pts.size != 4) return null
        val s = (0 until 4).map { hypot(pts[(it + 1) % 4].first - pts[it].first, pts[(it + 1) % 4].second - pts[it].second) }
        if (abs(s[0] - s[2]) > 0.01 || abs(s[1] - s[3]) > 0.01) return null
        val diag1 = hypot(pts[2].first - pts[0].first, pts[2].second - pts[0].second)
        if (abs(diag1 - sqrt(s[0] * s[0] + s[1] * s[1])) > 0.01) return null      // not a rectangle
        if (s[0] !in MIN_SIZE..MAX_SIZE || s[1] !in MIN_SIZE..MAX_SIZE) return null
        val cx = pts.sumOf { it.first } / 4; val cy = pts.sumOf { it.second } / 4
        val ang = atan2(pts[1].second - pts[0].second, pts[1].first - pts[0].first)
        val alongX = abs(kotlin.math.cos(ang)) >= abs(kotlin.math.sin(ang))
        return if (alongX) listOf(cx, cy, s[0], s[1]) else listOf(cx, cy, s[1], s[0])
    }

    private fun cluster(v: List<Double>, tol: Double): List<Double> {
        val out = ArrayList<MutableList<Double>>()
        for (x in v.sorted()) if (out.isNotEmpty() && x - out.last().last() <= tol) out.last() += x else out += mutableListOf(x)
        return out.map { it.average() }
    }
}

internal object LayoutLabels {
    fun corner(cols: List<PlanColumn>) = cols.minWithOrNull(compareBy({ it.gy }, { it.gx }))?.label ?: "—"
}

/** Inputs for take-down, sizing and model generation (kN, m, MPa). */
data class BuildingInput(
    val storeys: Int = 3,
    val storeyHeight: Double = 3.2,
    val plinthHeight: Double = 1.5,          // foundation top → plinth (first column lift)
    val finishes: Double = 1.5,              // kN/m² floor finish
    val liveLoad: Double = 3.0,              // kN/m² typical floor (IS 875-2)
    val roofLive: Double = 1.5,              // kN/m² accessible roof
    val wallLoad: Double = 12.0,             // kN/m on beams of typical floors (230 brick ≈ 0.23 × 20 × (h − D))
    val fck: Double = 25.0,
    val fy: Double = 500.0,
    val minColumn: Double = 0.3,             // m (IS 13920 Cl. 7.1.2: ≥ 300 mm)
    val steelRatio: Double = 0.01,
)

data class SizedColumn(val column: Int, val label: String, val storey: Int, val pu: Double, val b: Double, val d: Double, val position: String)
data class SizedBeam(val beam: PlanBeam, val label: String, val span: Double, val b: Double, val d: Double)

class PreliminaryDesign(
    val layout: PlanLayout, val input: BuildingInput,
    val slabThickness: Double,
    val columns: List<SizedColumn>,             // one row per column per storey (storey 1 = ground)
    val beams: List<SizedBeam>,
    val quantities: List<Triple<String, String, Double>>,   // item, unit, quantity
    val steps: List<String>,
) {
    fun csv(): String = buildString {
        appendLine("Structura AI — preliminary design from plan")
        appendLine("Storeys,${input.storeys},Storey height (m),${input.storeyHeight},Slab (mm),${(slabThickness * 1000).toInt()},fck,${input.fck},fy,${input.fy}")
        appendLine()
        appendLine("Column load take-down (IS 456 Cl. 39.3; Pu factored 1.5)")
        appendLine("Column,Storey,Position,Pu (kN),b (mm),D (mm)")
        columns.forEach { appendLine("${it.label},${it.storey},${it.position},${"%.1f".format(it.pu)},${(it.b * 1000).toInt()},${(it.d * 1000).toInt()}") }
        appendLine()
        appendLine("Beams (D ≈ span/12, IS 456 Cl. 23.2.1)")
        appendLine("Beam,Span (m),b (mm),D (mm)")
        beams.forEach { appendLine("${it.label},${"%.2f".format(it.span)},${(it.b * 1000).toInt()},${(it.d * 1000).toInt()}") }
        appendLine()
        appendLine("Quantities (superstructure, preliminary)")
        appendLine("Item,Unit,Quantity")
        quantities.forEach { appendLine("\"${it.first}\",${it.second},${"%.2f".format(it.third)}") }
    }
}

/** Load take-down, preliminary sizing, 3D model and quantities from a recognised plan. */
object PlanToStructure {
    private fun up(v: Double, step: Double) = ceil(v / step - 1e-9) * step

    fun design(layout: PlanLayout, inp: BuildingInput): PreliminaryDesign {
        require(layout.columns.size >= 2) { "Need at least two columns" }
        val steps = ArrayList<String>()
        val cols = layout.columns
        val bx = layout.gridX.zipWithNext { a, b -> b - a }; val by = layout.gridY.zipWithNext { a, b -> b - a }
        val lxMax = panels(layout).maxOfOrNull { min(it.second, it.third) } ?: (bx + by).maxOrNull() ?: 3.0
        val t = max(0.125, up(lxMax / 32, 0.005))
        steps += "Slab: largest short span ${"%.2f".format(lxMax)} m → t = lx/32 ≥ 125 mm → ${(t * 1000).toInt()} mm (IS 456 Cl. 24.1 Note 2, two-way continuous)"
        val beams = layout.beams.map { bm ->
            val sp = layout.span(bm)
            val d = max(0.3, up(sp / 12, 0.025)); val b = if (d <= 0.45) 0.23 else 0.3
            val p = layout.column(bm.a); val q = layout.column(bm.b)
            val label = if (p.gy == q.gy) "B-${PlanLayout.letter(p.gy)}/${p.gx + 1}-${q.gx + 1}" else "B-${p.gx + 1}/${PlanLayout.letter(p.gy)}-${PlanLayout.letter(q.gy)}"
            SizedBeam(bm, label, sp, b, d)
        }
        steps += "Beams: D = span/12 rounded up to 25 mm, ≥ 300 mm; b = 230 mm (D ≤ 450) else 300 mm"
        if (panels(layout).isEmpty()) steps += "No closed slab panels found — slab load not applied"

        // tributary area: half the adjoining bay on each side (only where a beam exists in that direction)
        fun trib(c: PlanColumn): Pair<Double, Double> {
            val xl = layout.gridX.getOrNull(c.gx - 1)?.let { c.x - it } ?: 0.0
            val xr = layout.gridX.getOrNull(c.gx + 1)?.let { it - c.x } ?: 0.0
            val yl = layout.gridY.getOrNull(c.gy - 1)?.let { c.y - it } ?: 0.0
            val yr = layout.gridY.getOrNull(c.gy + 1)?.let { it - c.y } ?: 0.0
            val hasL = cols.any { it.gy == c.gy && it.gx == c.gx - 1 }; val hasR = cols.any { it.gy == c.gy && it.gx == c.gx + 1 }
            val hasD = cols.any { it.gx == c.gx && it.gy == c.gy - 1 }; val hasU = cols.any { it.gx == c.gx && it.gy == c.gy + 1 }
            val wx = (if (hasL) xl / 2 else 0.0) + (if (hasR) xr / 2 else 0.0)
            val wy = (if (hasD) yl / 2 else 0.0) + (if (hasU) yr / 2 else 0.0)
            return wx to wy
        }
        val beamW = beams.associate { it.beam.key() to it }
        val sized = ArrayList<SizedColumn>()
        for (c in cols) {
            val (wx, wy) = trib(c)
            val area = wx * wy
            val nNb = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).count { (dx, dy) -> cols.any { it.gx == c.gx + dx && it.gy == c.gy + dy } }
            val (pos, factor) = when { nNb >= 4 -> "interior" to 1.1; nNb == 3 -> "edge" to 1.25; else -> "corner" to 1.5 }
            // beam self-weight + wall on tributary beam lengths
            val myBeams = beams.filter { it.beam.a == c.id || it.beam.b == c.id }
            val beamSw = myBeams.sumOf { 25 * it.b * (it.d - t) * it.span / 2 }
            val wallLen = myBeams.sumOf { it.span / 2 }
            var cum = 0.0
            val perStorey = ArrayList<SizedColumn>()
            var size = inp.minColumn to inp.minColumn
            for (s in inp.storeys downTo 1) {
                val roof = s == inp.storeys
                val floor = area * (25 * t + inp.finishes + if (roof) inp.roofLive else inp.liveLoad) + beamSw + (if (roof) 0.0 else inp.wallLoad * wallLen)
                val colSw = 25 * size.first * size.second * (if (s == 1) inp.storeyHeight + inp.plinthHeight else inp.storeyHeight)
                cum += floor + colSw
                val pu = 1.5 * cum * factor
                val p = inp.steelRatio
                val ag = pu * 1e3 / (0.4 * inp.fck * (1 - p) + 0.67 * inp.fy * p) / 1e6      // m², Cl. 39.3
                // keep the architect's short side (≥ minimum), deepen the long side as needed
                val short = max(inp.minColumn, up(min(c.b, c.d), 0.05))
                val long = maxOf(short, up(max(c.b, c.d), 0.05), up(ag / short, 0.05))
                size = if (c.b >= c.d) long to short else short to long     // (along X, along Y)
                perStorey += SizedColumn(c.id, c.label, s, pu, size.first, size.second, pos)
            }
            sized += perStorey.reversed()
        }
        steps += "Columns: tributary area × (slab ${"%.2f".format(25 * t)} + finishes ${inp.finishes} + LL ${inp.liveLoad}/${inp.roofLive} roof) + beams + walls ${inp.wallLoad} kN/m + self-weight, " +
            "summed from the roof; Pu = 1.5 × ΣP × (1.1 interior, 1.25 edge, 1.5 corner) for moments; Ag = Pu / (0.4fck(1−p) + 0.67fy·p), p = ${"%.1f".format(inp.steelRatio * 100)} % (Cl. 39.3); min ${(inp.minColumn * 1000).toInt()} mm (IS 13920)"

        // quantities
        val pnl = panels(layout)
        val slabArea = pnl.sumOf { it.second * it.third }
        val slabVol = slabArea * t * inp.storeys
        val beamVol = beams.sumOf { b -> b.b * (b.d - t) * clear(layout, b) } * inp.storeys
        val colVol = sized.sumOf { it.b * it.d * (if (it.storey == 1) inp.storeyHeight + inp.plinthHeight else inp.storeyHeight) }
        val form = (slabArea + beams.sumOf { b -> (2 * (b.d - t) + b.b) * clear(layout, b) }) * inp.storeys +
            sized.sumOf { 2 * (it.b + it.d) * (if (it.storey == 1) inp.storeyHeight + inp.plinthHeight else inp.storeyHeight) }
        val q = listOf(
            Triple("RCC M${inp.fck.toInt()} — slabs (${(t * 1000).toInt()} mm)", "m³", slabVol),
            Triple("RCC M${inp.fck.toInt()} — beams (below slab)", "m³", beamVol),
            Triple("RCC M${inp.fck.toInt()} — columns (incl. plinth lift)", "m³", colVol),
            Triple("Reinforcement Fe${inp.fy.toInt()} — allowance 80/130/180 kg/m³ (slab/beam/column)", "kg", slabVol * 80 + beamVol * 130 + colVol * 180),
            Triple("Centering & shuttering", "m²", form),
            Triple("Built-up floor area (panels × storeys)", "m²", slabArea * inp.storeys),
        )
        steps += "Quantities: clear spans between column faces; steel by kg/m³ allowance — replace with BBS after detailed design"
        return PreliminaryDesign(layout, inp, t, sized, beams, q, steps)
    }

    /** Slab panels: grid cells whose 4 corners have columns and 4 edges have beams → (index, lx, ly). */
    fun panels(layout: PlanLayout): List<Triple<Pair<Int, Int>, Double, Double>> {
        val at = layout.columns.associateBy { it.gx to it.gy }
        val keys = layout.beams.map { it.key() }.toSet()
        fun beam(p: PlanColumn?, q: PlanColumn?) = p != null && q != null && (if (p.id < q.id) p.id to q.id else q.id to p.id) in keys
        val out = ArrayList<Triple<Pair<Int, Int>, Double, Double>>()
        for (i in 0 until layout.gridX.size - 1) for (j in 0 until layout.gridY.size - 1) {
            val a = at[i to j]; val b = at[i + 1 to j]; val c = at[i + 1 to j + 1]; val d = at[i to j + 1]
            if (beam(a, b) && beam(b, c) && beam(d, c) && beam(a, d))
                out += Triple(i to j, layout.gridX[i + 1] - layout.gridX[i], layout.gridY[j + 1] - layout.gridY[j])
        }
        return out
    }

    private fun clear(layout: PlanLayout, b: SizedBeam): Double {
        val p = layout.column(b.beam.a); val q = layout.column(b.beam.b)
        val alongX = p.gy == q.gy
        val half = if (alongX) (p.b + q.b) / 2 else (p.d + q.d) / 2
        return max(b.span - half, 0.0)
    }

    /** Builds the 3D frame: fixed supports at foundation top, columns per storey, beams per floor, slab DL/LL by floor-load distribution, walls on beams. */
    fun model(design: PreliminaryDesign): FrameModel {
        val lay = design.layout; val inp = design.input; val t = design.slabThickness
        val conc = Material.concrete(inp.fck)
        // level 0 = foundation top (fixed), level k = plinth + k·h (floor k; the top level is the roof)
        val zs = (0..inp.storeys).map { k -> if (k == 0) 0.0 else inp.plinthHeight + k * inp.storeyHeight }
        val nodes = ArrayList<Node>()
        val nid = HashMap<Pair<Int, Int>, Int>()       // (column id, level) → node
        for (k in zs.indices) for (c in lay.columns) { nodes += Node(nodes.size + 1, c.x, c.y, zs[k]); nid[c.id to k] = nodes.size }
        val members = ArrayList<Member>()
        val secCache = HashMap<String, Section>()
        fun rect(b: Double, d: Double): Section = secCache.getOrPut("%.3f×%.3f".format(b, d)) { Section.rect(b, d) }
        val colSize = design.columns.associate { (it.column to it.storey) to (it.b to it.d) }
        for (k in 1 until zs.size) for (c in lay.columns) {
            val (b, d) = colSize[c.id to k] ?: (inp.minColumn to inp.minColumn)
            // Section.rect(b, h): FEM local axes of a vertical member have y = global Y, so h (depth) lies along global X
            members += Member(members.size + 1, nid[c.id to (k - 1)]!!, nid[c.id to k]!!, conc, rect(d, b))
        }
        val beamIds = ArrayList<Pair<Int, Boolean>>()    // member id, is roof
        for (k in 1 until zs.size) for (sb in design.beams) {
            members += Member(members.size + 1, nid[sb.beam.a to k]!!, nid[sb.beam.b to k]!!, conc, rect(sb.b, sb.d))
            beamIds += members.size to (k == zs.size - 1)
        }
        val supports = lay.columns.map { Support.fixed(nid[it.id to 0]!!) }
        val walls = if (inp.wallLoad > 0) beamIds.filter { !it.second }.map { MemberLoad(it.first, LoadDir.GLOBAL_Z, -inp.wallLoad, LoadCase.DL) } else emptyList()
        var m = FrameModel(nodes, members, supports, emptyList(), walls, true,
            title = "From plan: ${lay.columns.size} columns, ${inp.storeys} storeys")
        for (k in 1 until zs.size) {
            val roof = k == zs.size - 1
            m = runCatching { FloorLoad.applyToLevel(m, zs[k], -(25 * t + inp.finishes), LoadCase.DL).model }.getOrDefault(m)
            m = runCatching { FloorLoad.applyToLevel(m, zs[k], -(if (roof) inp.roofLive else inp.liveLoad), LoadCase.LL).model }.getOrDefault(m)
        }
        return m
    }
}
