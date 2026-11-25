package com.structura.core.fem

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * IS 1893 (Part 1):2016 response spectrum analysis.
 *
 *  • Design spectrum Sa/g for rock/hard (I), medium (II) and soft (III) soil — Cl. 6.4.2, 5 % damping.
 *  • Design horizontal coefficient Ah = (Z/2)·(Sa/g)/(R/I) — Cl. 6.4.2; Z Table 3, I Table 8, R Table 9.
 *  • Modal responses from the free-vibration modes; peak modal displacement u = Γ·Ah·g·φ/ω².
 *  • Combination by CQC with ζ = 0.05 — Cl. 7.7.5.4.
 *  • Base shear scaled so that VB ≥ static VB̄ = Ah(Ta)·W — Cl. 7.7.3, with Ta from Cl. 7.6.2.
 *  • Storey drift checked against 0.004 — Cl. 7.11.1.
 * Each horizontal direction is analysed separately (the 100 % + 30 % rule of Cl. 6.3.4 is not applied).
 */
object Seismic {

    enum class Soil(val label: String) { I("I — rock / hard"), II("II — medium"), III("III — soft") }

    enum class BuildingType(val label: String) {
        RC_MRF("RC moment frame (0.075 h^0.75)"),
        STEEL_MRF("Steel moment frame (0.085 h^0.75)"),
        OTHER("Other / infilled (0.09 h/√d)"),
        MODAL("Use modal period T1"),
    }

    data class Params(
        val z: Double = 0.16,            // zone III
        val importance: Double = 1.0,
        val r: Double = 5.0,             // RC SMRF
        val soil: Soil = Soil.II,
        val building: BuildingType = BuildingType.RC_MRF,
        val llFactor: Double = 0.25,     // Table 10
        val nModes: Int = 12,
        val segments: Int = 4,
    )

    /** Sa/g, IS 1893 (Part 1):2016 Cl. 6.4.2 (response spectrum method, 5 % damping). [static] uses 2.5 for T < 0.1 s. */
    fun saByG(t: Double, soil: Soil, static: Boolean = false): Double {
        val (tc, k, floorV) = when (soil) {
            Soil.I -> Triple(0.40, 1.00, 0.25)
            Soil.II -> Triple(0.55, 1.36, 0.34)
            Soil.III -> Triple(0.67, 1.67, 0.42)
        }
        return when {
            t < 0.10 -> if (static) 2.5 else 1 + 15 * t
            t <= tc -> 2.5
            t <= 4.0 -> k / t
            else -> floorV
        }
    }

    fun ah(t: Double, p: Params, static: Boolean = false) = p.z / 2 * saByG(t, p.soil, static) * p.importance / p.r

    /** CQC correlation coefficient (equal damping ζ). */
    fun rho(wi: Double, wj: Double, zeta: Double = 0.05): Double {
        val b = wj / wi
        return 8 * zeta * zeta * (1 + b) * b.pow(1.5) / ((1 - b * b).pow(2) + 4 * zeta * zeta * b * (1 + b).pow(2))
    }

    class ModeRow(val mode: Int, val period: Double, val saG: Double, val ah: Double, val massRatio: Double, val baseShear: Double)

    class Drift(val z: Double, val height: Double, val drift: Double) { val ok: Boolean get() = drift <= 0.004 }

    class Direction internal constructor(
        val name: String,
        val axis: Int,
        val rows: List<ModeRow>,
        val baseShearRs: Double,        // CQC, before scaling (kN)
        val ta: Double,
        val baseShearStatic: Double,    // Ah(Ta)·W (kN)
        val weight: Double,             // W (kN)
        val scale: Double,              // ≥ 1, Cl. 7.7.3
        val nodeDisplacement: Map<Int, Double>,   // CQC × scale, along the axis (m)
        val drifts: List<Drift>,
        private val rhoM: Array<DoubleArray>,
        private val modeEndForces: List<Map<Int, DoubleArray>>,   // per mode: mesh member → local end forces
        private val chains: Map<Int, List<Int>>,
        private val lengths: Map<Int, Double>,
    ) {
        val baseShear: Double get() = baseShearRs * scale
        val cumulativeMass: Double get() = rows.sumOf { it.massRatio }

        private fun cqc(v: DoubleArray): Double {
            var s = 0.0
            for (i in v.indices) for (j in v.indices) s += rhoM[i][j] * v[i] * v[j]
            return sqrt(maxOf(s, 0.0))
        }

        /** Absolute (CQC, scaled) internal forces at positions [ss] (m from node i) of an original member. */
        fun stations(member: Int, ss: List<Double>): List<Station> {
            val chain = chains[member] ?: return emptyList()
            val l = lengths[member]!!
            val seg = l / chain.size
            return ss.map { s ->
                val p = floor(s / seg).toInt().coerceIn(0, chain.size - 1)
                val x = s - p * seg
                val q = DoubleArray(6)
                val per = Array(6) { DoubleArray(modeEndForces.size) }
                modeEndForces.forEachIndexed { k, map ->
                    val fe = map[chain[p]]!!
                    per[0][k] = -fe[0]; per[1][k] = -fe[1]; per[2][k] = -fe[2]; per[3][k] = -fe[3]
                    per[4][k] = -(fe[4] + x * fe[2]); per[5][k] = -fe[5] + x * fe[1]
                }
                for (c in 0 until 6) q[c] = cqc(per[c]) * scale
                Station(s, q[0], q[1], q[2], q[3], q[4], q[5])
            }
        }
    }

    class Result(val params: Params, val modal: ModalResult, val x: Direction, val y: Direction, val height: Double)

    fun analyse(model: FrameModel, p: Params = Params()): Result {
        val sys = Modal.system(model, p.nModes, p.llFactor, p.segments)
        val modal = Modal.analyse(model, p.nModes, p.llFactor, p.segments)
        val mesh = sys.mesh
        val zs = model.nodes.map { it.z }
        val height = (zs.max() - zs.min()).coerceAtLeast(0.1)
        val omegas = sys.lambdas.map { sqrt(it) }
        val q = omegas.size
        val rhoM = Array(q) { i -> DoubleArray(q) { j -> if (omegas[i] > 0 && omegas[j] > 0) rho(omegas[i], omegas[j]) else if (i == j) 1.0 else 0.0 } }
        val lengths = model.members.associate { it.id to (model.node(it.j).pos - model.node(it.i).pos).length }
        val memberK = mesh.members.associate { it.id to FrameSolver.memberGlobalStiffness(mesh, it) }
        val memberR = mesh.members.associate { it.id to FrameSolver.rotation(FrameSolver.axes(mesh, it)) }
        fun dir(axis: Int, name: String): Direction {
            val n = sys.free.size
            val rv = DoubleArray(n) { if (sys.free[it] % 6 == axis) 1.0 else 0.0 }
            val mTot = Modal.quad(sys.mr, rv, rv)
            val w = mTot * Modal.G
            val rows = ArrayList<ModeRow>()
            val endForces = ArrayList<Map<Int, DoubleArray>>()
            val nodeDispModes = ArrayList<Map<Int, Double>>()
            val baseShears = DoubleArray(q)
            sys.phis.forEachIndexed { k, phi ->
                val gm = Modal.quad(sys.mr, phi, phi)
                val lk = Modal.quad(sys.mr, phi, rv)
                val gamma = lk / gm
                val om = omegas[k]
                val t = if (om > 0) 2 * PI / om else 0.0
                val sa = saByG(t, p.soil)
                val a = ah(t, p)
                val full = DoubleArray(sys.nDof)
                if (om > 0) for (r in 0 until n) full[sys.free[r]] = gamma * a * Modal.G / (om * om) * phi[r]
                // member end forces (local) and reactions
                val ef = HashMap<Int, DoubleArray>()
                val react = DoubleArray(sys.nDof)
                for (mem in mesh.members) {
                    val a0 = mesh.nodeIndex[mem.i]!! * 6; val b0 = mesh.nodeIndex[mem.j]!! * 6
                    val idx = IntArray(12) { if (it < 6) a0 + it else b0 + it - 6 }
                    val kg = memberK[mem.id]!!
                    val fg = DoubleArray(12) { i -> var s = 0.0; for (c in 0 until 12) s += kg[i][c] * full[idx[c]]; s }
                    for (i in 0 until 12) react[idx[i]] += fg[i]
                    val rot = memberR[mem.id]!!
                    ef[mem.id] = DoubleArray(12) { i -> val blk = i / 3; val aa = i % 3; var s = 0.0; for (bb in 0 until 3) s += rot[aa][bb] * fg[blk * 3 + bb]; s }
                }
                for (pn in mesh.panels) {
                    val kp = Shell.globalStiffness(mesh, pn)
                    val idx = IntArray(24) { mesh.nodeIndex[pn.nodes[it / 6]]!! * 6 + it % 6 }
                    for (i in 0 until 24) { var s = 0.0; for (c in 0 until 24) s += kp[i][c] * full[idx[c]]; react[idx[i]] += s }
                }
                // inertia forces acting directly on restrained DOFs: F = Γ·Ah·g·M·φ
                val phiFull = DoubleArray(sys.nDof); for (r in 0 until n) phiFull[sys.free[r]] = phi[r]
                var vb = 0.0
                for (s in mesh.supports) {
                    val dof = mesh.nodeIndex[s.node]!! * 6 + axis
                    if (!s.restraints[axis]) continue
                    var fm = 0.0
                    for (c in 0 until sys.nDof) fm += sys.m[dof][c] * phiFull[c]
                    vb += react[dof] - gamma * a * Modal.G * fm
                }
                baseShears[k] = vb
                rows += ModeRow(k + 1, t, sa, a, if (mTot > 0) lk * lk / gm / mTot else 0.0, abs(vb))
                endForces += ef
                nodeDispModes += model.nodes.associate { nd -> nd.id to full[mesh.nodeIndex[nd.id]!! * 6 + axis] }
            }
            fun cqc(v: DoubleArray): Double { var s = 0.0; for (i in 0 until q) for (j in 0 until q) s += rhoM[i][j] * v[i] * v[j]; return sqrt(maxOf(s, 0.0)) }
            val vbRs = cqc(baseShears)
            // static base shear for the minimum (Cl. 7.7.3)
            val extent = model.nodes.map { if (axis == 0) it.x else it.y }.let { (it.max() - it.min()).coerceAtLeast(0.1) }
            val t1 = rows.maxByOrNull { it.massRatio }?.period ?: 0.0
            val ta = when (p.building) {
                BuildingType.RC_MRF -> 0.075 * height.pow(0.75)
                BuildingType.STEEL_MRF -> 0.085 * height.pow(0.75)
                BuildingType.OTHER -> 0.09 * height / sqrt(extent)
                BuildingType.MODAL -> t1
            }
            val vbStatic = ah(ta, p, static = true) * w
            val scale = if (vbRs > 1e-12) maxOf(1.0, vbStatic / vbRs) else 1.0
            val disp = model.nodes.associate { nd -> nd.id to cqc(DoubleArray(q) { nodeDispModes[it][nd.id]!! }) * scale }
            // storey drifts: mean level displacement per mode, drift per mode, then CQC
            val levels = model.nodes.groupBy { Math.round(it.z * 1000) }.toSortedMap().values.map { ns -> ns.first().z to ns.map { it.id } }
            val drifts = (1 until levels.size).map { li ->
                val (z1, n1) = levels[li]; val (z0, n0) = levels[li - 1]
                val h = z1 - z0
                val per = DoubleArray(q) { k -> (n1.map { nodeDispModes[k][it]!! }.average() - n0.map { nodeDispModes[k][it]!! }.average()) / h }
                Drift(z1, h, cqc(per) * scale)
            }
            return Direction(name, axis, rows, vbRs, ta, vbStatic, w, scale, disp, drifts, rhoM, endForces, sys.chains, lengths)
        }
        return Result(p, modal, dir(0, "EQ X"), dir(1, "EQ Y"), height)
    }

    /**
     * Seismic design envelope per member: IS 1893 Cl. 6.3.1.2 combinations
     * 1.5(DL+LL), 1.2(DL+LL±EL), 1.5(DL±EL), 0.9DL±1.5EL with EL from X and Y separately.
     */
    fun envelope(model: FrameModel, res: Result, stations: Int = 40): List<MemberEnvelope> {
        val grav = listOf(
            LoadCombination("1.5(DL+LL)", mapOf(LoadCase.DL to 1.5, LoadCase.LL to 1.5)) to 0.0,
            LoadCombination("1.2(DL+LL)", mapOf(LoadCase.DL to 1.2, LoadCase.LL to 1.2)) to 1.2,
            LoadCombination("1.5DL", mapOf(LoadCase.DL to 1.5)) to 1.5,
            LoadCombination("0.9DL", mapOf(LoadCase.DL to 0.9)) to 1.5,
        )
        val statics = grav.map { (c, f) -> FrameSolver.analyse(model, c) to f }
        return model.members.map { m ->
            var myMax = 0.0; var myMin = 0.0; var vz = 0.0; var nAbs = 0.0
            for ((r, f) in statics) {
                val st = r.internalForces(m.id, stations)
                val ss = st.map { it.s }
                val eqs = if (f > 0) listOf(res.x.stations(m.id, ss), res.y.stations(m.id, ss)) else listOf(st.map { Station(it.s, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) })
                for (e in eqs) st.forEachIndexed { k, s ->
                    val de = e[k]
                    myMax = maxOf(myMax, s.my + f * de.my)
                    myMin = minOf(myMin, s.my - f * de.my)
                    vz = maxOf(vz, abs(s.vz) + f * de.vz)
                    val nn = if (s.n >= 0) s.n + f * de.n else s.n - f * de.n
                    if (abs(nn) > abs(nAbs)) nAbs = nn
                }
            }
            val ax = statics.first().first.memberAxes[m.id]!!
            MemberEnvelope(m.id, ax.length, abs(ax.x.z) < 0.2, nAbs, vz, myMax, myMin, m.section.b, m.section.h)
        }
    }
}
