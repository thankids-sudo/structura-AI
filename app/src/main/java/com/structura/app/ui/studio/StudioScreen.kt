package com.structura.app.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.structura.app.DesignSession
import com.structura.app.shareTextFile
import com.structura.app.ui.fem.Camera
import com.structura.app.ui.fem.FrameView3D
import com.structura.app.ui.fem.Plane
import com.structura.app.ui.fem.ResultView
import com.structura.app.ui.fem.ResultsSummary
import com.structura.app.ui.fem.TapInfo
import com.structura.app.ui.fem.WorkPlane
import com.structura.app.ui.theme.FailRed
import com.structura.core.fem.BeamTheory
import com.structura.core.fem.Buckling
import com.structura.core.fem.BucklingResult
import com.structura.core.fem.FemReport
import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameResults
import com.structura.core.fem.FrameSolver
import com.structura.core.fem.Generators
import com.structura.core.fem.HtmlReport
import com.structura.core.fem.LoadCase
import com.structura.core.fem.LoadCombination
import com.structura.core.fem.LoadDir
import com.structura.core.fem.Material
import com.structura.core.fem.MemberEnvelope
import com.structura.core.fem.Modal
import com.structura.core.fem.ModalResult
import com.structura.core.fem.ModelEditor
import com.structura.core.fem.ModelEditor.SupportType
import com.structura.core.fem.Section
import com.structura.core.fem.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class Tool(val label: String, val hint: String) {
    SELECT("Select", "Tap a node or member to edit it. Drag to orbit, pinch to zoom."),
    NODE("Node", "Tap on the grid to place a node (snapped to the work plane)."),
    MEMBER("Member", "Tap start node / grid point, then end. Keeps chaining; tap the last node again to stop."),
    SPLIT("½ Split", "Tap a member to split it at mid-length."),
    FIX("Fix", "Tap a node: fixed support (tap again to remove)."),
    PIN("Pin", "Tap a node: pinned support (tap again to remove)."),
    LOAD("Load", "Tap a node to add the nodal load Fx, Fy, Fz in the chosen case."),
    UDL("Member load", "Tap a member to add a uniform load q along global Z (− = downward)."),
    DELETE("Delete", "Tap a node or member to delete it."),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(onBack: () -> Unit, onWizard: () -> Unit, onDesignBeam: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera = remember { Camera() }

    // ---- model + undo/redo
    val start = remember { DesignSession.takeStudioModel() ?: ModelEditor.empty("Structura AI project") }
    var history by remember { mutableStateOf(listOf(start)) }
    var pos by remember { mutableStateOf(0) }
    val model = history[pos]

    // ---- editing state
    var tool by remember { mutableStateOf(Tool.SELECT) }
    var plane by remember { mutableStateOf(Plane.XY) }
    var offset by remember { mutableStateOf("0") }
    var snapTxt by remember { mutableStateOf("1.0") }
    val spec = remember { SectionSpec() }
    var fx by remember { mutableStateOf("0") }
    var fy by remember { mutableStateOf("0") }
    var fz by remember { mutableStateOf("-10") }
    var q by remember { mutableStateOf("-10") }
    var case by remember { mutableStateOf(LoadCase.DL) }
    var pending by remember { mutableStateOf<Int?>(null) }
    var selNode by remember { mutableStateOf<Int?>(null) }
    var selMember by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // ---- analysis state
    var results by remember { mutableStateOf<FrameResults?>(null) }
    var combos by remember { mutableStateOf(listOf<LoadCombination>()) }
    var comboIdx by remember { mutableStateOf(0) }
    var env by remember { mutableStateOf<List<MemberEnvelope>>(emptyList()) }
    var buckling by remember { mutableStateOf<BucklingResult?>(null) }
    var view by remember { mutableStateOf(ResultView.MODEL) }
    var busy by remember { mutableStateOf(false) }
    var animate by remember { mutableStateOf(true) }
    var modal by remember { mutableStateOf<ModalResult?>(null) }
    var modeIdx by remember { mutableStateOf(0) }
    var llMass by remember { mutableStateOf(0.25) }

    fun d(s: String, def: Double = 0.0) = s.replace(',', '.').toDoubleOrNull() ?: def

    fun commit(m: FrameModel, msg: String? = null) {
        history = (history.take(pos + 1) + m).takeLast(80)
        pos = history.size - 1
        results = null; buckling = null; env = emptyList(); modal = null
        if (view != ResultView.MODEL) view = ResultView.MODEL
        message = msg
    }

    val wp = WorkPlane(plane, d(offset), d(snapTxt, 1.0).coerceAtLeast(0.05), visible = tool != Tool.SELECT || model.nodes.isEmpty())

    fun material() = spec.material()
    fun section() = spec.section()

    fun onTap(t: TapInfo) {
        when (tool) {
            Tool.SELECT -> { selNode = t.node; selMember = if (t.node == null) t.member else null }
            Tool.NODE -> t.planePoint?.let { p ->
                val (m, id) = ModelEditor.addNode(model, p)
                if (m !== model) commit(m, "Node N$id at (${fmt(p.x)}, ${fmt(p.y)}, ${fmt(p.z)})")
            } ?: run { message = "Rotate the view — the work plane is seen edge-on." }
            Tool.MEMBER -> {
                var m = model
                val target = t.node ?: t.planePoint?.let { p -> ModelEditor.addNode(m, p).also { m = it.first }.second }
                if (target == null) { message = "Tap a node or the grid."; return }
                val p0 = pending
                if (p0 == null) {
                    if (m !== model) commit(m)
                    pending = target; message = "Start N$target — tap the end point"
                } else if (p0 == target) {
                    pending = null; message = "Member chain finished"
                } else {
                    val sec = runCatching { section() }.getOrElse { message = it.message; return }
                    val (m2, mid) = ModelEditor.addMember(m, p0, target, material(), sec)
                    commit(m2, if (mid != null) "Member M$mid: N$p0 → N$target" else "Member already exists")
                    pending = target
                }
            }
            Tool.SPLIT -> t.member?.let { id ->
                val (m, nid) = ModelEditor.splitMember(model, id)
                commit(m, "M$id split at N$nid")
            }
            Tool.FIX, Tool.PIN -> t.node?.let { id ->
                val want = if (tool == Tool.FIX) SupportType.FIXED else SupportType.PINNED
                val now = ModelEditor.supportType(model, id)
                commit(ModelEditor.setSupport(model, id, if (now == want) SupportType.NONE else want),
                    if (now == want) "Support removed at N$id" else "${want.name.lowercase()} support at N$id")
            }
            Tool.LOAD -> t.node?.let { id ->
                commit(ModelEditor.addNodalLoad(model, id, Vec3(d(fx), d(fy), d(fz)), case), "$case nodal load at N$id")
            }
            Tool.UDL -> t.member?.let { id ->
                commit(ModelEditor.addMemberUdl(model, id, LoadDir.GLOBAL_Z, d(q), case), "$case UDL ${fmt(d(q))} kN/m on M$id")
            }
            Tool.DELETE -> when {
                t.node != null -> commit(ModelEditor.deleteNode(model, t.node), "N${t.node} deleted")
                t.member != null -> commit(ModelEditor.deleteMember(model, t.member), "M${t.member} deleted")
            }
        }
    }

    fun analyse() {
        val issues = ModelEditor.check(model).filter { !it.startsWith("Zero") }
        val blocking = issues.filter { it.contains("no members") || it.contains("No supports") || it.contains("no nodes") }
        if (blocking.isNotEmpty()) { message = blocking.joinToString("\n"); return }
        busy = true; message = null
        scope.launch {
            try {
                val withLive = LoadCase.LL in model.loadCases
                val withLat = LoadCase.WL in model.loadCases
                val uls = LoadCombination.isLimitState(withLat)
                val list = uls + LoadCombination.cases(withLive, withLat)
                val (first, envelope) = withContext(Dispatchers.Default) {
                    val all = FrameSolver.analyseAll(model, uls).map { it.second }
                    all.first() to FemReport.envelope(all)
                }
                combos = list; comboIdx = 0; env = envelope; results = first
                view = ResultView.DEFORMED
                if (issues.isNotEmpty()) message = "Model check: " + issues.joinToString("; ")
            } catch (e: Exception) {
                message = e.message
            } finally { busy = false }
        }
    }

    fun selectCombo(k: Int) {
        comboIdx = k
        val c = combos.getOrNull(k) ?: return
        scope.launch {
            try { results = withContext(Dispatchers.Default) { FrameSolver.analyse(model, c) } } catch (e: Exception) { message = e.message }
        }
    }

    fun runModal() {
        busy = true
        scope.launch {
            try {
                modal = withContext(Dispatchers.Default) { Modal.analyse(model, nModes = 6, llFactor = llMass, segments = 4) }
                modeIdx = 0
                view = ResultView.MODAL
            } catch (e: Exception) { message = e.message } finally { busy = false }
        }
    }

    fun runBuckling() {
        val c = combos.getOrNull(comboIdx) ?: LoadCombination.isLimitState(false).first()
        busy = true
        scope.launch {
            try {
                buckling = withContext(Dispatchers.Default) { Buckling.analyse(model, c, segments = 4) }
                view = ResultView.BUCKLING
            } catch (e: Exception) { message = e.message } finally { busy = false }
        }
    }

    // ---- files
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(ProjectJson.write(model).toByteArray()) }
            message = "Project saved"
        }.onFailure { message = "Save failed: ${it.message}" }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            commit(ProjectJson.read(text), "Project opened")
            camera.iso()
        }.onFailure { message = "Open failed: ${it.message}" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("3D Studio", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { if (pos > 0) { pos--; results = null; buckling = null; view = ResultView.MODEL } }, enabled = pos > 0) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                    }
                    IconButton(onClick = { if (pos < history.size - 1) { pos++; results = null; buckling = null; view = ResultView.MODEL } }, enabled = pos < history.size - 1) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 10.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { commit(ModelEditor.empty("Structura AI project"), "Blank model"); pending = null }, label = { Text("+ Blank") })
                AssistChip(onClick = {
                    commit(
                        Generators.portalFrame(listOf(4.0), listOf(3.0), Section.rect(0.3, 0.3), Section.rect(0.23, 0.45),
                            Material.concrete(25.0), 10.0, 5.0, liveUdl = 5.0),
                        "Portal frame example",
                    ); camera.iso()
                }, label = { Text("Portal") })
                AssistChip(onClick = onWizard, label = { Text("Templates…") })
                AssistChip(onClick = { saveLauncher.launch("${model.title.replace(' ', '_')}.structura.json") }, label = { Text("↓ Save project") })
                AssistChip(onClick = { openLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, label = { Text("↑ Open project") })
                AssistChip(onClick = {
                    val issues = ModelEditor.check(model)
                    message = if (issues.isEmpty()) "Model check: OK ✔" else issues.joinToString("\n")
                }, label = { Text("✓ Model check") })
                AssistChip(onClick = {
                    val html = HtmlReport.build(
                        model, results?.let { r -> (combos.getOrNull(comboIdx) ?: LoadCombination.ALL) to r }, env,
                        combos.ifEmpty { LoadCombination.isLimitState(LoadCase.WL in model.loadCases) }, buckling,
                        modal = modal,
                    )
                    shareTextFile(context, "StructuraAI_report.html", "text/html", html, "Structura AI — analysis report")
                }, label = { Text("↓ HTML report") })
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(false, { camera.iso() }, { Text("◇ 3D") })
                FilterChip(false, { camera.top(); plane = Plane.XY }, { Text("Top XY") })
                FilterChip(false, { camera.front(); plane = Plane.XZ }, { Text("Front XZ") })
                FilterChip(false, { camera.right(); plane = Plane.YZ }, { Text("Right YZ") })
                if (results != null || buckling != null || modal != null) {
                    Spacer(Modifier.padding(4.dp))
                    ResultView.entries.forEach { v ->
                        val enabled = when (v) {
                            ResultView.MODEL -> true
                            ResultView.BUCKLING -> buckling != null
                            ResultView.MODAL -> modal != null
                            else -> results != null
                        }
                        FilterChip(v == view, { view = v }, { Text(v.label) }, enabled = enabled)
                    }
                }
            }

            Card(Modifier.fillMaxWidth().height(430.dp)) {
                FrameView3D(
                    model, results, view, Modifier.fillMaxWidth().height(430.dp),
                    animate = animate,
                    selectedNode = selNode,
                    camera = camera,
                    workPlane = wp,
                    selectedMember = selMember,
                    pendingNode = pending,
                    buckling = buckling,
                    onTap = { onTap(it) },
                    modeShape = modal?.modes?.getOrNull(modeIdx)?.shape,
                    modeLabel = modal?.modes?.getOrNull(modeIdx)?.let { "Mode ${it.number}: f = ${"%.3f".format(it.frequency)} Hz, T = ${"%.3f".format(it.period)} s" } ?: "",
                )
            }
            Text(
                message ?: tool.hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (message?.contains("fail", true) == true || message?.contains("unstable", true) == true) FailRed else MaterialTheme.colorScheme.onSurface,
            )

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tool.entries.forEach { t ->
                    FilterChip(t == tool, { tool = t; pending = null; message = null }, { Text(t.label) })
                }
            }

            // context parameters
            if (tool == Tool.NODE || tool == Tool.MEMBER) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Work plane", style = MaterialTheme.typography.labelMedium)
                    Plane.entries.forEach { p -> FilterChip(p == plane, { plane = p }, { Text(p.label) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Num("Offset (m)", offset, Modifier.weight(1f)) { offset = it }
                    Num("Snap (m)", snapTxt, Modifier.weight(1f)) { snapTxt = it }
                }
                if (tool == Tool.MEMBER) SectionPicker(spec)
            }
            if (tool == Tool.LOAD || tool == Tool.UDL) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Load case", style = MaterialTheme.typography.labelMedium)
                    listOf(LoadCase.DL, LoadCase.LL, LoadCase.WL).forEach { c -> FilterChip(c == case, { case = c }, { Text(c) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (tool == Tool.LOAD) {
                        Num("Fx (kN)", fx, Modifier.weight(1f)) { fx = it }
                        Num("Fy (kN)", fy, Modifier.weight(1f)) { fy = it }
                        Num("Fz (kN)", fz, Modifier.weight(1f)) { fz = it }
                    } else Num("q along global Z (kN/m)", q, Modifier.weight(1f)) { q = it }
                }
            }

            // selection editors
            selNode?.takeIf { tool == Tool.SELECT && model.nodeIndex.containsKey(it) }?.let { id ->
                NodeEditor(model, id, onApply = { m, msg -> commit(m, msg) }, onClose = { selNode = null })
            }
            selMember?.takeIf { tool == Tool.SELECT && model.members.any { m -> m.id == it } }?.let { id ->
                MemberEditor(model, id, onApply = { m, msg -> commit(m, msg) }, onClose = { selMember = null })
            }

            // analysis
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = model.selfWeight, onCheckedChange = { commit(model.copy(selfWeight = it), if (it) "Self-weight on" else "Self-weight off") })
                Text("  Self-weight (DL)", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Switch(checked = animate, onCheckedChange = { animate = it })
                Text("  Animate", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Beam theory", style = MaterialTheme.typography.labelMedium)
                BeamTheory.entries.forEach { t ->
                    FilterChip(t == model.beamTheory, { if (t != model.beamTheory) commit(model.copy(beamTheory = t), "Beam theory: ${t.label}") }, { Text(t.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { analyse() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("▶ Analyse") }
                OutlinedButton(onClick = { runBuckling() }, enabled = !busy && model.members.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Buckling λcr") }
                OutlinedButton(onClick = { runModal() }, enabled = !busy && model.members.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("♫ Modal") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Modal mass: DL + LL ×", style = MaterialTheme.typography.labelMedium)
                listOf(0.25, 0.5).forEach { f -> FilterChip(f == llMass, { llMass = f; modal = null }, { Text("$f") }) }
                Text("(IS 1893 Table 10: 0.25 for ≤ 3 kN/m², 0.5 above)", style = MaterialTheme.typography.labelSmall)
            }
            modal?.let { md ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    md.modes.forEachIndexed { k, mo ->
                        FilterChip(k == modeIdx && view == ResultView.MODAL, { modeIdx = k; view = ResultView.MODAL }, { Text("Mode ${mo.number}: ${"%.2f".format(mo.frequency)} Hz") })
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Modal analysis — mass: ${md.massSource}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(" Mode   f (Hz)    T (s)   Mx%   My%   Mz%", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        md.modes.forEach { mo ->
                            Text(
                                String.format(Locale.US, "%4d %8.3f %8.4f %5.1f %5.1f %5.1f", mo.number, mo.frequency, mo.period,
                                    mo.participation[0] * 100, mo.participation[1] * 100, mo.participation[2] * 100),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val c = md.cumulative
                        Text(String.format(Locale.US, "Cumulative: X %.1f %%, Y %.1f %%, Z %.1f %% (IS 1893 Cl. 7.7.5.2: ≥ 90 %%)", c[0] * 100, c[1] * 100, c[2] * 100),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            buckling?.let {
                Text(
                    "First elastic buckling multiplier λcr = ${"%.3f".format(it.lambda)} for ${it.combination}" +
                        if (it.lambda in 0.0..10.0) " — below 10: consider second-order (P-Δ) effects (IS 800 Cl. 4.4.2)." else ".",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (combos.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    combos.forEachIndexed { k, c -> FilterChip(k == comboIdx, { selectCombo(k) }, { Text(c.name) }) }
                }
            }
            results?.let { r -> ResultsSummary(r, env, onDesignBeam) }
            Text(
                "${model.nodes.size} nodes · ${model.members.size} members · ${model.supports.size} supports · " +
                    "${model.nodalLoads.size + model.memberLoads.size + model.pointLoads.size} loads · undo ${pos}/${history.size - 1}",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NodeEditor(model: FrameModel, id: Int, onApply: (FrameModel, String) -> Unit, onClose: () -> Unit) {
    val n = model.node(id)
    var x by remember(id, n) { mutableStateOf(fmt(n.x)) }
    var y by remember(id, n) { mutableStateOf(fmt(n.y)) }
    var z by remember(id, n) { mutableStateOf(fmt(n.z)) }
    val sup = ModelEditor.supportType(model, id)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Node N$id", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Num("X (m)", x, Modifier.weight(1f)) { x = it }
                Num("Y (m)", y, Modifier.weight(1f)) { y = it }
                Num("Z (m)", z, Modifier.weight(1f)) { z = it }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SupportType.entries.forEach { t ->
                    FilterChip(t == sup, { onApply(ModelEditor.setSupport(model, id, t), "N$id support: ${t.name.lowercase()}") },
                        { Text(t.name.lowercase().replace('_', ' ')) })
                }
            }
            val loads = model.nodalLoads.filter { it.node == id }
            if (loads.isNotEmpty()) Text(
                loads.joinToString("\n") { "${it.case}: Fx ${fmt(it.values[0])}, Fy ${fmt(it.values[1])}, Fz ${fmt(it.values[2])} kN" },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    val p = Vec3(x.toDoubleOrNull() ?: n.x, y.toDoubleOrNull() ?: n.y, z.toDoubleOrNull() ?: n.z)
                    onApply(ModelEditor.moveNode(model, id, p), "N$id moved")
                }) { Text("Move") }
                if (loads.isNotEmpty()) OutlinedButton(onClick = { onApply(ModelEditor.clearLoadsAtNode(model, id), "Loads at N$id cleared") }) { Text("Clear loads") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun MemberEditor(model: FrameModel, id: Int, onApply: (FrameModel, String) -> Unit, onClose: () -> Unit) {
    val m = model.members.first { it.id == id }
    val spec = remember(id, m) { SectionSpec().also { it.from(m.section, m.material) } }
    var ki by remember(id, m) { mutableStateOf(m.springI?.let { fmt(it) } ?: "") }
    var kj by remember(id, m) { mutableStateOf(m.springJ?.let { fmt(it) } ?: "") }
    val len = (model.node(m.j).pos - model.node(m.i).pos).length
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Member M$id  N${m.i} → N${m.j}, L = ${fmt(len)} m, ${m.section.name}, ${m.material.name}", fontWeight = FontWeight.SemiBold)
            SectionPicker(spec)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(m.hingeI, { v -> onApply(ModelEditor.updateMember(model, id) { it.copy(hingeI = v) }, "M$id hinge at i: $v") })
                Text("  Hinge i   ", style = MaterialTheme.typography.bodySmall)
                Switch(m.hingeJ, { v -> onApply(ModelEditor.updateMember(model, id) { it.copy(hingeJ = v) }, "M$id hinge at j: $v") })
                Text("  Hinge j", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Num("Spring i (kN·m/rad)", ki, Modifier.weight(1f)) { ki = it }
                Num("Spring j (kN·m/rad)", kj, Modifier.weight(1f)) { kj = it }
            }
            Text("Rotational springs make semi-rigid joints (blank = rigid). A hinge overrides the spring.", style = MaterialTheme.typography.labelSmall)
            val loads = model.memberLoads.filter { it.member == id }
            val pts = model.pointLoads.filter { it.member == id }
            if (loads.isNotEmpty() || pts.isNotEmpty()) Text(
                (loads.map { "${it.case}: q ${fmt(it.w)} kN/m (${it.dir.name.lowercase()})" } +
                    pts.map { "${it.case}: P ${fmt(it.p)} kN at ${fmt(it.a)} m" }).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    val sec = runCatching { spec.section() }.getOrNull() ?: return@OutlinedButton
                    val mat = spec.material()
                    val si = ki.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
                    val sj = kj.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
                    onApply(ModelEditor.updateMember(model, id) { it.copy(section = sec, material = mat, springI = si, springJ = sj) },
                        "M$id: ${sec.name}, ${mat.name}")
                }) { Text("Apply") }
                OutlinedButton(onClick = {
                    val sec = runCatching { spec.section() }.getOrNull() ?: return@OutlinedButton
                    val mat = spec.material()
                    onApply(model.copy(members = model.members.map { it.copy(section = sec, material = mat) }), "All members: ${sec.name}, ${mat.name}")
                }) { Text("Apply to all") }
                if (loads.isNotEmpty() || pts.isNotEmpty()) OutlinedButton(onClick = { onApply(ModelEditor.clearLoadsOnMember(model, id), "Loads on M$id cleared") }) { Text("Clear loads") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun Num(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = modifier,
    )
}

private fun fmt(v: Double): String {
    val r = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    return if (r == "-0") "0" else r
}
