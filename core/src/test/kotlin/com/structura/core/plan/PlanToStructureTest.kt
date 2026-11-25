package com.structura.core.plan

import com.structura.core.fem.FrameSolver
import com.structura.core.fem.LoadCombination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlanToStructureTest {
    private val plan = DxfReader.read(SamplePlan.dxf())

    @Test
    fun readsUnitsAndBlocks() {
        assertEquals(0.001, plan.unitScale, 0.0)
        assertTrue("COLUMN" in plan.layers && "GRID" in plan.layers)
        // 8 column polylines + 4 block-inserted columns + wall + door
        assertEquals(14, plan.entities.filterIsInstance<PlanEntity.Poly>().size)
        val inserted = plan.entities.filterIsInstance<PlanEntity.Poly>().filter { p -> p.pts.all { it.second > 8.5 } }
        assertEquals(4, inserted.size)
        assertTrue(inserted.all { it.layer == "COLUMN" })          // block layer 0 inherits the INSERT layer
    }

    @Test
    fun recognisesGridColumnsBeams() {
        val lay = PlanRecognizer.recognise(plan)
        assertEquals(listOf(0.0, 4.0, 8.5, 12.5), lay.gridX.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(listOf(0.0, 5.0, 9.0), lay.gridY.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(12, lay.columns.size)
        assertEquals(0.3, lay.columns.first().b, 1e-9); assertEquals(0.45, lay.columns.first().d, 1e-9)
        assertEquals("A1", lay.columns.first().label)
        assertEquals(3 * 3 + 4 * 2, lay.beams.size)          // 3 per lettered line × 3 + 2 per numbered line × 4
        assertEquals(6, PlanToStructure.panels(lay).size)
        val less = lay.without(setOf(lay.columns.first { it.label == "B2" }.id))
        assertEquals(11, less.columns.size)
        assertTrue(less.beams.size < lay.beams.size)
    }

    @Test
    fun takeDownSizingAndModel() {
        val lay = PlanRecognizer.recognise(plan)
        val d = PlanToStructure.design(lay, BuildingInput(storeys = 4))
        assertEquals(0.145, d.slabThickness, 1e-9)                  // largest short span 4.5 m / 32 → 141 → 145 mm
        val b2 = d.columns.filter { it.label == "B2" }
        assertEquals(4, b2.size)
        assertTrue(b2.first { it.storey == 1 }.pu > b2.first { it.storey == 4 }.pu * 3)
        for (c in d.columns) {                                        // every column satisfies Cl. 39.3 with p = 1 %
            val cap = (0.4 * 25 * 0.99 + 0.67 * 500 * 0.01) * c.b * c.d * 1e3
            assertTrue("${c.label}/${c.storey}", cap >= c.pu - 1e-6)
            assertTrue(c.b >= 0.3 - 1e-9 && c.d >= 0.3 - 1e-9)
        }
        assertTrue(d.beams.all { it.d >= it.span / 12 - 1e-9 && it.d >= 0.3 })
        val m = PlanToStructure.model(d)
        assertEquals(12 * 5, m.nodes.size)
        assertEquals(12 * 4 + 17 * 4, m.members.size)
        assertTrue(m.memberLoads.size > 17 * 4)
        val r = FrameSolver.analyse(m, LoadCombination.isLimitState(false).first())
        assertTrue(r.equilibriumError.all { abs(it) < 1e-4 })
        assertTrue(d.csv().contains("Column load take-down"))
    }
}
