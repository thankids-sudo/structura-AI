package com.structura.app.ui.rcc

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.structura.app.shareTextFile
import com.structura.app.ui.theme.FailRed
import com.structura.app.ui.theme.InfoBlue
import com.structura.app.ui.theme.PassGreen
import com.structura.core.rcc.BarBendingSchedule
import com.structura.core.rcc.BbsRow
import com.structura.core.rcc.ColumnDesigner
import com.structura.core.rcc.ColumnInput
import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.FootingDesigner
import com.structura.core.rcc.FootingInput
import com.structura.core.rcc.SlabDesigner
import com.structura.core.rcc.SlabInput
import com.structura.core.rcc.SlabType
import com.structura.core.rcc.Status
import java.util.Locale

private fun n(s: String, d: Double = 0.0) = s.replace(',', '.').toDoubleOrNull() ?: d

@Composable
internal fun Field(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
}

@Composable
internal fun CheckSheet(safe: Boolean, headline: List<String>, checks: List<DesignCheck>, steps: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (safe) "SAFE" else "REVISE", color = if (safe) PassGreen else FailRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            headline.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    Text("Check sheet", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
    checks.forEach { c ->
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row {
                Text(when (c.status) { Status.PASS -> "✔ "; Status.FAIL -> "✘ "; Status.INFO -> "ℹ " },
                    color = when (c.status) { Status.PASS -> PassGreen; Status.FAIL -> FailRed; Status.INFO -> InfoBlue }, fontWeight = FontWeight.Bold)
                Text(c.item, fontWeight = FontWeight.SemiBold)
            }
            Text("${c.value}   [${c.requirement}]", style = MaterialTheme.typography.bodySmall)
            Text(c.clause, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        HorizontalDivider()
    }
    Text("Calculation steps", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
    steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun BbsTable(rows: List<BbsRow>, title: String) {
    val context = LocalContext.current
    Text("Bar bending schedule", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
    Text("Mark  φ   No.   Cut (mm)   Wt (kg)", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    rows.forEach { r ->
        Text(String.format(Locale.US, "%-4s %3d %5d %9d %9.2f", r.mark, r.dia.toInt(), r.count, r.cutLength.toInt(), r.weight),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("   ${r.description} — ${r.shape}; ${r.formula}", style = MaterialTheme.typography.labelSmall)
    }
    Text(String.format(Locale.US, "Total steel: %.2f kg", rows.sumOf { it.weight }), fontWeight = FontWeight.SemiBold)
    OutlinedButton(onClick = { shareTextFile(context, "BBS_$title.csv", "text/csv", BarBendingSchedule.csv(rows), "Structura AI — BBS $title") },
        modifier = Modifier.fillMaxWidth()) { Text("Export BBS (CSV / Excel)") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RccScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun SlabScreen(onBack: () -> Unit) = RccScaffold("RCC Slab — IS 456", onBack) {
    var lx by rememberSaveable { mutableStateOf("4.0") }
    var ly by rememberSaveable { mutableStateOf("5.0") }
    var t by rememberSaveable { mutableStateOf("150") }
    var ff by rememberSaveable { mutableStateOf("1.0") }
    var ll by rememberSaveable { mutableStateOf("3.0") }
    var fck by rememberSaveable { mutableStateOf("20") }
    var fy by rememberSaveable { mutableStateOf("500") }
    var dia by rememberSaveable { mutableStateOf("10") }
    var cover by rememberSaveable { mutableStateOf("20") }
    var type by rememberSaveable { mutableStateOf(SlabType.TWO_WAY_SS) }
    var edge by rememberSaveable { mutableStateOf(com.structura.core.rcc.SlabEdgeCase.INTERIOR) }
    var out by remember { mutableStateOf<Pair<SlabInput, com.structura.core.rcc.SlabResult>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SlabType.entries.forEach { k -> FilterChip(k == type, { type = k }, { Text(k.label) }) }
    }
    if (type == SlabType.TWO_WAY_RESTRAINED) {
        Text("Edge condition (IS 456 Table 26)", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.structura.core.rcc.SlabEdgeCase.entries.forEach { k -> FilterChip(k == edge, { edge = k }, { Text(k.label) }) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field(if (type == SlabType.CANTILEVER) "Length (m)" else "lx (m)", lx, Modifier.weight(1f)) { lx = it }
        Field("ly (m)", ly, Modifier.weight(1f)) { ly = it }
        Field("D (mm)", t, Modifier.weight(1f)) { t = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("Finishes kN/m²", ff, Modifier.weight(1f)) { ff = it }
        Field("Live kN/m²", ll, Modifier.weight(1f)) { ll = it }
        Field("Cover mm", cover, Modifier.weight(1f)) { cover = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("fck", fck, Modifier.weight(1f)) { fck = it }
        Field("fy", fy, Modifier.weight(1f)) { fy = it }
        Field("Bar φ mm", dia, Modifier.weight(1f)) { dia = it }
    }
    Button(onClick = {
        err = null
        runCatching {
            val inp = SlabInput(n(lx, 4.0), n(ly, 5.0), n(t, 150.0), n(cover, 20.0), n(dia, 10.0), 8.0, n(ff, 1.0), n(ll, 3.0), n(fck, 20.0), n(fy, 500.0), type, edge)
            inp to SlabDesigner.design(inp)
        }.onSuccess { out = it }.onFailure { err = it.message; out = null }
    }, modifier = Modifier.fillMaxWidth()) { Text("Design slab") }
    err?.let { Text(it, color = FailRed) }
    out?.let { (inp, r) ->
        CheckSheet(r.isSafe, listOf(
            String.format(Locale.US, "wu = %.2f kN/m² · Mx = %.2f, My = %.2f kN·m/m", r.wu, r.mx, r.my),
            "Short span: φ${inp.barDia.toInt()} @ ${r.spacingX.toInt()} c/c (d = ${r.dx.toInt()} mm)",
            if (inp.twoWay) "Long span: φ${inp.barDia.toInt()} @ ${r.spacingY.toInt()} c/c (bottom)" else "Distribution: φ${inp.distBarDia.toInt()} @ ${r.spacingY.toInt()} c/c",
        ) + (r.top?.let { tp ->
            listOfNotNull(
                if (tp.spacingXNeg > 0) String.format(Locale.US, "Top over long edges: Mx− = %.2f kN·m/m → φ%d @ %d c/c", tp.mxNeg, inp.barDia.toInt(), tp.spacingXNeg.toInt()) else null,
                if (tp.spacingYNeg > 0) String.format(Locale.US, "Top over short edges: My− = %.2f kN·m/m → φ%d @ %d c/c", tp.myNeg, inp.barDia.toInt(), tp.spacingYNeg.toInt()) else null,
                if (tp.torsionFullCorners + tp.torsionHalfCorners > 0)
                    String.format(Locale.US, "Corner torsion: %d full (φ%d @ %d, 4 layers) + %d half, over %.2f m each way (D-1.8/1.9)",
                        tp.torsionFullCorners, inp.barDia.toInt(), tp.torsionSpacing.toInt(), tp.torsionHalfCorners, tp.torsionExtent)
                else "No corner torsion steel — all corners continuous (D-1.10)",
            )
        } ?: if (inp.type == SlabType.TWO_WAY_SS) listOf("Table 27: corners free to lift, no torsion steel. Choose Table 26 for slabs cast with beams.") else emptyList()),
        r.checks, r.steps)
        BbsTable(BarBendingSchedule.slab(inp, r), "slab")
    }
}

@Composable
fun ColumnScreen(onBack: () -> Unit) = RccScaffold("RCC Column — IS 456", onBack) {
    var b by rememberSaveable { mutableStateOf("300") }
    var d by rememberSaveable { mutableStateOf("450") }
    var dia by rememberSaveable { mutableStateOf("16") }
    var nb by rememberSaveable { mutableStateOf("3") }
    var nd by rememberSaveable { mutableStateOf("3") }
    var fck by rememberSaveable { mutableStateOf("25") }
    var fy by rememberSaveable { mutableStateOf("500") }
    var pu by rememberSaveable { mutableStateOf("1200") }
    var mx by rememberSaveable { mutableStateOf("60") }
    var my by rememberSaveable { mutableStateOf("30") }
    var l by rememberSaveable { mutableStateOf("3000") }
    var k by rememberSaveable { mutableStateOf("1.0") }
    var out by remember { mutableStateOf<Pair<ColumnInput, com.structura.core.rcc.ColumnResult>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("b (mm)", b, Modifier.weight(1f)) { b = it }
        Field("D (mm)", d, Modifier.weight(1f)) { d = it }
        Field("Bar φ", dia, Modifier.weight(1f)) { dia = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("Bars on b-face", nb, Modifier.weight(1f)) { nb = it }
        Field("Bars on D-face", nd, Modifier.weight(1f)) { nd = it }
        Field("fck", fck, Modifier.weight(1f)) { fck = it }
        Field("fy", fy, Modifier.weight(1f)) { fy = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("Pu (kN)", pu, Modifier.weight(1f)) { pu = it }
        Field("Mux (kN·m)", mx, Modifier.weight(1f)) { mx = it }
        Field("Muy (kN·m)", my, Modifier.weight(1f)) { my = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("Unsupported L (mm)", l, Modifier.weight(1f)) { l = it }
        Field("k (Table 28)", k, Modifier.weight(1f)) { k = it }
    }
    Text("Mux bends about the axis parallel to b (depth D); Muy about the axis parallel to D. k: 0.65 fixed–fixed, 0.80 fixed–pinned, 1.0 pinned–pinned, 1.2/2.0 sway (Table 28).",
        style = MaterialTheme.typography.labelSmall)
    Button(onClick = {
        err = null
        runCatching {
            val inp = ColumnInput(n(b, 300.0), n(d, 450.0), 40.0, n(dia, 16.0), n(nb, 3.0).toInt(), n(nd, 3.0).toInt(), 8.0, n(fck, 25.0), n(fy, 500.0),
                n(pu), n(mx), n(my), n(l, 3000.0), n(k, 1.0), n(k, 1.0))
            inp to ColumnDesigner.design(inp)
        }.onSuccess { out = it }.onFailure { err = it.message; out = null }
    }, modifier = Modifier.fillMaxWidth()) { Text("Design column") }
    err?.let { Text(it, color = FailRed) }
    out?.let { (inp, r) ->
        CheckSheet(r.isSafe, listOf(
            String.format(Locale.US, "%d-%d φ, Asc = %.0f mm² · Puz = %.0f kN", 2 * inp.nb + 2 * inp.nd - 4, inp.barDia.toInt(), r.asc, r.puz),
            String.format(Locale.US, "Design Mux = %.2f, Muy = %.2f kN·m · capacities Mux1 = %.2f, Muy1 = %.2f kN·m", r.muxDesign, r.muyDesign, r.mux1, r.muy1),
            String.format(Locale.US, "Interaction = %.3f (αn = %.2f) · ties φ%d @ %d c/c", r.interaction, r.alphaN, inp.tieDia.toInt(), r.tiePitch.toInt()),
        ), r.checks, r.steps)
        BbsTable(BarBendingSchedule.column(inp, r), "column")
    }
}

@Composable
fun FootingScreen(onBack: () -> Unit) = RccScaffold("Isolated footing — IS 456", onBack) {
    var p by rememberSaveable { mutableStateOf("1000") }
    var pu by rememberSaveable { mutableStateOf("1500") }
    var cb by rememberSaveable { mutableStateOf("300") }
    var cd by rememberSaveable { mutableStateOf("450") }
    var sbc by rememberSaveable { mutableStateOf("200") }
    var fck by rememberSaveable { mutableStateOf("25") }
    var fy by rememberSaveable { mutableStateOf("500") }
    var dia by rememberSaveable { mutableStateOf("12") }
    var out by remember { mutableStateOf<Pair<FootingInput, com.structura.core.rcc.FootingResult>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("P service (kN)", p, Modifier.weight(1f)) { p = it }
        Field("Pu factored (kN)", pu, Modifier.weight(1f)) { pu = it }
        Field("SBC (kN/m²)", sbc, Modifier.weight(1f)) { sbc = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("Column b", cb, Modifier.weight(1f)) { cb = it }
        Field("Column D", cd, Modifier.weight(1f)) { cd = it }
        Field("fck", fck, Modifier.weight(1f)) { fck = it }
        Field("fy", fy, Modifier.weight(1f)) { fy = it }
        Field("Bar φ", dia, Modifier.weight(1f)) { dia = it }
    }
    Button(onClick = {
        err = null
        runCatching {
            val inp = FootingInput(n(p, 1000.0), n(pu, 1500.0), n(cb, 300.0), n(cd, 450.0), n(sbc, 200.0), n(fck, 25.0), n(fy, 500.0), n(dia, 12.0))
            inp to FootingDesigner.design(inp)
        }.onSuccess { out = it }.onFailure { err = it.message; out = null }
    }, modifier = Modifier.fillMaxWidth()) { Text("Design footing") }
    err?.let { Text(it, color = FailRed) }
    out?.let { (inp, r) ->
        CheckSheet(r.isSafe, listOf(
            String.format(Locale.US, "Plan %.0f × %.0f mm, overall depth %.0f mm (d = %.0f mm)", r.lengthL, r.widthB, r.depth, r.d),
            String.format(Locale.US, "qu = %.1f kN/m² · bars ∥ L: %d-φ%d @ %d · bars ∥ B: %d-φ%d @ %d", r.qu, r.barsL, inp.barDia.toInt(), r.spacingL.toInt(), r.barsB, inp.barDia.toInt(), r.spacingB.toInt()),
        ), r.checks, r.steps)
        BbsTable(BarBendingSchedule.footing(inp, r), "footing")
    }
}
