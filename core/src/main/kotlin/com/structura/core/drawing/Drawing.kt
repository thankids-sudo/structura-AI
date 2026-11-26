package com.structura.core.drawing

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * UCS-based 2D drawing model (AutoCAD style).
 *
 * Every view is drawn in its own User Coordinate System: geometry is written in the view's
 * local coordinates (origin at a meaningful point such as the bottom-left corner of the beam),
 * and the UCS maps it into World Coordinates (WCS). The UCSs are also written to the DXF as
 * named UCS entries, so in AutoCAD:  UCS ▸ Named ▸ "SECTION_A-A" ▸ Set Current  puts the origin
 * back on that corner for further dimensioning or editing.
 *
 * Units: millimetres.
 */

data class P2(val x: Double, val y: Double) {
    operator fun plus(o: P2) = P2(x + o.x, y + o.y)
    operator fun minus(o: P2) = P2(x - o.x, y - o.y)
}

enum class Layer(val dxfName: String, val aciColor: Int) {
    CONCRETE("S-CONC", 7),     // white/black
    REBAR("S-REBAR", 1),       // red
    STIRRUP("S-STIRRUP", 3),   // green
    HATCH("S-HATCH", 8),       // grey
    DIM("S-DIM", 4),           // cyan
    TEXT("S-TEXT", 2),         // yellow
    CENTER("S-CENTER", 6),     // magenta
    STEEL("S-STEEL", 7),       // steel members and plates
    BOLT("S-BOLT", 1),         // bolt holes
}

sealed class Prim {
    abstract val layer: Layer

    data class Line(val a: P2, val b: P2, override val layer: Layer) : Prim()
    data class Circle(val c: P2, val r: Double, override val layer: Layer, val filled: Boolean = false) : Prim()
    data class Text(
        val at: P2, val height: Double, val text: String, override val layer: Layer = Layer.TEXT,
        val rotationDeg: Double = 0.0, val centered: Boolean = false,
    ) : Prim()
}

/** A User Coordinate System: origin and X-axis angle in WCS (Z is out of the drawing). */
data class Ucs(val name: String, val origin: P2, val angleDeg: Double = 0.0) {
    private val c = cos(Math.toRadians(angleDeg))
    private val s = sin(Math.toRadians(angleDeg))
    val xAxis: P2 get() = P2(c, s)
    val yAxis: P2 get() = P2(-s, c)

    fun toWcs(p: P2) = P2(origin.x + p.x * c - p.y * s, origin.y + p.x * s + p.y * c)

    fun toWcs(p: Prim): Prim = when (p) {
        is Prim.Line -> p.copy(a = toWcs(p.a), b = toWcs(p.b))
        is Prim.Circle -> p.copy(c = toWcs(p.c))
        is Prim.Text -> p.copy(at = toWcs(p.at), rotationDeg = p.rotationDeg + angleDeg)
    }
}

/** A view: a title, its UCS and primitives expressed in UCS coordinates. */
class View(val title: String, val ucs: Ucs) {
    val prims = ArrayList<Prim>()

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, layer: Layer) {
        prims += Prim.Line(P2(x1, y1), P2(x2, y2), layer)
    }

    fun rect(x: Double, y: Double, w: Double, h: Double, layer: Layer) {
        line(x, y, x + w, y, layer); line(x + w, y, x + w, y + h, layer)
        line(x + w, y + h, x, y + h, layer); line(x, y + h, x, y, layer)
    }

    fun circle(x: Double, y: Double, r: Double, layer: Layer, filled: Boolean = false) {
        prims += Prim.Circle(P2(x, y), r, layer, filled)
    }

    fun text(x: Double, y: Double, h: Double, s: String, rot: Double = 0.0, centered: Boolean = false) {
        prims += Prim.Text(P2(x, y), h, s, Layer.TEXT, rot, centered)
    }

    /** 45° hatch lines clipped to an axis-aligned rectangle (textbook-style diagonal hatching). */
    fun hatchRect(x: Double, y: Double, w: Double, h: Double, pitch: Double) {
        var c = -h
        while (c < w) {
            // line y' = (x' − c) inside [0,w]×[0,h]
            val x0 = max(0.0, c)
            val x1 = min(w, c + h)
            if (x1 > x0) line(x + x0, y + (x0 - c), x + x1, y + (x1 - c), Layer.HATCH)
            c += pitch
        }
    }

    /** Linear dimension between two points, offset perpendicular by [off] (aligned, with ticks). */
    fun dimH(x1: Double, x2: Double, y: Double, off: Double, txtH: Double, label: String? = null) {
        val yd = y + off
        line(x1, y, x1, yd + (if (off >= 0) txtH * 0.4 else -txtH * 0.4), Layer.DIM)
        line(x2, y, x2, yd + (if (off >= 0) txtH * 0.4 else -txtH * 0.4), Layer.DIM)
        line(x1, yd, x2, yd, Layer.DIM)
        tick(x1, yd, txtH); tick(x2, yd, txtH)
        text((x1 + x2) / 2, yd + txtH * 0.3, txtH, label ?: "%.0f".format(x2 - x1), centered = true)
    }

    fun dimV(y1: Double, y2: Double, x: Double, off: Double, txtH: Double, label: String? = null) {
        val xd = x + off
        line(x, y1, xd + (if (off >= 0) txtH * 0.4 else -txtH * 0.4), y1, Layer.DIM)
        line(x, y2, xd + (if (off >= 0) txtH * 0.4 else -txtH * 0.4), y2, Layer.DIM)
        line(xd, y1, xd, y2, Layer.DIM)
        tick(xd, y1, txtH); tick(xd, y2, txtH)
        text(xd - txtH * 0.3, (y1 + y2) / 2, txtH, label ?: "%.0f".format(y2 - y1), rot = 90.0, centered = true)
    }

    private fun tick(x: Double, y: Double, h: Double) {
        val t = h * 0.35
        line(x - t, y - t, x + t, y + t, Layer.DIM)
    }

    fun wcsPrims(): List<Prim> = prims.map { ucs.toWcs(it) }
}

class Drawing(val title: String) {
    val views = ArrayList<View>()

    fun view(title: String, ucs: Ucs): View = View(title, ucs).also { views += it }

    fun allWcs(): List<Prim> = views.flatMap { it.wcsPrims() }

    /** WCS bounding box (minX, minY, maxX, maxY). */
    fun bounds(): DoubleArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        fun acc(p: P2, pad: Double = 0.0) {
            x0 = min(x0, p.x - pad); y0 = min(y0, p.y - pad); x1 = max(x1, p.x + pad); y1 = max(y1, p.y + pad)
        }
        for (p in allWcs()) when (p) {
            is Prim.Line -> { acc(p.a); acc(p.b) }
            is Prim.Circle -> acc(p.c, p.r)
            is Prim.Text -> acc(p.at, p.height)
        }
        return doubleArrayOf(x0, y0, x1, y1)
    }
}
