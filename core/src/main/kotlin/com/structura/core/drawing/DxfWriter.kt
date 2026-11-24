package com.structura.core.drawing

import java.util.Locale

/**
 * Writes a [Drawing] as an ASCII DXF, release 12 (AC1009) — opens in every AutoCAD version,
 * BricsCAD, ZWCAD, LibreCAD, DraftSight and QCAD.
 *
 *  • one layer per [Layer] with its ACI colour
 *  • one named UCS per view (TABLES ▸ UCS), so each view's origin can be restored in AutoCAD
 *  • filled bars are written as donuts (closed 2-vertex POLYLINE with width), exactly as DONUT does
 *  • φ → %%c, ° → %%d (AutoCAD control codes); other non-ASCII characters are transliterated
 */
object DxfWriter {

    fun write(dwg: Drawing): String {
        val sb = StringBuilder()
        fun g(code: Int, v: Any) {
            sb.append(code).append("\r\n")
            sb.append(if (v is Double) fmt(v) else v.toString()).append("\r\n")
        }
        val bb = dwg.bounds()

        // HEADER
        g(0, "SECTION"); g(2, "HEADER")
        g(9, "\$ACADVER"); g(1, "AC1009")
        g(9, "\$INSBASE"); g(10, 0.0); g(20, 0.0); g(30, 0.0)
        g(9, "\$EXTMIN"); g(10, bb[0]); g(20, bb[1]); g(30, 0.0)
        g(9, "\$EXTMAX"); g(10, bb[2]); g(20, bb[3]); g(30, 0.0)
        g(9, "\$LUNITS"); g(70, 2)
        g(9, "\$LUPREC"); g(70, 0)
        g(0, "ENDSEC")

        // TABLES
        g(0, "SECTION"); g(2, "TABLES")
        g(0, "TABLE"); g(2, "LTYPE"); g(70, 1)
        g(0, "LTYPE"); g(2, "CONTINUOUS"); g(70, 0); g(3, "Solid line"); g(72, 65); g(73, 0); g(40, 0.0)
        g(0, "ENDTAB")

        g(0, "TABLE"); g(2, "LAYER"); g(70, Layer.entries.size + 1)
        g(0, "LAYER"); g(2, "0"); g(70, 0); g(62, 7); g(6, "CONTINUOUS")
        for (l in Layer.entries) { g(0, "LAYER"); g(2, l.dxfName); g(70, 0); g(62, l.aciColor); g(6, "CONTINUOUS") }
        g(0, "ENDTAB")

        g(0, "TABLE"); g(2, "STYLE"); g(70, 1)
        g(0, "STYLE"); g(2, "STANDARD"); g(70, 0); g(40, 0.0); g(41, 1.0); g(50, 0.0); g(71, 0); g(42, 2.5); g(3, "txt"); g(4, "")
        g(0, "ENDTAB")

        g(0, "TABLE"); g(2, "UCS"); g(70, dwg.views.size)
        for (v in dwg.views) {
            val u = v.ucs
            g(0, "UCS"); g(2, u.name); g(70, 0)
            g(10, u.origin.x); g(20, u.origin.y); g(30, 0.0)
            g(11, u.xAxis.x); g(21, u.xAxis.y); g(31, 0.0)
            g(12, u.yAxis.x); g(22, u.yAxis.y); g(32, 0.0)
        }
        g(0, "ENDTAB")
        g(0, "ENDSEC")

        // ENTITIES
        g(0, "SECTION"); g(2, "ENTITIES")
        for (p in dwg.allWcs()) when (p) {
            is Prim.Line -> {
                g(0, "LINE"); g(8, p.layer.dxfName)
                g(10, p.a.x); g(20, p.a.y); g(30, 0.0)
                g(11, p.b.x); g(21, p.b.y); g(31, 0.0)
            }
            is Prim.Circle -> if (p.filled) {
                // donut: inner Ø 0, outer Ø 2r → width r on a circle of radius r/2
                val w = p.r
                g(0, "POLYLINE"); g(8, p.layer.dxfName); g(66, 1); g(10, 0.0); g(20, 0.0); g(30, 0.0)
                g(70, 1); g(40, w); g(41, w)
                for (sx in listOf(-1.0, 1.0)) {
                    g(0, "VERTEX"); g(8, p.layer.dxfName)
                    g(10, p.c.x + sx * p.r / 2); g(20, p.c.y); g(30, 0.0)
                    g(40, w); g(41, w); g(42, 1.0)
                }
                g(0, "SEQEND"); g(8, p.layer.dxfName)
            } else {
                g(0, "CIRCLE"); g(8, p.layer.dxfName)
                g(10, p.c.x); g(20, p.c.y); g(30, 0.0); g(40, p.r)
            }
            is Prim.Text -> {
                g(0, "TEXT"); g(8, p.layer.dxfName)
                g(10, p.at.x); g(20, p.at.y); g(30, 0.0)
                g(40, p.height); g(1, ascii(p.text))
                if (p.rotationDeg != 0.0) g(50, p.rotationDeg)
                if (p.centered) { g(72, 1); g(11, p.at.x); g(21, p.at.y); g(31, 0.0) }
            }
        }
        g(0, "ENDSEC")
        g(0, "EOF")
        return sb.toString()
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.4f", v)

    internal fun ascii(s: String): String {
        val sb = StringBuilder()
        for (ch in s) sb.append(
            when (ch) {
                'φ', 'Φ', 'ø', 'Ø' -> "%%c"
                '°' -> "%%d"
                '±' -> "%%p"
                '×' -> "x"
                '·' -> "."
                '—', '–' -> "-"
                '▸', '›' -> ">"
                '²' -> "2"
                '³' -> "3"
                else -> if (ch.code in 32..126) ch.toString() else "?"
            }
        )
        return sb.toString()
    }
}
