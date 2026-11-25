package com.structura.app.ui.steel

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.structura.app.ui.rcc.CheckSheet
import com.structura.app.ui.rcc.Field
import com.structura.app.ui.theme.FailRed
import com.structura.core.steel.IS800
import com.structura.core.steel.SteelGrade
import com.structura.core.steel.SteelResult
import com.structura.core.steel.SteelSection
import java.util.Locale

enum class SteelTask(val label: String) { BEAM("Beam"), COLUMN("Column"), TENSION("Tension"), BEAM_COLUMN("Beam-column"), BOLTS("Bolts"), WELDS("Fillet weld") }

private val FAMILIES = listOf("ISMB", "ISHB", "ISMC", "SHS", "RHS", "CHS", "Custom I")

private fun n(s: String, d: Double = 0.0) = s.replace(',', '.').toDoubleOrNull() ?: d

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SteelScreen(onBack: () -> Unit) {
    var task by rememberSaveable { mutableStateOf(SteelTask.BEAM) }
    var grade by remember { mutableStateOf(SteelGrade.E250) }
    var family by rememberSaveable { mutableStateOf("ISMB") }
    var secName by rememberSaveable { mutableStateOf("ISMB 300") }
    var ch by rememberSaveable { mutableStateOf("400") }
    var cbf by rememberSaveable { mutableStateOf("200") }
    var ctf by rememberSaveable { mutableStateOf("16") }
    var ctw by rememberSaveable { mutableStateOf("10") }
    var welded by rememberSaveable { mutableStateOf(true) }
    // loads / lengths
    var mu by rememberSaveable { mutableStateOf("120") }
    var vu by rememberSaveable { mutableStateOf("90") }
    var p by rememberSaveable { mutableStateOf("400") }
    var my by rememberSaveable { mutableStateOf("5") }
    var span by rememberSaveable { mutableStateOf("6000") }
    var llt by rememberSaveable { mutableStateOf("0") }
    var klz by rememberSaveable { mutableStateOf("4000") }
    var kly by rememberSaveable { mutableStateOf("4000") }
    var ws by rememberSaveable { mutableStateOf("10") }
    var defl by rememberSaveable { mutableStateOf(300.0) }
    var cant by rememberSaveable { mutableStateOf(false) }
    var holes by rememberSaveable { mutableStateOf("4") }
    var holeDia by rememberSaveable { mutableStateOf("22") }
    // bolts / welds
    var boltDia by rememberSaveable { mutableStateOf("20") }
    var bolt88 by rememberSaveable { mutableStateOf(false) }
    var plateT by rememberSaveable { mutableStateOf("10") }
    var edge by rememberSaveable { mutableStateOf("40") }
    var pitch by rememberSaveable { mutableStateOf("60") }
    var planes by rememberSaveable { mutableStateOf("1") }
    var force by rememberSaveable { mutableStateOf("200") }
    var weld by rememberSaveable { mutableStateOf("6") }
    var thick by rememberSaveable { mutableStateOf("12") }
    var shop by rememberSaveable { mutableStateOf(true) }

    var out by remember { mutableStateOf<Pair<String, SteelResult>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    fun section(): SteelSection = if (family == "Custom I") SteelSection.iSection("I ${ch}×${cbf}×${ctf}×${ctw}", n(ch, 400.0), n(cbf, 200.0), n(ctf, 16.0), n(ctw, 10.0), welded)
    else SteelSection.library.first { it.name == secName }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Steel design — IS 800:2007") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SteelTask.entries.forEach { t -> FilterChip(t == task, { task = t; out = null }, { Text(t.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SteelGrade.all.forEach { g -> FilterChip(g == grade, { grade = g }, { Text(g.name) }) }
            }
            if (task != SteelTask.BOLTS && task != SteelTask.WELDS) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FAMILIES.forEach { f -> FilterChip(f == family, {
                        family = f
                        SteelSection.library.firstOrNull { it.name.startsWith(f) }?.let { secName = it.name }
                    }, { Text(f) }) }
                }
                if (family == "Custom I") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("h", ch, Modifier.weight(1f)) { ch = it }; Field("bf", cbf, Modifier.weight(1f)) { cbf = it }
                        Field("tf", ctf, Modifier.weight(1f)) { ctf = it }; Field("tw", ctw, Modifier.weight(1f)) { ctw = it }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(welded, { welded = it }); Text("  Welded plate girder (buckling class b/c, αLT 0.49)") }
                } else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SteelSection.library.filter { it.name.startsWith(family) }.forEach { s -> FilterChip(s.name == secName, { secName = s.name }, { Text(s.name) }) }
                }
                runCatching { section() }.getOrNull()?.let { s ->
                    Text(String.format(Locale.US, "%s: A %.1f cm², Izz %.0f cm⁴, Iyy %.1f cm⁴, Zpz %.1f cm³, rz %.1f mm, ry %.1f mm, %.1f kg/m",
                        s.name, s.area / 100, s.izz / 1e4, s.iyy / 1e4, s.zpz / 1e3, s.rz, s.ry, s.massPerM), style = MaterialTheme.typography.bodySmall)
                }
                Text("Properties from nominal IS 808 dimensions without root fillets (1–4 % conservative). Verify against IS 808 / SP 6.", style = MaterialTheme.typography.labelSmall)
            }
            when (task) {
                SteelTask.BEAM -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("Mu (kN·m)", mu, Modifier.weight(1f)) { mu = it }; Field("Vu (kN)", vu, Modifier.weight(1f)) { vu = it }
                        Field("Span (mm)", span, Modifier.weight(1f)) { span = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("Unbraced LLT (mm, 0 = restrained)", llt, Modifier.weight(1.4f)) { llt = it }
                        Field("Service UDL (kN/m)", ws, Modifier.weight(1f)) { ws = it }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(240.0, 300.0, 360.0).forEach { d -> FilterChip(d == defl, { defl = d }, { Text("span/${d.toInt()}") }) }
                        Switch(cant, { cant = it }); Text(" Cantilever")
                    }
                }
                SteelTask.COLUMN -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("P (kN)", p, Modifier.weight(1f)) { p = it }
                    Field("KL z–z (mm)", klz, Modifier.weight(1f)) { klz = it }; Field("KL y–y (mm)", kly, Modifier.weight(1f)) { kly = it }
                }
                SteelTask.TENSION -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("T (kN)", p, Modifier.weight(1f)) { p = it }; Field("Holes in section", holes, Modifier.weight(1f)) { holes = it }
                    Field("Hole Ø (mm)", holeDia, Modifier.weight(1f)) { holeDia = it }; Field("Length (mm)", klz, Modifier.weight(1f)) { klz = it }
                }
                SteelTask.BEAM_COLUMN -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("P (kN)", p, Modifier.weight(1f)) { p = it }; Field("Mz (kN·m)", mu, Modifier.weight(1f)) { mu = it }; Field("My (kN·m)", my, Modifier.weight(1f)) { my = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("KL z–z", klz, Modifier.weight(1f)) { klz = it }; Field("KL y–y", kly, Modifier.weight(1f)) { kly = it }; Field("LLT", llt, Modifier.weight(1f)) { llt = it }
                    }
                    Text("Equivalent moment factors Cm = 0.9 (Table 18 conservative default).", style = MaterialTheme.typography.labelSmall)
                }
                SteelTask.BOLTS -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("Bolt Ø (mm)", boltDia, Modifier.weight(1f)) { boltDia = it }; Field("Force V (kN)", force, Modifier.weight(1f)) { force = it }
                        Field("Plate t (mm)", plateT, Modifier.weight(1f)) { plateT = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("Edge e (mm)", edge, Modifier.weight(1f)) { edge = it }; Field("Pitch p (mm)", pitch, Modifier.weight(1f)) { pitch = it }
                        Field("Shear planes", planes, Modifier.weight(1f)) { planes = it }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(bolt88, { bolt88 = it }); Text(if (bolt88) "  Grade 8.8" else "  Grade 4.6") }
                }
                SteelTask.WELDS -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("Weld size s (mm)", weld, Modifier.weight(1f)) { weld = it }; Field("Force (kN)", force, Modifier.weight(1f)) { force = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field("Thinner part (mm)", plateT, Modifier.weight(1f)) { plateT = it }; Field("Thicker part (mm)", thick, Modifier.weight(1f)) { thick = it }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(shop, { shop = it }); Text(if (shop) "  Shop weld (γmw 1.25)" else "  Field weld (γmw 1.5)") }
                }
            }
            Button(onClick = {
                err = null
                runCatching {
                    when (task) {
                        SteelTask.BEAM -> { val s = section(); "Md" to IS800.beam(s, grade, n(mu), n(vu), n(span, 6000.0), n(llt), n(ws), defl, cant) }
                        SteelTask.COLUMN -> { val s = section(); "Pd" to IS800.compression(s, grade, n(p), n(klz, 3000.0), n(kly, 3000.0)) }
                        SteelTask.TENSION -> { val s = section(); "Td" to IS800.tension(s, grade, n(p), n(holes).toInt(), n(holeDia, 22.0), s.tf, n(klz, 3000.0)) }
                        SteelTask.BEAM_COLUMN -> { val s = section(); "Interaction" to IS800.beamColumn(s, grade, n(p), n(mu), n(my), n(klz, 3000.0), n(kly, 3000.0), n(llt)) }
                        SteelTask.BOLTS -> {
                            val b = if (bolt88) IS800.Bolt.g88(n(boltDia, 20.0)) else IS800.Bolt.g46(n(boltDia, 20.0))
                            "Bolt value (kN)" to IS800.boltGroup(b, grade.fu, n(plateT, 10.0), n(force), n(edge, 40.0), n(pitch, 60.0), n(planes, 1.0).toInt())
                        }
                        SteelTask.WELDS -> "Weld length (mm)" to IS800.filletWeld(n(weld, 6.0), n(force), grade.fu, n(plateT, 10.0), n(thick, 12.0), shop)
                    }
                }.onSuccess { out = it }.onFailure { err = it.message; out = null }
            }, modifier = Modifier.fillMaxWidth()) { Text("Check to IS 800") }
            err?.let { Text(it, color = FailRed) }
            out?.let { (label, r) ->
                CheckSheet(r.isSafe, listOf(
                    String.format(Locale.US, "%s = %.2f", label, r.capacity),
                    String.format(Locale.US, "Utilisation = %.3f", r.utilisation),
                ), r.checks, r.steps)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
