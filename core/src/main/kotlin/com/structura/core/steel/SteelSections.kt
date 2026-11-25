package com.structura.core.steel

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Steel cross-section for IS 800 design. Axes as in IS 800: z–z = major axis, y–y = minor axis.
 * Dimensions mm; properties mm², mm⁴, mm³.
 * Rolled shapes are built from nominal IS 808 flange/web dimensions with root fillets neglected,
 * so A, I and Z are typically 1–4 % below the IS 808 tables (conservative). Verify against IS 808 / SP 6.
 */
data class SteelSection(
    val name: String,
    val family: Family,
    val depth: Double,        // h
    val width: Double,        // bf (or B for tubes)
    val tf: Double,           // flange (or wall) thickness
    val tw: Double,           // web thickness (= wall for tubes)
    val area: Double,
    val izz: Double, val iyy: Double,
    val zez: Double, val zpz: Double,
    val zey: Double, val zpy: Double,
    val j: Double,            // St Venant torsion constant
    val avZ: Double,          // shear area for shear along y (major-axis bending)
) {
    enum class Family(val rolled: Boolean) { I(true), CHANNEL(true), SHS(true), RHS(true), CHS(true), WELDED_I(false) }

    val rz: Double get() = sqrt(izz / area)
    val ry: Double get() = sqrt(iyy / area)
    val massPerM: Double get() = area * 7850e-6   // kg/m

    companion object {
        /** Doubly symmetric I: h, bf, tf, tw (mm). */
        fun iSection(name: String, h: Double, bf: Double, tf: Double, tw: Double, welded: Boolean = false): SteelSection {
            val hw = h - 2 * tf
            val a = 2 * bf * tf + hw * tw
            val izz = (bf * h.pow(3) - (bf - tw) * hw.pow(3)) / 12
            val iyy = (2 * tf * bf.pow(3) + hw * tw.pow(3)) / 12
            val zpz = bf * tf * (h - tf) + tw * hw * hw / 4
            val zpy = 2 * tf * bf * bf / 4 + hw * tw * tw / 4
            val j = (2 * bf * tf.pow(3) + (h - tf) * tw.pow(3)) / 3
            return SteelSection(name, if (welded) Family.WELDED_I else Family.I, h, bf, tf, tw, a, izz, iyy,
                izz / (h / 2), zpz, iyy / (bf / 2), zpy, j, h * tw)
        }

        /** Channel (flanges one side): h, bf, tf (mean), tw. Minor-axis properties about its own centroid. */
        fun channel(name: String, h: Double, bf: Double, tf: Double, tw: Double): SteelSection {
            val hw = h - 2 * tf
            val a = 2 * bf * tf + hw * tw
            val izz = (bf * h.pow(3) - (bf - tw) * hw.pow(3)) / 12
            val xc = (2 * bf * tf * bf / 2 + hw * tw * tw / 2) / a
            val iyy = 2 * (tf * bf.pow(3) / 12 + bf * tf * (bf / 2 - xc).pow(2)) + hw * tw.pow(3) / 12 + hw * tw * (tw / 2 - xc).pow(2)
            val zpz = bf * tf * (h - tf) + tw * hw * hw / 4
            // plastic NA for minor axis: equal-area line, approximate via elastic for the outstands
            val zpy = 1.5 * iyy / (bf - xc)
            val j = (2 * bf * tf.pow(3) + (h - tf) * tw.pow(3)) / 3
            return SteelSection(name, Family.CHANNEL, h, bf, tf, tw, a, izz, iyy, izz / (h / 2), zpz, iyy / maxOf(bf - xc, xc), zpy, j, h * tw)
        }

        /** Rectangular hollow section: H (depth, major axis bending) × B × t (sharp corners). */
        fun rhs(name: String, h: Double, b: Double, t: Double): SteelSection {
            val hi = h - 2 * t; val bi = b - 2 * t
            val a = b * h - bi * hi
            val izz = (b * h.pow(3) - bi * hi.pow(3)) / 12
            val iyy = (h * b.pow(3) - hi * bi.pow(3)) / 12
            val zpz = b * h * h / 4 - bi * hi * hi / 4
            val zpy = h * b * b / 4 - hi * bi * bi / 4
            val am = (b - t) * (h - t)
            val j = 4 * am * am * t / (2 * ((b - t) + (h - t)))
            return SteelSection(name, if (h == b) Family.SHS else Family.RHS, h, b, t, t, a, izz, iyy, izz / (h / 2), zpz, iyy / (b / 2), zpy, j, a * h / (b + h))
        }

        fun chs(name: String, d: Double, t: Double): SteelSection {
            val di = d - 2 * t
            val a = PI / 4 * (d * d - di * di)
            val i = PI / 64 * (d.pow(4) - di.pow(4))
            val zp = (d.pow(3) - di.pow(3)) / 6
            return SteelSection(name, Family.CHS, d, d, t, t, a, i, i, i / (d / 2), zp, i / (d / 2), zp, 2 * i, 2 * a / PI)
        }

        /** IS 808 nominal dimensions (h, bf, tf, tw) — selected sizes. */
        val library: List<SteelSection> by lazy {
            listOf(
                iSection("ISMB 100", 100.0, 75.0, 7.2, 4.0), iSection("ISMB 125", 125.0, 75.0, 7.6, 4.4),
                iSection("ISMB 150", 150.0, 80.0, 7.6, 4.8), iSection("ISMB 175", 175.0, 90.0, 8.6, 5.5),
                iSection("ISMB 200", 200.0, 100.0, 10.8, 5.7), iSection("ISMB 225", 225.0, 110.0, 11.8, 6.5),
                iSection("ISMB 250", 250.0, 125.0, 12.5, 6.9), iSection("ISMB 300", 300.0, 140.0, 12.4, 7.5),
                iSection("ISMB 350", 350.0, 140.0, 14.2, 8.1), iSection("ISMB 400", 400.0, 140.0, 16.0, 8.9),
                iSection("ISMB 450", 450.0, 150.0, 17.4, 9.4), iSection("ISMB 500", 500.0, 180.0, 17.2, 10.2),
                iSection("ISMB 550", 550.0, 190.0, 19.3, 11.2), iSection("ISMB 600", 600.0, 210.0, 20.8, 12.0),
                iSection("ISHB 150", 150.0, 150.0, 9.0, 5.4), iSection("ISHB 200", 200.0, 200.0, 9.0, 6.1),
                iSection("ISHB 225", 225.0, 225.0, 9.1, 6.5), iSection("ISHB 250", 250.0, 250.0, 9.7, 6.9),
                iSection("ISHB 300", 300.0, 250.0, 10.6, 7.6), iSection("ISHB 350", 350.0, 250.0, 11.6, 8.3),
                iSection("ISHB 400", 400.0, 250.0, 12.7, 9.1), iSection("ISHB 450", 450.0, 250.0, 13.7, 9.8),
                channel("ISMC 75", 75.0, 40.0, 7.3, 4.4), channel("ISMC 100", 100.0, 50.0, 7.5, 4.7),
                channel("ISMC 125", 125.0, 65.0, 8.1, 5.0), channel("ISMC 150", 150.0, 75.0, 9.0, 5.4),
                channel("ISMC 200", 200.0, 75.0, 11.4, 6.1), channel("ISMC 250", 250.0, 80.0, 14.1, 7.1),
                channel("ISMC 300", 300.0, 90.0, 13.6, 7.6),
                rhs("SHS 72×72×4.0", 72.0, 72.0, 4.0), rhs("SHS 91.5×91.5×4.5", 91.5, 91.5, 4.5), rhs("SHS 113.5×113.5×5.4", 113.5, 113.5, 5.4),
                rhs("SHS 132×132×5.4", 132.0, 132.0, 5.4), rhs("SHS 180×180×6", 180.0, 180.0, 6.0),
                rhs("RHS 122×61×4.5", 122.0, 61.0, 4.5), rhs("RHS 145×82×4.8", 145.0, 82.0, 4.8), rhs("RHS 172×92×4.8", 172.0, 92.0, 4.8),
                rhs("RHS 200×100×6", 200.0, 100.0, 6.0),
                chs("CHS 88.9×4.0", 88.9, 4.0), chs("CHS 114.3×4.5", 114.3, 4.5), chs("CHS 139.7×4.8", 139.7, 4.8), chs("CHS 168.3×6.3", 168.3, 6.3),
            )
        }
    }
}
