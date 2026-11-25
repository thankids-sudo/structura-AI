package com.structura.core.plan

/** A small ASCII DXF (mm) used for the "Try a sample plan" button and the tests: 4 × 3 grid, columns on layer COLUMN
 *  (row C inserted as blocks), grid lines on layer GRID, plus walls and a door that must be ignored. */
object SamplePlan {
    val xs = listOf(0.0, 4000.0, 8500.0, 12500.0)
    val ys = listOf(0.0, 5000.0, 9000.0)

    fun dxf(): String = buildString {
        fun p(c: Int, v: Any) { append(c).append('\n').append(v).append('\n') }
        fun rect(layer: String, cx: Double, cy: Double, w: Double, h: Double) {
            p(0, "LWPOLYLINE"); p(8, layer); p(90, 4); p(70, 1)
            listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).forEach { (sx, sy) -> p(10, cx + sx * w / 2); p(20, cy + sy * h / 2) }
        }
        fun line(layer: String, x1: Double, y1: Double, x2: Double, y2: Double) { p(0, "LINE"); p(8, layer); p(10, x1); p(20, y1); p(11, x2); p(21, y2) }
        p(0, "SECTION"); p(2, "HEADER"); p(9, "\$INSUNITS"); p(70, 4); p(0, "ENDSEC")
        p(0, "SECTION"); p(2, "BLOCKS")
        p(0, "BLOCK"); p(8, "0"); p(2, "COL300X450"); p(70, 0); p(10, 0.0); p(20, 0.0)
        rect("0", 0.0, 0.0, 300.0, 450.0)
        p(0, "ENDBLK")
        p(0, "ENDSEC")
        p(0, "SECTION"); p(2, "ENTITIES")
        for (x in xs) line("GRID", x, -1500.0, x, ys.last() + 1500)
        for (y in ys) line("GRID", -1500.0, y, xs.last() + 1500, y)
        for ((j, y) in ys.withIndex()) for (x in xs) {
            if (j < 2) rect("COLUMN", x + 12.0, y - 8.0, 300.0, 450.0)       // slightly off-grid, as drawn
            else { p(0, "INSERT"); p(8, "COLUMN"); p(2, "COL300X450"); p(10, x); p(20, y) }
        }
        rect("WALL", 2000.0, 0.0, 1000.0, 230.0)     // wall piece — not on a COL layer
        rect("DOOR", 6000.0, 5000.0, 900.0, 100.0)
        p(0, "ENDSEC"); p(0, "EOF")
    }
}
