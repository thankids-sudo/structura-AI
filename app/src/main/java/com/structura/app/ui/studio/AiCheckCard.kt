package com.structura.app.ui.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.structura.core.fem.ResultCheck

/** "AI result check" panel: engineering review of the model and all combinations, with clauses; tap a finding to select its member. */
@Composable
internal fun AiCheckCard(review: ResultCheck.Review?, busy: Boolean, onRun: () -> Unit, onPick: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI result check", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(onClick = onRun, enabled = !busy) { Text("✦ Review") }
            }
            Text("Load path, equilibrium, deflection (span/250), drift (0.004 h), proportions, missing cases and combinations.",
                style = MaterialTheme.typography.labelSmall)
            review?.let { r ->
                r.summary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                r.findings.forEach { f ->
                    val (mark, col) = when (f.level) {
                        ResultCheck.Level.ERROR -> "✘" to Color(0xFFC62828)
                        ResultCheck.Level.WARN -> "⚠" to Color(0xFFE65100)
                        ResultCheck.Level.INFO -> "ℹ" to Color(0xFF1565C0)
                        ResultCheck.Level.OK -> "✔" to Color(0xFF2E7D32)
                    }
                    Text(
                        "$mark ${f.topic}: ${f.message}" + if (f.clause.isNotEmpty()) "  [${f.clause}]" else "",
                        color = col, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = f.member != null) { f.member?.let(onPick) },
                    )
                }
            }
        }
    }
}
