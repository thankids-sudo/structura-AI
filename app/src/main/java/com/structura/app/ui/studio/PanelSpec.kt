package com.structura.app.ui.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.structura.core.fem.LoadCase
import com.structura.core.fem.Panel
import kotlin.math.sqrt

enum class PanelKind(val label: String) { RCC("RCC slab"), GFRP("GFRP plate"), CUSTOM("Custom E/G/ν/γ") }

/** Panel thickness, material and pressure for the Panel tool and the panel editor (mm, MPa, kN/m³, kN/m²). */
class PanelSpec {
    var kind by mutableStateOf(PanelKind.RCC)
    var t by mutableStateOf("125")
    var fck by mutableStateOf("25")
    var e by mutableStateOf("18000")
    var g by mutableStateOf("3200")
    var nu by mutableStateOf("0.25")
    var gamma by mutableStateOf("19")
    var pressure by mutableStateOf("-3.0")
    var case by mutableStateOf(LoadCase.LL)
    var meshX by mutableStateOf("4")
    var meshY by mutableStateOf("4")

    private fun n(s: String, d: Double) = s.replace(',', '.').toDoubleOrNull() ?: d

    fun proto(id: Int = 0, nodes: List<Int> = listOf(0, 0, 0, 0)): Panel {
        val tt = n(t, 125.0) / 1000
        val p = n(pressure, 0.0)
        return when (kind) {
            PanelKind.RCC -> {
                val ee = 5000 * sqrt(n(fck, 25.0)) * 1000
                Panel(id, nodes, tt, ee, ee / 2.4, 0.2, 25.0, p, case, "RCC M${n(fck, 25.0).toInt()} slab")
            }
            PanelKind.GFRP -> Panel(id, nodes, tt, 18000e3, 3200e3, 0.25, 19.0, p, case, "GFRP plate (illustrative)")
            PanelKind.CUSTOM -> Panel(id, nodes, tt, n(e, 18000.0) * 1000, n(g, 3200.0) * 1000, n(nu, 0.25), n(gamma, 19.0), p, case, "Custom plate")
        }
    }

    fun mesh() = n(meshX, 4.0).toInt().coerceIn(1, 20) to n(meshY, 4.0).toInt().coerceIn(1, 20)

    fun from(p: Panel) {
        t = fmt(p.t * 1000); pressure = fmt(p.pressure); case = p.case
        e = fmt(p.e / 1000); g = fmt(p.g / 1000); nu = fmt(p.nu); gamma = fmt(p.gamma)
        kind = when {
            p.name.startsWith("RCC") -> { fck = p.name.substringAfter("M").substringBefore(" "); PanelKind.RCC }
            p.name.startsWith("GFRP") -> PanelKind.GFRP
            else -> PanelKind.CUSTOM
        }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}

@Composable
fun PanelPicker(spec: PanelSpec, showMesh: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Panel", style = MaterialTheme.typography.labelMedium)
            PanelKind.entries.forEach { k -> FilterChip(k == spec.kind, { spec.kind = k }, { Text(k.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            F("t (mm)", spec.t, Modifier.weight(1f)) { spec.t = it }
            if (spec.kind == PanelKind.RCC) F("fck (MPa)", spec.fck, Modifier.weight(1f)) { spec.fck = it }
            F("Pressure (kN/m²)", spec.pressure, Modifier.weight(1.4f)) { spec.pressure = it }
        }
        if (spec.kind == PanelKind.CUSTOM) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            F("E (MPa)", spec.e, Modifier.weight(1f)) { spec.e = it }
            F("G (MPa)", spec.g, Modifier.weight(1f)) { spec.g = it }
            F("ν", spec.nu, Modifier.weight(0.7f)) { spec.nu = it }
            F("γ (kN/m³)", spec.gamma, Modifier.weight(1f)) { spec.gamma = it }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pressure case", style = MaterialTheme.typography.labelMedium)
            listOf(LoadCase.DL, LoadCase.LL, LoadCase.WL).forEach { c -> FilterChip(c == spec.case, { spec.case = c }, { Text(c) }) }
        }
        if (showMesh) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            F("Mesh nx", spec.meshX, Modifier.weight(1f)) { spec.meshX = it }
            F("Mesh ny", spec.meshY, Modifier.weight(1f)) { spec.meshY = it }
        }
        Text(
            "Pressure acts along the panel normal (nodes picked anticlockwise seen from above → normal up, so − = downward). " +
                "Self-weight γ·t is added to DL automatically.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun F(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
}
