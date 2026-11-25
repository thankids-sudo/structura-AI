package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Static lateral load generators (STAAD "DEFINE 1893 LOAD" / "DEFINE WIND LOAD" equivalents).
 * Both produce storey forces applied as nodal loads at every non-support node of each floor level, shared in
 * proportion to the node's seismic weight (rigid-diaphragm assumption), in load case [LoadCase.WL] (lateral).
 */
object LateralLoads {
    class Storey(val z: Double, val weight: Double, val force: Double)
    class Result(val baseShear: Double, val storeys: List<Storey>, val loads: List<NodalLoad>, val steps: List<String>)

    /** Seismic weight per node (kN): self-weight + DL + [llFactor]·LL, members split half to each end (IS 1893 Cl. 7.3). */
    fun nodalWeights(model: FrameModel, llFactor: Double): Map<Int, Double> {
        val w = HashMap<Int, Double>()
        fun add(n: Int, v: Double) { w[n] = (w[n] ?: 0.0) + v }
        fun f(case: String) = when (case) { LoadCase.DL -> 1.0; LoadCase.LL -> llFactor; else -> 0.0 }
        for (m in model.members) {
            val l = (model.node(m.j).pos - model.node(m.i).pos).length
            var tot = if (model.selfWeight) m.material.gamma * m.section.a * l else 0.0
            for (ml in model.memberLoads.filter { it.member == m.id }) {
                val a = ml.a ?: 0.0; val b = ml.b ?: l
                tot += abs((ml.w + (ml.w2 ?: ml.w)) / 2) * (b - a) * f(ml.case)
            }
            add(m.i, tot / 2); add(m.j, tot / 2)
            for (p in model.pointLoads.filter { it.member == m.id }) {
                val t = (p.a / l).coerceIn(0.0, 1.0)
                add(m.i, abs(p.p) * f(p.case) * (1 - t)); add(m.j, abs(p.p) * f(p.case) * t)
            }
        }
        for (nl in model.nodalLoads) add(nl.node, abs(nl.values[2]) * f(nl.case))
        return w
    }

    private fun levels(model: FrameModel, weights: Map<Int, Double>): Pair<Double, List<Pair<Double, List<Int>>>> {
        val sup = model.supports.map { it.node }.toSet()
        val zBase = model.supports.minOf { model.node(it.node).z }
        val lv = model.nodes.filter { it.id !in sup && it.z > zBase + 1e-6 }.groupBy { Math.round(it.z * 1000) }.toSortedMap()
            .values.map { ns -> ns.first().z to ns.map { it.id } }
        return zBase to lv
    }

    /**
     * Distributes a storey force to the level's nodes by weight. With [ecc] ≠ 0 (fraction of the plan dimension
     * normal to the force, e.g. ±0.05 for IS 1893 Cl. 7.8.2) the resultant is moved off the centre of mass:
     * Fn = Q·wn/W + Q·e·wn·(cn − c̄)/Σw(c − c̄)², so ΣFn = Q and ΣFn·(cn − c̄) = Q·e.
     */
    private fun spread(model: FrameModel, level: List<Int>, force: Double, axis: Int, weights: Map<Int, Double>, case: String, ecc: Double = 0.0): List<NodalLoad> {
        val w = level.associateWith { (weights[it] ?: 0.0).takeIf { v -> v > 0 } ?: 1e-9 }
        val tot = w.values.sum()
        fun c(n: Int) = model.node(n).let { if (axis == 0) it.y else it.x }
        val cBar = level.sumOf { w.getValue(it) * c(it) } / tot
        val j = level.sumOf { w.getValue(it) * (c(it) - cBar) * (c(it) - cBar) }
        val b = level.map { c(it) }.let { (it.max() - it.min()) }
        val e = ecc * b
        return level.map { n ->
            val share = w.getValue(n) / tot + if (j > 1e-12 && e != 0.0) e * w.getValue(n) * (c(n) - cBar) / j else 0.0
            NodalLoad(n, List(6) { if (it == axis) force * share else 0.0 }, case)
        }.filter { abs(it.values[axis]) > 0 }
    }

    /**
     * IS 1893 (Part 1):2016 equivalent static method: Ta (Cl. 7.6.2), Ah = Z/2 · I/R · Sa/g (Cl. 6.4.2),
     * VB = Ah·W (Cl. 7.2.1), Qi = VB·Wi·hi² / ΣWj·hj² (Cl. 7.6.3) along global [axis] (0 = X, 1 = Y).
     * Accidental eccentricity (Cl. 7.8.2) is not applied.
     */
    fun seismicStatic(model: FrameModel, p: Seismic.Params, axis: Int = 0, period: Double? = null,
                      case: String = LoadCase.WL, eccentricity: Double = 0.0): Result {
        val wN = nodalWeights(model, p.llFactor)
        val (zBase, lv) = levels(model, wN)
        require(lv.isNotEmpty()) { "No floor levels above the supports" }
        val h = lv.last().first - zBase
        val d = model.nodes.map { if (axis == 0) it.x else it.y }.let { (it.max() - it.min()).coerceAtLeast(0.1) }
        val ta = period ?: when (p.building) {
            Seismic.BuildingType.RC_MRF -> 0.075 * h.pow(0.75)
            Seismic.BuildingType.STEEL_MRF -> 0.085 * h.pow(0.75)
            Seismic.BuildingType.OTHER, Seismic.BuildingType.MODAL -> 0.09 * h / sqrt(d)
        }
        val ah = Seismic.ah(ta, p, static = true)
        val ws = lv.map { (_, ns) -> ns.sumOf { wN[it] ?: 0.0 } }
        val wTot = ws.sum()
        val vb = ah * wTot
        val den = lv.indices.sumOf { ws[it] * (lv[it].first - zBase).pow(2) }
        val storeys = lv.indices.map { k -> Storey(lv[k].first, ws[k], vb * ws[k] * (lv[k].first - zBase).pow(2) / den) }
        val loads = lv.indices.flatMap { k -> spread(model, lv[k].second, storeys[k].force, axis, wN, case, eccentricity) }
        val steps = listOf(
            "h = ${"%.2f".format(h)} m, Ta = ${"%.3f".format(ta)} s (Cl. 7.6.2), Sa/g = ${"%.3f".format(Seismic.saByG(ta, p.soil, true))} (soil ${p.soil.name})",
            "Ah = Z/2 · I/R · Sa/g = ${p.z}/2 × ${p.importance}/${p.r} × … = ${"%.5f".format(ah)} (Cl. 6.4.2)",
            "W = DL + ${p.llFactor}·LL = ${"%.1f".format(wTot)} kN (Cl. 7.3.1, Table 10); VB = Ah·W = ${"%.2f".format(vb)} kN (Cl. 7.2.1)",
            "Qi = VB·Wi·hi²/ΣWj·hj² (Cl. 7.6.3), applied along ${if (axis == 0) "X" else "Y"} in case $case" +
                if (eccentricity != 0.0) ", accidental eccentricity ${"%+.0f".format(eccentricity * 100)} % of the plan dimension (Cl. 7.8.2)" else "",
        )
        return Result(vb, storeys, loads, steps)
    }

    /** IS 875 (Part 3):2015 parameters. */
    data class WindParams(
        val vb: Double = 44.0,           // basic wind speed m/s (Fig. 1 / Annex A — enter for the site)
        val terrain: Int = 2,            // category 1–4 (Cl. 6.3.2.1)
        val k1: Double = 1.0,            // risk coefficient (Table 1; 1.0 for 50-year general buildings)
        val k3: Double = 1.0,            // topography (Cl. 6.3.3)
        val k4: Double = 1.0,            // importance for cyclonic regions (Cl. 6.3.4)
        val kd: Double = 0.9, val ka: Double = 1.0, val kc: Double = 0.9,   // Cl. 7.2
        val cf: Double = 1.2,            // force coefficient for the building (Fig. 4 / Table 23)
    )

    private val K2H = doubleArrayOf(10.0, 15.0, 20.0, 30.0, 50.0, 100.0)
    private val K2 = arrayOf(
        doubleArrayOf(1.05, 1.09, 1.12, 1.15, 1.20, 1.26),
        doubleArrayOf(1.00, 1.05, 1.07, 1.12, 1.17, 1.24),
        doubleArrayOf(0.91, 0.97, 1.01, 1.06, 1.12, 1.20),
        doubleArrayOf(0.80, 0.80, 0.80, 0.97, 1.10, 1.20),
    )

    /** k2 (IS 875-3 Table 2), linear between tabulated heights, constant below 10 m. */
    fun k2(z: Double, terrain: Int): Double {
        val row = K2[(terrain - 1).coerceIn(0, 3)]
        if (z <= K2H[0]) return row[0]
        for (i in 1 until K2H.size) if (z <= K2H[i]) return row[i - 1] + (row[i] - row[i - 1]) * (z - K2H[i - 1]) / (K2H[i] - K2H[i - 1])
        return row.last()
    }

    /**
     * Storey wind forces: Vz = Vb·k1·k2·k3·k4, pz = 0.6·Vz², pd = max(Kd·Ka·Kc, 0.7)·pz (Cl. 7.2),
     * F = Cf·pd·(B × tributary height) with B the building width normal to the wind (Cl. 7.4.2).
     */
    fun wind(model: FrameModel, w: WindParams, axis: Int = 0): Result {
        val wN = nodalWeights(model, 0.25)
        val (zBase, lv) = levels(model, wN)
        require(lv.isNotEmpty()) { "No floor levels above the supports" }
        val width = model.nodes.map { if (axis == 0) it.y else it.x }.let { (it.max() - it.min()).coerceAtLeast(0.1) }
        val zs = lv.map { it.first - zBase }
        val storeys = lv.indices.map { k ->
            val below = if (k == 0) zs[0] else zs[k] - zs[k - 1]
            val above = if (k == lv.size - 1) 0.0 else zs[k + 1] - zs[k]
            val trib = below / 2 + above / 2
            val vz = w.vb * w.k1 * k2(zs[k], w.terrain) * w.k3 * w.k4
            val pz = 0.6 * vz * vz / 1000                                  // kN/m²
            val pd = maxOf(w.kd * w.ka * w.kc, 0.7) * pz
            Storey(lv[k].first, pd, w.cf * pd * width * trib)
        }
        val loads = lv.indices.flatMap { k -> spread(model, lv[k].second, storeys[k].force, axis, wN, LoadCase.WL) }
        val vb = storeys.sumOf { it.force }
        val steps = listOf(
            "Vz = Vb·k1·k2·k3·k4 with Vb = ${w.vb} m/s, terrain category ${w.terrain} (Table 2), k1 ${w.k1}, k3 ${w.k3}, k4 ${w.k4}",
            "pz = 0.6 Vz², pd = max(Kd·Ka·Kc = ${"%.3f".format(w.kd * w.ka * w.kc)}, 0.7) × pz (Cl. 7.2)",
            "F = Cf·pd·Ae, Cf = ${w.cf}, width normal to wind ${"%.2f".format(width)} m; total ${"%.2f".format(vb)} kN in case WL",
            "Storey 'weight' column of this table holds pd (kN/m²)",
        )
        return Result(vb, storeys, loads, steps)
    }
}
