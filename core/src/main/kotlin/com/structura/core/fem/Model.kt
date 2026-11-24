package com.structura.core.fem

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
    }
}

/**
 * Cross-section properties about member local axes (m², m⁴).
 * Iy — bending about local y (the strong axis for a beam whose depth is along local z).
 */
data class Section(
    val name: String, val a: Double, val iy: Double, val iz: Double, val j: Double,
    /** Overall width / depth (m) for rectangles; 0 for other shapes. */
    val b: Double = 0.0, val h: Double = 0.0,
) {
    companion object {
        /** Rectangle: [b] along local y (width), [h] along local z (depth). Dimensions in metres. */
        fun rect(b: Double, h: Double, name: String = "R ${(b * 1000).toInt()}×${(h * 1000).toInt()}"): Section {
            val a = max(b, h)
            val t = min(b, h)
            // St Venant torsion constant of a rectangle (Roark, Table 10.7)
            val jt = a * t.pow(3) * (1.0 / 3.0 - 0.21 * (t / a) * (1 - t.pow(4) / (12 * a.pow(4))))
            return Section(name, b * h, b * h.pow(3) / 12.0, h * b.pow(3) / 12.0, jt, b, h)
        }
    }
}

data class Member(
    val id: Int,
    val i: Int,
    val j: Int,
    val material: Material,
    val section: Section,
    val betaDeg: Double = 0.0,
)

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

/** Concentrated load at a node in global axes: Fx, Fy, Fz (kN), Mx, My, Mz (kN·m). */
data class NodalLoad(val node: Int, val values: List<Double>) {
    init { require(values.size == 6) }
}

enum class LoadDir { GLOBAL_X, GLOBAL_Y, GLOBAL_Z, LOCAL_X, LOCAL_Y, LOCAL_Z }

/** Uniform member load over the true member length, kN/m (gravity = GLOBAL_Z with negative w). */
data class MemberLoad(val member: Int, val dir: LoadDir, val w: Double)

data class FrameModel(
    val nodes: List<Node>,
    val members: List<Member>,
    val supports: List<Support>,
    val nodalLoads: List<NodalLoad> = emptyList(),
    val memberLoads: List<MemberLoad> = emptyList(),
    val selfWeight: Boolean = true,
    val title: String = "Model",
) {
    val nodeIndex: Map<Int, Int> = nodes.withIndex().associate { (k, n) -> n.id to k }
    fun node(id: Int): Node = nodes[nodeIndex[id] ?: error("Node $id not found")]
}
