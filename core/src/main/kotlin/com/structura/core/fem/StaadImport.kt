package com.structura.core.fem

/**
 * Reads a STAAD.Pro input file (.std) into a [FrameModel]: JOINT COORDINATES, MEMBER INCIDENCES, MEMBER PROPERTY
 * (PRIS YD ZD = rectangle, PRIS YD only = circle, TABLE ST name from the catalogue), SUPPORTS (FIXED, PINNED,
 * FIXED BUT …), MEMBER RELEASE (START / END MY MZ → hinges), MEMBER TRUSS, CONSTANTS (concrete / steel) and the
 * primary LOAD cases (SELFWEIGHT, JOINT LOAD, MEMBER LOAD UNI / CON / TRAP). Y-up files are turned Z-up as
 * (x, −z, y). UNIT METER / MMS / CM / FEET / INCH with KN / KIP / NEWTON / MTON / KG. Combinations are regenerated
 * by Structura (IS 456 Table 18 / IS 875-5), so LOAD COMB blocks are skipped.
 */
object StaadImport {
    class Result(val model: FrameModel, val notes: List<String>)

    fun read(text: String, title: String = "Imported STAAD model"): Result = Parser(text, title).run()

    private class Parser(val text: String, val title: String) {
        val notes = ArrayList<String>()
        var zUp = false; var lenF = 1.0; var forF = 1.0
        val nodes = LinkedHashMap<Int, Node>()
        val members = LinkedHashMap<Int, Pair<Int, Int>>()
        val props = HashMap<Int, Section>()
        val steelIds = HashSet<Int>(); var allSteel = false; var allConcrete = false
        val supports = LinkedHashMap<Int, Support>()
        val hingeI = HashSet<Int>(); val hingeJ = HashSet<Int>(); val truss = HashSet<Int>()
        val nodal = ArrayList<NodalLoad>(); val mloads = ArrayList<MemberLoad>(); val ploads = ArrayList<PointLoad>()
        var selfW = false
        var section = ""
        var case = LoadCase.DL; var caseCount = 0

        fun ids(t: List<String>): List<Int> {
            val out = ArrayList<Int>(); var k = 0
            while (k < t.size) {
                val a = t[k].toIntOrNull() ?: break
                if (k + 2 < t.size && t[k + 1].equals("TO", true)) {
                    val b = t[k + 2].toIntOrNull() ?: break
                    var by = 1; var adv = 3
                    if (k + 4 < t.size && t[k + 3].equals("BY", true)) { by = t[k + 4].toIntOrNull() ?: 1; adv = 5 }
                    var i = a; while (i <= b) { out += i; i += by.coerceAtLeast(1) }
                    k += adv
                } else { out += a; k++ }
            }
            return out
        }
        fun idLen(t: List<String>): Int {
            var k = 0
            while (k < t.size && (t[k].toIntOrNull() != null || t[k].equals("TO", true) || t[k].equals("BY", true))) k++
            return k
        }
        fun num(t: List<String>, key: String): Double? { val i = t.indexOfFirst { it.equals(key, true) }; return if (i >= 0 && i + 1 < t.size) t[i + 1].toDoubleOrNull() else null }
        fun vec(x: Double, y: Double, z: Double) = if (zUp) Vec3(x, y, z) else Vec3(x, -z, y)
        fun dir(t: String): Pair<LoadDir, Double>? = when (t.uppercase()) {
            "GX" -> LoadDir.GLOBAL_X to 1.0
            "GY" -> if (zUp) LoadDir.GLOBAL_Y to 1.0 else LoadDir.GLOBAL_Z to 1.0
            "GZ" -> if (zUp) LoadDir.GLOBAL_Z to 1.0 else LoadDir.GLOBAL_Y to -1.0
            "PY" -> LoadDir.GLOBAL_Z to 1.0
            "Y" -> { if (notes.none { it.startsWith("Local Y") }) notes += "Local Y member loads read as global vertical (horizontal beams)"; LoadDir.GLOBAL_Z to 1.0 }
            "X" -> LoadDir.LOCAL_X to 1.0
            else -> null
        }

        fun handle(t: List<String>) {
            if (t.isEmpty()) return
            when (section) {
                "J" -> if (t.size >= 4) { val id = t[0].toIntOrNull() ?: return
                    val p = vec(t[1].toDouble() * lenF, t[2].toDouble() * lenF, t[3].toDouble() * lenF); nodes[id] = Node(id, p.x, p.y, p.z) }
                "M" -> if (t.size >= 3) { val id = t[0].toIntOrNull() ?: return; members[id] = t[1].toInt() to t[2].toInt() }
                "P" -> {
                    val n = idLen(t); val list = ids(t.take(n)); val rest = t.drop(n)
                    val kind = rest.firstOrNull()?.uppercase() ?: return
                    val sec: Section? = when (kind) {
                        "PRIS", "PRISMATIC" -> {
                            val yd = num(rest, "YD"); val zd = num(rest, "ZD")
                            when {
                                yd != null && zd != null -> Section.rect(zd * lenF, yd * lenF)
                                yd != null -> Section.circle(yd * lenF)
                                else -> null
                            }
                        }
                        "TABLE" -> {
                            val name = rest.drop(2).joinToString(" ").replace(" ", "").uppercase()
                            list.forEach { steelIds += it }
                            SectionCatalogue.entries.firstOrNull { it.section.name.replace(" ", "").replace("×", "X").uppercase() == name }?.section
                                ?: SectionCatalogue.entries.firstOrNull { it.section.name.replace(" ", "").uppercase().startsWith(name.take(6)) }?.section
                                ?: run { notes += "Section $name not in the catalogue — ISMB 300 used"; Section.iBeam(0.3, 0.14, 0.0124, 0.0075, "ISMB 300") }
                        }
                        else -> null
                    }
                    if (sec != null) list.forEach { props[it] = sec } else notes += "Property \"${rest.joinToString(" ")}\" not read"
                }
                "S" -> {
                    val n = idLen(t); val list = ids(t.take(n)); val rest = t.drop(n).map { it.uppercase() }
                    val r = when {
                        rest.isEmpty() -> return
                        rest[0] == "PINNED" -> listOf(true, true, true, true, false, false)
                        rest[0] == "FIXED" && "BUT" in rest -> {
                            val names = listOf("FX", "FY", "FZ", "MX", "MY", "MZ")
                            val rel = rest.drop(rest.indexOf("BUT") + 1)
                            val staad = names.map { it !in rel }
                            if (zUp) staad else listOf(staad[0], staad[2], staad[1], staad[3], staad[5], staad[4])
                        }
                        rest[0] == "FIXED" -> List(6) { true }
                        else -> { notes += "Support \"${rest.joinToString(" ")}\" read as pinned"; listOf(true, true, true, true, false, false) }
                    }
                    list.forEach { supports[it] = Support(it, r) }
                }
                "R" -> {
                    val n = idLen(t); val list = ids(t.take(n)); val rest = t.drop(n).map { it.uppercase() }
                    if (rest.any { it == "MY" || it == "MZ" }) {
                        if ("START" in rest) hingeI += list
                        if ("END" in rest) hingeJ += list
                    }
                }
                "T" -> truss += ids(t)
                "C" -> {
                    val u = t.map { it.uppercase() }
                    if (u.firstOrNull() in setOf("E", "MATERIAL")) {
                        val mat = u.getOrNull(1) ?: return
                        val tail = t.drop(2)
                        val all = tail.any { it.equals("ALL", true) }
                        val list = if (all) emptyList() else ids(tail.dropWhile { it.toIntOrNull() == null && !it.equals("MEMB", true) }.drop(if (tail.any { it.equals("MEMB", true) }) 1 else 0))
                        if (mat.startsWith("STEEL")) { if (all) allSteel = true else steelIds += list }
                        if (mat.startsWith("CONCRETE") && all) allConcrete = true
                    }
                }
                "JL" -> {
                    val n = idLen(t); val list = ids(t.take(n)); val rest = t.drop(n)
                    val f = DoubleArray(6)
                    listOf("FX", "FY", "FZ", "MX", "MY", "MZ").forEachIndexed { k, key -> num(rest, key)?.let { f[k] = it * forF * (if (k >= 3) lenF else 1.0) } }
                    val fv = vec(f[0], f[1], f[2]); val mv = vec(f[3], f[4], f[5])
                    list.forEach { nodal += NodalLoad(it, listOf(fv.x, fv.y, fv.z, mv.x, mv.y, mv.z), case) }
                }
                "ML" -> {
                    val n = idLen(t); val list = ids(t.take(n)); val rest = t.drop(n)
                    val type = rest.getOrNull(0)?.uppercase() ?: return
                    val (d, sg) = dir(rest.getOrNull(1) ?: return) ?: run { notes += "Member load direction ${rest.getOrNull(1)} skipped"; return }
                    val v = rest.drop(2).mapNotNull { it.toDoubleOrNull() }
                    val wf = forF / lenF
                    when (type) {
                        "UNI", "UMOM" -> if (type == "UNI" && v.isNotEmpty()) list.forEach {
                            mloads += if (v.size >= 3) MemberLoad(it, d, sg * v[0] * wf, case, v[1] * lenF, v[2] * lenF) else MemberLoad(it, d, sg * v[0] * wf, case)
                        }
                        "CON" -> if (v.isNotEmpty()) list.forEach { m -> ploads += PointLoad(m, d, sg * v[0] * forF, (v.getOrNull(1) ?: 0.0) * lenF, case) }
                        "TRAP", "LIN" -> if (v.size >= 2) list.forEach {
                            mloads += MemberLoad(it, d, sg * v[0] * wf, case, v.getOrNull(2)?.times(lenF), v.getOrNull(3)?.times(lenF), sg * v[1] * wf)
                        }
                        else -> notes += "Member load type $type skipped"
                    }
                }
            }
        }

        fun run(): Result {
            val raw = text.replace("\r", "").lines().map { l -> l.trim().let { if (it.startsWith("*")) "" else it } }
            val joined = ArrayList<String>(); var acc = ""
            for (l in raw) { if (l.endsWith("-")) acc += l.dropLast(1) + " " else { joined += acc + l; acc = "" } }
            val stmts = joined.flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }
            for (st in stmts) {
                val tk = st.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val u = st.uppercase()
                fun after(words: Int) = handle(tk.drop(words))
                when {
                    u.startsWith("FINISH") -> break
                    u.startsWith("SET Z UP") -> zUp = true
                    u.startsWith("UNIT") -> for (w in tk.drop(1).map { it.uppercase() }) when (w) {
                        "METER", "METERS", "M" -> lenF = 1.0; "MMS", "MM" -> lenF = 0.001; "CM", "CMS" -> lenF = 0.01
                        "FEET", "FT" -> lenF = 0.3048; "INCH", "INCHES", "IN" -> lenF = 0.0254
                        "KN" -> forF = 1.0; "KIP", "KIPS" -> forF = 4.44822; "NEWTON", "N" -> forF = 0.001; "MTON" -> forF = 9.80665; "KG" -> forF = 0.00980665
                    }
                    u.startsWith("JOINT COORD") -> { section = "J"; after(2) }
                    u.startsWith("MEMBER INCI") -> { section = "M"; after(2) }
                    u.startsWith("MEMBER PROP") -> { section = "P"; after(2) }
                    u.startsWith("SUPPORT") -> { section = "S"; after(1) }
                    u.startsWith("MEMBER RELEASE") -> { section = "R"; after(2) }
                    u.startsWith("MEMBER TRUSS") -> { section = "T"; after(2) }
                    u.startsWith("CONSTANT") -> { section = "C"; after(1) }
                    u.startsWith("JOINT LOAD") -> { section = "JL"; after(2) }
                    u.startsWith("MEMBER LOAD") -> { section = "ML"; after(2) }
                    u.startsWith("LOAD COMB") || u.startsWith("PERFORM") || u.startsWith("DEFINE") || u.startsWith("END DEFINE") || u.startsWith("ISOTROPIC") ||
                        u.startsWith("ELEMENT") || u.startsWith("PRINT") || u.startsWith("START") || u.startsWith("END ") || u.startsWith("STAAD") ||
                        u.startsWith("INPUT") || u.startsWith("LOAD LIST") || u.startsWith("SET ") -> section = ""
                    u.startsWith("LOAD ") -> {
                        caseCount++
                        case = when {
                            "DEAD" in u -> LoadCase.DL; "LIVE" in u || "IMPOSED" in u -> LoadCase.LL; "WIND" in u -> LoadCase.WL
                            "SEISMIC" in u || "EARTHQUAKE" in u -> LoadCase.EX
                            caseCount == 1 -> LoadCase.DL; caseCount == 2 -> LoadCase.LL; else -> LoadCase.WL
                        }
                        section = "L"
                    }
                    u.startsWith("SELFWEIGHT") -> selfW = true
                    section == "C" && (u.startsWith("E ") || u.startsWith("MATERIAL") || u.startsWith("DENSITY") || u.startsWith("POISSON") || u.startsWith("BETA")) -> handle(tk)
                    else -> handle(tk)
                }
            }
            require(nodes.isNotEmpty() && members.isNotEmpty()) { "No JOINT COORDINATES / MEMBER INCIDENCES found" }
            val concrete = Material.concrete(25.0); val steel = Material.steel(250.0)
            val mems = members.entries.mapNotNull { (id, ij) ->
                if (ij.first !in nodes || ij.second !in nodes) { notes += "M$id skipped (missing node)"; return@mapNotNull null }
                val sec = props[id] ?: Section.rect(0.3, 0.45).also { if (notes.none { it.startsWith("Members without") }) notes += "Members without a property got R 300×450" }
                val isSteel = id in steelIds || (allSteel && !allConcrete && sec.shape != "RECT" && sec.shape != "CIRCLE")
                Member(id, ij.first, ij.second, if (isSteel) steel else concrete, sec, hingeI = id in hingeI, hingeJ = id in hingeJ, truss = id in truss)
            }
            val model = FrameModel(nodes.values.toList(), mems, supports.values.filter { it.node in nodes }.toList(), nodal, mloads, selfW || mloads.isEmpty() && nodal.isEmpty(),
                title, ploads)
            notes += "Read ${nodes.size} joints, ${mems.size} members, ${supports.size} supports, ${nodal.size + mloads.size + ploads.size} loads (${if (zUp) "Z" else "Y"} up)"
            return Result(model, notes)
        }
    }
}
