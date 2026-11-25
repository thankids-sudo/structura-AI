package com.structura.core.plan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device vision for scanned / photographed / PDF plans. Columns on architectural plans are drawn solid or
 * hatched; walls and text are thin. Pipeline:
 *  1. adaptive threshold (local mean over a window ≈ 1/40 of the image, so uneven lighting in photos is tolerated),
 *  2. morphological closing (fills hatching) then opening (removes lines and text thinner than the kernel),
 *  3. connected components → compact, well-filled blobs of similar size = columns.
 * The result is in pixels; [toPlan] converts it to metres with a scale from one known span and returns a
 * [DxfPlan] (layer COLUMN) so the rest of Drawing → Structure works unchanged.
 * Photos must be taken square-on (no perspective correction).
 */
object PlanImageReader {
    data class Blob(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val pixels: Int) {
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
        val cx get() = (x0 + x1) / 2.0
        val cy get() = (y0 + y1) / 2.0
        val fill get() = pixels.toDouble() / (w * h)
    }

    class Detection(val width: Int, val height: Int, val columns: List<Blob>, val rejected: Int, val notes: List<String>) {
        /** Column centre lines in pixels, clustered (tolerance ½ median column size). */
        fun gridPixelsX() = cluster(columns.map { it.cx }, tol())
        fun gridPixelsY() = cluster(columns.map { it.cy }, tol())
        private fun tol() = 0.5 * (columns.map { max(it.w, it.h).toDouble() }.sorted().let { if (it.isEmpty()) 10.0 else it[it.size / 2] })
    }

    /**
     * @param gray luminance 0–255, row-major, [w] × [h]
     * @param thinPx strokes up to about this width (px) are treated as lines/text and removed (default from image size)
     */
    fun detect(gray: IntArray, w: Int, h: Int, thinPx: Int = 0, offset: Int = 12): Detection {
        require(gray.size == w * h) { "Image size mismatch" }
        val notes = ArrayList<String>()
        // 1. adaptive threshold with an integral image
        val win = max(15, min(w, h) / 40) or 1
        val integ = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) { row += gray[y * w + x]; integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row }
        }
        val r = win / 2
        val bin = BooleanArray(w * h)
        for (y in 0 until h) {
            val ya = max(0, y - r); val yb = min(h - 1, y + r)
            for (x in 0 until w) {
                val xa = max(0, x - r); val xb = min(w - 1, x + r)
                val sum = integ[(yb + 1) * (w + 1) + xb + 1] - integ[ya * (w + 1) + xb + 1] - integ[(yb + 1) * (w + 1) + xa] + integ[ya * (w + 1) + xa]
                val mean = sum.toDouble() / ((xb - xa + 1) * (yb - ya + 1))
                val v = gray[y * w + x]
                bin[y * w + x] = v < mean - offset || v < 60      // locally dark, or plainly black (inside large fills)
            }
        }
        // 2. close (fill hatching) then open (remove thin strokes)
        val k = if (thinPx > 0) thinPx else max(2, min(w, h) / 400)
        val closed = erode(dilate(bin, w, h, 1), w, h, 1)
        val opened = dilate(erode(closed, w, h, k), w, h, k)
        notes += "Threshold window $win px; strokes ≤ ${2 * k + 1} px removed"
        // 3. components
        val blobs = components(opened, w, h)
        val minSide = max(4, min(w, h) / 250)
        val cand = blobs.filter { b ->
            val aspect = max(b.w, b.h).toDouble() / min(b.w, b.h)
            b.w >= minSide && b.h >= minSide && aspect <= 3.5 && b.fill >= 0.6 && max(b.w, b.h) < min(w, h) / 6
        }
        if (cand.isEmpty()) return Detection(w, h, emptyList(), blobs.size, notes + "No solid / hatched columns found — try a cleaner scan or the DXF route")
        val med = cand.map { it.w * it.h }.sorted()[cand.size / 2].toDouble()
        val cols = cand.filter { it.w * it.h in (0.2 * med)..(5.0 * med) }
        notes += "${cols.size} column blobs (median ${"%.0f".format(kotlin.math.sqrt(med))} px), ${blobs.size - cols.size} other dark shapes ignored"
        return Detection(w, h, cols, blobs.size - cols.size, notes)
    }

    /**
     * Converts detected columns to a metric plan. Scale from [spanMetres] = distance between the first two
     * X grid lines (left to right) of the detection. Image y points down, plan y up.
     */
    fun toPlan(det: Detection, spanMetres: Double): DxfPlan {
        val gx = det.gridPixelsX()
        require(gx.size >= 2) { "Need at least two column lines across the plan to set the scale" }
        val mpp = spanMetres / (gx[1] - gx[0])
        val ents = det.columns.map { b ->
            val x0 = b.x0 * mpp; val x1 = (b.x1 + 1) * mpp
            val y0 = (det.height - b.y1 - 1) * mpp; val y1 = (det.height - b.y0) * mpp
            PlanEntity.Poly("COLUMN", listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1), true)
        }
        return DxfPlan(ents, mpp, "image (${"%.1f".format(1000 * mpp)} mm/px)",
            listOf("Read from image: column sizes are ± 1 px (${"%.0f".format(1000 * mpp)} mm) — confirm against the drawing"))
    }

    // ---- morphology (separable square kernels) ----
    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray = pass(pass(src, w, h, r, true, true), w, h, r, false, true)
    private fun erode(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray = pass(pass(src, w, h, r, true, false), w, h, r, false, false)

    /** Running-window OR (dilate) / AND (erode) along rows ([horizontal]) or columns. Outside the image counts as background. */
    private fun pass(src: BooleanArray, w: Int, h: Int, r: Int, horizontal: Boolean, dilate: Boolean): BooleanArray {
        val out = BooleanArray(w * h)
        val n = if (horizontal) w else h; val lines = if (horizontal) h else w
        for (l in 0 until lines) {
            fun idx(i: Int) = if (horizontal) l * w + i else i * w + l
            var count = 0                         // set pixels in the window
            for (i in 0..min(r, n - 1)) if (src[idx(i)]) count++
            for (i in 0 until n) {
                val lo = i - r; val hi = i + r
                val size = min(hi, n - 1) - max(lo, 0) + 1
                out[idx(i)] = if (dilate) count > 0 else (count == size && lo >= 0 && hi <= n - 1)
                // slide
                if (lo >= 0 && src[idx(lo)]) count--
                if (hi + 1 < n && src[idx(hi + 1)]) count++
            }
        }
        return out
    }

    private fun components(bin: BooleanArray, w: Int, h: Int): List<Blob> {
        val seen = BooleanArray(w * h)
        val out = ArrayList<Blob>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!bin[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var n = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                n++; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (x > 0 && bin[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < w - 1 && bin[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && bin[p - w] && !seen[p - w]) { seen[p - w] = true; stack[sp++] = p - w }
                if (y < h - 1 && bin[p + w] && !seen[p + w]) { seen[p + w] = true; stack[sp++] = p + w }
            }
            out += Blob(x0, y0, x1, y1, n)
        }
        return out
    }

    internal fun cluster(v: List<Double>, tol: Double): List<Double> {
        val out = ArrayList<MutableList<Double>>()
        for (x in v.sorted()) if (out.isNotEmpty() && abs(x - out.last().last()) <= tol) out.last() += x else out += mutableListOf(x)
        return out.map { it.average() }
    }
}
