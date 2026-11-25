package com.structura.core.plan

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hand-over of the preliminary quantities to EstimateX / JAUEstimate: a Measurement sheet (Nos × L × B × D, live
 * formulas) and an Abstract (SUMIF × rate = amount) in .xlsx, plus a JSON for direct import. SOR item codes and
 * rates are the user's (e.g. Gujarat R&B SOR of the year) — entered once in the app, never invented here.
 */
object BoqExport {
    enum class Key(val title: String, val unit: String) {
        RCC_COLUMN("RCC in columns (superstructure)", "m³"),
        RCC_BEAM("RCC in beams (below slab)", "m³"),
        RCC_SLAB("RCC in slabs", "m³"),
        STEEL("Reinforcement steel, cutting, bending, placing (allowance)", "kg"),
        SHUTTER_SLAB("Centering & shuttering — slab soffit", "m²"),
        SHUTTER_BEAM("Centering & shuttering — beam sides & soffit", "m²"),
        SHUTTER_COLUMN("Centering & shuttering — columns", "m²"),
    }

    data class Sor(val code: String = "", val rate: Double = 0.0, val description: String = "")
    data class Meas(val text: String, val nos: Double, val l: Double?, val b: Double?, val d: Double?) {
        val qty: Double get() = nos * (l ?: 1.0) * (b ?: 1.0) * (d ?: 1.0)
    }
    data class Item(val key: Key, val description: String, val unit: String, val rows: List<Meas>) {
        val qty: Double get() = rows.sumOf { it.qty }
    }

    fun items(d: PreliminaryDesign): List<Item> {
        val lay = d.layout; val inp = d.input; val t = d.slabThickness
        val grade = "M${inp.fck.toInt()}"
        fun h(storey: Int) = if (storey == 1) inp.storeyHeight + inp.plinthHeight else inp.storeyHeight
        // columns grouped by size and storey
        val colRows = d.columns.groupBy { Triple(it.storey, it.b, it.d) }.toSortedMap(compareBy<Triple<Int, Double, Double>>({ it.first }, { it.second }, { it.third })).map { (k, cs) ->
            Meas("Storey ${k.first}: ${cs.joinToString(" ") { it.label }}", cs.size.toDouble(), k.second, k.third, h(k.first))
        }
        val clear = { b: SizedBeam ->
            val p = lay.column(b.beam.a); val q = lay.column(b.beam.b)
            val half = if (p.gy == q.gy) (p.b + q.b) / 2 else (p.d + q.d) / 2
            (b.span - half).coerceAtLeast(0.0)
        }
        val beamRows = d.beams.map { b -> Meas("${b.label} (clear span) × ${inp.storeys} floors", inp.storeys.toDouble(), clear(b), b.b, b.d - t) }
        val panels = PlanToStructure.panels(lay)
        val slabRows = panels.map { (ij, lx, ly) ->
            Meas("Panel ${PlanLayout.letter(ij.second)}${ij.first + 1}–${PlanLayout.letter(ij.second + 1)}${ij.first + 2} × ${inp.storeys} floors", inp.storeys.toDouble(), lx, ly, t)
        }
        val items = arrayListOf(
            Item(Key.RCC_COLUMN, "${Key.RCC_COLUMN.title}, $grade", "m³", colRows),
            Item(Key.RCC_BEAM, "${Key.RCC_BEAM.title}, $grade", "m³", beamRows),
            Item(Key.RCC_SLAB, "${Key.RCC_SLAB.title} ${(t * 1000).toInt()} mm, $grade", "m³", slabRows),
        )
        val vCol = items[0].qty; val vBeam = items[1].qty; val vSlab = items[2].qty
        items += Item(Key.STEEL, "${Key.STEEL.title}, Fe${inp.fy.toInt()}", "kg", listOf(
            Meas("Columns: concrete × 180 kg/m³", 1.0, vCol, 180.0, null),
            Meas("Beams: concrete × 130 kg/m³", 1.0, vBeam, 130.0, null),
            Meas("Slabs: concrete × 80 kg/m³", 1.0, vSlab, 80.0, null),
        ))
        items += Item(Key.SHUTTER_SLAB, Key.SHUTTER_SLAB.title, "m²", panels.map { (ij, lx, ly) ->
            Meas("Panel ${PlanLayout.letter(ij.second)}${ij.first + 1} × ${inp.storeys} floors", inp.storeys.toDouble(), lx, ly, null)
        })
        items += Item(Key.SHUTTER_BEAM, Key.SHUTTER_BEAM.title, "m²", d.beams.map { b ->
            Meas("${b.label}: 2(D − t) + b", inp.storeys.toDouble(), clear(b), 2 * (b.d - t) + b.b, null)
        })
        items += Item(Key.SHUTTER_COLUMN, Key.SHUTTER_COLUMN.title, "m²", colRows.map { m ->
            Meas(m.text + ": perimeter × height", m.nos, 2 * ((m.l ?: 0.0) + (m.b ?: 0.0)), m.d, null)
        })
        return items.filter { it.rows.isNotEmpty() }
    }

    // ----------------------------------------------------------------------------------------- JSON
    fun json(d: PreliminaryDesign, sor: Map<Key, Sor>, project: String): String {
        fun e(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun n(v: Double?) = if (v == null) "null" else "%.4f".format(java.util.Locale.US, v)
        val its = items(d)
        return buildString {
            append("{\n  \"schema\": \"structura-ai/boq-v1\",\n  \"target\": [\"EstimateX\", \"JAUEstimate\"],\n")
            append("  \"project\": ${e(project)},\n")
            append("  \"building\": {\"storeys\": ${d.input.storeys}, \"storeyHeight_m\": ${n(d.input.storeyHeight)}, \"slab_mm\": ${(d.slabThickness * 1000).toInt()}, \"fck\": ${n(d.input.fck)}, \"fy\": ${n(d.input.fy)}},\n")
            append("  \"items\": [\n")
            its.forEachIndexed { k, it ->
                val s = sor[it.key] ?: Sor()
                append("    {\"no\": ${k + 1}, \"key\": ${e(it.key.name)}, \"sorCode\": ${e(s.code)}, \"description\": ${e(it.description)}, \"unit\": ${e(it.unit)}, ")
                append("\"quantity\": ${n(it.qty)}, \"rate\": ${n(s.rate)}, \"amount\": ${n(it.qty * s.rate)},\n      \"measurements\": [")
                append(it.rows.joinToString(", ") { m -> "{\"text\": ${e(m.text)}, \"nos\": ${n(m.nos)}, \"l\": ${n(m.l)}, \"b\": ${n(m.b)}, \"d\": ${n(m.d)}, \"qty\": ${n(m.qty)}}" })
                append("]}")
                append(if (k < its.size - 1) ",\n" else "\n")
            }
            append("  ],\n  \"total\": ${n(its.sumOf { it.qty * (sor[it.key]?.rate ?: 0.0) })},\n")
            append("  \"note\": \"Preliminary superstructure quantities from Structura AI (plan take-down); steel by allowance. Foundations, masonry and finishes not included.\"\n}\n")
        }
    }

    // ----------------------------------------------------------------------------------------- XLSX
    private sealed class Cell { data class S(val v: String, val style: Int = 1) : Cell(); data class N(val v: Double, val style: Int = 3) : Cell(); data class F(val f: String, val cached: Double, val style: Int = 3) : Cell(); object Empty : Cell() }

    fun xlsx(d: PreliminaryDesign, sor: Map<Key, Sor>, project: String): ByteArray {
        val its = items(d)
        // Measurement sheet
        val meas = ArrayList<List<Cell>>()
        meas += listOf(Cell.S("Measurement sheet — $project", 4))
        meas += listOf(Cell.S("Item", 2), Cell.S("SOR code", 2), Cell.S("Description", 2), Cell.S("Nos", 2), Cell.S("L (m)", 2), Cell.S("B (m)", 2), Cell.S("D / H (m)", 2), Cell.S("Quantity", 2), Cell.S("Unit", 2))
        for ((k, it) in its.withIndex()) {
            val no = (k + 1).toDouble()
            meas += listOf(Cell.N(no, 5), Cell.S(sor[it.key]?.code.orEmpty(), 6), Cell.S(it.description, 6), Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.S(it.unit, 6))
            for (m in it.rows) {
                val r = meas.size + 1
                fun c(v: Double?) = if (v == null) Cell.Empty else Cell.N(v)
                meas += listOf(Cell.N(no, 5), Cell.S(""), Cell.S(m.text), Cell.N(m.nos, 5), c(m.l), c(m.b), c(m.d),
                    Cell.F("D$r*IF(E$r=\"\",1,E$r)*IF(F$r=\"\",1,F$r)*IF(G$r=\"\",1,G$r)", m.qty), Cell.S(it.unit))
            }
        }
        val lastM = meas.size
        // Abstract
        val abs = ArrayList<List<Cell>>()
        abs += listOf(Cell.S("Abstract of cost — $project", 4))
        abs += listOf(Cell.S("Item", 2), Cell.S("SOR code", 2), Cell.S("Description", 2), Cell.S("Unit", 2), Cell.S("Quantity", 2), Cell.S("Rate (₹)", 2), Cell.S("Amount (₹)", 2))
        for ((k, it) in its.withIndex()) {
            val r = abs.size + 1; val s = sor[it.key] ?: Sor()
            // header rows carry the item number but no quantity, so SUMIF over column H only adds the measurement rows
            abs += listOf(Cell.N((k + 1).toDouble(), 5), Cell.S(s.code), Cell.S(if (s.description.isNotBlank()) s.description else it.description), Cell.S(it.unit),
                Cell.F("SUMIF(Measurement!A\$3:A\$$lastM,A$r,Measurement!H\$3:H\$$lastM)", it.qty), Cell.N(s.rate), Cell.F("E$r*F$r", it.qty * s.rate))
        }
        val tr = abs.size + 1
        abs += listOf(Cell.Empty, Cell.Empty, Cell.S("Total", 2), Cell.Empty, Cell.Empty, Cell.Empty, Cell.F("SUM(G3:G${tr - 1})", its.sumOf { it.qty * (sor[it.key]?.rate ?: 0.0) }, 7))
        abs += emptyList()
        abs += listOf(Cell.S("Preliminary superstructure quantities from Structura AI; steel by kg/m³ allowance — replace with BBS. Foundations, masonry, finishes not included.", 0))
        // Info
        val inp = d.input
        val info = listOf(
            listOf(Cell.S("Structura AI — Drawing → Structure hand-over", 4)),
            listOf(Cell.S("Project", 2), Cell.S(project)),
            listOf(Cell.S("Storeys", 2), Cell.N(inp.storeys.toDouble(), 5)),
            listOf(Cell.S("Storey height (m)", 2), Cell.N(inp.storeyHeight)),
            listOf(Cell.S("Plinth (m)", 2), Cell.N(inp.plinthHeight)),
            listOf(Cell.S("Slab (mm)", 2), Cell.N(d.slabThickness * 1000, 5)),
            listOf(Cell.S("Concrete / steel", 2), Cell.S("M${inp.fck.toInt()} / Fe${inp.fy.toInt()}")),
            listOf(Cell.S("Columns · beams · panels", 2), Cell.S("${d.layout.columns.size} · ${d.beams.size} · ${PlanToStructure.panels(d.layout).size}")),
        )
        return zip(listOf("Abstract" to abs, "Measurement" to meas, "Info" to info),
            mapOf("Abstract" to listOf(8.0, 14.0, 60.0, 8.0, 12.0, 12.0, 16.0), "Measurement" to listOf(7.0, 12.0, 58.0, 7.0, 10.0, 10.0, 10.0, 12.0, 7.0), "Info" to listOf(26.0, 40.0)))
    }

    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }; return sb.toString() }

    private fun sheetXml(rows: List<List<Cell>>, widths: List<Double>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols>""")
        widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        append("</cols><sheetData>")
        rows.forEachIndexed { r, row ->
            append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, cell ->
                val ref = colName(c) + (r + 1)
                when (cell) {
                    is Cell.S -> append("""<c r="$ref" t="inlineStr" s="${cell.style}"><is><t xml:space="preserve">${x(cell.v)}</t></is></c>""")
                    is Cell.N -> append("""<c r="$ref" s="${cell.style}"><v>${cell.v}</v></c>""")
                    is Cell.F -> append("""<c r="$ref" s="${cell.style}"><f>${x(cell.f)}</f><v>${cell.cached}</v></c>""")
                    Cell.Empty -> append("""<c r="$ref" s="1"/>""")
                }
            }
            append("</row>")
        }
        append("</sheetData><pageSetup paperSize=\"9\" orientation=\"landscape\"/></worksheet>")
    }

    private fun zip(sheets: List<Pair<String, List<List<Cell>>>>, widths: Map<String, List<Double>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.mapIndexed { i, s -> """<sheet name="${s.first}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + """</sheets><calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            // styles: 0 plain, 1 border, 2 border+bold+fill (header), 3 border 0.000, 4 bold title, 5 border integer, 6 border bold, 7 border bold 0.00
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="0.000"/></numFmts><fonts count="2"><font><sz val="10"/><name val="Calibri"/></font><font><b/><sz val="10"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDDE4EE"/></patternFill></fill></fills><borders count="2"><border/><border><left style="thin"/><right style="thin"/><top style="thin"/><bottom style="thin"/></border></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="8"><xf/><xf borderId="1" applyBorder="1"><alignment wrapText="1" vertical="top"/></xf><xf fontId="1" fillId="2" borderId="1" applyFont="1" applyFill="1" applyBorder="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="164" borderId="1" applyNumberFormat="1" applyBorder="1"/><xf fontId="1" applyFont="1"/><xf numFmtId="1" borderId="1" applyNumberFormat="1" applyBorder="1"><alignment horizontal="center"/></xf><xf fontId="1" borderId="1" applyFont="1" applyBorder="1"><alignment wrapText="1" vertical="top"/></xf><xf numFmtId="4" fontId="1" borderId="1" applyNumberFormat="1" applyFont="1" applyBorder="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { i, (name, rows) -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(rows, widths[name] ?: emptyList())) }
        }
        return bos.toByteArray()
    }
}
