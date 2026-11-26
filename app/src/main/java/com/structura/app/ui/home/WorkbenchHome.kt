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
    var status by remember(model) { mutableStateOf("Σ static check —") }
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
            FrameView3D(
                model, if (view == ResultView.MODEL) null else shown?.second, view, Modifier.fillMaxSize(), animate = true, camera = camera,
                memberUtil = if (view == ResultView.MODEL) util else null, dark = true, loadCase = caseSel, reveal = reveal.value,
                doubleTapFit = true, onTouch = { spin = false; hint = false },
            )
            Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("◇ 3D", true) { camera.iso() }
                Pill("XY", false) { camera.top() }
                Pill("XZ", false) { camera.front() }
                Pill("YZ", false) { camera.right() }
            }
            Column(Modifier.align(Alignment.CenterStart).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DESIGN", color = Muted, fontSize = 10.sp)
                RailButton(Phase.RCC, sheet == Phase.RCC) { sheet = Phase.RCC }
                RailButton(Phase.STEEL, sheet == Phase.STEEL) { sheet = Phase.STEEL }
                RailButton(Phase.ROOF, sheet == Phase.ROOF) { sheet = Phase.ROOF }
                RailButton(Phase.WALL, sheet == Phase.WALL) { sheet = Phase.WALL }
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MODEL", color = Muted, fontSize = 10.sp)
                RailButton(Phase.FEM, sheet == Phase.FEM) { sheet = Phase.FEM }
                RailButton(Phase.PLAN, sheet == Phase.PLAN) { sheet = Phase.PLAN }
                RailButton(Phase.STAAD, sheet == Phase.STAAD) { sheet = Phase.STAAD }
            }
            val caption = when {
                view != ResultView.MODEL && shown != null -> "${view.label} · ${shown.first.name}"
                util != null -> "η · IS 800 · all combinations"
                caseSel == null && cases.isNotEmpty() -> "Loads · all cases"
                caseSel != null && caseSel != "" -> "Loads · $caseSel"
                else -> ""
            }
            if (caption.isNotEmpty()) Text(caption, color = Orange, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 52.dp))
            androidx.compose.animation.AnimatedVisibility(hint, Modifier.align(Alignment.Center),
                enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                Text("1 finger rotate · 2 fingers pan & zoom · double-tap to fit", color = Ink.copy(alpha = 0.85f), fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Bar.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            // bottom viewport controls: load case / result view / combination / fit / spin
            Row(
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
            Action("Design", primary = false, Modifier.weight(1f), icon = { c -> BarsIcon(c) }) { sheet = Phase.RCC }
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
