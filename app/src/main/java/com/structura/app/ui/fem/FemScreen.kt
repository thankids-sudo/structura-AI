package com.structura.app.ui.fem

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.structura.core.fem.Material
import com.structura.core.fem.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class Template(val label: String) { BEAM("Continuous beam"), PORTAL("2D portal frame"), SPACE("3D space frame") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FemScreen(onBack: () -> Unit, onDesignBeam: () -> Unit) {
    var template by rememberSaveable { mutableStateOf(Template.PORTAL) }
    var spans by rememberSaveable { mutableStateOf("5, 5") }
    var baysY by rememberSaveable { mutableStateOf("4") }
    var storeys by rememberSaveable { mutableStateOf("3.5, 3.2") }
    var colB by rememberSaveable { mutableStateOf("300") }
    var colH by rememberSaveable { mutableStateOf("450") }
    var beamB by rememberSaveable { mutableStateOf("230") }
    var beamH by rememberSaveable { mutableStateOf("450") }
    var fck by rememberSaveable { mutableStateOf("25") }
    var udl by rememberSaveable { mutableStateOf("25") }
    var lateral by rememberSaveable { mutableStateOf("10") }
    var selfWeight by rememberSaveable { mutableStateOf(true) }

    var model by remember { mutableStateOf<FrameModel?>(null) }
    var results by remember { mutableStateOf<FrameResults?>(null) }
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
            Template.BEAM -> Generators.continuousBeam(list(spans), beam, mat, udl.toDouble(), selfWeight)
            Template.PORTAL -> Generators.portalFrame(list(spans), list(storeys), col, beam, mat, udl.toDouble(), lateral.toDouble(), selfWeight)
            Template.SPACE -> Generators.spaceFrame(list(spans), list(baysY), list(storeys), col, beam, mat, udl.toDouble(), lateral.toDouble(), selfWeight)
        }
    }

    fun run() {
        error = null
        val m = try { build() } catch (e: Exception) { error = "Check inputs: ${e.message ?: "numbers only"}"; return }
        model = m; results = null; busy = true
        scope.launch {
            try {
                results = withContext(Dispatchers.Default) { FrameSolver.analyse(m) }
                if (view == ResultView.MODEL) view = ResultView.MY
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
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
                Field("Beam UDL (kN/m)", udl, Modifier.weight(1f)) { udl = it }
                if (template != Template.BEAM) Field("Lateral/floor (kN)", lateral, Modifier.weight(1f)) { lateral = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = selfWeight, onCheckedChange = { selfWeight = it })
                Text("  Include self-weight (γ = 25 kN/m³)")
            }
            Text(
                "Loads entered are taken as they are (service or factored). Global Z is up; member local z points down " +
                    "on beams, so sagging My is positive — same convention as RFEM.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { run() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Generate & analyse") }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error?.let { Text(it, color = FailRed) }

            model?.let { m ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ResultView.entries.forEach { v ->
                        FilterChip(
                            selected = v == view, onClick = { view = v }, label = { Text(v.label) },
                            enabled = v == ResultView.MODEL || results != null,
                        )
                    }
                }
                Card(Modifier.fillMaxWidth().height(380.dp)) { FrameView3D(m, results, view, Modifier.fillMaxWidth().height(380.dp)) }
                Text("Drag to rotate · pinch to zoom · red = positive, blue = negative", style = MaterialTheme.typography.labelSmall)
            }

            results?.let { r -> ResultsSummary(r, onDesignBeam) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResultsSummary(r: FrameResults, onDesignBeam: () -> Unit) {
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
    val env = FemReport.envelope(r)
    Text("Member envelope", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
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
    val crit = FemReport.criticalBeam(r)
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
                mu = 1.5 * maxOf(crit.myMax, -crit.myMin),
                vu = 1.5 * crit.vzAbsMax,
            )
            onDesignBeam()
        }, modifier = Modifier.fillMaxWidth()) { Text("Design this beam to IS 456 (×1.5 load factor)") }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = modifier,
    )
}
