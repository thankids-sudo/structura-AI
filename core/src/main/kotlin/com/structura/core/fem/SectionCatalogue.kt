package com.structura.core.fem

/**
 * Built-in hollow-section catalogue. Properties are COMPUTED from the nominal dimensions
 * (sharp corners), so they are slightly higher than tables that include corner radii.
 * Verify sizes and properties against the current IS 4923 / IS 1161 edition or the
 * manufacturer's catalogue before use.
 */
/** Tubes (IS 4923 / IS 1161), angles (IS 808 ISA, JIS G 3192 — nominal dimensions, sharp corners), GFRP tubes. */
object SectionCatalogue {

    data class Entry(val family: String, val section: Section)

    private fun shs(b: Double, t: Double) = Entry("Steel SHS (IS 4923)", Section.rhs(b / 1000, b / 1000, t / 1000))
    private fun rhs(h: Double, b: Double, t: Double) = Entry("Steel RHS (IS 4923)", Section.rhs(b / 1000, h / 1000, t / 1000))
    private fun chs(d: Double, t: Double) = Entry("Steel CHS (IS 1161)", Section.chs(d / 1000, t / 1000))
    private fun isa(b: Double, t: Double) = Entry("Steel angle ISA (IS 808)", Section.angle(b / 1000, b / 1000, t / 1000, "ISA ${b.toInt()}×${b.toInt()}×${t.toInt()}"))
    private fun isaU(b: Double, d: Double, t: Double) = Entry("Steel angle ISA (IS 808)", Section.angle(b / 1000, d / 1000, t / 1000, "ISA ${d.toInt()}×${b.toInt()}×${t.toInt()}"))
    private fun jis(b: Double, t: Double) = Entry("Steel angle JIS G 3192", Section.angle(b / 1000, b / 1000, t / 1000, "L ${b.toInt()}×${b.toInt()}×${t.toInt()}"))
    private fun frpSq(b: Double, t: Double) = Entry("GFRP square tube (typical)", Section.rhs(b / 1000, b / 1000, t / 1000, "GFRP □${b.toInt()}×${t}"))
    private fun frpRect(h: Double, b: Double, t: Double) = Entry("GFRP rect. tube (typical)", Section.rhs(b / 1000, h / 1000, t / 1000, "GFRP ▭${h.toInt()}×${b.toInt()}×$t"))
    private fun frpRound(d: Double, t: Double) = Entry("GFRP round tube (typical)", Section.chs(d / 1000, t / 1000, "GFRP ○${d.toInt()}×$t"))

    val entries: List<Entry> = listOf(
        shs(40.0, 2.6), shs(40.0, 3.2), shs(50.0, 2.9), shs(50.0, 3.6), shs(60.0, 3.2), shs(60.0, 4.0),
        shs(72.0, 3.2), shs(72.0, 4.0), shs(72.0, 4.8), shs(91.5, 3.6), shs(91.5, 4.5), shs(91.5, 5.4),
        shs(113.5, 4.5), shs(113.5, 5.4), shs(132.0, 4.8), shs(132.0, 5.4), shs(180.0, 6.0), shs(200.0, 8.0),
        rhs(50.0, 25.0, 2.9), rhs(66.0, 33.0, 2.9), rhs(80.0, 40.0, 3.2), rhs(96.0, 48.0, 3.2), rhs(96.0, 48.0, 4.8),
        rhs(122.0, 61.0, 3.6), rhs(122.0, 61.0, 5.4), rhs(145.0, 82.0, 4.8), rhs(172.0, 92.0, 4.8), rhs(200.0, 100.0, 6.0),
        chs(33.7, 3.2), chs(42.4, 3.2), chs(48.3, 3.2), chs(60.3, 3.6), chs(76.1, 3.6), chs(88.9, 4.0),
        chs(114.3, 4.5), chs(139.7, 4.8), chs(165.1, 4.8), chs(168.3, 6.3),
        frpSq(50.0, 5.0), frpSq(75.0, 6.0), frpSq(100.0, 6.0), frpSq(100.0, 8.0), frpSq(150.0, 9.5),
        frpRect(100.0, 50.0, 5.0), frpRect(150.0, 75.0, 6.0), frpRect(200.0, 100.0, 8.0),
        isa(50.0, 5.0), isa(50.0, 6.0), isa(60.0, 6.0), isa(65.0, 6.0), isa(65.0, 8.0), isa(75.0, 6.0), isa(75.0, 8.0),
        isa(90.0, 8.0), isa(90.0, 10.0), isa(100.0, 8.0), isa(100.0, 10.0), isa(100.0, 12.0),
        isaU(50.0, 75.0, 6.0), isaU(65.0, 100.0, 8.0),
        jis(40.0, 3.0), jis(50.0, 4.0), jis(60.0, 4.0), jis(60.0, 5.0), jis(65.0, 6.0), jis(75.0, 6.0),
        frpRound(50.0, 5.0), frpRound(76.0, 6.0), frpRound(100.0, 6.0), frpRound(150.0, 8.0),
    )

    val families: List<String> get() = entries.map { it.family }.distinct()
}
