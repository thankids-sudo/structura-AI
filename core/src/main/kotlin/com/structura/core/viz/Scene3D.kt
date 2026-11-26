package com.structura.core.viz

/**
 * Platform-neutral 3D scene of a designed element for the animated viewer: solids (prisms extruded along y),
 * reinforcement polylines, load / pressure arrows, dimensions and labels. Every item belongs to a construction
 * [stages] step (0-based) so the viewer can build the element up in sequence. Units: m; x across the section,
 * y along the length, z up.
 */
class Scene3D(val title: String, val stages: List<String>, val items: List<Item>) {
    data class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun times(k: Double) = V(x * k, y * k, z * k)
    }

    enum class Mat { CONCRETE, SOIL, GROUND, MASONRY, STEEL, SHEET }
    enum class Load { EARTH, SURCHARGE, BEARING, GRAVITY, WIND }

    sealed class Item { abstract val stage: Int }

    /** Polygon [section] in the x–z plane extruded from y0 to y1. */
    class Prism(val section: List<Pair<Double, Double>>, val y0: Double, val y1: Double, val mat: Mat, override val stage: Int) : Item()

    /** General flat face (e.g. a sheet or a curved roof strip). */
    class Face(val pts: List<V>, val mat: Mat, override val stage: Int) : Item()

    /** Reinforcing bar (main = drawn heavier) or a steel member line. */
    class Bar(val pts: List<V>, val dia: Double, override val stage: Int, val main: Boolean = true, val steel: Boolean = false) : Item()

    class Arrow(val from: V, val to: V, val kind: Load, override val stage: Int) : Item()

    /** Thin outline (pressure diagram envelope, setting-out). */
    class Line(val a: V, val b: V, val kind: Load?, override val stage: Int, val dashed: Boolean = false) : Item()

    class Label(val at: V, val text: String, override val stage: Int, val strong: Boolean = false) : Item()

    class Dim(val a: V, val b: V, val text: String, override val stage: Int) : Item()

    val bounds: Pair<V, V> by lazy {
        val pts = items.flatMap { points(it) }.ifEmpty { listOf(V(0.0, 0.0, 0.0), V(1.0, 1.0, 1.0)) }
        V(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }) to V(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
    }

    companion object {
        fun points(it: Item): List<V> = when (it) {
            is Prism -> it.section.flatMap { (x, z) -> listOf(V(x, it.y0, z), V(x, it.y1, z)) }
            is Face -> it.pts
            is Bar -> it.pts
            is Arrow -> listOf(it.from, it.to)
            is Line -> listOf(it.a, it.b)
            is Label -> listOf(it.at)
            is Dim -> listOf(it.a, it.b)
        }

        fun rect(x0: Double, z0: Double, x1: Double, z1: Double) = listOf(x0 to z0, x1 to z0, x1 to z1, x0 to z1)

        /** Positions from [from] to [to] at about [spacing], first and last at the ends. */
        fun along(from: Double, to: Double, spacing: Double, maxCount: Int = 60): List<Double> {
            if (to <= from) return listOf(from)
            val n = (kotlin.math.ceil((to - from) / spacing).toInt() + 1).coerceIn(2, maxCount)
            return (0 until n).map { from + (to - from) * it / (n - 1) }
        }
    }

    class Builder(private val title: String) {
        private val stages = ArrayList<String>()
        private val items = ArrayList<Item>()
        var stage = -1; private set
        fun stage(name: String): Builder { stages += name; stage = stages.size - 1; return this }
        fun prism(section: List<Pair<Double, Double>>, y0: Double, y1: Double, mat: Mat) { items += Prism(section, y0, y1, mat, stage) }
        fun face(pts: List<V>, mat: Mat) { items += Face(pts, mat, stage) }
        fun bar(pts: List<V>, dia: Double, main: Boolean = true) { items += Bar(pts, dia, stage, main) }
        fun member(a: V, b: V, sizeMm: Double = 60.0) { items += Bar(listOf(a, b), sizeMm, stage, true, steel = true) }
        fun arrow(from: V, to: V, kind: Load) { items += Arrow(from, to, kind, stage) }
        fun line(a: V, b: V, kind: Load? = null, dashed: Boolean = false) { items += Line(a, b, kind, stage, dashed) }
        fun label(at: V, text: String, strong: Boolean = false) { items += Label(at, text, stage, strong) }
        fun dim(a: V, b: V, text: String) { items += Dim(a, b, text, stage) }
        fun build() = Scene3D(title, stages.toList(), items.toList())
    }
}
