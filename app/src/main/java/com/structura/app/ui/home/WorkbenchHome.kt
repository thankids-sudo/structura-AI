package com.structura.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.structura.app.DesignSession
import com.structura.app.Routes
import com.structura.app.Units
import com.structura.app.shareTextFile
import com.structura.app.ui.fem.Camera
import com.structura.app.ui.fem.FrameView3D
import com.structura.app.ui.fem.ResultView
import com.structura.core.fem.Benchmarks
import com.structura.core.fem.FemReport
import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameResults
import com.structura.core.fem.FrameSolver
import com.structura.core.fem.HtmlReport
import com.structura.core.fem.LoadCombination
import com.structura.core.fem.ResultCheck
import com.structura.core.steel.SteelModelCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// Workbench palette (logo colours)
private val Ground = Color(0xFF0E1726)
private val Panel = Color(0xFF16233A)
private val Bar = Color(0xFF121F33)
private val Line = Color(0xFF24364F)
private val Ink = Color(0xFFE6EEF8)
private val Muted = Color(0xFF93A4BD)
private val Cyan = Color(0xFF3DD6F5)
private val Orange = Color(0xFFF5A524)
private val Green = Color(0xFF4ADE80)
private val Dark = Color(0xFF0B1320)

private enum class Phase(val no: String, val label: String, val title: String, val codes: String) {
    RCC("01", "RCC", "RCC design", "IS 456 · SP 16 · IS 2502"),
    STEEL("02", "Steel", "Steel design", "IS 800:2007 · IS 808"),
    FEM("03", "FEM", "FEM + AI", "3D frames · shells · modal · IS 1893"),
    PLAN("04", "Plan", "Drawing → Structure", "DXF · PDF · photo → model · BOQ"),
    STAAD("05", "STAAD", "STAAD tools", "CEE 330L workflow"),
    ROOF("06", "Roof", "Roof structures", "IS 800 · IS 801 · IS 875"),
    WALL("07", "Walls", "Walls", "IS 456 · IS 1905"),
}

private class Tool(val label: String, val note: String, val route: String, val panel: Int = 0)

private fun tools(p: Phase): List<Tool> = when (p) {
    Phase.RCC -> listOf(
        Tool("Beam design", "Cl. 38, 40, 23 · two layers · flanged", Routes.RCC_BEAM),
        Tool("Beam detail drawing", "UCS views · DXF", Routes.RCC_DRAWING),
        Tool("Slab", "Table 26 restrained · Table 27", Routes.RCC_SLAB),
        Tool("Column", "biaxial · slender · Cl. 39", Routes.RCC_COLUMN),
        Tool("Isolated footing", "with column moments · Cl. 34", Routes.RCC_FOOTING),
        Tool("Loads", "IS 875 Part 1 & 2 · Table 18", Routes.LOADS),
    )
    Phase.STEEL -> listOf(
        Tool("Steel member design", "beam · column · tension · beam-column · angle", Routes.STEEL),
        Tool("Bolts and welds", "Cl. 10", Routes.STEEL),
        Tool("Check all steel in the model", "η colouring in 3D Studio", Routes.STUDIO),
    )
    Phase.FEM -> listOf(
        Tool("3D Studio", "model · analyse · buckling · modal · report", Routes.STUDIO),
        Tool("Frame wizard", "beam · portal · space frame", Routes.FEM),
        Tool("IS 1893 / wind lateral loads", "EX + EY 100 % + 30 % · IS 875-3", Routes.STUDIO, 3),
        Tool("Indian Standards", "codes and load assumptions", Routes.STANDARDS),
    )
    Phase.PLAN -> listOf(
        Tool("Drawing → Structure", "DXF / PDF / photo plan → grid, columns, beams", Routes.PLAN),
        Tool("Take-down, sizes, BOQ", "Excel · EstimateX JSON", Routes.PLAN),
    )
    Phase.ROOF -> listOf(
        Tool("Roof truss", "Fink · Pratt · Howe · Fan — loads, analysis, angles, purlins", Routes.design("roof_truss")),
        Tool("Self-supported corrugated roof", "custom profile · arch · buckling", Routes.design("arch_roof")),
    )
    Phase.WALL -> listOf(
        Tool("Retaining wall", "cantilever RCC · stability · stem / heel / toe", Routes.design("retaining_wall")),
        Tool("Counterfort wall", "tall walls · continuous stem / heel · T-beam counterfort", Routes.design("counterfort_wall")),
        Tool("Load-bearing masonry wall", "IS 1905 slenderness, stress", Routes.design("wall_masonry")),
        Tool("RCC wall", "IS 456 Cl. 32", Routes.design("wall_rcc")),
        Tool("Compound wall", "wind, overturning, footing", Routes.design("wall_compound")),
    )
    Phase.STAAD -> listOf(
        Tool("Benchmarks and truss wizards", "B1–B4 · Fink · Pratt", Routes.STUDIO, 1),
        Tool("Translational repeat", "storeys · bays · verandas", Routes.STUDIO, 2),
        Tool("Lateral loads", "IS 1893 static · IS 875-3 wind", Routes.STUDIO, 3),
    )
}

/** Workbench home: the current model live in UCS space, phases docked on both sides, actions along the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchHome(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sample = remember { Benchmarks.building().copy(title = "Sample: 3-storey RCC frame") }
    val model: FrameModel = DesignSession.currentModel ?: sample
    androidx.compose.runtime.LaunchedEffect(Unit) { if (DesignSession.currentModel == null) DesignSession.currentModel = sample }
    val camera = remember { Camera() }
    var sheet by remember { mutableStateOf<Phase?>(null) }
    var results by remember(model) { mutableStateOf<List<Pair<LoadCombination, FrameResults>>?>(null) }
    var util by remember(model) { mutableStateOf<Map<Int, Double>?>(null) }
    var review by remember(model) { mutableStateOf<ResultCheck.Review?>(null) }
    var showReview by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Σ static check —") }
    // viewport state: result view, combination, load case shown, build-in, idle spin, gesture hint
    var view by remember(model) { mutableStateOf(ResultView.MODEL) }
    var combo by remember(model) { mutableStateOf(0) }
    val cases = remember(model) { (model.nodalLoads.map { it.case } + model.memberLoads.map { it.case }).distinct() }
    var caseIdx by remember(model) { mutableStateOf(0) }          // 0 = first case … cases.size = all, cases.size + 1 = off
    val caseSel: String? = when { cases.isEmpty() -> null; caseIdx < cases.size -> cases[caseIdx]; caseIdx == cases.size -> null; else -> "" }
    val reveal = remember(model) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(model) { reveal.animateTo(1f, androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    var spin by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(spin) {
        var last = 0L
        while (spin) {
            androidx.compose.runtime.withFrameNanos { t -> if (last != 0L) camera.yaw += (t - last) / 1e9f * 9f; last = t }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(6000); hint = false }

    // ---- drawing in the main space (same tools as the Studio, compact)
    var draw by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(DrawTool.MEMBER) }
    var plane by remember { mutableStateOf(com.structura.app.ui.fem.Plane.XZ) }
    var snap by remember { mutableStateOf(1.0) }
    var pending by remember { mutableStateOf<Int?>(null) }
    var selNode by remember { mutableStateOf<Int?>(null) }
    var selMember by remember { mutableStateOf<Int?>(null) }
    var loadVal by remember { mutableStateOf("-10") }
    var loadCase by remember { mutableStateOf(com.structura.core.fem.LoadCase.DL) }
    var undo by remember { mutableStateOf(listOf<FrameModel>()) }
    var redo by remember { mutableStateOf(listOf<FrameModel>()) }
    var autoRun by remember { mutableStateOf(true) }
    var sel by remember { mutableStateOf(com.structura.core.fem.ModelOps.Sel()) }
    var op by remember { mutableStateOf<EditOp?>(null) }
    // whole-model design (RCC beams / columns + IS 800 steel), shown on the members and in a list
    var design by remember(model) { mutableStateOf<Map<Int, Triple<Double, Boolean, String>>?>(null) }
    var rccDesign by remember(model) { mutableStateOf<List<com.structura.core.fem.RccModelDesign.MemberDesign>>(emptyList()) }
    var showDesign by remember { mutableStateOf(false) }
    fun commit(m: FrameModel, msg: String) {
        undo = (undo + model).takeLast(60); redo = emptyList()
        DesignSession.currentModel = m
        com.structura.app.project.ProjectStore.save(context)
        status = msg
        sel = com.structura.core.fem.ModelOps.Sel(sel.nodes.filter { id -> m.nodes.any { it.id == id } }.toSet(), sel.members.filter { id -> m.members.any { it.id == id } }.toSet())
    }
    fun runDesign(only: Set<Int>? = null) {
        busy = true
        scope.launch {
            try {
                val res = results ?: withContext(Dispatchers.Default) { FrameSolver.analyseAll(model, LoadCombination.forModel(model.loadCases)) }.also { results = it }
                val (rcc, steel) = withContext(Dispatchers.Default) { com.structura.core.fem.RccModelDesign.design(model, res) to SteelModelCheck.check(model, res) }
                rccDesign = rcc
                val map = HashMap<Int, Triple<Double, Boolean, String>>()
                rcc.forEach { map[it.member] = Triple(it.utilisation, it.ok, it.summary) }
                steel.forEach { c -> map[c.member] = Triple(c.utilisation, c.ok, "Steel ${c.section} ${c.grade}: η ${"%.2f".format(c.utilisation)} (${c.combination}) ${if (c.ok) "✔" else "✘"}") }
                design = if (only != null) map.filterKeys { it in only } else map
                util = design!!.mapValues { it.value.first }
                view = ResultView.MODEL
                val bad = design!!.values.count { !it.second }
                status = "Design: ${rcc.count { it.kind == "Beam" }} RCC beams, ${rcc.count { it.kind == "Column" }} columns, ${steel.size} steel — " + if (bad == 0) "all ✔" else "$bad ✘"
                showDesign = true
            } catch (e: Exception) { status = "Design: ${e.message}" } finally { busy = false }
        }
    }
    fun onDrawTap(t: com.structura.app.ui.fem.TapInfo) {
        val E = com.structura.core.fem.ModelEditor
        val proto = model.members.lastOrNull()
        when (tool) {
            DrawTool.SELECT -> {
                sel = when {
                    t.node != null -> sel.copy(nodes = if (t.node in sel.nodes) sel.nodes - t.node else sel.nodes + t.node)
                    t.member != null -> sel.copy(members = if (t.member in sel.members) sel.members - t.member else sel.members + t.member)
                    else -> sel
                }
                selNode = null; selMember = null
                status = t.member?.let { id -> design?.get(id)?.third }?.let { "M${t.member} · $it" } ?: t.node?.let { id -> val n = model.node(id); "N$id (${"%.2f".format(n.x)}, ${"%.2f".format(n.y)}, ${"%.2f".format(n.z)}) m · ${E.supportType(model, id).name.lowercase()}" }
                    ?: t.member?.let { id -> val m = model.members.first { it.id == id }; "M$id N${m.i}→N${m.j} · ${m.section.name} · ${"%.2f".format((model.node(m.j).pos - model.node(m.i).pos).length)} m" } ?: "Nothing selected" }
            DrawTool.NODE -> t.planePoint?.let { p -> val (m, id) = E.addNode(model, p); if (m !== model) commit(m, "Node N$id") } ?: run { status = "Rotate the view — the work plane is edge-on" }
            DrawTool.MEMBER -> {
                var m = model
                val target = t.node ?: t.planePoint?.let { p -> E.addNode(m, p).also { m = it.first }.second }
                if (target == null) { status = "Tap a node or the grid"; return }
                val p0 = pending
                when {
                    p0 == null -> { if (m !== model) commit(m, "Start N$target"); pending = target; status = "Start N$target — tap the end point" }
                    p0 == target -> { pending = null; status = "Chain finished" }
                    else -> {
                        val mat = proto?.material ?: com.structura.app.ui.studio.SectionSpec().material()
                        val sec = proto?.section ?: com.structura.app.ui.studio.SectionSpec().section()
                        val (m2, mid) = E.addMember(m, p0, target, mat, sec)
                        commit(m2, if (mid != null) "Member M$mid: N$p0 → N$target" else "Member already exists"); pending = target
                    }
                }
            }
            DrawTool.SPLIT -> t.member?.let { id -> val (m, nid) = E.splitMember(model, id); commit(m, "M$id split at N$nid") }
            DrawTool.FIX, DrawTool.PIN -> t.node?.let { id ->
                val want = if (tool == DrawTool.FIX) com.structura.core.fem.ModelEditor.SupportType.FIXED else com.structura.core.fem.ModelEditor.SupportType.PINNED
                val now = E.supportType(model, id)
                commit(E.setSupport(model, id, if (now == want) com.structura.core.fem.ModelEditor.SupportType.NONE else want), if (now == want) "Support removed at N$id" else "${want.name.lowercase()} support at N$id")
            }
            DrawTool.LOAD -> t.node?.let { id ->
                val v = loadVal.replace(',', '.').toDoubleOrNull() ?: 0.0
                commit(E.addNodalLoad(model, id, com.structura.core.fem.Vec3(0.0, 0.0, v * com.structura.app.Units.force), loadCase), "$loadCase ${"%.1f".format(v)} at N$id")
            }
            DrawTool.UDL -> t.member?.let { id ->
                val v = loadVal.replace(',', '.').toDoubleOrNull() ?: 0.0
                commit(E.addMemberUdl(model, id, com.structura.core.fem.LoadDir.GLOBAL_Z, v * com.structura.app.Units.udl, loadCase), "$loadCase UDL ${"%.1f".format(v)} on M$id")
            }
            DrawTool.DELETE -> when {
                t.node != null -> commit(E.deleteNode(model, t.node), "N${t.node} deleted")
                t.member != null -> commit(E.deleteMember(model, t.member), "M${t.member} deleted")
                else -> {}
            }
        }
    }
    // re-analyse quietly whenever the model changes, then play the deflected shape (as in the Studio)
    androidx.compose.runtime.LaunchedEffect(model, autoRun) {
        if (!autoRun || model.supports.isEmpty() || model.members.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(if (draw) 700 else 1500)
        runCatching {
            val res = withContext(Dispatchers.Default) { FrameSolver.analyseAll(model, LoadCombination.forModel(model.loadCases)) }
            if (res.isNotEmpty()) {
                results = res
                combo = res.indices.maxBy { res[it].second.maxDisplacement().second }
                val fe = res.maxOf { (_, r) -> r.equilibriumError.maxOf { abs(it) } }
                if (!draw) status = "Σ ${res.size} combos · ΣF ${"%.0e".format(fe)} kN · live deflected shape"
                if (view == ResultView.MODEL && !draw) view = ResultView.DEFORMED
            }
        }
    }

    fun open(t: Tool) { sheet = null; if (t.panel != 0) DesignSession.studioPanel = t.panel; onOpen(t.route) }

    suspend fun ensureResults(): List<Pair<LoadCombination, FrameResults>> = results ?: withContext(Dispatchers.Default) {
        FrameSolver.analyseAll(model, LoadCombination.forModel(model.loadCases))
    }.also { results = it }

    fun analyse() {
        busy = true
        scope.launch {
            try {
                val res = ensureResults()
                val fe = res.maxOf { (_, r) -> r.equilibriumError.maxOf { abs(it) } }
                val me = res.maxOf { (_, r) -> r.momentEquilibriumError.maxOf { abs(it) } }
                val steel = withContext(Dispatchers.Default) { SteelModelCheck.check(model, res) }
                util = steel.takeIf { it.isNotEmpty() }?.associate { it.member to it.utilisation }
                // show the governing combination's deflected shape, animated
                combo = res.indices.maxBy { res[it].second.maxDisplacement().second }
                view = ResultView.DEFORMED
                status = "Σ ${res.size} combos · ΣF ${"%.0e".format(fe)} kN · ΣM ${"%.0e".format(me)} kN·m" +
                    (util?.let { " · η max ${"%.2f".format(it.values.max())}" } ?: "")
            } catch (e: Exception) { status = "Analysis: ${e.message}" } finally { busy = false }
        }
    }

    fun aiReview() {
        busy = true
        scope.launch {
            try {
                val res = runCatching { ensureResults() }.getOrDefault(emptyList())
                review = withContext(Dispatchers.Default) { ResultCheck.review(model, res) }
                showReview = true
            } finally { busy = false }
        }
    }

    fun report() {
        busy = true
        scope.launch {
            try {
                val res = ensureResults()
                val doc = withContext(Dispatchers.Default) {
                    com.structura.core.fem.FemReportDoc.build(model, res, SteelModelCheck.check(model, res), review ?: ResultCheck.review(model, res))
                }
                com.structura.app.report.PdfExport.share(context, doc)
            } catch (e: Exception) { status = "Report: ${e.message}" } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(Ground).statusBarsPadding().navigationBarsPadding()) {
        // top bar
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(com.structura.app.R.drawable.structura_logo), "Structura AI logo", Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Projects") { onOpen(Routes.PROJECTS) }) {
                Text((com.structura.app.project.ProjectStore.current?.name ?: model.title) + "  ▾", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text("${model.members.size} members · ${model.nodes.size} nodes · ${model.supports.size} supports", color = Muted, fontSize = 11.sp)
            }
            Pill(if (Units.imperial) "ft · kip" else "m · kN", false) { Units.set(context, !Units.imperial) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

        // UCS space
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val shown = results?.getOrNull(combo)
            val showResults = !draw && view != ResultView.MODEL
            FrameView3D(
                model, if (showResults) shown?.second else null, if (showResults) view else ResultView.MODEL, Modifier.fillMaxSize(), animate = true, camera = camera,
                memberUtil = if (!showResults) util else null, dark = true, loadCase = if (draw) null else caseSel, reveal = if (draw) 1f else reveal.value,
                doubleTapFit = !draw, onTouch = { spin = false; hint = false },
                workPlane = if (draw) com.structura.app.ui.fem.WorkPlane(plane, 0.0, snap, visible = true) else null,
                onTap = if (draw) ({ t -> onDrawTap(t) }) else null,
                selectedNode = if (draw) selNode else null, selectedMember = if (draw) selMember else null, pendingNode = if (draw) pending else null,
                selectedNodes = if (draw) sel.nodes else emptySet(), selectedMembers = if (draw) sel.members else emptySet(),
            )
            Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("◇ 3D", true) { camera.iso() }
                Pill("XY", false) { camera.top() }
                Pill("XZ", false) { camera.front() }
                Pill("YZ", false) { camera.right() }
                if (draw) Pill("✓ Done", true) { draw = false; pending = null; selNode = null; selMember = null; sel = com.structura.core.fem.ModelOps.Sel(); camera.iso() }
            }
            if (!draw) Column(Modifier.align(Alignment.CenterStart).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DESIGN", color = Muted, fontSize = 10.sp)
                RailButton(Phase.RCC, sheet == Phase.RCC) { sheet = Phase.RCC }
                RailButton(Phase.STEEL, sheet == Phase.STEEL) { sheet = Phase.STEEL }
                RailButton(Phase.ROOF, sheet == Phase.ROOF) { sheet = Phase.ROOF }
                RailButton(Phase.WALL, sheet == Phase.WALL) { sheet = Phase.WALL }
            }
            if (!draw) Column(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MODEL", color = Muted, fontSize = 10.sp)
                RailButton(Phase.FEM, sheet == Phase.FEM) { sheet = Phase.FEM }
                RailButton(Phase.PLAN, sheet == Phase.PLAN) { sheet = Phase.PLAN }
                RailButton(Phase.STAAD, sheet == Phase.STAAD) { sheet = Phase.STAAD }
            }
            val caption = when {
                draw -> "✎ ${tool.hint}"
                view != ResultView.MODEL && shown != null -> "${view.label} · ${shown.first.name}"
                util != null -> "η · IS 800 · all combinations"
                caseSel == null && cases.isNotEmpty() -> "Loads · all cases"
                caseSel != null && caseSel != "" -> "Loads · $caseSel"
                else -> ""
            }
            if (caption.isNotEmpty()) Text(caption, color = Orange, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 52.dp))
            androidx.compose.animation.AnimatedVisibility(hint && !draw, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp, start = 70.dp, end = 70.dp),
                enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                Text("Drag to rotate · two fingers pan & zoom · double-tap to fit", textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Ink.copy(alpha = 0.85f), fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Bar.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            if (draw) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Bar.copy(alpha = 0.92f)).padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    DrawTool.entries.forEach { t -> Pill(t.label, tool == t) { tool = t; pending = null } }
                }
                if (tool == DrawTool.SELECT) {
                    val O = com.structura.core.fem.ModelOps
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${sel.members.size} M · ${sel.nodes.size} N", color = Orange, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Pill("All", false) { sel = O.all(model) }
                        Pill("None", false) { sel = com.structura.core.fem.ModelOps.Sel() }
                        Pill("Invert", false) { sel = O.invert(model, sel) }
                        Pill("Columns", false) { sel = sel.copy(members = sel.members + O.columns(model)) }
                        Pill("Beams", false) { sel = sel.copy(members = sel.members + O.beams(model)) }
                        Pill("Braces", false) { sel = sel.copy(members = sel.members + O.braces(model)) }
                        Pill("Supports", false) { sel = sel.copy(nodes = sel.nodes + model.supports.map { it.node }) }
                        Pill("Same level", false) {
                            val z = (sel.nodes.firstOrNull() ?: sel.members.firstOrNull()?.let { id -> model.members.first { it.id == id }.i })?.let { model.node(it).z }
                            if (z == null) status = "Select a node or beam first" else { val l = O.atLevel(model, z); sel = com.structura.core.fem.ModelOps.Sel(sel.nodes + l.nodes, sel.members + l.members) }
                        }
                        Pill("Similar", false) { sel.members.firstOrNull()?.let { sel = sel.copy(members = sel.members + O.similar(model, it)) } ?: run { status = "Select a member first" } }
                    }
                    if (!sel.isEmpty) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        EditOp.entries.forEach { o -> Pill(o.label, false) {
                            if (o == EditOp.DELETE) commit(O.delete(model, sel), "${sel.count} item(s) deleted").also { sel = com.structura.core.fem.ModelOps.Sel() }
                            else if (o == EditOp.DESIGN) runDesign(sel.members)
                            else op = o
                        } }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("↶", false) { undo.lastOrNull()?.let { prev -> redo = redo + model; undo = undo.dropLast(1); DesignSession.currentModel = prev; com.structura.app.project.ProjectStore.save(context); status = "Undo" } }
                    Pill("↷", false) { redo.lastOrNull()?.let { nxt -> undo = undo + model; redo = redo.dropLast(1); DesignSession.currentModel = nxt; com.structura.app.project.ProjectStore.save(context); status = "Redo" } }
                    com.structura.app.ui.fem.Plane.entries.forEach { pl -> Pill("Plane " + pl.name, plane == pl) {
                        plane = pl; when (pl) { com.structura.app.ui.fem.Plane.XY -> camera.top(); com.structura.app.ui.fem.Plane.XZ -> camera.front(); com.structura.app.ui.fem.Plane.YZ -> camera.right() } } }
                    listOf(0.5, 1.0, 1.5, 3.0).forEach { sn -> Pill("${sn} m", snap == sn) { snap = sn } }
                    if (tool == DrawTool.LOAD || tool == DrawTool.UDL) {
                        androidx.compose.foundation.text.BasicTextField(loadVal, { loadVal = it }, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Cyan),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.width(70.dp).border(BorderStroke(1.dp, Cyan), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 7.dp))
                        Text(if (tool == DrawTool.LOAD) (if (com.structura.app.Units.imperial) "kip (Z)" else "kN (Z)") else (if (com.structura.app.Units.imperial) "kip/ft" else "kN/m"), color = Muted, fontSize = 11.sp)
                        listOf("DL", "LL", "WL").forEach { c -> Pill(c, loadCase == c) { loadCase = c } }
                    }
                }
            }
            // bottom viewport controls: load case / result view / combination / fit / spin
            if (!draw) Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (results == null || view == ResultView.MODEL) {
                    if (cases.isNotEmpty()) Pill("Loads: " + when (caseSel) { null -> "All"; "" -> "Off"; else -> caseSel } + " ▸", false) { caseIdx = (caseIdx + 1) % (cases.size + 2) }
                }
                if (results != null) {
                    listOf(ResultView.MODEL, ResultView.DEFORMED, ResultView.N, ResultView.MY, ResultView.MZ).forEach { v ->
                        Pill(if (v == ResultView.MODEL) "Model" else v.label, view == v) { view = v; spin = false }
                    }
                    results?.let { rs ->
                        if (view != ResultView.MODEL && rs.size > 1) {
                            Pill("◂", false) { combo = (combo - 1 + rs.size) % rs.size }
                            Text("${combo + 1}/${rs.size}", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Pill("▸", false) { combo = (combo + 1) % rs.size }
                        }
                    }
                }
                Pill("✎ Edit", false) { draw = true; spin = false; hint = false; camera.front(); plane = com.structura.app.ui.fem.Plane.XZ; status = "Draw: pick a tool, tap the grid" }
                Pill("⤢ Fit", false) { camera.fit() }
                Pill(if (spin) "⏸ Spin" else "⟳ Spin", spin) { spin = !spin }
            }
        }

        // status strip
        Row(Modifier.fillMaxWidth().background(Bar).border(BorderStroke(1.dp, Line)).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(if (busy) "Working…" else status, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
            review?.let { Text("AI ${it.errors}✘ ${it.warnings}⚠", color = if (it.errors > 0) Orange else Green, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
        // actions
        Row(Modifier.fillMaxWidth().background(Bar).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("Analyse", primary = true, Modifier.weight(1f), icon = { c -> PlayIcon(c) }) { if (!busy) analyse() }
            Action("AI review", primary = false, Modifier.weight(1f), icon = { c -> SparkIcon(c) }) { if (!busy) aiReview() }
            Action("Design", primary = false, Modifier.weight(1f), icon = { c -> BarsIcon(c) }) { if (!busy) runDesign() }
            Action("Report", primary = false, Modifier.weight(1f), icon = { c -> DocIcon(c) }) { if (!busy) report() }
        }
    }

    sheet?.let { p ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Panel) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text("${p.no}  ", color = Orange, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Text(p.title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text(p.codes, color = Muted, fontSize = 11.sp)
                }
                // phase switcher
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Phase.entries.forEach { q -> Pill("${q.no} ${q.label}", q == p) { sheet = q } }
                }
                tools(p).forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { open(t) }
                            .border(BorderStroke(1.dp, Line), RoundedCornerShape(12.dp)).background(Ground).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.label, color = Ink, fontSize = 15.sp)
                            Text(t.note, color = Muted, fontSize = 11.sp)
                        }
                        Text("›", color = Cyan, fontSize = 20.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    op?.let { o ->
        EditOpDialog(o, model, sel, onDismiss = { op = null }) { m2, newSel, msg -> commit(m2, msg); newSel?.let { sel = it }; op = null }
    }

    if (showDesign) design?.let { dm ->
        ModalBottomSheet(onDismissRequest = { showDesign = false }, containerColor = Panel) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Member design — IS 456 / IS 800", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Members are coloured by utilisation in the main space. Tap a row to select it there; beams open in the beam designer with their forces.", color = Muted, fontSize = 12.sp)
                if (dm.isEmpty()) Text("No designable members: RCC needs a rectangular section with an M-grade concrete; steel needs an IS 808 / hollow section.", color = Orange, fontSize = 13.sp)
                dm.entries.sortedByDescending { it.value.first }.forEach { (id, v) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(BorderStroke(1.dp, Line), RoundedCornerShape(10.dp))
                        .clickable { draw = true; tool = DrawTool.SELECT; sel = com.structura.core.fem.ModelOps.Sel(members = setOf(id)); showDesign = false; status = "M$id · ${v.third}" }
                        .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("M$id", color = if (v.second) Green else Color(0xFFEF6B5B), fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.width(48.dp))
                        Text(v.third, color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        rccDesign.firstOrNull { it.member == id && it.kind == "Beam" }?.let { b ->
                            Pill("Beam ›", false) {
                                DesignSession.prefill = DesignSession.Prefill(b.b, b.d, b.span, b.mu, b.vu)
                                showDesign = false; onOpen(Routes.RCC_BEAM)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("RCC tools", false) { showDesign = false; sheet = Phase.RCC }
                    Pill("Steel tools", false) { showDesign = false; sheet = Phase.STEEL }
                    Pill("Roof / walls", false) { showDesign = false; sheet = Phase.ROOF }
                }
            }
        }
    }

    if (showReview) review?.let { r ->
        ModalBottomSheet(onDismissRequest = { showReview = false }, containerColor = Panel) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("AI result check", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                r.summary.forEach { Text(it, color = Muted, fontSize = 13.sp) }
                r.findings.forEach { f ->
                    val (mark, c) = when (f.level) {
                        ResultCheck.Level.ERROR -> "✘" to Color(0xFFEF6B5B)
                        ResultCheck.Level.WARN -> "⚠" to Orange
                        ResultCheck.Level.INFO -> "ℹ" to Cyan
                        ResultCheck.Level.OK -> "✔" to Green
                    }
                    Text("$mark ${f.topic}: ${f.message}" + if (f.clause.isNotEmpty()) "  [${f.clause}]" else "", color = c, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Pill("Open in 3D Studio", true) { showReview = false; onOpen(Routes.STUDIO) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
            .background(if (selected) Cyan.copy(alpha = 0.12f) else Ground)
            .border(BorderStroke(1.dp, if (selected) Cyan else Line), RoundedCornerShape(17.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) Cyan else Muted, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun RailButton(p: Phase, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) Orange else Cyan
    Column(
        Modifier.size(58.dp, 66.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF2A2416) else Panel.copy(alpha = 0.92f))
            .border(BorderStroke(1.dp, if (selected) Orange else Line), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = p.title, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        PhaseIcon(p, c)
        Spacer(Modifier.height(4.dp))
        Text(p.label, color = Ink, fontSize = 10.sp)
    }
}

@Composable
private fun Action(label: String, primary: Boolean, modifier: Modifier, icon: @Composable (Color) -> Unit, onClick: () -> Unit) {
    val c = if (primary) Dark else Cyan
    Column(
        modifier.height(64.dp).clip(RoundedCornerShape(14.dp))
            .background(if (primary) Cyan else Panel)
            .border(BorderStroke(1.dp, if (primary) Cyan else Line), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        icon(c)
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (primary) Dark else Ink, fontSize = 12.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ---- stroke icons (drawn, no image assets) ----
@Composable
private fun PhaseIcon(p: Phase, c: Color) = Canvas(Modifier.size(22.dp)) {
    val s = size.width / 24f
    val st = Stroke(1.7f * s * 1.2f)
    fun l(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, androidx.compose.ui.geometry.Offset(x1 * s, y1 * s), androidx.compose.ui.geometry.Offset(x2 * s, y2 * s), st.width)
    when (p) {
        Phase.RCC -> { drawRect(c, androidx.compose.ui.geometry.Offset(3 * s, 7 * s), androidx.compose.ui.geometry.Size(18 * s, 10 * s), style = st)
            for (x in listOf(7f, 12f, 17f)) drawCircle(c, 1.2f * s, androidx.compose.ui.geometry.Offset(x * s, 14 * s)) }
        Phase.STEEL -> { l(5f, 4f, 19f, 4f); l(5f, 20f, 19f, 20f); l(12f, 4f, 12f, 20f) }
        Phase.FEM -> { for ((x, y) in listOf(5f to 6f, 19f to 6f, 5f to 18f, 19f to 18f)) drawCircle(c, 2f * s, androidx.compose.ui.geometry.Offset(x * s, y * s), style = st)
            l(5f, 8f, 5f, 16f); l(19f, 8f, 19f, 16f); l(7f, 6f, 17f, 6f); l(7f, 7f, 17f, 17f) }
        Phase.PLAN -> { drawRect(c, androidx.compose.ui.geometry.Offset(3 * s, 3 * s), androidx.compose.ui.geometry.Size(18 * s, 18 * s), style = st)
            l(9f, 3f, 9f, 21f); l(15f, 3f, 15f, 21f); l(3f, 9f, 21f, 9f); l(3f, 15f, 21f, 15f) }
        Phase.ROOF -> { l(2f, 14f, 12f, 4f); l(12f, 4f, 22f, 14f); l(5f, 11f, 5f, 20f); l(19f, 11f, 19f, 20f); l(5f, 20f, 19f, 20f) }
        Phase.WALL -> { l(4f, 4f, 4f, 20f); l(10f, 4f, 10f, 14f); l(10f, 14f, 20f, 14f); l(20f, 14f, 20f, 20f); l(4f, 20f, 20f, 20f); l(4f, 4f, 10f, 4f) }
        Phase.STAAD -> { l(2f, 18f, 22f, 18f); l(2f, 18f, 7f, 10f); l(7f, 10f, 17f, 10f); l(17f, 10f, 22f, 18f); l(7f, 10f, 10f, 18f); l(17f, 10f, 14f, 18f); l(12f, 10f, 12f, 18f) }
    }
}

@Composable
private fun PlayIcon(c: Color) = Canvas(Modifier.size(18.dp)) {
    drawPath(Path().apply { moveTo(size.width * 0.28f, size.height * 0.15f); lineTo(size.width * 0.85f, size.height * 0.5f); lineTo(size.width * 0.28f, size.height * 0.85f); close() }, c)
}

@Composable
private fun SparkIcon(c: Color) = Canvas(Modifier.size(18.dp)) {
    val m = size.width / 2; val w = 1.8f * size.width / 18
    drawLine(c, androidx.compose.ui.geometry.Offset(m, 0f), androidx.compose.ui.geometry.Offset(m, size.height), w)
    drawLine(c, androidx.compose.ui.geometry.Offset(0f, m), androidx.compose.ui.geometry.Offset(size.width, m), w)
    drawCircle(c, size.width * 0.18f, androidx.compose.ui.geometry.Offset(m, m))
}

@Composable
private fun BarsIcon(c: Color) = Canvas(Modifier.size(18.dp)) {
    val w = 1.8f * size.width / 18; val h = size.height
    drawLine(c, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(size.width, h), w)
    for ((x, top) in listOf(0.2f to 0.45f, 0.5f to 0.1f, 0.8f to 0.6f)) drawLine(c, androidx.compose.ui.geometry.Offset(size.width * x, h), androidx.compose.ui.geometry.Offset(size.width * x, h * top), w)
}

@Composable
private fun DocIcon(c: Color) = Canvas(Modifier.size(18.dp)) {
    val w = 1.8f * size.width / 18
    drawRect(c, androidx.compose.ui.geometry.Offset(size.width * 0.2f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height), style = Stroke(w))
    for (y in listOf(0.35f, 0.55f, 0.75f)) drawLine(c, androidx.compose.ui.geometry.Offset(size.width * 0.32f, size.height * y), androidx.compose.ui.geometry.Offset(size.width * 0.68f, size.height * y), w)
}


/** Drawing tools available directly in the Workbench space. */
enum class DrawTool(val label: String, val hint: String) {
    SELECT("Select", "tap nodes / members to select; then Move, Copy, Mirror …"),
    NODE("Node", "tap the grid to place a node"),
    MEMBER("Member", "tap start, then end; tap the last node again to stop"),
    SPLIT("½ Split", "tap a member to split it"),
    FIX("Fix", "tap a node: fixed support"),
    PIN("Pin", "tap a node: pinned support"),
    LOAD("Load", "tap a node: vertical load (− = down)"),
    UDL("UDL", "tap a member: uniform load (− = down)"),
    DELETE("Delete", "tap a node or member to delete"),
}


/** Operations on the Workbench selection. */
enum class EditOp(val label: String) {
    MOVE("⇄ Move"), COPY("⧉ Copy"), MIRROR("⇋ Mirror"), ROTATE("⟲ Rotate"), DELETE("🗑 Delete"), SECTION("▭ Section"),
    RELEASE("◦ Releases"), SUPPORT("▲ Support"), LOAD("↓ Load"), SPLIT("⫶ Divide"), DESIGN("Design ✔")
}

@Composable
private fun EditOpDialog(
    o: EditOp, model: FrameModel, sel: com.structura.core.fem.ModelOps.Sel, onDismiss: () -> Unit,
    onApply: (FrameModel, com.structura.core.fem.ModelOps.Sel?, String) -> Unit,
) {
    val O = com.structura.core.fem.ModelOps
    val E = com.structura.core.fem.ModelEditor
    val u = com.structura.app.Units
    val c = remember(sel) { O.centroid(model, sel) }
    var a by remember { mutableStateOf("0") }; var b by remember { mutableStateOf("0") }; var cc by remember { mutableStateOf("0") }
    var n by remember { mutableStateOf("1") }
    var axis by remember { mutableStateOf('X') }
    var keep by remember { mutableStateOf(true) }
    var at by remember(o) { mutableStateOf("%.3f".format(c.x / u.len)) }
    var cx by remember(o) { mutableStateOf("%.3f".format(c.x / u.len)) }; var cy by remember(o) { mutableStateOf("%.3f".format(c.y / u.len)) }
    var hI by remember { mutableStateOf(false) }; var hJ by remember { mutableStateOf(false) }; var truss by remember { mutableStateOf(false) }
    var sup by remember { mutableStateOf(com.structura.core.fem.ModelEditor.SupportType.FIXED) }
    var loadCase by remember { mutableStateOf("DL") }
    val spec = remember { com.structura.app.ui.studio.SectionSpec() }
    fun d(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    val lu = if (u.imperial) "ft" else "m"
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${o.label} — ${sel.members.size} members, ${sel.nodes.size} nodes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                @Composable fun num(label: String, v: String, set: (String) -> Unit) = androidx.compose.material3.OutlinedTextField(v, set, label = { Text(label) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                when (o) {
                    EditOp.MOVE, EditOp.COPY -> {
                        num("ΔX ($lu)", a) { a = it }; num("ΔY ($lu)", b) { b = it }; num("ΔZ ($lu)", cc) { cc = it }
                        if (o == EditOp.COPY) { num("Number of copies", n) { n = it }; Text("Each copy is offset by Δ from the previous one (translational repeat); loads and supports are copied and coincident nodes merged.", fontSize = 12.sp) }
                    }
                    EditOp.MIRROR -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf('X', 'Y', 'Z').forEach { ax -> androidx.compose.material3.FilterChip(axis == ax, { axis = ax; at = "%.3f".format((when (ax) { 'X' -> c.x; 'Y' -> c.y; else -> c.z }) / u.len) }, { Text("about $ax =") }) } }
                        num("Plane position ($lu)", at) { at = it }
                        Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Switch(keep, { keep = it }); Text("  keep original (copy)") }
                    }
                    EditOp.ROTATE -> {
                        num("Angle (°, anticlockwise from above)", a) { a = it }; num("Centre X ($lu)", cx) { cx = it }; num("Centre Y ($lu)", cy) { cy = it }
                        Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Switch(keep, { keep = it }); Text("  copy (polar repeat)") }
                        if (keep) num("Number of copies", n) { n = it }
                    }
                    EditOp.SECTION -> { com.structura.app.ui.studio.SectionPicker(spec); Text("Applied to the ${sel.members.size} selected members.", fontSize = 12.sp) }
                    EditOp.RELEASE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Switch(hI, { hI = it }); Text("  moment hinge at i") }
                        Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Switch(hJ, { hJ = it }); Text("  moment hinge at j") }
                        Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Switch(truss, { truss = it }); Text("  truss member (axial only)") }
                    }
                    EditOp.SUPPORT -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.structura.core.fem.ModelEditor.SupportType.entries.forEach { t -> androidx.compose.material3.FilterChip(sup == t, { sup = t }, { Text(t.name.lowercase().replace('_', ' ')) }) }
                    }
                    EditOp.LOAD -> {
                        Text(if (sel.members.isNotEmpty()) "UDL on members, kN/m (global Z, − = down)" else "Nodal load, kN (global axes)", fontSize = 12.sp)
                        if (sel.members.isNotEmpty()) num("w", cc) { cc = it } else { num("Fx", a) { a = it }; num("Fy", b) { b = it }; num("Fz", cc) { cc = it } }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("DL", "LL", "WL").forEach { k -> androidx.compose.material3.FilterChip(loadCase == k, { loadCase = k }, { Text(k) }) } }
                    }
                    EditOp.SPLIT -> num("Divide each member into n parts", n) { n = it }
                    else -> {}
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                runCatching {
                    val L = u.len
                    when (o) {
                        EditOp.MOVE -> onApply(O.move(model, sel, com.structura.core.fem.Vec3(d(a) * L, d(b) * L, d(cc) * L)), null, "Moved ${sel.count} item(s)")
                        EditOp.COPY -> { val (m, s) = O.copy(model, sel, com.structura.core.fem.Vec3(d(a) * L, d(b) * L, d(cc) * L), d(n).toInt().coerceAtLeast(1)); onApply(m, s, "${d(n).toInt()} cop(ies) made") }
                        EditOp.MIRROR -> { val (m, s) = O.mirror(model, sel, axis, d(at) * L, keep); onApply(m, s, "Mirrored about $axis = ${at}") }
                        EditOp.ROTATE -> { val (m, s) = O.rotate(model, sel, d(a), d(cx) * L, d(cy) * L, keep, d(n).toInt().coerceAtLeast(1)); onApply(m, s, "Rotated ${a}°") }
                        EditOp.SECTION -> onApply(O.setSection(model, sel.members, spec.section(), spec.material()), null, "Section ${spec.section().name} on ${sel.members.size} members")
                        EditOp.RELEASE -> onApply(O.setReleases(model, sel.members, hI, hJ, truss), null, "Releases set on ${sel.members.size} members")
                        EditOp.SUPPORT -> onApply(O.setSupports(model, sel.nodes, sup), null, "${sup.name.lowercase()} supports at ${sel.nodes.size} nodes")
                        EditOp.LOAD -> if (sel.members.isNotEmpty()) onApply(O.addUdls(model, sel.members, d(cc) * u.udl, loadCase), null, "$loadCase UDL on ${sel.members.size} members")
                            else onApply(O.addNodalLoads(model, sel.nodes, com.structura.core.fem.Vec3(d(a) * u.force, d(b) * u.force, d(cc) * u.force), loadCase), null, "$loadCase load at ${sel.nodes.size} nodes")
                        EditOp.SPLIT -> onApply(O.subdivide(model, sel.members, d(n).toInt().coerceIn(2, 20)), com.structura.core.fem.ModelOps.Sel(), "Divided into ${d(n).toInt()} parts")
                        else -> onDismiss()
                    }
                }.onFailure { onApply(model, null, "Not applied: ${it.message}") }
            }) { Text("Apply") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
