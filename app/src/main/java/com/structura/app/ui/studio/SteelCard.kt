package com.structura.app.ui.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.structura.app.ui.rcc.CheckSheet
import com.structura.core.steel.SteelModelCheck
import java.util.Locale

/** RFEM-style steel design add-on panel: run IS 800 checks on all steel members, list utilisation, show a member's check sheet. */
@Composable
internal fun SteelCard(
    checks: List<SteelModelCheck.MemberCheck>,
    busy: Boolean,
    k: Double, onK: (Double) -> Unit,
    llt: Double, onLlt: (Double) -> Unit,
    showUtil: Boolean, onShowUtil: (Boolean) -> Unit,
    selectedMember: Int?,
    onRun: () -> Unit,
    onPick: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Steel design — IS 800:2007 (all ULS combinations)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("K", style = MaterialTheme.typography.labelMedium)
                listOf(0.65, 0.8, 1.0, 1.2, 2.0).forEach { v -> FilterChip(v == k, { onK(v) }, { Text("$v") }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LLT / L", style = MaterialTheme.typography.labelMedium)
                listOf(0.0 to "0 (restrained)", 0.5 to "0.5", 1.0 to "1.0", 1.2 to "1.2").forEach { (v, l) -> FilterChip(v == llt, { onLlt(v) }, { Text(l) }) }
            }
            Text("K·L about both axes (Cl. 7.2.2, Table 11); LLT per Cl. 8.3 / Table 15. Node-to-node length L — split members at bracing points.",
                style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRun, enabled = !busy) { Text("⚙ Steel checks") }
                if (checks.isNotEmpty()) FilterChip(showUtil, { onShowUtil(!showUtil) }, { Text("Colour by η") })
            }
            if (checks.isNotEmpty()) {
                Text(" Mem  Section     Comb            η    ", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                checks.forEach { c ->
                    Text(
                        String.format(Locale.US, "%4s  %-10s  %-14s %5.3f %s", "M${c.member}", c.section.take(10), c.combination.take(14), c.utilisation, if (c.ok) "✔" else "✘"),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        color = if (!c.ok) Color(0xFFC62828) else if (c.member == selectedMember) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (c.member == selectedMember) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(c.member) },
                    )
                }
                val sel = checks.firstOrNull { it.member == selectedMember } ?: checks.first()
                CheckSheet(
                    sel.ok,
                    listOf(
                        "Member M${sel.member} · ${sel.section} · ${sel.grade} — governing ${sel.combination}",
                        String.format(Locale.US, "P = %.2f kN (+ compression) · Mz,major = %.2f · My,minor = %.2f kN·m · V = %.2f kN", sel.p, sel.mMajor, sel.mMinor, sel.v),
                        String.format(Locale.US, "Utilisation η = %.3f", sel.utilisation),
                    ),
                    sel.result.checks, sel.result.steps,
                )
            }
        }
    }
}
