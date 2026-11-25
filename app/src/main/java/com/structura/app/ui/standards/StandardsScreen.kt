package com.structura.app.ui.standards

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Imposed floor loads, IS 875 (Part 2):1987 Table 1 (selected occupancies; verify against the current edition). */
private val OCCUPANCY = listOf(
    "Residential rooms" to 2.0,
    "Residential stairs / corridors / balconies" to 3.0,
    "Classrooms" to 3.0,
    "Office (with separate storage)" to 2.5,
    "Office (without separate storage)" to 4.0,
    "Assembly, fixed seats" to 4.0,
    "Assembly, no fixed seats" to 5.0,
    "Shops / retail" to 4.0,
    "Roof, access provided" to 1.5,
    "Roof, no access" to 0.75,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Indian Standards · Load assumptions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FloorLoadCalculator()
            SlabToBeamCalculator()
            Ref(
                "Unit weights — IS 875 (Part 1):1987",
                listOf(
                    "Reinforced concrete" to "25.0 kN/m³", "Plain concrete" to "24.0 kN/m³",
                    "Brick masonry" to "≈ 19–20 kN/m³", "Structural steel" to "78.5 kN/m³",
                    "GFRP pultruded (typical)" to "≈ 18–20 kN/m³", "Water" to "9.81 kN/m³",
                    "Floor finish (typical allowance)" to "1.0–1.5 kN/m²",
                ),
            )
            Ref("Imposed loads — IS 875 (Part 2):1987 Table 1", OCCUPANCY.map { it.first to "${it.second} kN/m²" })
            Ref(
                "Limit-state combinations — IS 456:2000 Table 18 / IS 875 (Part 5)",
                listOf(
                    "DL + LL" to "1.5 DL + 1.5 LL", "DL + WL (EL)" to "1.5 DL ± 1.5 WL  or  0.9 DL ± 1.5 WL",
                    "DL + LL + WL (EL)" to "1.2 DL + 1.2 LL ± 1.2 WL", "Serviceability" to "1.0 DL + 1.0 LL (+ 0.8 combos with WL)",
                ),
            )
            Ref(
                "Seismic — IS 1893 (Part 1):2016",
                listOf(
                    "Zone factor Z (Table 3)" to "II 0.10 · III 0.16 · IV 0.24 · V 0.36",
                    "Design coefficient" to "Ah = (Z/2)·(Sa/g)/(R/I)",
                    "Importance factor I (Table 8)" to "1.0 normal · 1.2 ordinary important · 1.5 critical",
                    "Response reduction R (Table 9)" to "RC OMRF 3.0 · RC SMRF 5.0",
                    "Imposed load in seismic weight (Table 10)" to "25 % if ≤ 3 kN/m², 50 % if > 3 kN/m²",
                    "Modal mass (Cl. 7.7.5.2)" to "Modes up to ≥ 90 % mass participation",
                ),
            )
            Ref(
                "Wind — IS 875 (Part 3):2015",
                listOf(
                    "Design wind speed" to "Vz = Vb · k1 · k2 · k3 · k4",
                    "Design wind pressure" to "pz = 0.6 · Vz²  (N/m², Vz in m/s)",
                    "Basic wind speed Vb" to "From the code map / Annex A for the site",
                ),
            )
            Ref(
                "Durability & deflection — IS 456 / IS 800",
                listOf(
                    "Nominal cover (IS 456 Table 16)" to "Mild 20 · Moderate 30 · Severe 45 · Very severe 50 · Extreme 75 mm",
                    "RCC deflection (IS 456 Cl. 23.2)" to "Final ≤ span/250; after partitions ≤ span/350 or 20 mm",
                    "Steel floor beams (IS 800 Table 6)" to "span/300 (elastic cladding) · span/360 (brittle finishes)",
                    "Second-order effects (IS 800 Cl. 4.4.2)" to "Consider when λcr < 10",
                ),
            )
            Text(
                "Values are quick-reference extracts. Always confirm against the current edition of each code and the project specification.",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Ref(title: String, rows: List<Pair<String, String>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            rows.forEach { (k, v) ->
                Row { Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(v, Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun FloorLoadCalculator() {
    var t by remember { mutableStateOf("125") }
    var ff by remember { mutableStateOf("1.0") }
    var part by remember { mutableStateOf("1.0") }
    var occ by remember { mutableStateOf(0) }
    fun n(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    val sw = 25.0 * n(t) / 1000
    val dl = sw + n(ff) + n(part)
    val ll = OCCUPANCY[occ].second
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Load assumptions — floor area loads", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Num("Slab t (mm)", t, Modifier.weight(1f)) { t = it }
                Num("Floor finish (kN/m²)", ff, Modifier.weight(1f)) { ff = it }
                Num("Partitions (kN/m²)", part, Modifier.weight(1f)) { part = it }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OCCUPANCY.forEachIndexed { k, (name, v) -> FilterChip(k == occ, { occ = k }, { Text("$name ($v)") }) }
            }
            Text(
                String.format(Locale.US, "DL = 25 × %.3f + %.2f + %.2f = %.2f kN/m²   ·   LL = %.2f kN/m²\n" +
                    "Factored 1.5(DL + LL) = %.2f kN/m²   ·   Seismic weight DL + %d %% LL = %.2f kN/m²",
                    n(t) / 1000, n(ff), n(part), dl, ll, 1.5 * (dl + ll), if (ll <= 3.0) 25 else 50, dl + ll * (if (ll <= 3.0) 0.25 else 0.5)),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SlabToBeamCalculator() {
    var w by remember { mutableStateOf("8.0") }
    var lx by remember { mutableStateOf("3.0") }
    var ly by remember { mutableStateOf("4.0") }
    fun n(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    val a = minOf(n(lx), n(ly)); val b = maxOf(n(lx), n(ly))
    val q = n(w)
    val oneWay = b / a > 2.0
    val shortBeam = q * a / 3
    val longBeam = if (b > 0) q * a / 6 * (3 - (a / b) * (a / b)) else 0.0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Slab → beam load transfer (equivalent UDL for bending)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Num("w (kN/m²)", w, Modifier.weight(1f)) { w = it }
                Num("lx (m)", lx, Modifier.weight(1f)) { lx = it }
                Num("ly (m)", ly, Modifier.weight(1f)) { ly = it }
            }
            Text(
                if (oneWay) String.format(Locale.US, "ly/lx = %.2f > 2 → one-way slab: each long beam carries w·lx/2 = %.2f kN/m (short beams ≈ nil).", b / a, q * a / 2)
                else String.format(Locale.US, "ly/lx = %.2f ≤ 2 → two-way slab (45° yield lines):\n" +
                    "short-span beams (triangular) ≈ w·lx/3 = %.2f kN/m\nlong-span beams (trapezoidal) ≈ w·lx/6·(3 − (lx/ly)²) = %.2f kN/m", b / a, shortBeam, longBeam),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            )
            Text("Per side of each beam; add both sides for interior beams. For exact shapes use the Trapezoid member load in 3D Studio.",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Num(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
}
