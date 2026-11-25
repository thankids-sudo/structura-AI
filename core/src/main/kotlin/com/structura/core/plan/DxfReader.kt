package com.structura.core.plan

import kotlin.math.cos
import kotlin.math.sin

/** 2D plan geometry read from a DXF, in metres. */
sealed class PlanEntity {
    abstract val layer: String
    data class Line(override val layer: String, val x1: Double, val y1: Double, val x2: Double, val y2: Double) : PlanEntity()
    data class Poly(override val layer: String, val pts: List<Pair<Double, Double>>, val closed: Boolean) : PlanEntity()
    data class Circle(override val layer: String, val cx: Double, val cy: Double, val r: Double) : PlanEntity()
}

class DxfPlan(val entities: List<PlanEntity>, val unitScale: Double, val unitName: String, val warnings: List<String>) {
    val layers: List<String> get() = entities.map { it.layer }.distinct().sorted()
}

/**
 * Minimal ASCII DXF reader for building plans: LINE, LWPOLYLINE, POLYLINE/VERTEX, CIRCLE and INSERT
 * (blocks expanded with position, scale and rotation, nested up to 4 levels). Coordinates are converted to
 * metres from $INSUNITS (mm, cm, m, inch, foot); without it, drawings larger than 200 units are taken as mm.
 * Binary DXF and DWG are not supported (save as ASCII DXF from AutoCAD: SAVEAS → DXF).
 */
object DxfReader {
    private class Raw(val type: String, val codes: List<Pair<Int, String>>)

    fun read(text: String): DxfPlan {
        require(!text.startsWith("AutoCAD Binary DXF")) { "Binary DXF — save as ASCII DXF" }
        val lines = text.lines()
        val pairs = ArrayList<Pair<Int, String>>(lines.size / 2)
        var k = 0
        while (k + 1 < lines.size) {
            val c = lines[k].trim().toIntOrNull()
            if (c != null) { pairs += c to lines[k + 1].trim(); k += 2 } else k++
        }
        require(pairs.isNotEmpty()) { "Not a DXF file" }
        val warnings = ArrayList<String>()

        // header $INSUNITS
        var insUnits = 0
        run {
            val i = pairs.indexOfFirst { it.first == 9 && it.second == "\$INSUNITS" }
            if (i >= 0 && i + 1 < pairs.size) insUnits = pairs[i + 1].second.toIntOrNull() ?: 0
        }

        // split into sections → entity lists
        val blocks = HashMap<String, Pair<Pair<Double, Double>, List<Raw>>>()
        val entities = ArrayList<Raw>()
        var section = ""
        var i = 0
        var curBlock: String? = null
        var curBase = 0.0 to 0.0
        var curList = ArrayList<Raw>()
        while (i < pairs.size) {
            val (c, v) = pairs[i]
            if (c == 0 && v == "SECTION") {
                section = pairs.getOrNull(i + 1)?.takeIf { it.first == 2 }?.second ?: ""
                i += 2; continue
            }
            if (c == 0 && v == "ENDSEC") { section = ""; i++; continue }
            if (c == 0 && (section == "ENTITIES" || section == "BLOCKS")) {
                var j = i + 1
                val codes = ArrayList<Pair<Int, String>>()
                while (j < pairs.size && pairs[j].first != 0) { codes += pairs[j]; j++ }
                val raw = Raw(v, codes)
                if (section == "BLOCKS") {
                    when (v) {
                        "BLOCK" -> {
                            curBlock = codes.firstOrNull { it.first == 2 }?.second
                            curBase = (codes.d(10) ?: 0.0) to (codes.d(20) ?: 0.0)
                            curList = ArrayList()
                        }
                        "ENDBLK" -> { curBlock?.let { blocks[it] = curBase to curList }; curBlock = null }
                        else -> if (curBlock != null) curList += raw
                    }
                } else entities += raw
                i = j; continue
            }
            i++
        }

        val out = ArrayList<PlanEntity>()
        // transform: scale sx, sy, rotation, translation (applied after subtracting the block base point)
        class Tf(val sx: Double, val sy: Double, val rot: Double, val tx: Double, val ty: Double, val bx: Double, val by: Double) {
            fun ap(x: Double, y: Double): Pair<Double, Double> {
                val px = (x - bx) * sx; val py = (y - by) * sy
                return (tx + px * cos(rot) - py * sin(rot)) to (ty + px * sin(rot) + py * cos(rot))
            }
        }
        fun emit(list: List<Raw>, tfs: List<Tf>, depth: Int, parentLayer: String?) {
            fun p(x: Double, y: Double): Pair<Double, Double> { var q = x to y; for (t in tfs) q = t.ap(q.first, q.second); return q }
            val scale = tfs.fold(1.0) { a, t -> a * kotlin.math.abs(t.sx) }
            var idx = 0
            while (idx < list.size) {
                val r = list[idx]
                val layer = (r.codes.s(8) ?: "0").let { if (it == "0" && parentLayer != null) parentLayer else it }
                when (r.type) {
                    "LINE" -> {
                        val a = p(r.codes.d(10) ?: 0.0, r.codes.d(20) ?: 0.0); val b = p(r.codes.d(11) ?: 0.0, r.codes.d(21) ?: 0.0)
                        out += PlanEntity.Line(layer, a.first, a.second, b.first, b.second)
                    }
                    "LWPOLYLINE" -> {
                        val xs = r.codes.filter { it.first == 10 }.map { it.second.toDouble() }
                        val ys = r.codes.filter { it.first == 20 }.map { it.second.toDouble() }
                        val closed = ((r.codes.s(70)?.toIntOrNull() ?: 0) and 1) == 1
                        if (xs.size >= 2) out += PlanEntity.Poly(layer, xs.indices.map { p(xs[it], ys.getOrElse(it) { 0.0 }) }, closed)
                    }
                    "POLYLINE" -> {
                        val closed = ((r.codes.s(70)?.toIntOrNull() ?: 0) and 1) == 1
                        val pts = ArrayList<Pair<Double, Double>>()
                        var j = idx + 1
                        while (j < list.size && list[j].type == "VERTEX") {
                            pts += p(list[j].codes.d(10) ?: 0.0, list[j].codes.d(20) ?: 0.0); j++
                        }
                        if (j < list.size && list[j].type == "SEQEND") j++
                        if (pts.size >= 2) out += PlanEntity.Poly(layer, pts, closed)
                        idx = j; continue
                    }
                    "CIRCLE" -> {
                        val c = p(r.codes.d(10) ?: 0.0, r.codes.d(20) ?: 0.0)
                        out += PlanEntity.Circle(layer, c.first, c.second, (r.codes.d(40) ?: 0.0) * scale)
                    }
                    "INSERT" -> {
                        val name = r.codes.s(2)
                        val blk = name?.let { blocks[it] }
                        if (blk != null && depth < 4) {
                            val t = Tf(r.codes.d(41) ?: 1.0, r.codes.d(42) ?: 1.0, Math.toRadians(r.codes.d(50) ?: 0.0),
                                r.codes.d(10) ?: 0.0, r.codes.d(20) ?: 0.0, blk.first.first, blk.first.second)
                            emit(blk.second, listOf(t) + tfs, depth + 1, layer)
                        }
                    }
                }
                idx++
            }
        }
        emit(entities, emptyList(), 0, null)

        // units → metres
        val (scale, unitName) = when (insUnits) {
            1 -> 0.0254 to "inch"
            2 -> 0.3048 to "foot"
            4 -> 0.001 to "mm"
            5 -> 0.01 to "cm"
            6 -> 1.0 to "m"
            else -> {
                val ext = out.flatMap { e ->
                    when (e) {
                        is PlanEntity.Line -> listOf(e.x1, e.x2, e.y1, e.y2)
                        is PlanEntity.Poly -> e.pts.flatMap { listOf(it.first, it.second) }
                        is PlanEntity.Circle -> listOf(e.cx, e.cy)
                    }
                }
                val span = if (ext.isEmpty()) 0.0 else ext.max() - ext.min()
                warnings += "\$INSUNITS not set — units assumed from the drawing size"
                if (span > 200) 0.001 to "mm (assumed)" else 1.0 to "m (assumed)"
            }
        }
        val ents = out.map { e ->
            when (e) {
                is PlanEntity.Line -> e.copy(x1 = e.x1 * scale, y1 = e.y1 * scale, x2 = e.x2 * scale, y2 = e.y2 * scale)
                is PlanEntity.Poly -> e.copy(pts = e.pts.map { it.first * scale to it.second * scale })
                is PlanEntity.Circle -> e.copy(cx = e.cx * scale, cy = e.cy * scale, r = e.r * scale)
            }
        }
        if (ents.isEmpty()) warnings += "No LINE / POLYLINE / CIRCLE / INSERT entities found"
        return DxfPlan(ents, scale, unitName, warnings.distinct())
    }

    private fun List<Pair<Int, String>>.d(code: Int) = firstOrNull { it.first == code }?.second?.toDoubleOrNull()
    private fun List<Pair<Int, String>>.s(code: Int) = firstOrNull { it.first == code }?.second
}
