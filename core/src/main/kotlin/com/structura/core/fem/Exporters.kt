package com.structura.core.fem

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/** Minimal JSON writer (no dependencies) — values: null, Boolean, Number, String, List, Map. */
object Json {
    fun write(v: Any?, indent: String = "", step: String = "  "): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Double -> if (v.isNaN() || v.isInfinite()) "null" else {
            val s = String.format(Locale.US, "%.6g", v)
            if (s.contains('e') || s.contains('E')) s.lowercase().replace("e+", "e")
            else if (s.contains('.')) s.trimEnd('0').let { if (it.endsWith('.')) it + "0" else it } else s
        }
        is Float -> write(v.toDouble(), indent, step)
        is String -> "\"" + v.flatMap { c ->
            when (c) {
                '"' -> "\\\"".toList(); '\\' -> "\\\\".toList(); '\n' -> "\\n".toList(); '\r' -> "\\r".toList(); '\t' -> "\\t".toList()
                else -> if (c.code < 0x20) String.format("\\u%04x", c.code).toList() else listOf(c)
            }
        }.joinToString("") + "\""
        is DoubleArray -> write(v.toList(), indent, step)
        is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, x) ->
            "$indent$step${write(k.toString())}: ${write(x, indent + step, step)}"
        }
        is Iterable<*> -> {
            val list = v.toList()
            if (list.isEmpty()) "[]"
            else if (list.all { it == null || it is Number || it is Boolean }) list.joinToString(", ", "[", "]") { write(it) }
            else list.joinToString(",\n", "[\n", "\n$indent]") { "$indent$step${write(it, indent + step, step)}" }
        }
        else -> write(v.toString())
    }
}

/**
 * "Engineering JSON": the complete model plus analysis results in one machine-readable file, for
 * archiving, spreadsheets, scripts or AI review. Units: kN, m, kN·m, rad, MPa where stated.
 */
object EngineeringJson {
    fun build(
        model: FrameModel,
        results: List<Pair<LoadCombination, FrameResults>> = emptyList(),
        envelope: List<MemberEnvelope> = emptyList(),
        buckling: BucklingResult? = null,
        modal: ModalResult? = null,
        stations: Int = 10,
        seismic: Seismic.Result? = null,
        steel: List<com.structura.core.steel.SteelModelCheck.MemberCheck> = emptyList(),
    ): String {
        val root = linkedMapOf<String, Any?>(
            "format" to "structura-ai-engineering",
            "version" to 1,
            "generated" to LocalDateTime.now().toString(),
            "units" to mapOf("force" to "kN", "length" to "m", "moment" to "kN·m", "stress" to "MPa", "axes" to "global Z up; member local x i→j, beams local z down (RFEM)"),
            "title" to model.title,
            "analysis" to mapOf("beamTheory" to model.beamTheory.name, "selfWeight" to model.selfWeight),
            "nodes" to model.nodes.map { mapOf("id" to it.id, "x" to it.x, "y" to it.y, "z" to it.z) },
            "supports" to model.supports.map { mapOf("node" to it.node, "restraints" to it.restraints, "type" to ModelEditor.supportType(model, it.node).name) },
            "members" to model.members.map { m ->
                mapOf(
                    "id" to m.id, "i" to m.i, "j" to m.j,
                    "length" to (model.node(m.j).pos - model.node(m.i).pos).length,
                    "section" to mapOf("name" to m.section.name, "shape" to m.section.shape, "A" to m.section.a, "Iy" to m.section.iy,
                        "Iz" to m.section.iz, "J" to m.section.j, "Asy" to m.section.asy, "Asz" to m.section.asz, "b" to m.section.b, "h" to m.section.h, "t" to m.section.t),
                    "material" to mapOf("name" to m.material.name, "E_kNm2" to m.material.e, "G_kNm2" to m.material.g, "gamma_kNm3" to m.material.gamma),
                    "beta_deg" to m.betaDeg, "hingeI" to m.hingeI, "hingeJ" to m.hingeJ, "springI" to m.springI, "springJ" to m.springJ,
                    "offsetI" to m.offsetI, "offsetJ" to m.offsetJ,
                )
            },
            "panels" to model.panels.map { p ->
                mapOf("id" to p.id, "nodes" to p.nodes, "t" to p.t, "E_kNm2" to p.e, "G_kNm2" to p.g, "nu" to p.nu, "gamma_kNm3" to p.gamma,
                    "pressure_kNm2" to p.pressure, "case" to p.case, "name" to p.name)
            },
            "loads" to mapOf(
                "nodal" to model.nodalLoads.map { mapOf("case" to it.case, "node" to it.node, "values" to it.values) },
                "distributed" to model.memberLoads.map {
                    mapOf("case" to it.case, "member" to it.member, "dir" to it.dir.name, "w" to it.w, "w2" to (it.w2 ?: it.w), "a" to it.a, "b" to it.b)
                },
                "concentrated" to model.pointLoads.map { mapOf("case" to it.case, "member" to it.member, "dir" to it.dir.name, "P" to it.p, "a" to it.a) },
            ),
            "results" to results.map { (c, r) ->
                linkedMapOf(
                    "combination" to c.name,
                    "factors" to c.factors,
                    "totalLoad" to r.totalLoad,
                    "equilibriumError" to r.equilibriumError,
                    "maxDisplacement" to r.maxDisplacement().let { (n, d) -> mapOf("node" to n, "value_m" to d) },
                    "nodeDisplacements" to r.nodeDisplacements.map { (n, d) -> mapOf("node" to n, "u" to d) },
                    "reactions" to r.reactions.map { (n, v) -> mapOf("node" to n, "R" to v) },
                    "members" to model.members.map { m ->
                        mapOf("id" to m.id, "endForcesLocal" to r.memberEndForces[m.id],
                            "stations" to r.internalForces(m.id, stations).map { s -> listOf(s.s, s.n, s.vy, s.vz, s.mt, s.my, s.mz) })
                    },
                    "stationColumns" to listOf("x", "N", "Vy", "Vz", "Mt", "My", "Mz"),
                    "panels" to r.panelResults.values.map {
                        mapOf("id" to it.id, "area" to it.area, "meanNormalDeflection_m" to it.meanNormalDeflection,
                            "N" to listOf(it.nx, it.ny, it.nxy), "M" to listOf(it.mx, it.my, it.mxy), "maxFaceStress_MPa" to it.maxFaceStress)
                    },
                )
            },
            "ulsEnvelope" to envelope.map {
                mapOf("member" to it.member, "length" to it.length, "N_at_absmax" to it.nAbsMax, "Vz_absmax" to it.vzAbsMax, "My_max" to it.myMax, "My_min" to it.myMin)
            },
            "buckling" to buckling?.let { mapOf("lambdaCr" to it.lambda, "combination" to it.combination, "elementsPerMember" to it.segmentsPerMember) },
            "seismicIS1893" to seismic?.let { s ->
                mapOf("Z" to s.params.z, "I" to s.params.importance, "R" to s.params.r, "soil" to s.params.soil.name, "height" to s.height,
                    "directions" to listOf(s.x, s.y).map { d ->
                        mapOf("name" to d.name, "W_kN" to d.weight, "VB_cqc_kN" to d.baseShearRs, "Ta_s" to d.ta, "VB_static_kN" to d.baseShearStatic,
                            "scale" to d.scale, "VB_design_kN" to d.baseShear,
                            "modes" to d.rows.map { mapOf("mode" to it.mode, "T_s" to it.period, "SaG" to it.saG, "Ah" to it.ah, "massRatio" to it.massRatio, "VB_kN" to it.baseShear) },
                            "drifts" to d.drifts.map { mapOf("z" to it.z, "h" to it.height, "drift" to it.drift, "ok" to it.ok) })
                    })
            },
            "steelChecksIS800" to steel.map {
                mapOf("member" to it.member, "section" to it.section, "grade" to it.grade, "governingCombination" to it.combination,
                    "P_kN_compressionPositive" to it.p, "Mmajor_kNm" to it.mMajor, "Mminor_kNm" to it.mMinor, "V_kN" to it.v,
                    "utilisation" to it.utilisation, "ok" to it.ok,
                    "checks" to it.result.checks.map { c -> mapOf("item" to c.item, "value" to c.value, "requirement" to c.requirement, "status" to c.status.name, "clause" to c.clause) })
            },
            "modal" to modal?.let { md ->
                mapOf("massSource" to md.massSource, "totalMass_t" to md.totalMass, "cumulativeParticipation" to md.cumulative,
                    "modes" to md.modes.map { mapOf("mode" to it.number, "f_Hz" to it.frequency, "T_s" to it.period, "participation" to it.participation) })
            },
        )
        return Json.write(root)
    }
}

/**
 * STAAD.Pro input file (.std) writer — geometry, properties, supports, releases, offsets, load cases and
 * combinations, ready to open in STAAD.Pro (SET Z UP keeps the Structura axes). Plates are written as
 * ELEMENT INCIDENCES SHELL with thickness and pressure. Check the imported model before running.
 */
object StaadExport {
    fun write(model: FrameModel): String = buildString {
        fun f(v: Double) = String.format(Locale.US, "%.6g", v).let { s ->
            if (s.contains('e')) s.uppercase() else if (s.contains('.')) s.trimEnd('0').trimEnd('.').ifEmpty { "0" } else s
        }
        fun line(s: String) { append(s).append("\r\n") }
        line(if (model.planeXZ) "STAAD PLANE" else "STAAD SPACE")
        line("* Generated by Structura AI — ${model.title}")
        line("START JOB INFORMATION")
        line("ENGINEER DATE ${java.time.LocalDate.now()}")
        line("END JOB INFORMATION")
        line("INPUT WIDTH 79")
        line("SET Z UP")
        line("UNIT METER KN")
        line("JOINT COORDINATES")
        model.nodes.forEach { line("${it.id} ${f(it.x)} ${f(it.y)} ${f(it.z)};") }
        if (model.members.isNotEmpty()) {
            line("MEMBER INCIDENCES")
            model.members.forEach { line("${it.id} ${it.i} ${it.j};") }
        }
        // STAAD element numbers must not clash with member numbers
        val elemBase = (model.members.maxOfOrNull { it.id } ?: 0)
        if (model.panels.isNotEmpty()) {
            line("ELEMENT INCIDENCES SHELL")
            model.panels.forEach { p -> line("${elemBase + p.id} ${p.nodes.joinToString(" ")};") }
        }

        // materials
        val mats = model.members.map { it.material }.distinct()
        val panelMats = model.panels.map { Triple(it.e, it.nu, it.gamma) }.distinct()
        line("DEFINE MATERIAL START")
        mats.forEachIndexed { k, m ->
            val nu = (m.e / (2 * m.g) - 1).coerceIn(0.0, 0.49)
            line("ISOTROPIC MAT${k + 1}")
            line("E ${f(m.e)}"); line("POISSON ${f(nu)}"); line("DENSITY ${f(m.gamma)}"); line("G ${f(m.g)}")
        }
        panelMats.forEachIndexed { k, (e, nu, g) ->
            line("ISOTROPIC PLATEMAT${k + 1}")
            line("E ${f(e)}"); line("POISSON ${f(nu)}"); line("DENSITY ${f(g)}")
        }
        line("END DEFINE MATERIAL")

        // member properties
        if (model.members.isNotEmpty()) {
            line("MEMBER PROPERTY")
            for (m in model.members) {
                val s = m.section
                val prop = when (s.shape) {
                    "RECT" -> "PRIS YD ${f(s.h)} ZD ${f(s.b)}"
                    "CHS" -> "PIPE OD ${f(s.h)} ID ${f(s.h - 2 * s.t)}"
                    "SHS", "RHS" -> "TUBE DT ${f(s.h)} WT ${f(s.b)} TH ${f(s.t)}"
                    else -> "PRIS AX ${f(s.a)} IX ${f(s.j)} IY ${f(s.iz)} IZ ${f(s.iy)}"
                }
                line("${m.id} $prop")
            }
        }
        if (model.panels.isNotEmpty()) {
            line("ELEMENT PROPERTY")
            model.panels.forEach { line("${elemBase + it.id} THICKNESS ${f(it.t)}") }
        }
        line("CONSTANTS")
        mats.forEachIndexed { k, m ->
            val ids = model.members.filter { it.material == m }.joinToString(" ") { it.id.toString() }
            line("MATERIAL MAT${k + 1} MEMB $ids")
        }
        panelMats.forEachIndexed { k, pm ->
            val ids = model.panels.filter { Triple(it.e, it.nu, it.gamma) == pm }.joinToString(" ") { (elemBase + it.id).toString() }
            line("MATERIAL PLATEMAT${k + 1} MEMB $ids")
        }
        model.members.filter { it.betaDeg != 0.0 }.forEach { line("BETA ${f(it.betaDeg)} MEMB ${it.id}") }

        // offsets (global vectors along the member axis)
        val off = model.members.filter { it.offsetI > 0 || it.offsetJ > 0 }
        if (off.isNotEmpty()) {
            line("MEMBER OFFSET")
            for (m in off) {
                val u = (model.node(m.j).pos - model.node(m.i).pos).unit()
                if (m.offsetI > 0) { val v = u * m.offsetI; line("${m.id} START ${f(v.x)} ${f(v.y)} ${f(v.z)}") }
                if (m.offsetJ > 0) { val v = u * (-m.offsetJ); line("${m.id} END ${f(v.x)} ${f(v.y)} ${f(v.z)}") }
            }
        }

        // supports
        if (model.supports.isNotEmpty()) {
            line("SUPPORTS")
            for (s in model.supports) {
                val r = s.restraints
                when {
                    r.all { it } -> line("${s.node} FIXED")
                    r[0] && r[1] && r[2] && !r[4] && !r[5] -> line("${s.node} PINNED")
                    else -> {
                        val names = listOf("FX", "FY", "FZ", "MX", "MY", "MZ")
                        val released = names.filterIndexed { k, _ -> !r[k] }
                        line("${s.node} FIXED BUT ${released.joinToString(" ")}")
                    }
                }
            }
        }

        // releases and springs
        val rel = model.members.filter { it.endI != null || it.endJ != null }
        if (rel.isNotEmpty()) {
            line("MEMBER RELEASE")
            for (m in rel) {
                fun end(tag: String, k: Double?) {
                    if (k == null) return
                    if (k == 0.0) line("${m.id} $tag MY MZ") else line("${m.id} $tag KMY ${f(k)} KMZ ${f(k)}")
                }
                end("START", m.endI); end("END", m.endJ)
            }
        }

        model.members.filter { it.truss }.takeIf { it.isNotEmpty() }?.let { t -> line("MEMBER TRUSS"); line(t.joinToString(" ") { it.id.toString() }) }
        model.members.filter { it.tensionOnly }.takeIf { it.isNotEmpty() }?.let { t -> line("MEMBER TENSION"); line(t.joinToString(" ") { it.id.toString() }) }

        // load cases
        val cases = (listOf(LoadCase.DL) + model.loadCases.filter { it != LoadCase.DL && it != LoadCase.TAP }).distinct()
        val caseNo = cases.withIndex().associate { (k, c) -> c to k + 1 }
        val dirName = mapOf(LoadDir.GLOBAL_X to "GX", LoadDir.GLOBAL_Y to "GY", LoadDir.GLOBAL_Z to "GZ", LoadDir.LOCAL_X to "X", LoadDir.LOCAL_Y to "Y", LoadDir.LOCAL_Z to "Z")
        for (c in cases) {
            line("LOAD ${caseNo[c]} LOADTYPE ${if (c == LoadCase.DL) "DEAD" else if (c == LoadCase.LL) "LIVE" else "WIND"} TITLE $c")
            if (c == LoadCase.DL && model.selfWeight) line("SELFWEIGHT Z -1")
            val nl = model.nodalLoads.filter { it.case == c }
            if (nl.isNotEmpty()) {
                line("JOINT LOAD")
                nl.forEach { l ->
                    val parts = listOf("FX", "FY", "FZ", "MX", "MY", "MZ").zip(l.values).filter { abs(it.second) > 0 }
                    if (parts.isNotEmpty()) line("${l.node} " + parts.joinToString(" ") { "${it.first} ${f(it.second)}" })
                }
            }
            val ml = model.memberLoads.filter { it.case == c }
            val pl = model.pointLoads.filter { it.case == c }
            if (ml.isNotEmpty() || pl.isNotEmpty()) {
                line("MEMBER LOAD")
                ml.forEach { l ->
                    val d = dirName[l.dir]
                    if (l.isUniformFull) line("${l.member} UNI $d ${f(l.w)}")
                    else if (l.w2 == null || l.w2 == l.w) line("${l.member} UNI $d ${f(l.w)} ${f(l.a ?: 0.0)} ${f(l.b ?: memberLength(model, l.member))}")
                    else line("${l.member} TRAP $d ${f(l.w)} ${f(l.w2)} ${f(l.a ?: 0.0)} ${f(l.b ?: memberLength(model, l.member))}")
                }
                pl.forEach { l -> line("${l.member} CON ${dirName[l.dir]} ${f(l.p)} ${f(l.a)}") }
            }
            val pp = model.panels.filter { it.case == c && it.pressure != 0.0 }
            if (pp.isNotEmpty()) {
                line("ELEMENT LOAD")
                pp.forEach { line("${elemBase + it.id} PR ${f(it.pressure)}") }
            }
            if (c == LoadCase.DL && model.selfWeight && model.panels.isNotEmpty()) {
                // plate self-weight is covered by SELFWEIGHT (density) in STAAD
            }
        }
        val combos = LoadCombination.isLimitState(LoadCase.WL in cases)
        combos.forEachIndexed { k, cb ->
            val parts = cb.factors.entries.filter { caseNo.containsKey(it.key) && it.value != 0.0 }
            if (parts.isEmpty()) return@forEachIndexed
            line("LOAD COMB ${101 + k} ${cb.name.replace('−', '-')}")
            line(parts.joinToString(" ") { "${caseNo[it.key]} ${f(it.value)}" })
        }
        line("PERFORM ANALYSIS")
        line("FINISH")
    }

    private fun memberLength(model: FrameModel, id: Int): Double {
        val m = model.members.first { it.id == id }
        return (model.node(m.j).pos - model.node(m.i).pos).length
    }
}
