package com.structura.app.ui.loads

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.structura.core.loads.IS875
import java.util.Locale

/** IS 875 Part 1 & 2 loads: unit weights, imposed loads, slab and wall loads, partition allowance, LL reduction, IS 456 Table 18. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoadsScreen(onBack: () -> Unit) {
    var occ by rememberSaveable { mutableStateOf(0) }
    var t by rememberSaveable { mutableStateOf("150") }
    var fin by rememberSaveable { mutableStateOf("1.0") }
    var wt by rememberSaveable { mutableStateOf("230") }
    var wh by rememberSaveable { mutableStateOf("2.75") }
    var mat by rememberSaveable { mutableStateOf(2) }
    fun n(s: String, d: Double) = s.replace(',', '.').toDoubleOrNull() ?: d

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Loads — IS 875 Part 1 & 2", fontWeight = FontWeight.Bold); Text("Dead, imposed, walls, reduction, combinations", style = MaterialTheme.typography.labelMedium) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Sec("Imposed load by occupancy (IS 875-2 Table 1 / Table 2)") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IS875.imposed.forEachIndexed { i, o -> FilterChip(i == occ, { occ = i }, { Text(o.use) }) }
                }
                val o = IS875.imposed[occ]
                Text(String.format(Locale.US, "%s: %.2f kN/m²%s", o.use, o.udl, if (o.note.isNotEmpty()) " (${o.note})" else ""), fontWeight = FontWeight.SemiBold)
            }
            Sec("Slab dead load (IS 875-1, IS 456 Cl. 19.2.1)") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    F("Slab t (mm)", t, Modifier.weight(1f)) { t = it }
                    F("Finish (kN/m²)", fin, Modifier.weight(1f)) { fin = it }
                }
                val dl = IS875.slabDead(n(t, 150.0) / 1000, n(fin, 1.0))
                Text(String.format(Locale.US, "DL = 25 × %.3f + %.2f + ceiling plaster 0.012 × 20.4 = %.2f kN/m²", n(t, 150.0) / 1000, n(fin, 1.0), dl), fontWeight = FontWeight.SemiBold)
                Text(String.format(Locale.US, "With %s: wu = 1.5 × (%.2f + %.2f) = %.2f kN/m²", IS875.imposed[occ].use, dl, IS875.imposed[occ].udl, 1.5 * (dl + IS875.imposed[occ].udl)),
                    style = MaterialTheme.typography.bodySmall)
            }
            Sec("Wall line load and partition allowance (IS 875-2 Cl. 3.1.2)") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IS875.unitWeights.forEachIndexed { i, u -> if (u.kNm3 < 30) FilterChip(i == mat, { mat = i }, { Text(u.material) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    F("Wall t (mm)", wt, Modifier.weight(1f)) { wt = it }
                    F("Height (m)", wh, Modifier.weight(1f)) { wh = it }
                }
                val g = IS875.unitWeights[mat].kNm3
                val wl = IS875.wallLoad(n(wt, 230.0) / 1000, n(wh, 2.75), g)
                Text(String.format(Locale.US, "w = %.3f × %.2f × %.2f + 2 × 0.012 × 20.4 × %.2f = %.2f kN/m", n(wt, 230.0) / 1000, g, n(wh, 2.75), n(wh, 2.75), wl), fontWeight = FontWeight.SemiBold)
                Text(String.format(Locale.US, "If partitions are not on beams: UDL ≥ max(1.0, w/3) = %.2f kN/m² over the floor.", IS875.partitionAllowance(wl)), style = MaterialTheme.typography.bodySmall)
            }
            Sec("Unit converter — imperial ↔ SI (STAAD / ACI drawings)") { UnitConverter() }
            Sec("Unit weights (IS 875-1 Table 1)") {
                IS875.unitWeights.forEach { Text(String.format(Locale.US, "%-34s %6.2f kN/m³  %s", it.material, it.kNm3, it.source), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
            Sec("Live-load reduction on columns / footings (IS 875-2 Cl. 3.2.1, Table 3)") {
                listOf(1, 2, 3, 4, 5, 11).forEach { k ->
                    Text(String.format(Locale.US, "%-24s %3.0f %%", if (k == 5) "5 to 10 floors" else if (k == 11) "over 10 floors" else "$k floor(s) carried", 100 * IS875.reduction(k)),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Text("Roof counts as a floor. Not for storage, warehouse or garage floors. Applied automatically in Drawing → Structure take-down.", style = MaterialTheme.typography.labelSmall)
            }
            Sec("Load combinations — IS 456 Table 18 (used by Analyse / Steel checks / AI check)") {
                IS875.table18.forEach { (k, v) -> Text("$k:  $v", style = MaterialTheme.typography.bodySmall) }
                Text("Seismic: IS 1893-1 Cl. 6.3.1.2 — EL replaces WL; 100 % + 30 % orthogonal not yet applied.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Sec(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@Composable
private fun F(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

private val UNITS = listOf(
    Triple("kip", "kN", 4.4482216), Triple("ft", "m", 0.3048), Triple("in", "mm", 25.4), Triple("kip/ft", "kN/m", 14.593903),
    Triple("ksi", "MPa", 6.8947573), Triple("psi", "MPa", 0.0068947573), Triple("kip·ft", "kN·m", 1.3558179), Triple("psf", "kN/m²", 0.04788026),
    Triple("pcf", "kN/m³", 0.15708746), Triple("in²", "mm²", 645.16), Triple("in⁴", "mm⁴", 416231.43),
)

@Composable
private fun UnitConverter() {
    var k by rememberSaveable { mutableStateOf(0) }
    var v by rememberSaveable { mutableStateOf("1") }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        UNITS.forEachIndexed { i, (a, b, _) -> FilterChip(i == k, { k = i }, { Text("$a → $b") }) }
    }
    F("Value", v, Modifier.fillMaxWidth()) { v = it }
    val x = v.replace(',', '.').toDoubleOrNull() ?: 0.0
    val (a, b, f) = UNITS[k]
    Text(String.format(Locale.US, "%.4f %s = %.4f %s     |     %.4f %s = %.4f %s", x, a, x * f, b, x, b, x / f, a), fontWeight = FontWeight.SemiBold)
}
