package com.structura.app.ui.fem

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.structura.app.DesignSession
import com.structura.app.shareTextFile
import com.structura.app.ui.theme.FailRed
import com.structura.app.ui.theme.PassGreen
import com.structura.core.fem.FemReport
import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameResults
import com.structura.core.fem.FrameSolver
import com.structura.core.fem.Generators
import com.structura.core.fem.LoadCase
import com.structura.core.fem.LoadCombination
import com.structura.core.fem.Material
import com.structura.core.fem.MemberEnvelope
import com.structura.core.fem.NodalLoad
import com.structura.core.fem.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class Template(val label: String) { BEAM("Continuous beam"), PORTAL("2D portal frame"), SPACE("3D space frame") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FemScreen(onBack: () -> Unit, onDesignBeam: () -> Unit, onStudio: () -> Unit = {}) {
    var template by rememberSaveable { mutableStateOf(Template.PORTAL) }
    var spans by rememberSaveable { mutableStateOf("5, 5") }
    var baysY by rememberSaveable { mutableStateOf("4") }
    var storeys by rememberSaveable { mutableStateOf("3.5, 3.2") }
    var colB by rememberSaveable { mutableStateOf("300") }
    var colH by rememberSaveable { mutableStateOf("450") }
    var beamB by rememberSaveable { mutableStateOf("230") }
    var beamH by rememberSaveable { mutableStateOf("450") }
    var fck by rememberSaveable { mutableStateOf("25") }
    var udl by rememberSaveable { mutableStateOf("15") }
    var liveUdl by rememberSaveable { mutableStateOf("10") }
    var pointMid by rememberSaveable { mutableStateOf("0") }
    var hinged by rememberSaveable { mutableStateOf(false) }
    var lateral by rememberSaveable { mutableStateOf("10") }
    var selfWeight by rememberSaveable { mutableStateOf(true) }

    var baseModel by remember { mutableStateOf<FrameModel?>(null) }
    var model by remember { mutableStateOf<FrameModel?>(null) }
    var results by remember { mutableStateOf<FrameResults?>(null) }
    // Live loads applied by tapping a node: node id → (Fx, Fy, Fz) kN
    var liveLoads by remember { mutableStateOf(mapOf<Int, Triple<Float, Float, Float>>()) }
    var selectedNode by remember { mutableStateOf<Int?>(null) }
    var autoScale by remember { mutableStateOf(0.0) }
    var scaleMul by remember { mutableFloatStateOf(1f) }
    var animate by remember { mutableStateOf(true) }
    var combos by remember { mutableStateOf(listOf<LoadCombination>()) }
    var comboIdx by remember { mutableStateOf(0) }
    var ulsEnvelope by remember { mutableStateOf<List<MemberEnvelope>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(ResultView.MODEL) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun list(s: String) = s.split(',', ' ', ';').filter { it.isNotBlank() }.map { it.trim().toDouble() }

    fun build(): FrameModel {
        val mat = Material.concrete(fck.toDouble())
        val beam = Section.rect(beamB.toDouble() / 1000, beamH.toDouble() / 1000)
        val col = Section.rect(colB.toDouble() / 1000, colH.toDouble() / 1000)
        return when (template) {
            Template.BEAM -> Generators.continuousBeam(
                list(spans), beam, mat, udl.toDouble(), selfWeight, liveUdl.toDouble(), pointMid.toDouble(),
            )
            Template.PORTAL -> Generators.portalFrame(
                list(spans), list(storeys), col, beam, mat, udl.toDouble(), lateral.toDouble(), selfWeight,
                liveUdl.toDouble(), pointMid.toDouble(), hinged,
            )
            Template.SPACE -> Generators.spaceFrame(
                list(spans), list(baysY), list(storeys), col, beam, mat, udl.toDouble(), lateral.toDouble(), selfWeight,
                liveUdl.toDouble(), pointMid.toDouble(), hinged,
            )
        }
    }

    fun withLive(base: FrameModel, live: Map<Int, Triple<Float, Float, Float>>): FrameModel =
        if (live.isEmpty()) base else base.copy(
            nodalLoads = base.nodalLoads + live.filter { (_, f) -> f.first != 0f || f.second != 0f || f.third != 0f }
                .map { (n, f) -> NodalLoad(n, listOf(f.first.toDouble(), f.second.toDouble(), f.third.toDouble(), 0.0, 0.0, 0.0), LoadCase.TAP) },
        )

    fun run() {
        error = null
        val m = try { build() } catch (e: Exception) { error = "Check inputs: ${e.message ?: "numbers only"}"; return }
        baseModel = m; model = m; results = null; busy = true
        liveLoads = emptyMap(); selectedNode = null
        scope.launch {
            try {
                val cases = m.loadCases
                val withLive = LoadCase.LL in cases
                val withLat = LoadCase.WL in cases
                val uls = LoadCombination.isLimitState(withLat)
                val list = uls + LoadCombination.cases(withLive, withLat)
                val (r, env) = withContext(Dispatchers.Default) {
                    val all = FrameSolver.analyseAll(m, uls).map { it.second }
                    all.first() to FemReport.envelope(all)
                }
                combos = list; comboIdx = 0; ulsEnvelope = env
                val maxD = maxOf(r.maxDisplacement().second, r.maxMemberDeflection())
                autoScale = if (maxD > 1e-12) 0.08 * modelSpan(m) / maxD else 1.0
                results = r
                view = ResultView.DEFORMED
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    // Re-solve as soon as a live load changes (debounced) — the view animates to the new shape.
    LaunchedEffect(liveLoads, comboIdx) {
        val base = baseModel ?: return@LaunchedEffect
        if (results == null) return@LaunchedEffect
        val combo = combos.getOrNull(comboIdx) ?: return@LaunchedEffect
        delay(120)
        val m = withLive(base, liveLoads)
        try {
            val r = withContext(Dispatchers.Default) { FrameSolver.analyse(m, combo) }
            model = m; results = r; error = null
        } catch (e: Exception) {
            error = e.message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FEM — 3D frame analysis") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    results?.let { r ->
                        IconButton(onClick = {
                            shareTextFile(context, "StructuraAI_FEM_results.csv", "text/csv", FemReport.csv(r), "Structura AI — FEM results")
                        }) { Icon(Icons.Filled.IosShare, "Export results") }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Template.entries.forEach { t -> FilterChip(selected = t == template, onClick = { template = t }, label = { Text(t.label) }) }
            }
            Field(if (template == Template.BEAM) "Spans (m), comma separated" else "Bays along X (m)", spans) { spans = it }
            if (template == Template.SPACE) Field("Bays along Y (m)", baysY) { baysY = it }
            if (template != Template.BEAM) Field("Storey heights (m), bottom up", storeys) { storeys = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Beam b (mm)", beamB, Modifier.weight(1f)) { beamB = it }
                Field("Beam D (mm)", beamH, Modifier.weight(1f)) { beamH = it }
            }
            if (template != Template.BEAM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Column b (mm)", colB, Modifier.weight(1f)) { colB = it }
                Field("Column D (mm)", colH, Modifier.weight(1f)) { colH = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Concrete fck", fck, Modifier.weight(1f)) { fck = it }
                if (template != Template.BEAM) Field("WL lateral/floor (kN)", lateral, Modifier.weight(1f)) { lateral = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("DL UDL (kN/m)", udl, Modifier.weight(1f)) { udl = it }
                Field("LL UDL (kN/m)", liveUdl, Modifier.weight(1f)) { liveUdl = it }
                Field("LL point @ mid (kN)", pointMid, Modifier.weight(1f)) { pointMid = it }
            }
            if (template != Template.BEAM) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = hinged, onCheckedChange = { hinged = it })
                Text("  Beams hinged at both ends (simply supported on columns)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = selfWeight, onCheckedChange = { selfWeight = it })
                Text("  Include self-weight (γ = 25 kN/m³)")
            }
            Text(
                "Enter characteristic (service) loads: DL = IS 875 Part 1, LL = Part 2, WL = Part 3 wind or IS 1893 " +
                    "seismic force (applied ±X). Combinations per IS 456 Table 18. Z is up; on beams local z points down, " +
                    "so sagging My is positive (RFEM convention).",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { run() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Generate & analyse") }
            OutlinedButton(onClick = {
                val m = baseModel ?: runCatching { build() }.getOrNull()
                if (m == null) error = "Check inputs" else { DesignSession.studioModel = m; onStudio() }
            }, modifier = Modifier.fillMaxWidth()) { Text("Open this model in 3D Studio (edit nodes, members, loads)") }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error?.let { Text(it, color = FailRed) }

            model?.let { m ->
                if (combos.isNotEmpty()) {
                    Text("Load combination / case", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        combos.forEachIndexed { k, c ->
                            FilterChip(selected = k == comboIdx, onClick = { comboIdx = k }, label = { Text(c.name) })
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ResultView.entries.filter { it != ResultView.BUCKLING && it != ResultView.MODAL }.forEach { v ->
                        FilterChip(
                            selected = v == view, onClick = { view = v }, label = { Text(v.label) },
                            enabled = v == ResultView.MODEL || results != null,
                        )
                    }
                }
                Card(Modifier.fillMaxWidth().height(400.dp)) {
                    FrameView3D(
                        m, results, view, Modifier.fillMaxWidth().height(400.dp),
                        deformScale = autoScale * scaleMul,
                        animate = animate,
                        selectedNode = selectedNode,
                        onNodeTap = { selectedNode = it },
                    )
                }
                Text(
                    "Drag to rotate · pinch to zoom · tap a node to apply a load · red = +, blue = −",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (view == ResultView.DEFORMED && results != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = animate, onCheckedChange = { animate = it })
                    Text("  Animate", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(12.dp))
                    Text("Scale ×${"%.1f".format(scaleMul)}", style = MaterialTheme.typography.bodySmall)
                    Slider(value = scaleMul, onValueChange = { scaleMul = it }, valueRange = 0.25f..5f, modifier = Modifier.weight(1f))
                }
                val sel = selectedNode
                if (sel != null && results != null) {
                    val node = m.node(sel)
                    val cur = liveLoads[sel] ?: Triple(0f, 0f, 0f)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Node $sel  (${"%.2f".format(node.x)}, ${"%.2f".format(node.y)}, ${"%.2f".format(node.z)}) — live load",
                                fontWeight = FontWeight.SemiBold,
                            )
                            LoadSlider("Fx", cur.first) { liveLoads = liveLoads + (sel to cur.copy(first = it)) }
                            LoadSlider("Fy", cur.second) { liveLoads = liveLoads + (sel to cur.copy(second = it)) }
                            LoadSlider("Fz", cur.third) { liveLoads = liveLoads + (sel to cur.copy(third = it)) }
                            results?.nodeDisplacements?.get(sel)?.let { d ->
                                Text(
                                    "Displacement: ux ${"%.2f".format(d[0] * 1000)}  uy ${"%.2f".format(d[1] * 1000)}  uz ${"%.2f".format(d[2] * 1000)} mm",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { liveLoads = liveLoads - sel }) { Text("Remove") }
                                OutlinedButton(onClick = { liveLoads = emptyMap() }) { Text("Clear all") }
                                OutlinedButton(onClick = { selectedNode = null }) { Text("Done") }
                            }
                        }
                    }
                }
            }

            results?.let { r -> ResultsSummary(r, ulsEnvelope, onDesignBeam) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun ResultsSummary(r: FrameResults, uls: List<MemberEnvelope>, onDesignBeam: () -> Unit, envTitle: String = "Member envelope — all IS 456 ULS combinations") {
    val eq = r.equilibriumError
    val ok = eq.all { abs(it) < 1e-3 * (1 + abs(r.totalLoad[2])) }
    val mono = FontFamily.Monospace
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.model.title, fontWeight = FontWeight.Bold)
            Text("${r.model.nodes.size} nodes · ${r.model.members.size} members · ${r.model.nodes.size * 6} DOF")
            Text(
                "Equilibrium: ΣLoad Z = ${"%.2f".format(r.totalLoad[2])} kN, ΣR Z = ${"%.2f".format(-r.totalLoad[2] + eq[2])} kN " +
                    if (ok) "✔" else "✘",
                color = if (ok) PassGreen else FailRed,
            )
            val (nid, d) = r.maxDisplacement()
            Text("Max node displacement: ${"%.2f".format(d * 1000)} mm at node $nid")
            Text("Max member deflection (from chord): ${"%.2f".format(r.maxMemberDeflection() * 1000)} mm")
        }
    }
    val env = uls.ifEmpty { FemReport.envelope(r) }
    Text(envTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
    Text(" M#   L(m)   N(kN)  Vz(kN) My+(kNm) My-(kNm)", fontFamily = mono, style = MaterialTheme.typography.bodySmall)
    env.forEach { e ->
        Text(
            "%3d %6.2f %7.1f %7.1f %8.1f %8.1f".format(e.member, e.length, e.nAbsMax, e.vzAbsMax, e.myMax, e.myMin),
            fontFamily = mono, style = MaterialTheme.typography.bodySmall,
        )
    }
    HorizontalDivider()
    Text("Support reactions", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
    Text(" Node    Rx      Ry      Rz      My", fontFamily = mono, style = MaterialTheme.typography.bodySmall)
    r.reactions.forEach { (n, v) ->
        Text("%5d %7.1f %7.1f %7.1f %7.1f".format(n, v[0], v[1], v[2], v[4]), fontFamily = mono, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    val crit = FemReport.criticalBeam(env)
    if (crit != null) {
        Text(
            "Critical beam: M${crit.member}, span ${"%.2f".format(crit.length)} m, My+ ${"%.1f".format(crit.myMax)} kN·m, " +
                "My− ${"%.1f".format(crit.myMin)} kN·m, Vz ${"%.1f".format(crit.vzAbsMax)} kN",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            DesignSession.prefill = DesignSession.Prefill(
                b = crit.b * 1000,
                d = crit.h * 1000,
                spanM = crit.length,
                mu = maxOf(crit.myMax, -crit.myMin),
                vu = crit.vzAbsMax,
            )
            onDesignBeam()
        }, modifier = Modifier.fillMaxWidth()) { Text("Design critical beam to IS 456 (ULS envelope)") }
    }
}

@Composable
private fun LoadSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${"%+.0f".format(value)} kN", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
        Slider(
            value = value,
            onValueChange = { onChange(Math.round(it / 5f) * 5f) },   // 5 kN steps
            valueRange = -200f..200f,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun modelSpan(m: FrameModel): Double {
    val xs = m.nodes.map { it.x }; val ys = m.nodes.map { it.y }; val zs = m.nodes.map { it.z }
    return maxOf(xs.max() - xs.min(), ys.max() - ys.min(), zs.max() - zs.min()).coerceAtLeast(1.0)
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = modifier,
    )
}
