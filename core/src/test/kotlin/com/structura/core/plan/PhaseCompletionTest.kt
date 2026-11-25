package com.structura.core.plan

import com.structura.core.fem.FrameSolver
import com.structura.core.fem.Generators
import com.structura.core.fem.LoadCombination
import com.structura.core.fem.Material
import com.structura.core.fem.ResultCheck
import com.structura.core.fem.Section
import com.structura.core.loads.IS875
import com.structura.core.steel.SteelLibrary
import com.structura.core.steel.SteelModelCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class PhaseCompletionTest {

    @Test
    fun is875_helpers() {
        assertEquals(0.0, IS875.reduction(1), 0.0)
        assertEquals(0.3, IS875.reduction(4), 0.0)
        assertEquals(0.4, IS875.reduction(10), 0.0)
        assertEquals(0.5, IS875.reduction(11), 0.0)
        assertEquals(1.0, IS875.partitionAllowance(2.4), 0.0)
        assertEquals(4.0, IS875.partitionAllowance(12.0), 1e-12)
        assertEquals(0.23 * 18.85 * 3.0 + 2 * 0.012 * 20.4 * 3.0, IS875.wallLoad(0.23, 3.0), 1e-12)
        assertTrue(IS875.imposed.any { it.udl == 1.5 })
    }

    @Test
    fun steelLibrary_csvImport() {
        val csv = """
            name,family,h,b,tf,tw,A_cm2,Izz_cm4
            ISLB 300,I,300,150,9.4,6.7,48.08,7332.9
            ISWB 300,I,300,200,10,7.4,,
            ISA 50x50x6,ANGLE,50,50,6,6,,
            BOX 200,SHS,200,200,8,8,,
        """.trimIndent()
        val r = SteelLibrary.import(csv)
        assertEquals(3, r.sections.size)
        val lb = SteelLibrary.find("ISLB 300")!!
        assertEquals(4808.0, lb.area, 1e-6)                  // tabulated cm² → mm²
        assertEquals(7332.9e4, lb.izz, 1e-3)
        assertTrue(r.notes.any { it.contains("angles") })
        assertTrue("ISLB" in SteelLibrary.prefixes())
        // FEM round trip keeps the tabulated section
        val back = SteelModelCheck.steelSection(SteelModelCheck.femSection(lb))!!
        assertEquals(lb.area, back.area, 1e-9)
        SteelLibrary.clear()
        assertTrue(SteelLibrary.find("ISLB 300") == null)
    }

    @Test
    fun resultCheck_flagsMissingLateralAndPassesSoundFrame() {
        val m = Generators.spaceFrame(listOf(5.0), listOf(4.0), listOf(3.5, 3.5), Section.rect(0.3, 0.45), Section.rect(0.3, 0.5),
            Material.concrete(25.0), beamUdl = 15.0, liveUdl = 8.0)
        val combos = LoadCombination.isLimitState(false)
        val rev = ResultCheck.review(m, FrameSolver.analyseAll(m, combos))
        assertTrue(rev.findings.any { it.topic == "Lateral load" })
        assertEquals(0, rev.findings.count { it.level == ResultCheck.Level.ERROR })
        assertTrue(rev.summary.first().startsWith("Load path"))
        // remove supports → error
        val bad = m.copy(supports = emptyList())
        val r2 = ResultCheck.review(bad, emptyList())
        assertTrue(r2.findings.any { it.level == ResultCheck.Level.ERROR && it.topic == "Supports" })
        // a long shallow beam triggers span/depth
        val slim = Generators.spaceFrame(listOf(9.0), listOf(4.0), listOf(3.0), Section.rect(0.4, 0.4), Section.rect(0.23, 0.3),
            Material.concrete(25.0), beamUdl = 10.0)
        val r3 = ResultCheck.review(slim, FrameSolver.analyseAll(slim, LoadCombination.isLimitState(false)))
        assertTrue(r3.findings.any { it.topic == "Beam depth" })
    }

    /** Synthetic scanned plan: 4 × 3 columns (one hatched), 1-px walls, small text. */
    private fun syntheticPlan(): Triple<IntArray, Int, Int> {
        val w = 900; val h = 640
        val g = IntArray(w * h) { 245 }
        fun px(x: Int, y: Int, v: Int) { if (x in 0 until w && y in 0 until h) g[y * w + x] = v }
        val xs = listOf(100, 300, 550, 750); val ys = listOf(100, 320, 540)
        for (y in ys) for (x0 in 80..770) px(x0, y, 30)                 // walls along lettered lines
        for (x in xs) for (y0 in 80..560) px(x, y0, 30)                 // walls along numbered lines
        for ((j, y) in ys.withIndex()) for ((i, x) in xs.withIndex()) {
            val hatched = i == 2 && j == 1
            for (yy in y - 10..y + 10) for (xx in x - 7..x + 7) {
                val edge = yy == y - 10 || yy == y + 10 || xx == x - 7 || xx == x + 7
                if (!hatched || edge || (xx + yy) % 3 == 0) px(xx, yy, 10)
            }
        }
        for (k in 0 until 20) for (yy in 0..4) for (xx in 0..2) px(150 + k * 30 + xx, 200 + yy, 20)   // "text"
        return Triple(g, w, h)
    }

    @Test
    fun imageReader_findsColumnsAndBuildsPlan() {
        val (g, w, h) = syntheticPlan()
        val det = PlanImageReader.detect(g, w, h)
        assertEquals(12, det.columns.size)
        assertEquals(4, det.gridPixelsX().size); assertEquals(3, det.gridPixelsY().size)
        val plan = PlanImageReader.toPlan(det, 4.0)                    // grid 1–2 = 200 px = 4.0 m
        val lay = PlanRecognizer.recognise(plan)
        assertEquals(12, lay.columns.size)
        assertEquals(listOf(0.0, 4.0, 9.0, 13.0), lay.gridX.map { Math.round(it * 10) / 10.0 })
        assertEquals(listOf(0.0, 4.4, 8.8), lay.gridY.map { Math.round(it * 10) / 10.0 })
        assertEquals(0.3, lay.columns.first().b, 0.03)                  // 15 px × 0.02 m
    }

    @Test
    fun boqExport_xlsxAndJson() {
        val lay = PlanRecognizer.recognise(DxfReader.read(SamplePlan.dxf()))
        val d = PlanToStructure.design(lay, BuildingInput(storeys = 2))
        val sor = mapOf(BoqExport.Key.RCC_SLAB to BoqExport.Sor("GJ-RB 5.2", 7500.0))
        val items = BoqExport.items(d)
        assertEquals(7, items.size)
        val slab = items.first { it.key == BoqExport.Key.RCC_SLAB }
        val area = PlanToStructure.panels(lay).sumOf { it.second * it.third }
        assertEquals(area * d.slabThickness * 2, slab.qty, 1e-9)
        val steel = items.first { it.key == BoqExport.Key.STEEL }
        assertEquals(items[0].qty * 180 + items[1].qty * 130 + slab.qty * 80, steel.qty, 1e-6)
        val json = BoqExport.json(d, sor, "Test block")
        org.json.JSONObject(json).let { o ->
            assertEquals("GJ-RB 5.2", o.getJSONArray("items").getJSONObject(2).getString("sorCode"))
            assertEquals(slab.qty * 7500.0, o.getDouble("total"), 1e-2)
        }
        val bytes = BoqExport.xlsx(d, sor, "Test block")
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                names += e.name
                if (e.name.endsWith(".xml") || e.name.endsWith(".rels")) {
                    val data = z.readBytes()
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(data))   // well-formed
                }
                e = z.nextEntry
            }
        }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/styles.xml")))
    }
}
