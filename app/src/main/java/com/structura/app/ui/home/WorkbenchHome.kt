package com.structura.app.ui.home

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlinx.coroutines.delay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextAlign
import com.structura.app.ui.fem.TapInfo
import com.structura.app.ui.fem.Plane
import com.structura.app.ui.fem.WorkPlane
import com.structura.core.fem.ModelOps
import com.structura.core.fem.ModelEditor
import com.structura.core.fem.RccModelDesign
import com.structura.core.fem.Validation
import com.structura.core.fem.LoadCase
import com.structura.core.fem.LoadDir
import com.structura.core.fem.Vec3
import kotlin.math.max
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
// black-and-white "paper" palette (engineering-drawing look)
private val Paper = Color.White
private val InkB = Color(0xFF111111)
private val Grey = Color(0xFF6B6B6B)
private val Hair = Color(0xFFD4D4D4)
private val Ground = Paper
private val Panel = Paper
private val Bar = Color(0xFFFAFAFA)
private val Line = Hair
private val Ink = InkB
private val Muted = Grey
private val Cyan = InkB
private val Orange = InkB
private val Green = InkB
private val Dark = InkB

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

/** Main-space modes: one canvas, the tools change with the mode. */
private enum class Mode(val label: String) { MODEL("Model"), EDIT("Edit"), LOADS("Loads"), ANALYSE("Analyse"), DESIGN("Design") }

/**
 * Workbench — the whole structure in one black-and-white main space. Model (inspect), Edit (draw, select, move,
 * copy, mirror, rotate, properties), Loads (cases, nodal loads, UDLs, self-weight), Analyse (live, animated
 * deflected shape and force diagrams per combination) and Design (every member designed to IS 456 / IS 800 /
 * IS 13920 with clause-wise checks, plus a validation list). Tapping a member or node opens its inspector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchHome(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sample = remember { Benchmarks.building().copy(title = "Sample: 3-storey RCC frame") }
    val model: FrameModel = DesignSession.currentModel ?: sample
    LaunchedEffect(Unit) { if (DesignSession.currentModel == null) DesignSession.currentModel = sample }
    val camera = remember { Camera() }
    var mode by remember { mutableStateOf(Mode.MODEL) }
    var sheet by remember { mutableStateOf<Phase?>(null) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(6000); hint = false }
    val projectKey = com.structura.app.project.ProjectStore.current?.name ?: model.title
    val reveal = remember(projectKey) { Animatable(0f) }
    LaunchedEffect(projectKey) { reveal.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)) }

    // ---- analysis (live) ----
    var results by remember(model) { mutableStateOf<List<Pair<LoadCombination, FrameResults>>?>(null) }
    var analysisError by remember(model) { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf(ResultView.DEFORMED) }
    var combo by remember(model) { mutableStateOf(0) }
    // ---- design + validation ----
    var rcc by remember(model) { mutableStateOf<List<RccModelDesign.MemberDesign>?>(null) }
    var steel by remember(model) { mutableStateOf<List<SteelModelCheck.MemberCheck>>(emptyList()) }
    var validation by remember(model) { mutableStateOf<Validation.Report?>(null) }
    var showValidation by remember { mutableStateOf(false) }
    // ---- edit ----
    var tool by remember { mutableStateOf(DrawTool.SELECT) }
    var sel by remember { mutableStateOf(ModelOps.Sel()) }
    var op by remember { mutableStateOf<EditOp?>(null) }
    var opSel by remember { mutableStateOf(ModelOps.Sel()) }
    var pending by remember { mutableStateOf<Int?>(null) }
    var plane by remember { mutableStateOf(Plane.XZ) }
    var snap by remember { mutableStateOf(1.0) }
    var undo by remember { mutableStateOf(listOf<FrameModel>()) }
    var redo by remember { mutableStateOf(listOf<FrameModel>()) }
    // ---- loads ----
    var loadCase by remember { mutableStateOf(LoadCase.DL) }
    var loadVal by remember { mutableStateOf("-10") }
    var onMembers by remember { mutableStateOf(true) }
    // ---- inspector ----
    var inspM by remember { mutableStateOf<Int?>(null) }
    var inspN by remember { mutableStateOf<Int?>(null) }
    var inspTab by remember { mutableStateOf(0) }
    if (inspM != null && model.members.none { it.id == inspM }) inspM = null
    if (inspN != null && model.nodes.none { it.id == inspN }) inspN = null

    fun commit(m: FrameModel, msg: String) {
        undo = (undo + model).takeLast(80); redo = emptyList()
        DesignSession.currentModel = m
        com.structura.app.project.ProjectStore.save(context)
        status = msg
        sel = ModelOps.Sel(sel.nodes.filter { id -> m.nodes.any { it.id == id } }.toSet(), sel.members.filter { id -> m.members.any { it.id == id } }.toSet())
    }
    fun setModel(m: FrameModel, msg: String) { DesignSession.currentModel = m; com.structura.app.project.ProjectStore.save(context); status = msg }

    // live analysis: every change is re-analysed in the background
    LaunchedEffect(model) {
        if (model.supports.isEmpty() || model.members.isEmpty()) { analysisError = if (model.members.isEmpty()) null else "Add supports to analyse"; return@LaunchedEffect }
        delay(450)
        try {
            val res = withContext(Dispatchers.Default) { FrameSolver.analyseAll(model, LoadCombination.forModel(model.loadCases)) }
            results = res
            combo = res.indices.maxByOrNull { res[it].second.maxDisplacement().second } ?: 0
        } catch (e: Exception) { analysisError = e.message ?: "analysis failed" }
    }
    // design + validation whenever the design mode is open (or asked for)
    fun runDesign() {
        val res = results ?: run { status = analysisError ?: "Analysing…"; return }
        busy = true
        scope.launch {
            try {
                val (r, s) = withContext(Dispatchers.Default) { RccModelDesign.design(model, res) to SteelModelCheck.check(model, res) }
                rcc = r; steel = s
                validation = withContext(Dispatchers.Default) { Validation.run(model, res, r, s) }
                val bad = r.count { !it.ok } + s.count { !it.ok }
                status = "Designed ${r.count { it.kind == "Beam" }} beams, ${r.count { it.kind != "Beam" }} columns, ${s.size} steel — " + if (bad == 0) "all ✔" else "$bad ✘"
            } catch (e: Exception) { status = "Design: ${e.message}" } finally { busy = false }
        }
    }
    LaunchedEffect(mode, results) { if (mode == Mode.DESIGN && results != null && rcc == null) runDesign() }

    fun report() {
        val res = results ?: run { status = "Analysis not ready"; return }
        busy = true
        scope.launch {
            try {
                val doc = withContext(Dispatchers.Default) {
                    com.structura.core.fem.FemReportDoc.build(model, res, SteelModelCheck.check(model, res), ResultCheck.review(model, res))
                }
                com.structura.app.report.PdfExport.share(context, doc)
            } catch (e: Exception) { status = "Report: ${e.message}" } finally { busy = false }
        }
    }

    fun onEditTap(t: TapInfo) {
        val E = ModelEditor
        val proto = model.members.lastOrNull()
        when (tool) {
            DrawTool.SELECT -> {
                sel = when {
                    t.node != null -> sel.copy(nodes = if (t.node in sel.nodes) sel.nodes - t.node else sel.nodes + t.node)
                    t.member != null -> sel.copy(members = if (t.member in sel.members) sel.members - t.member else sel.members + t.member)
                    else -> sel
                }
                status = "${sel.members.size} members, ${sel.nodes.size} nodes selected"
            }
            DrawTool.NODE -> t.planePoint?.let { p -> val (m, id) = E.addNode(model, p); if (m !== model) commit(m, "Node N$id") } ?: run { status = "Turn the view — the work plane is edge-on" }
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
                val want = if (tool == DrawTool.FIX) ModelEditor.SupportType.FIXED else ModelEditor.SupportType.PINNED
                val now = E.supportType(model, id)
                commit(E.setSupport(model, id, if (now == want) ModelEditor.SupportType.NONE else want), if (now == want) "Support removed at N$id" else "${want.name.lowercase()} support at N$id")
            }
            DrawTool.LOAD -> t.node?.let { id -> val v = num(loadVal); commit(E.addNodalLoad(model, id, Vec3(0.0, 0.0, v * Units.force), loadCase), "$loadCase ${fmt1(v)} at N$id") }
            DrawTool.UDL -> t.member?.let { id -> val v = num(loadVal); commit(E.addMemberUdl(model, id, LoadDir.GLOBAL_Z, v * Units.udl, loadCase), "$loadCase UDL ${fmt1(v)} on M$id") }
            DrawTool.DELETE -> when {
                t.node != null -> commit(E.deleteNode(model, t.node), "N${t.node} deleted")
                t.member != null -> commit(E.deleteMember(model, t.member), "M${t.member} deleted")
                else -> {}
            }
        }
    }

    fun onTap(t: TapInfo) {
        when (mode) {
            Mode.EDIT -> onEditTap(t)
            Mode.LOADS -> {
                val v = num(loadVal)
                if (onMembers) t.member?.let { id -> commit(ModelEditor.addMemberUdl(model, id, LoadDir.GLOBAL_Z, v * Units.udl, loadCase), "$loadCase UDL ${fmt1(v)} on M$id") }
                    ?: run { status = "Tap a member to add the UDL" }
                else t.node?.let { id -> commit(ModelEditor.addNodalLoad(model, id, Vec3(0.0, 0.0, v * Units.force), loadCase), "$loadCase ${fmt1(v)} at N$id") }
                    ?: run { status = "Tap a node to add the load" }
            }
            else -> {
                inspN = t.node; inspM = if (t.node == null) t.member else null
                if (inspM != null || inspN != null) inspTab = when (mode) { Mode.DESIGN -> 2; Mode.ANALYSE -> 1; else -> 0 }
            }
        }
    }

    val shown = results?.getOrNull(combo)
    val util: Map<Int, Double>? = if (mode == Mode.DESIGN && rcc != null) (rcc!!.associate { it.member to it.utilisation } + steel.associate { it.member to it.utilisation }) else null

    Column(Modifier.fillMaxSize().background(Paper).statusBarsPadding().navigationBarsPadding()) {
        // ---------------- top bar
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(com.structura.app.R.drawable.structura_logo), "Structura AI", Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Projects") { onOpen(Routes.PROJECTS) }) {
                Text((com.structura.app.project.ProjectStore.current?.name ?: model.title) + "  ▾", color = InkB, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                Text("${model.members.size} members · ${model.nodes.size} nodes · ${model.supports.size} supports · ${model.loadCases.joinToString(" ")}", color = Grey, fontSize = 11.sp, maxLines = 1)
            }
            Pill(if (Units.imperial) "ft·kip" else "m·kN", false) { Units.set(context, !Units.imperial) }
            Spacer(Modifier.width(6.dp))
            Box {
                Pill("⋯", false) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Projects") }, { menu = false; onOpen(Routes.PROJECTS) })
                    DropdownMenuItem({ Text("PDF report (analysis + design)") }, { menu = false; report() })
                    DropdownMenuItem({ Text("Validation list") }, { menu = false; if (validation == null) { mode = Mode.DESIGN }; showValidation = true })
                    DropdownMenuItem({ Text("3D Studio (panels, floor loads, modal, buckling)") }, { menu = false; onOpen(Routes.STUDIO) })
                    DropdownMenuItem({ Text("Frame wizard / templates") }, { menu = false; onOpen(Routes.FEM) })
                    Phase.entries.forEach { p -> DropdownMenuItem({ Text("${p.no}  ${p.title}") }, { menu = false; sheet = p }) }
                    DropdownMenuItem({ Text("Indian Standards") }, { menu = false; onOpen(Routes.STANDARDS) })
                }
            }
        }
        // ---------------- mode tabs
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).border(BorderStroke(1.dp, InkB), RoundedCornerShape(10.dp))) {
            Mode.entries.forEachIndexed { k, md ->
                val on = md == mode
                Box(Modifier.weight(1f).height(38.dp).background(if (on) InkB else Paper)
                    .clickable(role = Role.Tab) { mode = md; pending = null; hint = false; if (md != Mode.EDIT) sel = ModelOps.Sel()
                        if (md == Mode.EDIT) { camera.front(); plane = Plane.XZ } },
                    contentAlignment = Alignment.Center) {
                    Text(md.label, color = if (on) Paper else InkB, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
                if (k < Mode.entries.size - 1) Box(Modifier.width(1.dp).height(38.dp).background(InkB))
            }
        }

        // ---------------- canvas
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val analyse = mode == Mode.ANALYSE
            FrameView3D(
                model, if (analyse) shown?.second else null, if (analyse) view else ResultView.MODEL, Modifier.fillMaxSize(), animate = true, camera = camera,
                memberUtil = util, dark = false, mono = true,
                loadCase = if (mode == Mode.LOADS) loadCase else "", reveal = reveal.value,
                doubleTapFit = mode != Mode.EDIT && mode != Mode.LOADS, onTouch = { hint = false },
                workPlane = if (mode == Mode.EDIT) WorkPlane(plane, 0.0, snap, visible = true) else null,
                onTap = { onTap(it) },
                selectedNode = inspN, selectedMember = inspM, pendingNode = pending,
                selectedNodes = sel.nodes, selectedMembers = sel.members,
            )
            // caption
            val caption = when (mode) {
                Mode.MODEL -> "Tap a member or node to inspect"
                Mode.EDIT -> tool.hint
                Mode.LOADS -> "Case $loadCase · tap ${if (onMembers) "members for a UDL" else "nodes for a load"}"
                Mode.ANALYSE -> analysisError ?: shown?.let { (c, r) -> "${view.label} · ${c.name}" + if (view == ResultView.DEFORMED) " · δmax ${fmt1(max(r.maxDisplacement().second, r.maxMemberDeflection()) * 1000)} mm" else "" } ?: "Analysing…"
                Mode.DESIGN -> if (busy) "Designing…" else rcc?.let { "Line weight = utilisation η · ✘ = fails · tap a member" } ?: (analysisError ?: "Analysing…")
            }
            Text(caption, color = InkB, fontSize = 12.sp, maxLines = 2, modifier = Modifier.align(Alignment.TopStart).padding(start = if (mode == Mode.MODEL) 64.dp else 12.dp, top = 8.dp, end = 64.dp)
                .background(Paper.copy(alpha = 0.85f)).padding(horizontal = 4.dp, vertical = 2.dp))
            // view buttons
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Square("3D") { camera.iso() }; Square("XY") { camera.top(); if (mode == Mode.EDIT) plane = Plane.XY }
                Square("XZ") { camera.front(); if (mode == Mode.EDIT) plane = Plane.XZ }; Square("YZ") { camera.right(); if (mode == Mode.EDIT) plane = Plane.YZ }
                Square("⤢") { camera.fit() }
            }
            // phase rail (model mode)
            if (mode == Mode.MODEL) Column(Modifier.align(Alignment.CenterStart).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Phase.entries.forEach { p -> RailButton(p, sheet == p) { sheet = p } }
            }
            androidx.compose.animation.AnimatedVisibility(hint && mode == Mode.MODEL, Modifier.align(Alignment.Center).padding(horizontal = 70.dp),
                enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                Text("Drag to rotate · two fingers pan & zoom · double-tap to fit", color = Paper, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(InkB.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            // contextual tool strip
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(12.dp))
                .background(Paper.copy(alpha = 0.96f)).border(BorderStroke(1.dp, Hair), RoundedCornerShape(12.dp)).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (mode) {
                    Mode.MODEL -> Strip {
                        Pill("✎ Edit", false) { mode = Mode.EDIT; camera.front(); plane = Plane.XZ }
                        Pill("↓ Loads", false) { mode = Mode.LOADS }
                        Pill("▶ Results", false) { mode = Mode.ANALYSE }
                        Pill("✔ Design", false) { mode = Mode.DESIGN }
                        Pill("PDF", false) { report() }
                    }
                    Mode.EDIT -> {
                        Strip { DrawTool.entries.filter { it != DrawTool.LOAD && it != DrawTool.UDL }.forEach { t -> Pill(t.label, tool == t) { tool = t; pending = null } } }
                        if (tool == DrawTool.SELECT) {
                            Strip {
                                Text("${sel.members.size}M ${sel.nodes.size}N", color = InkB, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                Pill("All", false) { sel = ModelOps.all(model) }
                                Pill("None", false) { sel = ModelOps.Sel() }
                                Pill("Invert", false) { sel = ModelOps.invert(model, sel) }
                                Pill("Columns", false) { sel = sel.copy(members = sel.members + ModelOps.columns(model)) }
                                Pill("Beams", false) { sel = sel.copy(members = sel.members + ModelOps.beams(model)) }
                                Pill("Braces", false) { sel = sel.copy(members = sel.members + ModelOps.braces(model)) }
                                Pill("Supports", false) { sel = sel.copy(nodes = sel.nodes + model.supports.map { it.node }) }
                                Pill("Level", false) {
                                    val z = (sel.nodes.firstOrNull() ?: sel.members.firstOrNull()?.let { id -> model.members.first { it.id == id }.i })?.let { model.node(it).z }
                                    if (z == null) status = "Select a node or beam first" else { val l = ModelOps.atLevel(model, z); sel = ModelOps.Sel(sel.nodes + l.nodes, sel.members + l.members) }
                                }
                                Pill("Similar", false) { sel.members.firstOrNull()?.let { sel = sel.copy(members = sel.members + ModelOps.similar(model, it)) } ?: run { status = "Select a member first" } }
                            }
                            if (!sel.isEmpty) Strip {
                                EditOp.entries.forEach { o -> Pill(o.label, false) {
                                    when (o) {
                                        EditOp.DELETE -> { commit(ModelOps.delete(model, sel), "${sel.count} item(s) deleted"); sel = ModelOps.Sel() }
                                        EditOp.DESIGN -> { mode = Mode.DESIGN; if (rcc == null) runDesign() }
                                        else -> { opSel = sel; op = o }
                                    }
                                } }
                            }
                        }
                        Strip {
                            Pill("↶", false) { undo.lastOrNull()?.let { prev -> redo = redo + model; undo = undo.dropLast(1); setModel(prev, "Undo") } }
                            Pill("↷", false) { redo.lastOrNull()?.let { nxt -> undo = undo + model; redo = redo.dropLast(1); setModel(nxt, "Redo") } }
                            Plane.entries.forEach { pl -> Pill("Plane ${pl.name}", plane == pl) {
                                plane = pl; when (pl) { Plane.XY -> camera.top(); Plane.XZ -> camera.front(); Plane.YZ -> camera.right() } } }
                            listOf(0.5, 1.0, 1.5, 3.0).forEach { sn -> Pill("$sn m", snap == sn) { snap = sn } }
                        }
                    }
                    Mode.LOADS -> {
                        Strip {
                            Pill("UDL on members", onMembers) { onMembers = true }
                            Pill("Load at nodes", !onMembers) { onMembers = false }
                            listOf(LoadCase.DL, LoadCase.LL, LoadCase.WL).forEach { c -> Pill(c, loadCase == c) { loadCase = c } }
                            NumberBox(loadVal) { loadVal = it }
                            Text(if (onMembers) (if (Units.imperial) "kip/ft" else "kN/m") else (if (Units.imperial) "kip" else "kN"), color = Grey, fontSize = 11.sp)
                        }
                        Strip {
                            Pill(if (model.selfWeight) "Self-weight ✓" else "Self-weight ✗", model.selfWeight) { commit(model.copy(selfWeight = !model.selfWeight), "Self-weight ${if (!model.selfWeight) "on" else "off"} (IS 875-1)") }
                            Pill("Clear $loadCase", false) { commit(model.copy(nodalLoads = model.nodalLoads.filter { it.case != loadCase }, memberLoads = model.memberLoads.filter { it.case != loadCase },
                                pointLoads = model.pointLoads.filter { it.case != loadCase }), "$loadCase loads cleared") }
                            Pill("Floor / wind / seismic ›", false) { DesignSession.studioPanel = 3; onOpen(Routes.STUDIO) }
                            Text("− = downward (global Z)", color = Grey, fontSize = 11.sp)
                        }
                    }
                    Mode.ANALYSE -> {
                        Strip { listOf(ResultView.DEFORMED, ResultView.N, ResultView.VZ, ResultView.MY, ResultView.MZ).forEach { v -> Pill(v.label, view == v) { view = v } } }
                        results?.let { rs -> if (rs.isNotEmpty()) Strip {
                            Pill("◂", false) { combo = (combo - 1 + rs.size) % rs.size }
                            Text("${combo + 1}/${rs.size}  ${rs[combo].first.name}", color = InkB, fontSize = 12.sp, maxLines = 1)
                            Pill("▸", false) { combo = (combo + 1) % rs.size }
                        } }
                    }
                    Mode.DESIGN -> Strip {
                        Pill(if (busy) "Designing…" else "↻ Re-design", false) { if (!busy) { rcc = null; runDesign() } }
                        validation?.let { v -> Pill("Validation ✘${v.errors} ⚠${v.warnings}", v.errors > 0) { showValidation = true } }
                        rcc?.let { r -> Text("RCC ${r.size} · steel ${steel.size} · ✘ ${r.count { !it.ok } + steel.count { !it.ok }}", color = InkB, fontSize = 12.sp) }
                        Pill("PDF", false) { report() }
                    }
                }
            }
        }

        // ---------------- inspector
        val im = inspM?.let { id -> model.members.firstOrNull { it.id == id } }
        val inode = inspN?.let { id -> model.nodes.firstOrNull { it.id == id } }
        if (im != null || inode != null) Column(Modifier.fillMaxWidth().heightIn(max = 330.dp).border(BorderStroke(1.dp, InkB)).background(Paper)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val title = if (im != null) {
                    val len = (model.node(im.j).pos - model.node(im.i).pos).length
                    val kind = rcc?.firstOrNull { it.member == im.id }?.kind ?: if (im.id in ModelOps.columns(model)) "Column" else if (im.id in ModelOps.beams(model)) "Beam" else "Member"
                    "M${im.id} · $kind · ${im.section.name} · ${im.material.name} · ${fmt2(len)} m"
                } else "N${inode!!.id} · (${fmt2(inode.x)}, ${fmt2(inode.y)}, ${fmt2(inode.z)}) m"
                Text(title, color = InkB, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Pill("✕", false) { inspM = null; inspN = null }
            }
            if (im != null) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Properties", "Forces", "Design").forEachIndexed { k, t -> Pill(t, inspTab == k) { inspTab = k; if (k == 2 && rcc == null) runDesign() } }
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (inspTab) {
                        0 -> {
                            Kv("Nodes", "N${im.i} → N${im.j}")
                            Kv("Section", "${im.section.name} (b ${fmt0(im.section.b * 1000)}, h ${fmt0(im.section.h * 1000)} mm, A ${fmt0(im.section.a * 1e6)} mm²)")
                            Kv("Material", "${im.material.name} · E ${fmt0(im.material.e / 1000)} MPa · γ ${fmt1(im.material.gamma)} kN/m³")
                            Strip(pad = false) {
                                Pill("▭ Change section", false) { opSel = ModelOps.Sel(members = setOf(im.id)); op = EditOp.SECTION }
                                Pill("Hinge i", im.hingeI) { commit(ModelEditor.updateMember(model, im.id) { it.copy(hingeI = !it.hingeI) }, "M${im.id} hinge at i") }
                                Pill("Hinge j", im.hingeJ) { commit(ModelEditor.updateMember(model, im.id) { it.copy(hingeJ = !it.hingeJ) }, "M${im.id} hinge at j") }
                                Pill("Truss", im.truss) { commit(ModelEditor.updateMember(model, im.id) { it.copy(truss = !it.truss) }, "M${im.id} truss toggled") }
                            }
                            val ml = model.memberLoads.filter { it.member == im.id } + emptyList()
                            Kv("Loads", if (ml.isEmpty()) "none" else ml.joinToString { "${it.case} ${fmt1(it.w)} kN/m" })
                            Strip(pad = false) {
                                Pill("⧉ Copy", false) { opSel = ModelOps.Sel(members = setOf(im.id)); op = EditOp.COPY }
                                Pill("⇄ Move", false) { opSel = ModelOps.Sel(members = setOf(im.id)); op = EditOp.MOVE }
                                Pill("⫶ Divide", false) { opSel = ModelOps.Sel(members = setOf(im.id)); op = EditOp.SPLIT }
                                if (ml.isNotEmpty()) Pill("Clear loads", false) { commit(ModelEditor.clearLoadsOnMember(model, im.id), "Loads on M${im.id} cleared") }
                                Pill("🗑 Delete", false) { commit(ModelEditor.deleteMember(model, im.id), "M${im.id} deleted"); inspM = null }
                            }
                        }
                        1 -> {
                            val rs = results
                            if (rs == null) Text(analysisError ?: "Analysing…", color = Grey, fontSize = 12.sp) else {
                                val e = remember(im.id, rs) { RccModelDesign.envelope(im.id, rs) }
                                Text("Envelope over ${rs.size} combinations (IS 456 Table 18 / IS 875-5)", color = Grey, fontSize = 11.sp)
                                Kv("Axial compression", "${fmt1(e.comp)} kN  (${e.govN})")
                                Kv("Axial tension", "${fmt1(e.tens)} kN")
                                Kv("Shear Vz / Vy", "${fmt1(e.vz)} / ${fmt1(e.vy)} kN  (${e.govV})")
                                Kv("Moment My +sagging", "${fmt1(e.myPos)} kN·m")
                                Kv("Moment My −hogging", "${fmt1(e.myNeg)} kN·m  (${e.govMy})")
                                Kv("Moment Mz (minor)", "${fmt1(e.mz)} kN·m")
                                Kv("Torsion", "${fmt1(e.mt)} kN·m")
                                shown?.let { (c, r) -> val d = r.deformedShape(im.id, 1.0, 12); val z = r.deformedShape(im.id, 0.0, 12)
                                    Kv("Max displacement (${c.name})", "${fmt1(d.indices.maxOf { (d[it] - z[it]).length } * 1000)} mm") }
                            }
                        }
                        else -> {
                            val rd = rcc?.firstOrNull { it.member == im.id }
                            val sd = steel.firstOrNull { it.member == im.id }
                            when {
                                rcc == null -> Text(if (busy) "Designing…" else "Tap ↻ to design", color = Grey, fontSize = 12.sp)
                                rd != null -> {
                                    Text(rd.summary, color = InkB, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    if (rd.kind == "Beam") { Kv("Bottom (sagging)", rd.bottom); Kv("Top (hogging)", rd.top); Kv("Stirrups", rd.stirrups); Kv("l/d basis", rd.support) }
                                    rd.checks.forEach { CheckRow(it) }
                                    Strip(pad = false) {
                                        if (rd.kind == "Beam") Pill("Full beam design ›", false) { DesignSession.prefill = DesignSession.Prefill(rd.b, rd.d, rd.span, max(rd.mu, rd.muHog), rd.vu); onOpen(Routes.RCC_BEAM) }
                                        else Pill("Column designer ›", false) { onOpen(Routes.RCC_COLUMN) }
                                    }
                                }
                                sd != null -> {
                                    Text("Steel ${sd.section} ${sd.grade} · η ${fmt2(sd.utilisation)} (${sd.combination}) ${if (sd.ok) "✔" else "✘"}", color = InkB, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    sd.result.checks.forEach { CheckRow(it) }
                                    Pill("Steel designer ›", false) { onOpen(Routes.STEEL) }
                                }
                                else -> Text("Not designed: use a rectangular RCC section (M-grade) or an IS 808 / hollow steel section.", color = Grey, fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else if (inode != null) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val now = ModelEditor.supportType(model, inode.id)
                    Strip(pad = false) {
                        Text("Support", color = Grey, fontSize = 12.sp)
                        ModelEditor.SupportType.entries.forEach { t -> Pill(t.name.lowercase().replace('_', ' '), now == t) { commit(ModelEditor.setSupport(model, inode.id, t), "N${inode.id}: ${t.name.lowercase()}") } }
                    }
                    val nl = model.nodalLoads.filter { it.node == inode.id }
                    Kv("Loads", if (nl.isEmpty()) "none" else nl.joinToString { "${it.case} (${fmt1(it.values[0])}, ${fmt1(it.values[1])}, ${fmt1(it.values[2])}) kN" })
                    shown?.let { (c, r) ->
                        r.nodeDisplacements[inode.id]?.let { d -> Kv("Displacement (${c.name})", "ux ${fmt2(d[0] * 1000)}, uy ${fmt2(d[1] * 1000)}, uz ${fmt2(d[2] * 1000)} mm") }
                        r.reactions[inode.id]?.let { f -> Kv("Reaction", "Fx ${fmt1(f[0])}, Fy ${fmt1(f[1])}, Fz ${fmt1(f[2])} kN; Mx ${fmt1(f[3])}, My ${fmt1(f[4])}, Mz ${fmt1(f[5])} kN·m") }
                    }
                    Strip(pad = false) {
                        Pill("⇄ Move", false) { opSel = ModelOps.Sel(nodes = setOf(inode.id)); op = EditOp.MOVE }
                        if (nl.isNotEmpty()) Pill("Clear loads", false) { commit(ModelEditor.clearLoadsAtNode(model, inode.id), "Loads at N${inode.id} cleared") }
                        Pill("🗑 Delete", false) { commit(ModelEditor.deleteNode(model, inode.id), "N${inode.id} deleted"); inspN = null }
                    }
                }
            }
        }

        // ---------------- status line
        Row(Modifier.fillMaxWidth().border(BorderStroke(1.dp, Hair)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            val base = when {
                busy -> "Working…"
                status.isNotEmpty() -> status
                analysisError != null -> analysisError!!
                results != null -> "Analysed: ${results!!.size} combinations · ΣF residual ${"%.0e".format(results!!.maxOf { (_, r) -> r.equilibriumError.maxOf { abs(it) } })} kN"
                else -> "Analysing…"
            }
            Text(base, color = InkB, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
            validation?.let { v -> Text("✘${v.errors} ⚠${v.warnings}", color = InkB, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { showValidation = true }.padding(start = 8.dp)) }
        }
    }

    op?.let { o ->
        EditOpDialog(o, model, opSel, onDismiss = { op = null }) { m2, newSel, msg -> commit(m2, msg); if (mode == Mode.EDIT) newSel?.let { sel = it }; op = null }
    }

    sheet?.let { p ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Paper) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text("${p.no}  ", color = Grey, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Text(p.title, color = InkB, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text(p.codes, color = Grey, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Phase.entries.forEach { q -> Pill("${q.no} ${q.label}", q == p) { sheet = q } }
                }
                tools(p).forEach { t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { sheet = null; if (t.panel != 0) DesignSession.studioPanel = t.panel; onOpen(t.route) }
                        .border(BorderStroke(1.dp, Hair), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(t.label, color = InkB, fontSize = 15.sp); Text(t.note, color = Grey, fontSize = 11.sp) }
                        Text("›", color = InkB, fontSize = 20.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    if (showValidation) ModalBottomSheet(onDismissRequest = { showValidation = false }, containerColor = Paper) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Validation — model, analysis and member design", color = InkB, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            val v = validation
            if (v == null) Text(if (busy) "Designing…" else "Open the Design tab to run the design and validation.", color = Grey, fontSize = 13.sp)
            else {
                Text("${v.errors} error(s) · ${v.warnings} warning(s) · tap an item to show the member", color = Grey, fontSize = 12.sp)
                v.items.forEach { it2 ->
                    val mark = when (it2.level) { Validation.Level.ERROR -> "✘"; Validation.Level.WARN -> "⚠"; Validation.Level.INFO -> "ℹ"; Validation.Level.OK -> "✔" }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(BorderStroke(1.dp, if (it2.level == Validation.Level.ERROR) InkB else Hair), RoundedCornerShape(8.dp))
                        .clickable { it2.member?.let { m -> inspM = m; inspN = null; inspTab = 2; showValidation = false } }.padding(10.dp)) {
                        Text(mark, color = InkB, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it2.topic, color = InkB, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(it2.message, color = InkB, fontSize = 12.sp)
                            if (it2.clause.isNotEmpty()) Text(it2.clause, color = Grey, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

private fun num(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
private fun fmt0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)
private fun fmt1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
private fun fmt2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

@Composable
private fun Strip(pad: Boolean = true, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = if (pad) 8.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun Square(text: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(Paper).border(BorderStroke(1.dp, InkB), RoundedCornerShape(8.dp))
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) { Text(text, color = InkB, fontSize = 12.sp) }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, color = Grey, fontSize = 12.sp, modifier = Modifier.width(150.dp))
        Text(v, color = InkB, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CheckRow(c: com.structura.core.rcc.DesignCheck) {
    val mark = when (c.status) { com.structura.core.rcc.Status.PASS -> "✔"; com.structura.core.rcc.Status.FAIL -> "✘"; com.structura.core.rcc.Status.INFO -> "ℹ" }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(mark, color = InkB, fontSize = 13.sp, fontWeight = if (c.status == com.structura.core.rcc.Status.FAIL) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text("${c.item}: ${c.value}", color = InkB, fontSize = 12.sp, fontWeight = if (c.status == com.structura.core.rcc.Status.FAIL) FontWeight.Bold else FontWeight.Normal)
            Text("${c.requirement} · ${c.clause}", color = Grey, fontSize = 11.sp)
        }
    }
}

@Composable
private fun NumberBox(value: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(value, onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = InkB, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(InkB),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        modifier = Modifier.width(68.dp).border(BorderStroke(1.dp, InkB), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 7.dp))
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) InkB else Paper)
            .border(BorderStroke(1.dp, InkB), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) Paper else InkB, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun RailButton(p: Phase, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.size(48.dp, 50.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) InkB else Paper.copy(alpha = 0.94f))
            .border(BorderStroke(1.dp, InkB), RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = p.title, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        PhaseIcon(p, if (selected) Paper else InkB)
        Text(p.label, color = if (selected) Paper else InkB, fontSize = 9.sp)
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
