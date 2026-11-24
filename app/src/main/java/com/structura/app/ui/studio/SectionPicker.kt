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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.structura.core.fem.Material
import com.structura.core.fem.Section
import com.structura.core.fem.SectionCatalogue

enum class ShapeKind(val label: String) { RECT("Rectangle"), CHS("CHS ○"), SHS("SHS □"), RHS("RHS ▭"), CATALOGUE("Catalogue") }
enum class MaterialKind(val label: String) { CONCRETE("Concrete"), STEEL("Steel"), GFRP("GFRP"), CUSTOM("Custom E/G/γ") }

/** Section + material choice shared by the Member tool and the member editor. All inputs in mm / MPa / kN/m³. */
class SectionSpec {
    var shape by mutableStateOf(ShapeKind.RECT)
    var b by mutableStateOf("300")
    var d by mutableStateOf("450")
    var t by mutableStateOf("6")
    var family by mutableStateOf(SectionCatalogue.families.first())
    var catIndex by mutableIntStateOf(0)
    var material by mutableStateOf(MaterialKind.CONCRETE)
    var fck by mutableStateOf("25")
    var e by mutableStateOf("18000")
    var g by mutableStateOf("3200")
    var gamma by mutableStateOf("19")

    private fun n(s: String, def: Double) = s.replace(',', '.').toDoubleOrNull() ?: def

    fun catalogueEntries() = SectionCatalogue.entries.filter { it.family == family }

    fun section(): Section = when (shape) {
        ShapeKind.RECT -> Section.rect(n(b, 300.0) / 1000, n(d, 450.0) / 1000)
        ShapeKind.CHS -> Section.chs(n(d, 100.0) / 1000, n(t, 5.0) / 1000)
        ShapeKind.SHS -> Section.rhs(n(b, 100.0) / 1000, n(b, 100.0) / 1000, n(t, 5.0) / 1000)
        ShapeKind.RHS -> Section.rhs(n(b, 50.0) / 1000, n(d, 100.0) / 1000, n(t, 5.0) / 1000)
        ShapeKind.CATALOGUE -> catalogueEntries().getOrElse(catIndex) { catalogueEntries().first() }.section
    }

    fun material(): Material = when (material) {
        MaterialKind.CONCRETE -> Material.concrete(n(fck, 25.0))
        MaterialKind.STEEL -> Material.steel()
        MaterialKind.GFRP -> Material.gfrp()
        MaterialKind.CUSTOM -> Material.custom("E ${e} / G ${g} MPa", n(e, 18000.0), n(g, 3200.0), n(gamma, 19.0))
    }

    /** Load an existing member's section into the picker. */
    fun from(s: Section, m: Material) {
        when (s.shape) {
            "CHS" -> { shape = ShapeKind.CHS; d = mm(s.h); t = mm(s.t) }
            "SHS" -> { shape = ShapeKind.SHS; b = mm(s.b); t = mm(s.t) }
            "RHS" -> { shape = ShapeKind.RHS; b = mm(s.b); d = mm(s.h); t = mm(s.t) }
            else -> { shape = ShapeKind.RECT; if (s.b > 0) b = mm(s.b); if (s.h > 0) d = mm(s.h) }
        }
        e = mm(m.e); g = mm(m.g); gamma = m.gamma.toString()
        material = when {
            m.name.startsWith("M") && m.gamma == 25.0 -> { fck = m.name.drop(1); MaterialKind.CONCRETE }
            m.name.startsWith("E") && m.gamma == 78.5 -> MaterialKind.STEEL
            m.name.startsWith("GFRP") -> MaterialKind.GFRP
            else -> MaterialKind.CUSTOM
        }
    }

    private fun mm(v: Double): String {
        val x = v * 1000
        return if (kotlin.math.abs(x - Math.round(x)) < 1e-6) Math.round(x).toString() else String.format(java.util.Locale.US, "%.1f", x)
    }
}

@Composable
fun SectionPicker(spec: SectionSpec) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Section", style = MaterialTheme.typography.labelMedium)
            ShapeKind.entries.forEach { k -> FilterChip(k == spec.shape, { spec.shape = k }, { Text(k.label) }) }
        }
        when (spec.shape) {
            ShapeKind.RECT -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("b (mm)", spec.b, Modifier.weight(1f)) { spec.b = it }
                Field("D (mm)", spec.d, Modifier.weight(1f)) { spec.d = it }
            }
            ShapeKind.CHS -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("OD (mm)", spec.d, Modifier.weight(1f)) { spec.d = it }
                Field("t (mm)", spec.t, Modifier.weight(1f)) { spec.t = it }
            }
            ShapeKind.SHS -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("B (mm)", spec.b, Modifier.weight(1f)) { spec.b = it }
                Field("t (mm)", spec.t, Modifier.weight(1f)) { spec.t = it }
            }
            ShapeKind.RHS -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("B (mm)", spec.b, Modifier.weight(1f)) { spec.b = it }
                Field("H (mm)", spec.d, Modifier.weight(1f)) { spec.d = it }
                Field("t (mm)", spec.t, Modifier.weight(1f)) { spec.t = it }
            }
            ShapeKind.CATALOGUE -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionCatalogue.families.forEach { f -> FilterChip(f == spec.family, { spec.family = f; spec.catIndex = 0 }, { Text(f) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    spec.catalogueEntries().forEachIndexed { k, en ->
                        FilterChip(k == spec.catIndex, { spec.catIndex = k }, { Text(en.section.name) })
                    }
                }
                Text("Properties computed from nominal dimensions (sharp corners) — verify against IS 4923 / IS 1161 or the manufacturer.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Material", style = MaterialTheme.typography.labelMedium)
            MaterialKind.entries.forEach { k -> FilterChip(k == spec.material, { spec.material = k }, { Text(k.label) }) }
        }
        when (spec.material) {
            MaterialKind.CONCRETE -> Field("fck (MPa)", spec.fck, Modifier) { spec.fck = it }
            MaterialKind.CUSTOM -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("E (MPa)", spec.e, Modifier.weight(1f)) { spec.e = it }
                Field("G (MPa)", spec.g, Modifier.weight(1f)) { spec.g = it }
                Field("γ (kN/m³)", spec.gamma, Modifier.weight(1f)) { spec.gamma = it }
            }
            MaterialKind.GFRP -> Text("GFRP pultruded, illustrative: E 23 000 MPa, G 3 500 MPa, γ 19 kN/m³. Use Custom for tested values.",
                style = MaterialTheme.typography.labelSmall)
            MaterialKind.STEEL -> Text("Steel E250: E 200 000 MPa, G 76 900 MPa, γ 78.5 kN/m³ (IS 800).", style = MaterialTheme.typography.labelSmall)
        }
        val sec = runCatching { spec.section() }
        Text(
            sec.fold(
                { s -> "${s.name}: A ${"%.2f".format(s.a * 1e4)} cm², Iy ${"%.1f".format(s.iy * 1e8)} cm⁴, Iz ${"%.1f".format(s.iz * 1e8)} cm⁴, J ${"%.1f".format(s.j * 1e8)} cm⁴" },
                { "Section error: ${it.message}" },
            ),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = modifier,
    )
}
