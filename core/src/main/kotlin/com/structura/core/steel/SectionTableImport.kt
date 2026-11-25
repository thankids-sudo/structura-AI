package com.structura.core.steel

/**
 * Section library = built-in IS 808 selection + sections imported from a CSV (e.g. the full SP 6(1) / IS 808 table).
 *
 * CSV: first row is a header. Required columns: name, family (I, CHANNEL, SHS, RHS, CHS, WELDED_I), h, b, tf, tw (mm;
 * for CHS b = D and tf = t). Optional tabulated properties override the geometric values (which ignore root radii and
 * flange taper): A, Izz, Iyy, Zez, Zpz, Zey, Zpy, J — in mm units, or with a unit suffix _cm2 / _cm4 / _cm3
 * (e.g. "A_cm2", "Izz_cm4", "Zpz_cm3"). Ixx / Zxx / Zpx are accepted as aliases of the major axis. Angles (ISA) are not
 * yet designable and are skipped with a note.
 */
object SteelLibrary {
    @Volatile var imported: List<SteelSection> = emptyList()
        private set

    val all: List<SteelSection> get() {
        val names = imported.map { it.name }.toSet()
        return SteelSection.library.filter { it.name !in names } + imported
    }

    fun find(name: String): SteelSection? = imported.firstOrNull { it.name == name } ?: SteelSection.library.firstOrNull { it.name == name }

    /** Designation prefixes present (ISMB, ISHB, ISLB, ISMC, SHS …) in library order. */
    fun prefixes(filter: (SteelSection) -> Boolean = { true }): List<String> = all.filter(filter).map { it.name.substringBefore(' ') }.distinct()

    class ImportResult(val sections: List<SteelSection>, val notes: List<String>)

    fun parse(csv: String): ImportResult {
        val rows = csv.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        require(rows.size >= 2) { "CSV needs a header row and at least one section" }
        val head = split(rows[0]).map { it.trim().lowercase() }
        fun col(vararg names: String) = head.indexOfFirst { h -> names.any { h == it || h.startsWith("${it}_") } }
        val iName = col("name", "designation"); val iFam = col("family", "type")
        val iH = col("h", "depth", "d"); val iB = col("b", "bf", "width"); val iTf = col("tf", "t"); val iTw = col("tw")
        require(iName >= 0 && iFam >= 0 && iH >= 0 && iB >= 0 && iTf >= 0) { "Header must contain name, family, h, b, tf (and tw for I / channel)" }
        fun scale(i: Int, power: Int): Double {
            val h = head[i]
            return when {
                h.endsWith("_cm2") || h.endsWith("_cm3") || h.endsWith("_cm4") -> Math.pow(10.0, power.toDouble())
                else -> 1.0
            }
        }
        val props = mapOf(
            "a" to (col("a", "area") to 2), "izz" to (col("izz", "ixx", "iz") to 4), "iyy" to (col("iyy", "iy") to 4),
            "zez" to (col("zez", "zxx", "zez") to 3), "zpz" to (col("zpz", "zpx") to 3), "zey" to (col("zey", "zyy") to 3),
            "zpy" to (col("zpy") to 3), "j" to (col("j", "it") to 4),
        )
        val out = ArrayList<SteelSection>(); val notes = ArrayList<String>()
        for ((k, line) in rows.drop(1).withIndex()) {
            val c = split(line)
            fun d(i: Int) = c.getOrNull(i)?.trim()?.replace(",", ".")?.toDoubleOrNull()
            val name = c.getOrNull(iName)?.trim()?.trim('"').orEmpty()
            val fam = c.getOrNull(iFam)?.trim()?.uppercase().orEmpty()
            val h = d(iH); val b = d(iB); val tf = d(iTf); val tw = if (iTw >= 0) d(iTw) else null
            if (name.isEmpty() || h == null || b == null || tf == null) { notes += "Row ${k + 2}: missing name or dimensions — skipped"; continue }
            val geo = when (fam) {
                "I", "ISMB", "ISHB", "ISLB", "ISWB", "ISJB", "BEAM" -> tw?.let { SteelSection.iSection(name, h, b, tf, it) }
                "WELDED_I" -> tw?.let { SteelSection.iSection(name, h, b, tf, it, welded = true) }
                "CHANNEL", "C", "ISMC", "ISLC", "ISJC" -> tw?.let { SteelSection.channel(name, h, b, tf, it) }
                "SHS", "RHS" -> SteelSection.rhs(name, h, b, tf)
                "CHS", "PIPE" -> SteelSection.chs(name, h, tf)
                "ANGLE", "L", "ISA" -> { notes += "Row ${k + 2} $name: angles not yet designable — skipped"; null }
                else -> { notes += "Row ${k + 2} $name: unknown family '$fam' — skipped"; null }
            } ?: run { if (fam !in listOf("ANGLE", "L", "ISA") && fam.isNotEmpty() && tw == null) notes += "Row ${k + 2} $name: tw required"; null } ?: continue
            fun p(key: String, geoV: Double): Double {
                val (i, pw) = props.getValue(key)
                return if (i >= 0) d(i)?.let { it * scale(i, pw) } ?: geoV else geoV
            }
            out += geo.copy(
                area = p("a", geo.area), izz = p("izz", geo.izz), iyy = p("iyy", geo.iyy),
                zez = p("zez", geo.zez), zpz = p("zpz", geo.zpz), zey = p("zey", geo.zey), zpy = p("zpy", geo.zpy), j = p("j", geo.j),
            )
        }
        val tab = props.values.count { it.first >= 0 }
        notes.add(0, "${out.size} sections read; ${if (tab > 0) "$tab tabulated property column(s) used" else "properties computed from dimensions (no root radius — slightly conservative)"}")
        return ImportResult(out, notes)
    }

    fun import(csv: String): ImportResult = parse(csv).also { imported = it.sections }

    fun clear() { imported = emptyList() }

    /** Template with the header and two example rows (dimensions from the built-in library). */
    fun template(): String = buildString {
        appendLine("# Structura AI section table — fill from SP 6(1) / IS 808. Units mm; tabulated columns may use _cm2/_cm3/_cm4.")
        appendLine("name,family,h,b,tf,tw,A_cm2,Izz_cm4,Iyy_cm4,Zez_cm3,Zpz_cm3,Zey_cm3,Zpy_cm3")
        for (s in SteelSection.library.filter { it.name == "ISMB 300" || it.name == "ISMC 200" }) {
            appendLine(listOf(s.name, s.family.name, s.depth, s.width, s.tf, s.tw, "", "", "", "", "", "", "").joinToString(","))
        }
    }

    private fun split(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) when {
            ch == '"' -> q = !q
            (ch == ',' || ch == ';' || ch == '\t') && !q -> { out += sb.toString(); sb.clear() }
            else -> sb.append(ch)
        }
        out += sb.toString()
        return out
    }
}
