package com.structura.core.viz

import com.structura.core.viz.Scene3D.Load
import com.structura.core.viz.Scene3D.Mat
import com.structura.core.viz.Scene3D.V
import com.structura.core.viz.Scene3D.Companion.along
import com.structura.core.viz.Scene3D.Companion.rect
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 3D scenes for the wall modules (retaining, counterfort, RCC, masonry, compound). */
object WallScenes {
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
    private fun f0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)

    /** Spacing (m) of φ[dia] bars for [ast] mm²/m, capped at [cap] m. */
    fun spacingFor(ast: Double, dia: Double, cap: Double = 0.3): Double = min(cap, floor(1000 * PI * dia * dia / 4 / max(ast, 1.0) / 10) * 10 / 1000).coerceAtLeast(0.075)

    /**
     * Cantilever retaining wall, a [len] m strip. [sStem]/[sHeel]/[sToe] main bar spacings (m), [sDist] distribution spacing.
     */
    fun cantilever(
        h: Double, b: Double, toe: Double, d: Double, stemTop: Double, stemBot: Double, ka: Double, gamma: Double, q: Double,
        qmax: Double, qmin: Double, pa: Double, ps: Double, cover: Double, bar: Double, sStem: Double, sHeel: Double, sToe: Double, sDist: Double,
        len: Double = 3.0,
    ): Scene3D {
        val c = cover / 1000; val r = bar / 2000
        val heel = b - toe - stemBot
        val back = { z: Double -> toe + stemBot + (stemTop - stemBot) * (z - d) / (h - d) }
        val sb = Scene3D.Builder("Cantilever retaining wall — H ${f2(h)} m")
        sb.stage("Excavation and blinding")
        sb.prism(rect(-1.2, -0.25, b + 2.2, -0.05), -0.6, len + 0.6, Mat.GROUND)
        sb.prism(rect(-0.05, -0.05, b + 0.05, 0.0), 0.0, len, Mat.CONCRETE)
        sb.stage("Base slab — toe and heel")
        sb.prism(rect(0.0, 0.0, b, d), 0.0, len, Mat.CONCRETE)
        sb.label(V(toe / 2, len, d + 0.05), "toe ${f2(toe)}"); sb.label(V(toe + stemBot + heel / 2, len, d + 0.05), "heel ${f2(heel)}")
        sb.stage("Stem")
        sb.prism(listOf(toe to d, toe + stemBot to d, toe + stemTop to h, toe to h), 0.0, len, Mat.CONCRETE)
        sb.stage("Backfill and surcharge")
        val xe = b + 1.6
        sb.prism(listOf(toe + stemBot to d, b to d, b to 0.0, xe to 0.0, xe to h, toe + stemTop to h), 0.0, len, Mat.SOIL)
        sb.prism(rect(-1.2, 0.0, toe, min(d + 0.3, h)), 0.0, len, Mat.GROUND)                 // fill in front of the toe
        if (q > 0) for (x in along(toe + stemTop + 0.25, xe - 0.15, 0.45)) sb.arrow(V(x, len / 2, h + 0.55), V(x, len / 2, h), Load.SURCHARGE)
        if (q > 0) sb.label(V(xe - 0.4, len / 2, h + 0.7), "q = ${f2(q)} kN/m²")
        sb.stage("Active earth pressure (Rankine)")
        val pk = ka * (gamma * h + q); val sc = 1.2 / max(pk, 1.0)
        val xp = b
        for (z in along(0.08, h - 0.05, 0.3)) {
            val p = ka * (gamma * (h - z) + q)
            sb.arrow(V(xp + p * sc, len, z), V(xp, len, z), Load.EARTH)
        }
        sb.line(V(xp + ka * q * sc, len, h), V(xp + pk * sc, len, 0.0), Load.EARTH); sb.line(V(xp, len, h), V(xp, len, 0.0), Load.EARTH, dashed = true)
        sb.label(V(xp + pk * sc + 0.1, len, 0.25), "Ka(γH + q) = ${f2(pk)} kN/m²", strong = true)
        sb.label(V(xp + pk * sc * 0.5 + 0.1, len, h / 3), "Pa ${f2(pa)} + Ps ${f2(ps)} kN/m")
        sb.stage("Base pressure")
        val qs = 0.9 / max(qmax, 1.0)
        for (x in along(0.05, b - 0.05, 0.25)) {
            val p = qmax + (qmin - qmax) * x / b
            sb.arrow(V(x, len, -0.3 - max(p, 0.0) * qs), V(x, len, -0.3), Load.BEARING)
        }
        sb.line(V(0.0, len, -0.3 - qmax * qs), V(b, len, -0.3 - max(qmin, 0.0) * qs), Load.BEARING)
        sb.label(V(-0.2, len, -0.5 - qmax * qs), "qmax ${f0(qmax)}", strong = true); sb.label(V(b - 0.4, len, -0.5 - max(qmin, 0.0) * qs), "qmin ${f0(qmin)} kN/m²")
        sb.stage("Reinforcement")
        val ys = along(c + r, len - c - r, sStem, 80)
        ys.forEachIndexed { k, y ->
            val top = if (k % 2 == 1) d + (h - d) / 2 + 0.5 else h - c                     // alternate bars curtailed above mid-height
            val xb0 = back(d) - c - r
            sb.bar(listOf(V(xb0 + 0.35, y, c + 2 * r), V(xb0, y, c + 2 * r), V(back(top) - c - r, y, top)), bar)
        }
        for (y in along(c + r, len - c - r, 0.3)) sb.bar(listOf(V(toe + c + r, y, c + 2 * r), V(toe + c + r, y, h - c)), 10.0, main = false)
        for (z in along(d + 0.1, h - c, sDist)) {
            sb.bar(listOf(V(back(z) - c - 3 * r, c, z), V(back(z) - c - 3 * r, len - c, z)), 10.0, main = false)
            sb.bar(listOf(V(toe + c + 3 * r, c, z), V(toe + c + 3 * r, len - c, z)), 10.0, main = false)
        }
        for (y in along(c + r, len - c - r, sHeel, 80)) sb.bar(listOf(V(toe, y, d - c - r), V(b - c, y, d - c - r), V(b - c, y, c + 2 * r)), bar)
        for (y in along(c + r, len - c - r, sToe, 80)) sb.bar(listOf(V(c, y, d - c), V(c, y, c + r), V(min(b - c, toe + stemBot + 0.6), y, c + r)), bar)
        for (x in along(c + 0.05, b - c - 0.05, sDist)) {
            sb.bar(listOf(V(x, c, c + 3 * r), V(x, len - c, c + 3 * r)), 10.0, main = false)
            sb.bar(listOf(V(x, c, d - c - 3 * r), V(x, len - c, d - c - 3 * r)), 10.0, main = false)
        }
        sb.label(V(back(h * 0.8) - 0.1, 0.0, h * 0.8), "φ${f0(bar)} @ ${f0(sStem * 1000)} earth face", strong = true)
        sb.label(V(b - heel / 2, 0.0, d + 0.15), "φ${f0(bar)} @ ${f0(sHeel * 1000)} top")
        sb.label(V(toe / 2, 0.0, -0.15), "φ${f0(bar)} @ ${f0(sToe * 1000)} bottom")
        sb.stage("Dimensions")
        sb.dim(V(0.0, -0.4, -0.05), V(b, -0.4, -0.05), "B = ${f2(b)} m")
        sb.dim(V(-0.5, -0.4, 0.0), V(-0.5, -0.4, h), "H = ${f2(h)} m")
        sb.dim(V(toe, -0.4, h + 0.15), V(toe + stemTop, -0.4, h + 0.15), "${f0(stemTop * 1000)}")
        sb.dim(V(toe, -0.4, d + 0.12), V(toe + stemBot, -0.4, d + 0.12), "${f0(stemBot * 1000)}")
        sb.dim(V(b + 0.15, -0.4, 0.0), V(b + 0.15, -0.4, d), "${f0(d * 1000)}")
        return sb.build()
    }

    /** Counterfort wall: two bays of spacing [l], counterforts [tc] thick. */
    fun counterfort(
        h: Double, b: Double, toe: Double, d: Double, stem: Double, l: Double, tc: Double, ka: Double, gamma: Double, q: Double,
        qmax: Double, qmin: Double, cover: Double, bar: Double, sStem: Double, sHeel: Double, sToe: Double, nCf: Int,
    ): Scene3D {
        val c = cover / 1000; val r = bar / 2000
        val len = 2 * l
        val heelEnd = b
        val cfs = listOf(l / 2, 3 * l / 2)
        val sb = Scene3D.Builder("Counterfort retaining wall — H ${f2(h)} m")
        sb.stage("Excavation and blinding")
        sb.prism(rect(-1.2, -0.25, b + 2.2, -0.05), -0.6, len + 0.6, Mat.GROUND)
        sb.prism(rect(-0.05, -0.05, b + 0.05, 0.0), 0.0, len, Mat.CONCRETE)
        sb.stage("Base slab")
        sb.prism(rect(0.0, 0.0, b, d), 0.0, len, Mat.CONCRETE)
        sb.stage("Stem and counterforts")
        sb.prism(rect(toe, d, toe + stem, h), 0.0, len, Mat.CONCRETE)
        for (y in cfs) sb.prism(listOf(toe + stem to d, heelEnd to d, toe + stem to h), y - tc / 2, y + tc / 2, Mat.CONCRETE)
        sb.label(V(toe + stem + (b - toe - stem) / 3, cfs[1] + tc / 2, d + (h - d) / 3), "counterfort ${f0(tc * 1000)} @ ${f2(l)} m")
        sb.stage("Backfill and surcharge")
        val xe = b + 1.6
        sb.prism(listOf(toe + stem to d, b to d, b to 0.0, xe to 0.0, xe to h, toe + stem to h), 0.0, len, Mat.SOIL)
        sb.prism(rect(-1.2, 0.0, toe, min(d + 0.3, h)), 0.0, len, Mat.GROUND)
        if (q > 0) for (x in along(toe + stem + 0.25, xe - 0.15, 0.5)) sb.arrow(V(x, len / 2, h + 0.55), V(x, len / 2, h), Load.SURCHARGE)
        sb.stage("Active earth pressure")
        val pk = ka * (gamma * h + q); val sc = 1.4 / max(pk, 1.0)
        for (z in along(0.08, h - 0.05, 0.35)) sb.arrow(V(b + ka * (gamma * (h - z) + q) * sc, len, z), V(b, len, z), Load.EARTH)
        sb.line(V(b + ka * q * sc, len, h), V(b + pk * sc, len, 0.0), Load.EARTH)
        sb.label(V(b + pk * sc + 0.1, len, 0.25), "Ka(γH + q) = ${f2(pk)} kN/m²", strong = true)
        sb.stage("Base pressure")
        val qs = 0.9 / max(qmax, 1.0)
        for (x in along(0.05, b - 0.05, 0.3)) sb.arrow(V(x, len, -0.3 - max(qmax + (qmin - qmax) * x / b, 0.0) * qs), V(x, len, -0.3), Load.BEARING)
        sb.label(V(-0.2, len, -0.5 - qmax * qs), "qmax ${f0(qmax)} kN/m²", strong = true)
        sb.stage("Reinforcement")
        // stem: horizontal main bars spanning between counterforts (earth face at supports, front face mid-span)
        for (z in along(d + c, h - c, sStem, 80)) {
            sb.bar(listOf(V(toe + stem - c - r, c, z), V(toe + stem - c - r, len - c, z)), bar)
            sb.bar(listOf(V(toe + c + r, c, z), V(toe + c + r, len - c, z)), bar, main = false)
        }
        for (y in along(c, len - c, 0.3)) sb.bar(listOf(V(toe + stem - c - 3 * r, y, c), V(toe + stem - c - 3 * r, y, h - c)), 10.0, main = false)
        // heel: bars along the wall (span between counterforts), top and bottom
        for (x in along(toe + stem + c, b - c, sHeel, 80)) {
            sb.bar(listOf(V(x, c, d - c - r), V(x, len - c, d - c - r)), bar)
            sb.bar(listOf(V(x, c, c + r), V(x, len - c, c + r)), bar, main = false)
        }
        for (y in along(c, len - c, sToe, 80)) sb.bar(listOf(V(c, y, d - c), V(c, y, c + 3 * r), V(toe + stem + 0.6, y, c + 3 * r)), bar)
        // counterfort main bars along the inclined back, ties into stem and heel
        for (y in cfs) {
            for (k in 0 until nCf.coerceIn(2, 8)) {
                val off = (k % 2) * 0.05; val yy = y - tc / 2 + c + (k / 2) * min(0.08, (tc - 2 * c) / 3)
                sb.bar(listOf(V(heelEnd - c - off, yy, c), V(heelEnd - c - off, yy, d - c), V(toe + stem + c, yy, h - c - off * 2)), bar)
            }
            for (z in along(d + 0.2, h - 0.3, 0.45)) {
                val xIn = toe + stem + (b - toe - stem) * (h - z) / (h - d) - c
                sb.bar(listOf(V(toe + stem - c, y - tc / 2 + c, z), V(xIn, y - tc / 2 + c, z), V(xIn, y + tc / 2 - c, z), V(toe + stem - c, y + tc / 2 - c, z)), 10.0, main = false)
            }
            for (x in along(toe + stem + 0.2, b - 0.2, 0.4)) {
                val zt = d + (h - d) * (b - x) / (b - toe - stem) - c
                sb.bar(listOf(V(x, y - tc / 2 + c, c), V(x, y - tc / 2 + c, zt), V(x, y + tc / 2 - c, zt), V(x, y + tc / 2 - c, c)), 10.0, main = false)
            }
        }
        sb.label(V(toe + stem, 0.0, h * 0.75), "stem φ${f0(bar)} @ ${f0(sStem * 1000)} horizontal", strong = true)
        sb.label(V((toe + stem + b) / 2, cfs[0], (d + h) / 2), "$nCf φ${f0(bar)} inclined + ties")
        sb.stage("Dimensions")
        sb.dim(V(0.0, -0.4, -0.05), V(b, -0.4, -0.05), "B = ${f2(b)} m")
        sb.dim(V(-0.5, -0.4, 0.0), V(-0.5, -0.4, h), "H = ${f2(h)} m")
        sb.dim(V(b + 0.2, cfs[0], d), V(b + 0.2, cfs[1], d), "${f2(l)} m c/c")
        sb.dim(V(toe, -0.4, h + 0.15), V(toe + stem, -0.4, h + 0.15), "${f0(stem * 1000)}")
        return sb.build()
    }

    /** RCC wall panel [len] m long with vertical / horizontal bars on both faces and the axial load. */
    fun rccWall(h: Double, t: Double, pu: Double, sV: Double, sH: Double, dv: Double, dh: Double, cover: Double = 25.0, len: Double = 2.4): Scene3D {
        val tt = t / 1000; val c = cover / 1000
        val sb = Scene3D.Builder("RCC wall — ${f0(t)} mm")
        sb.stage("Footing")
        sb.prism(rect(-0.4, -0.4, tt + 0.4, 0.0), -0.2, len + 0.2, Mat.CONCRETE)
        sb.stage("Reinforcement")
        for (y in along(c, len - c, sV, 80)) for (x in listOf(c, tt - c)) sb.bar(listOf(V(x, y, -0.3), V(x, y, h - c)), dv)
        for (z in along(0.05, h - c, sH, 80)) for (x in listOf(c + 0.012, tt - c - 0.012)) sb.bar(listOf(V(x, c, z), V(x, len - c, z)), dh, main = false)
        sb.stage("Wall concrete")
        sb.prism(rect(0.0, 0.0, tt, h), 0.0, len, Mat.CONCRETE)
        sb.stage("Axial load")
        for (y in along(0.1, len - 0.1, 0.3)) sb.arrow(V(tt / 2, y, h + 0.6), V(tt / 2, y, h), Load.GRAVITY)
        sb.label(V(tt / 2, len / 2, h + 0.75), "Pu = ${f0(pu)} kN/m", strong = true)
        sb.stage("Dimensions")
        sb.dim(V(-0.3, -0.3, 0.0), V(-0.3, -0.3, h), "H = ${f2(h)} m"); sb.dim(V(0.0, -0.3, h + 0.1), V(tt, -0.3, h + 0.1), "${f0(t)}")
        sb.label(V(tt, len, h * 0.6), "V φ${f0(dv)} @ ${f0(sV * 1000)}, H φ${f0(dh)} @ ${f0(sH * 1000)} both faces")
        return sb.build()
    }

    /** Load-bearing masonry wall with courses shown, and a compound wall with piers when [pierSpacing] > 0. */
    fun masonry(h: Double, t: Double, load: Double, fw: Double = 0.0, fd: Double = 0.0, pierSpacing: Double = 0.0, len: Double = 4.0, title: String = "Masonry wall", wind: Double = 0.0): Scene3D {
        val tt = t / 1000
        val sb = Scene3D.Builder(title)
        sb.stage("Foundation")
        val w = if (fw > 0) fw else tt + 0.4; val dd = if (fd > 0) fd else 0.45
        sb.prism(rect(-1.0, -dd - 0.1, tt + 1.0, -dd), -0.3, len + 0.3, Mat.GROUND)
        sb.prism(rect(tt / 2 - w / 2, -dd, tt / 2 + w / 2, -dd + 0.15), 0.0, len, Mat.CONCRETE)
        sb.prism(rect(tt / 2 - w / 2 + 0.115, -dd + 0.15, tt / 2 + w / 2 - 0.115, 0.0), 0.0, len, Mat.MASONRY)
        sb.stage("Masonry courses")
        var z = 0.0; var k = 0
        while (z < h - 1e-6) {
            val top = min(h, z + 0.6)                                  // lifts of ~6 courses for the build-up
            sb.prism(rect(0.0, z, tt, top), 0.0, len, Mat.MASONRY)
            if (pierSpacing > 0) for (y in along(0.0, len, pierSpacing)) sb.prism(rect(-0.115, z, tt + 0.115, top), max(0.0, y - 0.175), min(len, y + 0.175), Mat.MASONRY)
            z = top; k++
        }
        if (load > 0) {
            sb.stage("Loads")
            for (y in along(0.1, len - 0.1, 0.35)) sb.arrow(V(tt / 2, y, h + 0.6), V(tt / 2, y, h), Load.GRAVITY)
            sb.label(V(tt / 2, len / 2, h + 0.75), "${f0(load)} kN/m", strong = true)
        }
        if (wind > 0) {
            sb.stage("Wind")
            for (y in along(0.3, len - 0.3, 0.9)) for (zz in along(0.2, h - 0.1, 0.4)) sb.arrow(V(-0.9, y, zz), V(-0.05, y, zz), Load.WIND)
            sb.label(V(-1.0, len / 2, h + 0.2), "wind ${f2(wind)} kN/m", strong = true)
        }
        sb.stage("Dimensions")
        sb.dim(V(-0.3, -0.3, 0.0), V(-0.3, -0.3, h), "H = ${f2(h)} m"); sb.dim(V(0.0, -0.3, h + 0.1), V(tt, -0.3, h + 0.1), "${f0(t)}")
        if (pierSpacing > 0) sb.dim(V(tt + 0.3, 0.0, h), V(tt + 0.3, pierSpacing, h), "piers @ ${f2(pierSpacing)} m")
        return sb.build()
    }
}
