package com.structura.core.roof

import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameResults
import com.structura.core.fem.FrameSolver
import com.structura.core.fem.LateralLoads
import com.structura.core.fem.LoadCase
import com.structura.core.fem.LoadCombination
import com.structura.core.fem.Material
import com.structura.core.fem.Member
import com.structura.core.fem.NodalLoad
import com.structura.core.fem.Node
import com.structura.core.fem.Section
import com.structura.core.fem.Support
import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.Status
import com.structura.core.report.ReportDoc
import com.structura.core.report.f0
import com.structura.core.report.f1
import com.structura.core.report.f2
import com.structura.core.report.f3
import com.structura.core.steel.AngleDesign
import com.structura.core.steel.IS800
import com.structura.core.steel.SteelGrade
import com.structura.core.steel.SteelSection
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pitched steel roof truss: geometry, IS 875 loads (Part 1 sheeting, Part 2 roof live load, Part 3 wind),
 * pin-jointed FEM analysis for the IS 800 Table 4 combinations, lightest single-angle section per member group
 * (top chord, bottom chord, webs) by IS 800 Cl. 6 / 7.5.1.2 / 6.4, and channel purlins (Cl. 8, Table 6).
 */
object RoofTruss {
    enum class Type(val label: String) { FINK("Fink (W)"), PRATT("Pratt"), HOWE("Howe"), FAN("Fan") }

    data class Input(
        val type: Type = Type.FINK,
        val span: Double = 12.0,               // m, centre of supports
        val rise: Double = 3.0,                // m, eaves to apex
        val panels: Int = 6,                   // bottom-chord panels (Pratt / Howe / Fan; even)
        val spacing: Double = 4.0,             // m, truss to truss
        val purlinSpacing: Double = 1.4,       // m, along the slope (max)
        val sheeting: Double = 0.15,           // kN/m² on slope (GI / profiled sheet + fixings)
        val services: Double = 0.10,           // kN/m² on plan (lighting, insulation)
        val roofLive: Double? = null,          // kN/m² on plan; null = IS 875-2 Table 2 by slope
        val vb: Double = 44.0, val terrain: Int = 2, val eavesHeight: Double = 6.0,
        val k1: Double = 1.0, val k3: Double = 1.0,
        val cpeWindward: Double = -0.8, val cpeLeeward: Double = -0.4, val cpi: Double = 0.2,
        val grade: SteelGrade = SteelGrade.E250,
        val bolts: Int = 2, val holeDia: Double = 18.0,
        val doubleAngles: Boolean = false,      // two angles back to back on the gusset
        val gusset: Double = 8.0,               // mm
        val boltDia: Double = 16.0,             // mm, grade 4.6
    )

    class Group(val name: String, val members: List<Int>, val length: Double, val tension: Double, val compression: Double,
                val section: String, val result: com.structura.core.steel.SteelResult)

    class Result(val model: FrameModel, val slopeDeg: Double, val ll: Double, val pd: Double, val groups: List<Group>,
                 val purlin: String, val purlinChecks: List<DesignCheck>, val report: ReportDoc, val forces: Map<Int, Pair<Double, Double>>,
                 val detail: TrussDetail.Result)

    /** Roof live load on plan for a sloping roof without access (IS 875-2 Table 2): 0.75 − 0.02(θ − 10) ≥ 0.4 kN/m². */
    fun roofLive(slopeDeg: Double) = if (slopeDeg <= 10) 0.75 else max(0.4, 0.75 - 0.02 * (slopeDeg - 10))

    /** Truss geometry (XZ plane, pin-jointed). Returns model + member groups (top, bottom, web). */
    fun geometry(t: Type, span: Double, rise: Double, panels0: Int, sec: Section, mat: Material): Pair<FrameModel, Map<String, List<Int>>> {
        val nodes = ArrayList<Node>(); val mem = ArrayList<Triple<Int, Int, String>>()
        fun z(x: Double) = rise * (1 - abs(x - span / 2) / (span / 2))
        when (t) {
            Type.FINK -> {
                listOf(0.0, span / 3, 2 * span / 3, span).forEachIndexed { i, x -> nodes += Node(i + 1, x, 0.0, 0.0) }
                listOf(span / 4, span / 2, 3 * span / 4).forEachIndexed { i, x -> nodes += Node(i + 5, x, 0.0, z(x)) }
                listOf(1 to 5, 5 to 6, 6 to 7, 7 to 4).forEach { mem += Triple(it.first, it.second, "Top chord") }
                listOf(1 to 2, 2 to 3, 3 to 4).forEach { mem += Triple(it.first, it.second, "Bottom chord") }
                listOf(2 to 5, 2 to 6, 3 to 6, 3 to 7).forEach { mem += Triple(it.first, it.second, "Web") }
            }
            Type.FAN -> {
                // bottom chord at thirds; from each third point three webs fan out to the top-chord nodes (6 top panels)
                listOf(0.0, span / 3, 2 * span / 3, span).forEachIndexed { i, x -> nodes += Node(i + 1, x, 0.0, 0.0) }
                (1..5).forEach { k -> val x = span * k / 6; nodes += Node(4 + k, x, 0.0, z(x)) }
                listOf(1 to 5, 5 to 6, 6 to 7, 7 to 8, 8 to 9, 9 to 4).forEach { mem += Triple(it.first, it.second, "Top chord") }
                listOf(1 to 2, 2 to 3, 3 to 4).forEach { mem += Triple(it.first, it.second, "Bottom chord") }
                listOf(2 to 5, 2 to 6, 2 to 7, 3 to 7, 3 to 8, 3 to 9).forEach { mem += Triple(it.first, it.second, "Web") }
            }
            else -> {
                val n = (if (panels0 % 2 == 0) panels0 else panels0 + 1).coerceAtLeast(4)
                val p = span / n
                for (i in 0..n) nodes += Node(i + 1, i * p, 0.0, 0.0)
                for (i in 1 until n) nodes += Node(n + 1 + i, i * p, 0.0, z(i * p))
                fun b(i: Int) = i + 1
                fun tp(i: Int) = if (i == 0) b(0) else if (i == n) b(n) else n + 1 + i
                for (i in 0 until n) mem += Triple(b(i), b(i + 1), "Bottom chord")
                for (i in 0 until n) mem += Triple(tp(i), tp(i + 1), "Top chord")
                for (i in 1 until n) mem += Triple(b(i), tp(i), "Web")
                when (t) {
                    Type.PRATT -> {
                        for (i in 1 until n / 2) mem += Triple(tp(i), b(i + 1), "Web")
                        for (i in n / 2 + 1 until n) mem += Triple(tp(i), b(i - 1), "Web")
                    }
                    Type.HOWE -> {
                        for (i in 1 until n / 2) mem += Triple(b(i), tp(i + 1), "Web")
                        for (i in n / 2 + 1 until n) mem += Triple(b(i), tp(i - 1), "Web")
                    }
                    else -> {}
                }
            }
        }
        val members = mem.mapIndexed { k, (a, c, _) -> Member(k + 1, a, c, mat, sec, truss = true) }
        val groups = mem.mapIndexed { k, tr -> tr.third to (k + 1) }.groupBy({ it.first }, { it.second })
        val left = nodes.minBy { it.x }.id; val right = nodes.filter { it.z == 0.0 }.maxBy { it.x }.id
        val supports = listOf(Support(left, listOf(true, true, true, false, false, false)), Support(right, listOf(false, false, true, false, false, false)))
        return FrameModel(nodes, members, supports, selfWeight = true, title = "${t.label} roof truss ${f1(span)} m", planeXZ = true) to groups
    }

    private val angles: List<Triple<String, Double, Double>> by lazy {   // name, leg (mm), t (mm) — ISA equal angles, lightest first
        listOf(50 to 5, 50 to 6, 60 to 5, 60 to 6, 65 to 6, 65 to 8, 75 to 6, 75 to 8, 80 to 8, 90 to 8, 90 to 10, 100 to 8, 100 to 10, 100 to 12, 110 to 10, 130 to 10, 150 to 12)
            .map { (b, t) -> Triple("ISA ${b}×${b}×${t}", b.toDouble(), t.toDouble()) }
            .sortedBy { (_, b, t) -> t * (2 * b - t) }
    }

    fun design(inp: Input): Result {
        val steps = ArrayList<String>()
        val theta = atan(inp.rise / (inp.span / 2)); val thDeg = Math.toDegrees(theta)
        val ll = inp.roofLive ?: roofLive(thDeg)
        steps += "Roof slope θ = atan(${f2(inp.rise)} / ${f2(inp.span / 2)}) = ${f1(thDeg)}°"
        steps += "Roof live load = ${f2(ll)} kN/m² on plan" + if (inp.roofLive == null) " (IS 875-2 Table 2: 0.75 − 0.02(θ − 10) ≥ 0.40)" else " (user)"
        // wind pressure at eaves height (+ rise / 2)
        val h = inp.eavesHeight + inp.rise / 2
        val vz = inp.vb * inp.k1 * LateralLoads.k2(h, inp.terrain) * inp.k3
        val pz = 0.6 * vz * vz / 1000
        val pd = maxOf(0.9 * 1.0 * 0.9, 0.7) * pz
        steps += "Wind: Vz = ${f1(inp.vb)} × ${inp.k1} × k2(${f1(h)} m, cat ${inp.terrain}) ${f3(LateralLoads.k2(h, inp.terrain))} × ${inp.k3} = ${f2(vz)} m/s; pz = 0.6Vz² = ${f3(pz)}; pd = ${f3(pd)} kN/m² (IS 875-3 Cl. 6.3, 7.2)"
        steps += "Net roof pressure (Cpe − Cpi): windward ${f2(inp.cpeWindward - inp.cpi)}, leeward ${f2(inp.cpeLeeward - inp.cpi)} (Cpi = +${inp.cpi}, uplift governs; Cpe from IS 875-3 Table 6 for θ = ${f1(thDeg)}°)"

        val mat = Material.steel(inp.grade.fy)
        var sec = Section.angle(0.065, 0.065, 0.006, "ISA 65×65×6")
        var (model, groupsMap) = geometry(inp.type, inp.span, inp.rise, inp.panels, sec, mat)
        // panel-point loads on the top chord
        val topIds = groupsMap.getValue("Top chord").flatMap { listOf(model.members[it - 1].i, model.members[it - 1].j) }.toSet()
        val trib = HashMap<Int, Pair<Double, Double>>()   // node → (slope length, plan length)
        for (id in groupsMap.getValue("Top chord")) {
            val m = model.members[id - 1]; val a = model.node(m.i); val c = model.node(m.j)
            val ls = hypot(c.x - a.x, c.z - a.z); val lp = abs(c.x - a.x)
            for (n in listOf(m.i, m.j)) trib[n] = (trib[n]?.first ?: 0.0) + ls / 2 to (trib[n]?.second ?: 0.0) + lp / 2
        }
        val purlinsPerM = 1 / inp.purlinSpacing
        val purlinW = 0.09                                       // kN/m assumed for the first pass (≈ ISMC 100)
        val loads = ArrayList<NodalLoad>()
        for (n in topIds.sorted()) {
            val (ls, lp) = trib.getValue(n)
            val node = model.node(n)
            val dl = (inp.sheeting * ls + inp.services * lp + purlinW * ls * purlinsPerM) * inp.spacing
            val lv = ll * lp * inp.spacing
            loads += NodalLoad(n, listOf(0.0, 0.0, -dl, 0.0, 0.0, 0.0), LoadCase.DL)
            loads += NodalLoad(n, listOf(0.0, 0.0, -lv, 0.0, 0.0, 0.0), LoadCase.LL)
            // wind from the left: windward = left slope; outward normals (−sinθ, cosθ) left, (sinθ, cosθ) right
            val leftSide = node.x < inp.span / 2 - 1e-6; val apex = abs(node.x - inp.span / 2) < 1e-6
            val parts = if (apex) listOf(true to ls / 2, false to ls / 2) else listOf(leftSide to ls)
            var fx = 0.0; var fz = 0.0
            for ((isLeft, len) in parts) {
                val net = (if (isLeft) inp.cpeWindward else inp.cpeLeeward) - inp.cpi
                val f = -net * pd * len * inp.spacing                       // along the outward normal
                fx += f * (if (isLeft) -sin(theta) else sin(theta)); fz += f * cos(theta)
            }
            loads += NodalLoad(n, listOf(fx, 0.0, fz, 0.0, 0.0, 0.0), LoadCase.WL)
        }
        model = model.copy(nodalLoads = loads)
        val combos = listOf(
            LoadCombination("1.5(DL+LL)", mapOf(LoadCase.DL to 1.5, LoadCase.LL to 1.5)),
            LoadCombination("1.5(DL+WL)", mapOf(LoadCase.DL to 1.5, LoadCase.WL to 1.5)),
            LoadCombination("0.9DL+1.5WL", mapOf(LoadCase.DL to 0.9, LoadCase.WL to 1.5)),
            LoadCombination("1.2(DL+LL+WL)", mapOf(LoadCase.DL to 1.2, LoadCase.LL to 1.2, LoadCase.WL to 1.2)),
        )
        steps += "Combinations (IS 800 Table 4): ${combos.joinToString { it.name }}; self-weight of the truss included in DL"

        // split webs into struts and ties by their force under gravity (different slenderness limits and sizes)
        run {
            val webs = groupsMap["Web"] ?: return@run
            val r0 = FrameSolver.analyse(model, combos.first())
            val (struts, ties) = webs.partition { r0.internalForces(it, 2)[1].n < 0 }
            groupsMap = (groupsMap - "Web") + listOf("Web — struts" to struts, "Web — ties" to ties).filter { it.second.isNotEmpty() }
        }
        // design loop: analyse → size groups → re-analyse with the chosen sections (self-weight changes)
        var groups = emptyList<Group>()
        var forces = emptyMap<Int, Pair<Double, Double>>()
        var chosen = groupsMap.keys.associateWith { 0 }
        repeat(3) {
            val res = FrameSolver.analyseAll(model, combos)
            forces = model.members.associate { m ->
                val n = res.map { (_, r) -> r.internalForces(m.id, 2).map { it.n } }.flatten()
                m.id to (max(0.0, n.max()) to max(0.0, -n.min()))
            }
            val gravComp = model.members.associate { m ->
                m.id to res.filter { (c, _) -> (c.factors[LoadCase.WL] ?: 0.0) == 0.0 }.maxOf { (_, r) -> max(0.0, -r.internalForces(m.id, 2).minOf { it.n }) }
            }
            val gravTen = model.members.associate { m ->
                m.id to res.filter { (c, _) -> (c.factors[LoadCase.WL] ?: 0.0) == 0.0 }.maxOf { (_, r) -> max(0.0, r.internalForces(m.id, 2).maxOf { it.n }) }
            }
            val newChosen = HashMap<String, Int>()
            groups = groupsMap.map { (g, ids) ->
                val t = ids.maxOf { forces.getValue(it).first }; val c = ids.maxOf { forces.getValue(it).second }
                val len = ids.maxOf { id -> val m = model.members[id - 1]; (model.node(m.j).pos - model.node(m.i).pos).length } * 1000
                // IS 800 Table 3: 180 for compression under gravity; 350 for a tie reversed only by wind; 250 for wind-only struts
                val lim = when {
                    ids.any { gravComp.getValue(it) > 0.5 } -> 180.0
                    ids.all { gravTen.getValue(it) > 0.5 || forces.getValue(it).second < 0.5 } -> 350.0
                    else -> 250.0
                }
                fun chk(a: Triple<String, Double, Double>) = if (inp.doubleAngles)
                    AngleDesign.checkDouble(a.second, a.second, a.third, inp.gusset, inp.grade, t, c, len, inp.bolts, inp.holeDia, "2-" + a.first, slenderLimit = lim)
                else AngleDesign.check(a.second, a.second, a.third, inp.grade, t, c, len, inp.bolts, inp.holeDia, true, a.first, slenderLimit = lim)
                var k = 0; var r = chk(angles[0])
                while (k < angles.size - 1 && (r.utilisation > 1.0 || r.checks.any { it.status == Status.FAIL })) {
                    k++
                    val a = angles[k]
                    r = chk(a)
                }
                newChosen[g] = k
                Group(g, ids, len, t, c, (if (inp.doubleAngles) "2-" else "") + angles[k].first, r)
            }
            model = model.copy(members = model.members.map { m ->
                val g = groupsMap.entries.first { m.id in it.value }.key
                val a = angles[newChosen.getValue(g)]
                val one = Section.angle(a.second / 1000, a.second / 1000, a.third / 1000, a.first)
                m.copy(section = if (!inp.doubleAngles) one else one.copy(name = "2-" + a.first, a = 2 * one.a, iy = 2 * one.iy, iz = 2 * one.iz, j = 2 * one.j,
                    asy = 2 * one.asy, asz = 2 * one.asz, shape = "DOUBLE_ANGLE"))
            })
            if (newChosen == chosen) return@repeat
            chosen = newChosen
        }

        // purlins: simply supported over the truss spacing, ISMC, no sag rods (LTB over full span)
        val g = inp.grade
        val wDead = inp.sheeting * inp.purlinSpacing + inp.services * inp.purlinSpacing * cos(theta)
        val wLive = ll * inp.purlinSpacing * cos(theta)
        val wWind = -(minOf(inp.cpeWindward, inp.cpeLeeward) - inp.cpi) * pd * inp.purlinSpacing   // outward (uplift) kN/m
        var purlin = "—"; var pChecks = emptyList<DesignCheck>()
        for (s in SteelSection.library.filter { it.family == SteelSection.Family.CHANNEL }.sortedBy { it.area }) {
            val sw = s.massPerM * 9.81 / 1000
            val gN = (wDead + sw) * cos(theta) + wLive * cos(theta)       // normal to slope (gravity)
            val gT = (wDead + sw + wLive) * sin(theta)                    // along slope
            val l = inp.spacing
            val mz1 = 1.5 * gN * l * l / 8; val my1 = 1.5 * gT * l * l / 8
            val upN = 1.5 * wWind - 0.9 * (wDead + sw) * cos(theta)         // net uplift (bottom flange in compression)
            val mz2 = max(0.0, upN) * l * l / 8; val my2 = 0.9 * (wDead + sw) * sin(theta) * l * l / 8
            val bc = IS800.bendingCapacity(s, g, l * 1000)
            val mdy = s.zey * g.fy / IS800.GM0 / 1e6
            val u1 = mz1 / bc.mdSupported + my1 / mdy                          // gravity: top flange restrained by sheeting
            val u2 = mz2 / bc.md + my2 / mdy                                   // uplift: free bottom flange, LTB over span
            val v = 1.5 * gN * l / 2
            val delta = 5 * (gN) * Math.pow(l * 1000, 4.0) / (384 * 2e5 * s.izz)   // service, mm (kN/m = N/mm)
            val checks = listOf(
                DesignCheck("Purlin bending, gravity", "${f2(u1)}", "Mz/Mdz + My/Mdy ≤ 1 (sheeting restrains top flange)", if (u1 <= 1) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 8.2.1, 9.3.1.1"),
                DesignCheck("Purlin bending, wind uplift", "${f2(u2)}", "LTB over ${f1(l)} m, Md = ${f1(bc.md)} kN·m", if (u2 <= 1) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 8.2.2"),
                DesignCheck("Purlin shear", "${f1(v)} kN", "≤ Vd ${f1(bc.vd)} kN", if (v <= bc.vd) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 8.4"),
                DesignCheck("Purlin deflection", "${f1(delta)} mm", "≤ span/180 = ${f1(l * 1000 / 180)} mm", if (delta <= l * 1000 / 180) Status.PASS else Status.FAIL, "IS 800:2007 Table 6"),
            )
            purlin = s.name; pChecks = checks
            if (checks.all { it.status != Status.FAIL }) break
        }

        // connections
        val specs = model.members.associate { m ->
            val gName = groupsMap.entries.first { m.id in it.value }.key
            val a = angles[chosen[gName] ?: 0]
            m.id to TrussDetail.MemberSpec(a.first, a.second, a.third)
        }
        val detail = TrussDetail.detail(model, specs, forces, inp.doubleAngles, inp.gusset, inp.boltDia, inp.grade)

        // report
        val ref = FrameSolver.analyseAll(model, combos)
        val eq = ref.maxOf { (_, r) -> r.equilibriumError.maxOf { abs(it) } }
        val rb = ReportDoc.Builder("${inp.type.label} roof truss — analysis and design", "IS 800:2007 · IS 875 Parts 1–3 · span ${f1(inp.span)} m, rise ${f2(inp.rise)} m")
        rb.h("1. Data").kv(
            "Truss type" to inp.type.label, "Span × rise" to "${f2(inp.span)} m × ${f2(inp.rise)} m (θ = ${f1(thDeg)}°)",
            "Truss spacing" to "${f2(inp.spacing)} m", "Purlin spacing" to "${f2(inp.purlinSpacing)} m on slope",
            "Sheeting / services" to "${f2(inp.sheeting)} kN/m² on slope / ${f2(inp.services)} kN/m² on plan",
            "Steel" to "${inp.grade.name}, fy ${f0(inp.grade.fy)} MPa, fu ${f0(inp.grade.fu)} MPa; ${if (inp.doubleAngles) "double angles back to back" else "single angles"} on ${f0(inp.gusset)} mm gussets, M${f0(inp.boltDia)} bolts",
            "Wind" to "Vb ${f1(inp.vb)} m/s, terrain ${inp.terrain}, eaves ${f1(inp.eavesHeight)} m",
        )
        rb.fig(figure(model, groups))
        rb.h("2. Loads").steps(steps)
        rb.table(listOf("Node", "DL (kN)", "LL (kN)", "WL Fx (kN)", "WL Fz (kN)"), topIds.sorted().map { n ->
            val d = loads.first { it.node == n && it.case == LoadCase.DL }.values[2]; val l = loads.first { it.node == n && it.case == LoadCase.LL }.values[2]
            val w = loads.first { it.node == n && it.case == LoadCase.WL }.values
            listOf("N$n", f2(d), f2(l), f2(w[0]), f2(w[2]))
        })
        rb.h("3. Analysis (pin-jointed, all combinations)")
        rb.p("Equilibrium residual ≤ ${String.format(java.util.Locale.US, "%.1e", eq)} kN. Member forces: tension +, compression −; envelope of the four combinations.")
        rb.table(listOf("Member", "Group", "Length (m)", "Max T (kN)", "Max C (kN)"), model.members.map { m ->
            val gName = groupsMap.entries.first { m.id in it.value }.key
            val (t, c) = forces.getValue(m.id)
            listOf("M${m.id}", gName, f2((model.node(m.j).pos - model.node(m.i).pos).length), f2(t), f2(c))
        }, listOf(1f, 1.6f, 1f, 1f, 1f))
        rb.h("4. Member design — single angles, IS 800")
        rb.table(listOf("Group", "Section", "L (mm)", "T (kN)", "C (kN)", "η"), groups.map { listOf(it.name, it.section, f0(it.length), f1(it.tension), f1(it.compression), f3(it.result.utilisation)) },
            listOf(1.4f, 1.4f, 0.8f, 0.8f, 0.8f, 0.7f))
        for (gp in groups) { rb.p("${gp.name}: ${gp.section}"); rb.steps(gp.result.steps); rb.checks(gp.result.checks) }
        rb.h("5. Purlins — $purlin, simply supported over ${f2(inp.spacing)} m")
        rb.p("Gravity: 1.5(DL + LL) resolved normal / parallel to the slope; uplift: 1.5WL − 0.9DL with the bottom flange unrestrained. No sag rods assumed.")
        rb.checks(pChecks)
        rb.h("6. Connections — gussets and bolts").steps(detail.steps).checks(detail.checks)
        rb.table(listOf("Member", "Section", "Design force (kN)", "Bolts per end", "Bolt value (kN)"), model.members.map { m ->
            val e = detail.ends.first { it.member == m.id }
            listOf("M${m.id}", m.section.name, f1(e.force), "${e.bolts} M${f0(inp.boltDia)}", f2(e.boltValue))
        }, listOf(0.7f, 1.4f, 1f, 1f, 1f))
        detail.joints.forEach { rb.fig(it) }
        rb.h("7. Notes")
        rb.p("Connections: gusset plates and bolt groups to be detailed for the member forces above (IS 800 Cl. 10). Cpe values to be confirmed from IS 875-3 Table 6 for the actual roof angle and building h/w; truss to be braced in the plane of the roof and the bottom chord.")
        return Result(model, thDeg, ll, pd, groups, purlin, pChecks, rb.build(), forces, detail)
    }

    private fun figure(m: FrameModel, groups: List<Group>): ReportDoc.Block.Figure {
        val lines = ArrayList<ReportDoc.FLine>(); val txt = ArrayList<ReportDoc.FText>()
        for (mem in m.members) {
            val a = m.node(mem.i); val b = m.node(mem.j)
            val g = groups.firstOrNull { mem.id in it.members }?.name
            lines += ReportDoc.FLine(a.x, a.z, b.x, b.z, if (g?.startsWith("Web") == true) 1 else 2)
            txt += ReportDoc.FText((a.x + b.x) / 2, (a.z + b.z) / 2 + 0.12, "M${mem.id}", 5.5f)
        }
        for (n in m.nodes) txt += ReportDoc.FText(n.x, n.z - 0.35, "N${n.id}", 5.5f)
        for (s in m.supports) { val p = m.node(s.node); lines += ReportDoc.FLine(p.x - 0.25, p.z - 0.3, p.x + 0.25, p.z - 0.3, 2); lines += ReportDoc.FLine(p.x - 0.25, p.z - 0.3, p.x, p.z); lines += ReportDoc.FLine(p.x, p.z, p.x + 0.25, p.z - 0.3) }
        return ReportDoc.Block.Figure("Truss geometry — chords heavy, webs light; member and node numbers", lines, txt)
    }

    internal fun sq(v: Double) = sqrt(v)
}
