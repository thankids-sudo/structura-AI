package com.structura.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.structura.app.Routes

/** One Structura AI module. A tool with a null route is planned for a later phase. */
data class ModuleTool(val name: String, val route: String?)

data class StructuraModule(
    val title: String,
    val codes: String,
    val icon: ImageVector,
    val phase: Int,
    val tools: List<ModuleTool>,
)

val MODULES = listOf(
    StructuraModule(
        "RCC Design", "IS 456 · IS 875 · IS 1893 · SP 16", Icons.Filled.ViewWeek, 1,
        listOf(
            ModuleTool("Beam design", Routes.RCC_BEAM),
            ModuleTool("Beam detail drawing (UCS · DXF)", Routes.RCC_DRAWING),
            ModuleTool("Slab design (one-way / two-way)", Routes.RCC_SLAB),
            ModuleTool("Column design (biaxial, slender)", Routes.RCC_COLUMN),
            ModuleTool("Isolated footing design", Routes.RCC_FOOTING),
        ),
    ),
    StructuraModule(
        "Steel Design", "IS 800 · IS 808 · IS 4923", Icons.Filled.Architecture, 1,
        listOf(ModuleTool("Beams, columns, tension, beam-columns", Routes.STEEL), ModuleTool("Bolted / welded connections", Routes.STEEL)),
    ),
    StructuraModule(
        "FEM Analysis + AI", "3D frame FEM · N / V / M diagrams · RFEM-style", Icons.Filled.AccountTree, 1,
        listOf(
            ModuleTool("3D Studio — model editor, analysis, buckling, report", Routes.STUDIO),
            ModuleTool("Frame wizard: beam / portal / space frame", Routes.FEM),
            ModuleTool("Indian Standards · load assumptions", Routes.STANDARDS),
            ModuleTool("Load combinations (IS 875 / IS 1893)", null),
            ModuleTool("AI result check", null),
        ),
    ),
    StructuraModule(
        "Drawing → Structure AI", "Plan → grid → layout → sizes", Icons.Filled.ViewInAr, 4,
        listOf(ModuleTool("Import plan", null), ModuleTool("Column / beam layout", null), ModuleTool("Load take-down", null)),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Structura AI", fontWeight = FontWeight.Bold)
                    Text("Structural design to Indian Standards", style = MaterialTheme.typography.labelMedium)
                }
            })
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad),
        ) {
            items(MODULES) { m -> ModuleCard(m, onOpen) }
        }
    }
}

@Composable
private fun ModuleCard(m: StructuraModule, onOpen: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(m.icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(m.codes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                AssistChip(onClick = {}, label = { Text("Phase ${m.phase}") })
            }
            Spacer(Modifier.padding(top = 8.dp))
            m.tools.forEach { t ->
                OutlinedButton(
                    onClick = { t.route?.let(onOpen) },
                    enabled = t.route != null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Text(if (t.route != null) t.name else "${t.name} — coming soon")
                }
            }
        }
    }
}
