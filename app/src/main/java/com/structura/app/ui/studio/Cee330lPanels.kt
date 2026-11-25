package com.structura.app.ui.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.structura.core.fem.Benchmarks
import com.structura.core.fem.Builders
import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameResults
import com.structura.core.fem.LateralLoads
import com.structura.core.fem.LoadCase
import com.structura.core.fem.Material
import com.structura.core.fem.Section
import com.structura.core.fem.Seismic
import com.structura.core.fem.Support
import com.structura.core.fem.Vec3
import java.util.Locale

private fun d(s: String, def: Double) = s.replace(',', '.').toDoubleOrNull() ?: def

@Composable
private fun N(label: String, v: String, modifier: Modifier, on: (String) -> Unit) =
    OutlinedTextField(v, on, label = { Text(label) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

/** Templates → CEE 330L benchmark examples and the parametric Fink / Pratt truss wizards. */
@Composable
internal fun BenchmarkPanel(onLoad: (FrameModel, String) -> Unit) {
    var span by remember { mutableStateOf("12") }
    var rise by remember { mutableStateOf("3") }
    var panels by remember { mutableStateOf("6") }
    var load by remember { mutableStateOf("10") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Benchmark examples (CEE 330L STAAD.Pro manual, SI)", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Benchmarks.all.forEach { (name, make) -> OutlinedButton(onClick = { onLoad(make(), name) }) { Text(name) } }
            }
            Text("Truss wizards — pin-ended (truss) members, pinned + roller, 2D plane model", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                N("Span m", span, Modifier.weight(1f)) { span = it }
                N("Rise / height m", rise, Modifier.weight(1f)) { rise = it }
                N("Panels (Pratt)", panels, Modifier.weight(1f)) { panels = it }
                N("Panel load kN", load, Modifier.weight(1f)) { load = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    runCatching { Builders.finkTruss(d(span, 12.0), d(rise, 3.0), Section.angle(0.065, 0.065, 0.006, "ISA 65×65×6"), Material.steel(250.0), d(load, 10.0)) }
                        .onSuccess { onLoad(it, "Fink truss") }
                }) { Text("Fink roof truss") }
                OutlinedButton(onClick = {
                    runCatching {
                        Builders.prattTruss(d(span, 24.0), d(rise, 4.0), d(panels, 6.0).toInt(), Section.rhs(0.2, 0.2, 0.008), Section.rhs(0.15, 0.15, 0.006),
                            Material.steel(250.0), d(load, 50.0))
                    }.onSuccess { onLoad(it, "Pratt truss") }
                }) { Text("Pratt bridge truss") }
            }
        }
    }
}

/** STAAD "Translational Repeat": scope, direction, steps × spacing, link steps. */
@Composable
internal fun RepeatPanel(model: FrameModel, selectedMember: Int?, onApply: (FrameModel, String) -> Unit) {
    var scope by remember { mutableStateOf(0) }      // 0 top level, 1 whole model, 2 selected member
    var axis by remember { mutableStateOf(2) }
    var steps by remember { mutableStateOf("1") }
    var spacing by remember { mutableStateOf("3.2") }
    var link by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Translational repeat", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Top storey (columns + beams)", "Whole model", "Selected member").forEachIndexed { i, l -> FilterChip(i == scope, { scope = i }, { Text(l) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("X", "Y", "Z").forEachIndexed { i, l -> FilterChip(i == axis, { axis = i }, { Text("+$l") }) }
                Switch(link, { link = it }); Text(" Link steps", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                N("Steps", steps, Modifier.weight(1f)) { steps = it }
                N("Spacing m (or list 3.2,3.0)", spacing, Modifier.weight(2f)) { spacing = it }
            }
            Text("Up (+Z) with 'Top storey' adds storeys; sideways with Link on creates beams or cantilever projections (veranda / balcony).",
                style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = {
                val list = spacing.split(',', ';', ' ').mapNotNull { it.trim().replace(',', '.').toDoubleOrNull() }
                val sp = if (list.size > 1) list else List(d(steps, 1.0).toInt().coerceIn(1, 50)) { list.firstOrNull() ?: 3.0 }
                val sel: Set<Int> = when (scope) {
                    0 -> {
                        val zTop = model.nodes.maxOfOrNull { it.z } ?: 0.0
                        val zBelow = model.nodes.map { it.z }.filter { it < zTop - 1e-6 }.maxOrNull() ?: zTop
                        model.members.filter { m -> val a = model.node(m.i).z; val b = model.node(m.j).z; minOf(a, b) >= zBelow - 1e-6 && maxOf(a, b) <= zTop + 1e-6 }.map { it.id }.toSet()
                    }
                    1 -> model.members.map { it.id }.toSet()
                    else -> setOfNotNull(selectedMember)
                }
                if (sel.isEmpty()) return@OutlinedButton
                val dir = listOf(Vec3.X, Vec3.Y, Vec3.Z)[axis]
                runCatching { Builders.translationalRepeat(model, sel, dir = dir, spacings = sp, link = link) }
                    .onSuccess { onApply(it, "Repeated ${sel.size} members × ${sp.size} along ${"XYZ"[axis]}") }
            }) { Text("Repeat") }
        }
    }
}

/** IS 1893 equivalent static and IS 875-3 wind storey forces, applied as case WL. */
@Composable
internal fun LateralPanel(model: FrameModel, onApply: (FrameModel, String) -> Unit) {
    var zone by remember { mutableStateOf(1) }
    var soil by remember { mutableStateOf(Seismic.Soil.II) }
    var r by remember { mutableStateOf("5") }
    var imp by remember { mutableStateOf("1.0") }
    var axis by remember { mutableStateOf(0) }
    var vb by remember { mutableStateOf("44") }
    var terrain by remember { mutableStateOf(2) }
    var cf by remember { mutableStateOf("1.2") }
    var table by remember { mutableStateOf<String?>(null) }
    var ecc by remember { mutableStateOf(1) }      // 0 none, 1 +5 %, 2 −5 %
    val zones = listOf("II" to 0.10, "III" to 0.16, "IV" to 0.24, "V" to 0.36)
    fun apply(res: LateralLoads.Result, what: String) {
        val kept = model.nodalLoads.filter { it.case != LoadCase.WL }
        onApply(model.copy(nodalLoads = kept + res.loads), "$what along ${"XY"[axis]}: base shear ${"%.2f".format(res.baseShear)} kN (case WL)")
        table = (res.steps + res.storeys.map { String.format(Locale.US, "z = %6.2f m   %10.2f   F = %9.2f kN", it.z, it.weight, it.force) }).joinToString("\n")
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Lateral loads — IS 1893 static · IS 875-3 wind (replace case WL)", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("X", "Y").forEachIndexed { i, l -> FilterChip(i == axis, { axis = i }, { Text("Along $l") }) }
                zones.forEachIndexed { i, (n, z) -> FilterChip(i == zone, { zone = i }, { Text("Zone $n ($z)") }) }
                Seismic.Soil.entries.forEach { s -> FilterChip(s == soil, { soil = s }, { Text("Soil ${s.name}") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                N("R (Table 9)", r, Modifier.weight(1f)) { r = it }
                N("I (Table 8)", imp, Modifier.weight(1f)) { imp = it }
                OutlinedButton(onClick = {
                    runCatching { LateralLoads.seismicStatic(model, Seismic.Params(z = zones[zone].second, importance = d(imp, 1.0), r = d(r, 5.0), soil = soil), axis) }
                        .onSuccess { apply(it, "IS 1893 static") }.onFailure { table = it.message }
                }, modifier = Modifier.weight(1.4f)) { Text("Seismic VB = Ah·W") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("No ecc.", "+5 % ecc.", "−5 % ecc.").forEachIndexed { i, l -> FilterChip(i == ecc, { ecc = i }, { Text(l) }) }
                OutlinedButton(onClick = {
                    runCatching {
                        val pp = Seismic.Params(z = zones[zone].second, importance = d(imp, 1.0), r = d(r, 5.0), soil = soil)
                        val e = listOf(0.0, 0.05, -0.05)[ecc]
                        val ex = LateralLoads.seismicStatic(model, pp, 0, case = LoadCase.EX, eccentricity = e)
                        val ey = LateralLoads.seismicStatic(model, pp, 1, case = LoadCase.EY, eccentricity = e)
                        val kept = model.nodalLoads.filter { it.case != LoadCase.EX && it.case != LoadCase.EY }
                        onApply(model.copy(nodalLoads = kept + ex.loads + ey.loads),
                            "IS 1893 EX = ${"%.1f".format(ex.baseShear)} kN, EY = ${"%.1f".format(ey.baseShear)} kN; 25 combinations with EL = ±EX ± 0.3EY, ±EY ± 0.3EX (Cl. 6.3.4.1)")
                        table = (ex.steps + ey.steps.takeLast(1)).joinToString("\n")
                    }.onFailure { table = it.message }
                }) { Text("EX + EY (100 % + 30 %)") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..4).forEach { t -> FilterChip(t == terrain, { terrain = t }, { Text("Terrain $t") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                N("Vb m/s", vb, Modifier.weight(1f)) { vb = it }
                N("Cf", cf, Modifier.weight(1f)) { cf = it }
                OutlinedButton(onClick = {
                    runCatching { LateralLoads.wind(model, LateralLoads.WindParams(vb = d(vb, 44.0), terrain = terrain, cf = d(cf, 1.2)), axis) }
                        .onSuccess { apply(it, "IS 875-3 wind") }.onFailure { table = it.message }
                }, modifier = Modifier.weight(1.4f)) { Text("Wind storey forces") }
            }
            Text("Vb from IS 875-3 Fig. 1 for the site; k2 per Table 2; Kd·Ka·Kc ≥ 0.7. Seismic weight DL + 0.25 LL; accidental eccentricity not added.",
                style = MaterialTheme.typography.labelSmall)
            table?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Node inspector: 6 displacements and the support reaction of the selected node. */
@Composable
internal fun NodeInspector(results: FrameResults?, node: Int) {
    val r = results ?: return
    val u = r.nodeDisplacements[node] ?: return
    Text(String.format(Locale.US, "ux %.3f  uy %.3f  uz %.3f mm   φx %.4f  φy %.4f  φz %.4f mrad",
        u[0] * 1000, u[1] * 1000, u[2] * 1000, u[3] * 1000, u[4] * 1000, u[5] * 1000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    r.reactions[node]?.let { re ->
        Text(String.format(Locale.US, "Reaction Rx %.2f  Ry %.2f  Rz %.2f kN   Mx %.2f  My %.2f  Mz %.2f kN·m", re[0], re[1], re[2], re[3], re[4], re[5]),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/** Support springs / roller for the selected node. */
@Composable
internal fun SupportExtras(model: FrameModel, id: Int, onApply: (FrameModel, String) -> Unit) {
    val s = model.supports.firstOrNull { it.node == id }
    var kx by remember(id) { mutableStateOf(s?.springs?.get(0)?.takeIf { it > 0 }?.toString() ?: "") }
    var ky by remember(id) { mutableStateOf(s?.springs?.get(1)?.takeIf { it > 0 }?.toString() ?: "") }
    var kz by remember(id) { mutableStateOf(s?.springs?.get(2)?.takeIf { it > 0 }?.toString() ?: "") }
    var inc by remember(id) { mutableStateOf(s?.inclination?.get(1)?.toString() ?: "0") }
    if (s != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        N("Incline about Y (°)", inc, Modifier.weight(1f)) { inc = it }
        OutlinedButton(onClick = {
            val a = d(inc, 0.0)
            onApply(model.copy(supports = model.supports.map { if (it.node == id) it.copy(inclination = if (a == 0.0) null else listOf(0.0, a, 0.0)) else it }),
                "N$id support axes rotated ${a}° about Y (restraints act along the inclined axes)")
        }) { Text("Incline") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Roller Z", "Roller Y").forEachIndexed { i, l ->
            FilterChip(false, { onApply(model.copy(supports = model.supports.filter { it.node != id } + Support.roller(id, if (i == 0) 2 else 1)), "N$id: ${l.lowercase()}") }, { Text(l) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        N("kx kN/m", kx, Modifier.weight(1f)) { kx = it }
        N("ky kN/m", ky, Modifier.weight(1f)) { ky = it }
        N("kz kN/m", kz, Modifier.weight(1f)) { kz = it }
        OutlinedButton(onClick = {
            val ks = listOf(d(kx, 0.0), d(ky, 0.0), d(kz, 0.0))
            val base = s?.restraints ?: List(6) { false }
            val sp = Support(id, base.mapIndexed { i, r -> r && (i >= 3 || ks[i] == 0.0) }, ks + listOf(0.0, 0.0, 0.0))
            onApply(model.copy(supports = model.supports.filter { it.node != id } + sp), "N$id elastic support")
        }) { Text("Springs") }
    }
}
