package com.structura.core.fem

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * 3D frame model in the style of RFEM / STAAD.
 *
 * Units: kN, m (stresses kN/m², densities kN/m³).
 * Global axes: X, Y horizontal, Z vertical UP. Gravity acts in −Z.
 *
 * Member local axes (as in RFEM):
 *   x — along the member, from node i to node j
 *   non-vertical members: y = x × Z (horizontal), z = x × y  → z points DOWN for a horizontal beam,
 *                         so a sagging moment is a positive My (RFEM sign convention)
 *   vertical members:     y = global Y, z = x × y
 *   then rotated about x by the member angle β.
 */

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Double get() = sqrt(this dot this)
    fun unit(): Vec3 = this * (1.0 / length)

    companion object {
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

data class Node(val id: Int, val x: Double, val y: Double, val z: Double) {
    val pos: Vec3 get() = Vec3(x, y, z)
}

/** E, G in kN/m²; unit weight γ in kN/m³. */
data class Material(val name: String, val e: Double, val g: Double, val gamma: Double) {
    companion object {
        /** Concrete: E = 5000·√fck MPa (IS 456 Cl. 6.2.3.1), ν = 0.2, γ = 25 kN/m³ (IS 875 Part 1). */
        fun concrete(fck: Double): Material {
            val e = 5000.0 * sqrt(fck) * 1000.0
            return Material("M${fck.toInt()}", e, e / (2 * 1.2), 25.0)
        }

        /** Structural steel: E = 2×10⁵ MPa, G = 0.769×10⁵ MPa (IS 800 Cl. 2.2.4.1), γ = 78.5 kN/m³. */
        fun steel(fy: Double = 250.0) = Material("E${fy.toInt()}", 2.0e8, 0.769e8, 78.5)

        /**
         * Pultruded GFRP — ILLUSTRATIVE longitudinal values (E ≈ 23 GPa, G ≈ 3.5 GPa, γ ≈ 19 kN/m³),
         * typical of EN 13706 grade E23 profiles. Replace with the manufacturer's tested values.
         */
        fun gfrp() = Material("GFRP (illustrative)", 23.0e6, 3.5e6, 19.0)

        /** User-defined material from MPa and kN/m³. */
        fun custom(name: String, eMpa: Double, gMpa: Double, gamma: Double) = Material(name, eMpa * 1000, gMpa * 1000, gamma)
    }
}

/**
 * Cross-section properties about member local axes (m², m⁴).
 * Iy — bending about local y (the strong axis for a beam whose depth is along local z).
 */
data class Section(
    val name: String, val a: Double, val iy: Double, val iz: Double, val j: Double,
    /** Overall width (along local y) / depth (along local z), m. */
    val b: Double = 0.0, val h: Double = 0.0,
    /** Shear areas for Timoshenko theory (m²) for shear along local y / z; 0 = shear-rigid. */
    val asy: Double = 0.0, val asz: Double = 0.0,
    /** RECT, CHS, SHS, RHS or GENERAL; [t] = wall thickness (m) for tubes. */
    val shape: String = "GENERAL", val t: Double = 0.0,
) {
    val isSolidRectangle: Boolean get() = shape == "RECT"

    companion object {
        /** Circular hollow section: outside diameter [d], wall [t] (m). J = 2I (exact), shear area A/2. */
        fun chs(d: Double, t: Double, name: String = "CHS ${mm(d)}×${mm(t)}"): Section {
            require(t > 0 && 2 * t < d) { "CHS wall must be > 0 and < D/2" }
            val di = d - 2 * t
            val a = Math.PI / 4 * (d * d - di * di)
            val i = Math.PI / 64 * (d.pow(4) - di.pow(4))
            return Section(name, a, i, i, 2 * i, d, d, a / 2, a / 2, "CHS", t)
        }

        /**
         * Rectangular hollow section [b] (along local y) × [h] (along local z), wall [t] (m), sharp corners.
         * J by Bredt's thin-wall formula 4·Am²·t / pm; shear areas A·h/(b+h) (webs) and A·b/(b+h).
         */
        fun rhs(b: Double, h: Double, t: Double, name: String = (if (b == h) "SHS ${mm(b)}×${mm(t)}" else "RHS ${mm(h)}×${mm(b)}×${mm(t)}")): Section {
            require(t > 0 && 2 * t < min(b, h)) { "Tube wall must be > 0 and < half the smaller side" }
            val bi = b - 2 * t; val hi = h - 2 * t
            val a = b * h - bi * hi
            val iy = (b * h.pow(3) - bi * hi.pow(3)) / 12
            val iz = (h * b.pow(3) - hi * bi.pow(3)) / 12
            val am = (b - t) * (h - t)
            val pm = 2 * ((b - t) + (h - t))
            val jt = 4 * am * am * t / pm
            return Section(name, a, iy, iz, jt, b, h, a * b / (b + h), a * h / (b + h), if (b == h) "SHS" else "RHS", t)
        }

        private fun mm(v: Double): String {
            val x = v * 1000
            return if (abs(x - Math.round(x)) < 1e-6) Math.round(x).toString() else String.format(java.util.Locale.US, "%.1f", x)
        }

        /** Rectangle: [b] along local y (width), [h] along local z (depth). Dimensions in metres. */
        fun rect(b: Double, h: Double, name: String = "R ${(b * 1000).toInt()}×${(h * 1000).toInt()}"): Section {
            val a = max(b, h)
            val t = min(b, h)
            // St Venant torsion constant of a rectangle (Roark, Table 10.7)
            val jt = a * t.pow(3) * (1.0 / 3.0 - 0.21 * (t / a) * (1 - t.pow(4) / (12 * a.pow(4))))
            return Section(name, b * h, b * h.pow(3) / 12.0, h * b.pow(3) / 12.0, jt, b, h, 5.0 / 6 * b * h, 5.0 / 6 * b * h, "RECT")
        }
    }
}

/**
 * A member. [hingeI] / [hingeJ] release both bending moments (My, Mz) at that end — a moment hinge,
 * as RFEM's "hinged" member end release. Axial, shear and torsion stay connected.
 */
data class Member(
    val id: Int,
    val i: Int,
    val j: Int,
    val material: Material,
    val section: Section,
    val betaDeg: Double = 0.0,
    val hingeI: Boolean = false,
    val hingeJ: Boolean = false,
    /** Rotational spring at end i / j (kN·m/rad, about local y and z); null = rigid joint. Semi-rigid connection. */
    val springI: Double? = null,
    val springJ: Double? = null,
    /** Rigid end offsets (m) measured along the member from node i / node j (joint zones). */
    val offsetI: Double = 0.0,
    val offsetJ: Double = 0.0,
) {
    /** Effective end condition: null = rigid, 0 = hinge, > 0 = spring stiffness. */
    val endI: Double? get() = if (hingeI) 0.0 else springI
    val endJ: Double? get() = if (hingeJ) 0.0 else springJ
}

/** Beam theory used for member stiffness. */
enum class BeamTheory(val label: String) {
    EULER("Euler–Bernoulli"),
    TIMOSHENKO("Timoshenko (shear deformable)"),
}

/** Six restraints in global axes: ux, uy, uz, φx, φy, φz (true = fixed). */
data class Support(val node: Int, val restraints: List<Boolean>) {
    init { require(restraints.size == 6) }

    companion object {
        fun fixed(node: Int) = Support(node, List(6) { true })
        /** Pinned in all translations, torsion about X held so lines of beams are stable. */
        fun pinned(node: Int) = Support(node, listOf(true, true, true, true, false, false))
        /** Roller: free to slide in X. */
        fun rollerX(node: Int) = Support(node, listOf(false, true, true, true, false, false))
    }
}

/** Load cases used by the generators and the IS 875 combinations. Self-weight always belongs to DL. */
object LoadCase {
    const val DL = "DL"      // dead load (IS 875 Part 1)
    const val LL = "LL"      // imposed load (IS 875 Part 2)
    const val WL = "WL"      // wind / lateral (IS 875 Part 3) or EL (IS 1893)
    const val TAP = "TAP"    // interactive loads applied on screen
}

/** Concentrated load at a node in global axes: Fx, Fy, Fz (kN), Mx, My, Mz (kN·m). */
data class NodalLoad(val node: Int, val values: List<Double>, val case: String = LoadCase.DL) {
    init { require(values.size == 6) }
}

enum class LoadDir { GLOBAL_X, GLOBAL_Y, GLOBAL_Z, LOCAL_X, LOCAL_Y, LOCAL_Z }

/**
 * Distributed member load, kN/m over the true length (gravity = GLOBAL_Z with negative w).
 * Uniform over the whole member by default; give [a]/[b] (m from node i) for a partial load and
 * [w2] (intensity at b) for a trapezoidal / triangular load varying linearly from [w] at a.
 */
data class MemberLoad(
    val member: Int, val dir: LoadDir, val w: Double, val case: String = LoadCase.DL,
    val a: Double? = null, val b: Double? = null, val w2: Double? = null,
) {
    val isUniformFull: Boolean get() = a == null && b == null && (w2 == null || w2 == w)
}

/**
 * Four-node flat shell panel (Q4: membrane + Mindlin plate, 6 DOF per node).
 * [nodes] in order around the panel; the normal follows the right-hand rule (anticlockwise seen from +normal).
 * [pressure] kN/m² acts along the panel normal (+ = along the normal), in load case [case].
 * Units: t in m, E and G in kN/m², γ in kN/m³.
 */
data class Panel(
    val id: Int,
    val nodes: List<Int>,
    val t: Double,
    val e: Double,
    val g: Double,
    val nu: Double,
    val gamma: Double,
    val pressure: Double = 0.0,
    val case: String = LoadCase.LL,
    val name: String = "Panel",
) {
    init { require(nodes.size == 4) { "A panel needs 4 nodes" } }

    companion object {
        /** RCC slab: E = 5000√fck MPa, ν = 0.2, γ = 25 kN/m³. */
        fun rcc(id: Int, nodes: List<Int>, t: Double, fck: Double = 25.0, pressure: Double = 0.0, case: String = LoadCase.LL): Panel {
            val e = 5000 * sqrt(fck) * 1000
            return Panel(id, nodes, t, e, e / 2.4, 0.2, 25.0, pressure, case, "RCC M${fck.toInt()} slab")
        }
    }
}

/** Concentrated force [p] (kN) on a member at distance [a] (m) from node i. */
data class PointLoad(val member: Int, val dir: LoadDir, val p: Double, val a: Double, val case: String = LoadCase.DL)

/** A load combination: factor per load case; cases not listed get factor 0. */
data class LoadCombination(val name: String, val factors: Map<String, Double>, val selfWeightFactor: Double = factors[LoadCase.DL] ?: 0.0) {
    fun factor(case: String) = factors[case] ?: 0.0

    companion object {
        /** Every load × 1.0 — what `FrameSolver.analyse(model)` uses. */
        val ALL = LoadCombination("All loads × 1.0", emptyMap(), 1.0)

        /**
         * IS 456:2000 Table 18 / IS 875 (Part 5) Cl. 6.3 limit-state combinations for strength.
         * WL stands for wind (IS 875 Part 3) or earthquake (IS 1893) — both are applied in ±X.
         */
        fun isLimitState(withLateral: Boolean): List<LoadCombination> {
            val list = arrayListOf(LoadCombination("1.5(DL+LL)", mapOf(LoadCase.DL to 1.5, LoadCase.LL to 1.5)))
            if (withLateral) {
                list += LoadCombination("1.2(DL+LL+WL)", mapOf(LoadCase.DL to 1.2, LoadCase.LL to 1.2, LoadCase.WL to 1.2))
                list += LoadCombination("1.2(DL+LL−WL)", mapOf(LoadCase.DL to 1.2, LoadCase.LL to 1.2, LoadCase.WL to -1.2))
                list += LoadCombination("1.5(DL+WL)", mapOf(LoadCase.DL to 1.5, LoadCase.WL to 1.5))
                list += LoadCombination("1.5(DL−WL)", mapOf(LoadCase.DL to 1.5, LoadCase.WL to -1.5))
                list += LoadCombination("0.9DL+1.5WL", mapOf(LoadCase.DL to 0.9, LoadCase.WL to 1.5))
                list += LoadCombination("0.9DL−1.5WL", mapOf(LoadCase.DL to 0.9, LoadCase.WL to -1.5))
            }
            return list
        }

        /** Single load cases × 1.0 (service level, for deflection). */
        fun cases(withLive: Boolean, withLateral: Boolean): List<LoadCombination> = buildList {
            add(LoadCombination("DL", mapOf(LoadCase.DL to 1.0)))
            if (withLive) add(LoadCombination("LL", mapOf(LoadCase.LL to 1.0)))
            if (withLateral) add(LoadCombination("WL", mapOf(LoadCase.WL to 1.0)))
            add(LoadCombination("DL+LL (service)", mapOf(LoadCase.DL to 1.0, LoadCase.LL to 1.0)))
        }
    }
}

data class FrameModel(
    val nodes: List<Node>,
    val members: List<Member>,
    val supports: List<Support>,
    val nodalLoads: List<NodalLoad> = emptyList(),
    val memberLoads: List<MemberLoad> = emptyList(),
    val selfWeight: Boolean = true,
    val title: String = "Model",
    val pointLoads: List<PointLoad> = emptyList(),
    /** Multiplier on self-weight (set by load combinations). */
    val selfWeightFactor: Double = 1.0,
    val beamTheory: BeamTheory = BeamTheory.EULER,
    val panels: List<Panel> = emptyList(),
) {
    /** Load cases present in this model (DL is always present when self-weight is on). */
    val loadCases: Set<String>
        get() = buildSet {
            if (selfWeight) add(LoadCase.DL)
            nodalLoads.forEach { add(it.case) }; memberLoads.forEach { add(it.case) }; pointLoads.forEach { add(it.case) }
            panels.filter { it.pressure != 0.0 }.forEach { add(it.case) }
        }

    /** The same model with every load multiplied by its combination factor (linear superposition). */
    fun factored(c: LoadCombination): FrameModel {
        if (c === LoadCombination.ALL) return this
        fun f(case: String) = if (case == LoadCase.TAP) 1.0 else c.factor(case)
        return copy(
            nodalLoads = nodalLoads.mapNotNull { l -> f(l.case).takeIf { it != 0.0 }?.let { k -> l.copy(values = l.values.map { it * k }) } },
            memberLoads = memberLoads.mapNotNull { l -> f(l.case).takeIf { it != 0.0 }?.let { k -> l.copy(w = l.w * k, w2 = l.w2?.times(k)) } },
            panels = panels.map { p -> p.copy(pressure = p.pressure * f(p.case)) },
            pointLoads = pointLoads.mapNotNull { l -> f(l.case).takeIf { it != 0.0 }?.let { k -> l.copy(p = l.p * k) } },
            selfWeightFactor = if (selfWeight) c.selfWeightFactor * selfWeightFactor else 0.0,
            title = "$title — ${c.name}",
        )
    }
    val nodeIndex: Map<Int, Int> = nodes.withIndex().associate { (k, n) -> n.id to k }
    fun node(id: Int): Node = nodes[nodeIndex[id] ?: error("Node $id not found")]
}
