package com.structura.app.ui.rcc

import android.content.Intent
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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.structura.app.DesignSession
import com.structura.app.ui.theme.FailRed
import com.structura.app.ui.theme.InfoBlue
import com.structura.app.ui.theme.PassGreen
import com.structura.core.codes.IS456
import com.structura.core.codes.IS456.Support
import com.structura.core.rcc.BeamDesigner
import com.structura.core.rcc.BeamInput
import com.structura.core.rcc.BeamReport
import com.structura.core.rcc.BeamResult
import com.structura.core.rcc.Status

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeamDesignScreen(onBack: () -> Unit, onDrawing: () -> Unit) {
    val pre = remember { DesignSession.takePrefill() }
    fun n0(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
    var b by rememberSaveable { mutableStateOf(pre?.let { n0(it.b) } ?: "230") }
    var dOverall by rememberSaveable { mutableStateOf(pre?.let { n0(it.d) } ?: "450") }
    var cover by rememberSaveable { mutableStateOf("25") }
    var bar by rememberSaveable { mutableStateOf("16") }
    var compBar by rememberSaveable { mutableStateOf("12") }
    var stirrup by rememberSaveable { mutableStateOf("8") }
    var spanM by rememberSaveable { mutableStateOf(pre?.let { n0(it.spanM) } ?: "5.0") }
    var support by rememberSaveable { mutableStateOf(Support.SIMPLY_SUPPORTED) }
    var fck by rememberSaveable { mutableStateOf(20) }
    var fy by rememberSaveable { mutableStateOf(500) }
    var useUdl by rememberSaveable { mutableStateOf(pre == null) }
    var wu by rememberSaveable { mutableStateOf("30") }
    var muIn by rememberSaveable { mutableStateOf(pre?.let { String.format(java.util.Locale.US, "%.1f", it.mu) } ?: "90") }
    var vuIn by rememberSaveable { mutableStateOf(pre?.let { String.format(java.util.Locale.US, "%.1f", it.vu) } ?: "80") }

    var result by remember { mutableStateOf<BeamResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fun run() {
        error = null
        try {
            val span = spanM.toDouble()
            val (mu, vu) = if (useUdl) {
                if (support == Support.CANTILEVER) BeamDesigner.cantileverUdl(wu.toDouble(), span)
                else BeamDesigner.simplySupportedUdl(wu.toDouble(), span)
            } else muIn.toDouble() to vuIn.toDouble()
            val input =
                BeamInput(
                    widthB = b.toDouble(), overallDepthD = dOverall.toDouble(), clearCover = cover.toDouble(),
                    mainBarDia = bar.toDouble(), compBarDia = compBar.toDouble(), stirrupDia = stirrup.toDouble(),
                    effectiveSpan = span * 1000.0, support = support,
                    fck = fck.toDouble(), fy = fy.toDouble(),
                    factoredMoment = mu, factoredShear = vu,
                )
            val r = BeamDesigner.design(input)
            result = r
            DesignSession.beamInput = input
            DesignSession.beamResult = r
        } catch (e: NumberFormatException) {
            error = "Please fill every field with a number."
            result = null
        } catch (e: IllegalArgumentException) {
            error = e.message
            result = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RCC Beam — IS 456") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    result?.let { r ->
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Structura AI — RCC beam design")
                                putExtra(Intent.EXTRA_TEXT, BeamReport.text(r))
                            }
                            context.startActivity(Intent.createChooser(send, "Share design"))
                        }) { Icon(Icons.Filled.Share, "Share report") }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Section")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num("b (mm)", b, Modifier.weight(1f)) { b = it }
                Num("D (mm)", dOverall, Modifier.weight(1f)) { dOverall = it }
                Num("Cover (mm)", cover, Modifier.weight(1f)) { cover = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num("Tension φ", bar, Modifier.weight(1f)) { bar = it }
                Num("Comp. φ", compBar, Modifier.weight(1f)) { compBar = it }
                Num("Stirrup φ", stirrup, Modifier.weight(1f)) { stirrup = it }
            }

            Section("Materials")
            ChipRow("Concrete", IS456.CONCRETE_GRADES.filter { it >= 15 }, fck, { "M$it" }) { fck = it }
            ChipRow("Steel", listOf(250, 415, 500), fy, { "Fe $it" }) { fy = it }

            Section("Span & loading")
            ChipRow("Support", Support.entries.toList(), support, { it.label }) { support = it }
            Num("Effective span (m)", spanM, Modifier.fillMaxWidth()) { spanM = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = useUdl, onClick = { useUdl = true }, label = { Text("Factored UDL") })
                FilterChip(selected = !useUdl, onClick = { useUdl = false }, label = { Text("Mu & Vu directly") })
            }
            if (useUdl) {
                Num("wu (kN/m, factored)", wu, Modifier.fillMaxWidth()) { wu = it }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("Mu (kN·m)", muIn, Modifier.weight(1f)) { muIn = it }
                    Num("Vu (kN)", vuIn, Modifier.weight(1f)) { vuIn = it }
                }
            }

            Button(onClick = { run() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Design beam") }
            error?.let { Text(it, color = FailRed) }
            if (pre != null && result == null) Text(
                "Mu and Vu come from the FEM ULS envelope (IS 456 Table 18). Check them before designing.",
                style = MaterialTheme.typography.bodySmall,
            )
            result?.let {
                OutlinedButton(onClick = onDrawing, modifier = Modifier.fillMaxWidth()) {
                    Text("Detail drawing (UCS) & DXF export")
                }
                ResultView(it)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ResultView(r: BeamResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (r.isSafe) "SECTION SAFE" else "REVISE SECTION",
                color = if (r.isSafe) PassGreen else FailRed,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
            )
            Text("Bottom: ${r.tensionBars}  (${"%.0f".format(r.tensionBars.area)} mm²)")
            r.compressionBars?.let { Text("Top: $it  (${"%.0f".format(it.area)} mm²)") }
                ?: Text("Top: 2 hanger bars (nominal)")
            r.stirrupSpacing?.let { Text("Stirrups: @ ${it.toInt()} mm c/c") }
            Text("d = ${"%.1f".format(r.effectiveDepth)} mm · Mu,lim = ${"%.2f".format(r.muLim)} kN·m")
        }
    }
    Section("Check sheet")
    r.checks.forEach { c ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row {
                Text(
                    when (c.status) { Status.PASS -> "✔ "; Status.FAIL -> "✘ "; Status.INFO -> "ℹ " },
                    color = when (c.status) { Status.PASS -> PassGreen; Status.FAIL -> FailRed; Status.INFO -> InfoBlue },
                    fontWeight = FontWeight.Bold,
                )
                Text(c.item, fontWeight = FontWeight.SemiBold)
            }
            Text("${c.value}   [${c.requirement}]", style = MaterialTheme.typography.bodySmall)
            Text(c.clause, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        HorizontalDivider()
    }
    Section("Calculation steps")
    r.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Num(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

@Composable
private fun <T> ChipRow(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(text(o)) }) }
        }
    }
}
