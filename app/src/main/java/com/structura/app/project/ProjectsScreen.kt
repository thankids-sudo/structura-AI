package com.structura.app.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.structura.core.fem.Benchmarks
import com.structura.core.fem.FrameModel
import com.structura.core.fem.Generators
import com.structura.core.fem.Material
import com.structura.core.fem.ModelEditor
import com.structura.core.fem.Section

private val Ground = Color(0xFF0E1726)
private val Panel = Color(0xFF16233A)
private val Line = Color(0xFF24364F)
private val Ink = Color(0xFFE6EEF8)
private val Muted = Color(0xFF93A4BD)
private val Cyan = Color(0xFF3DD6F5)
private val Orange = Color(0xFFF5A524)
private val Dark = Color(0xFF0B1320)

private enum class Template(val label: String) { EMPTY("Empty"), PORTAL("Portal frame"), BUILDING("3-storey building"), TRUSS("Roof truss shed") }

private fun model(t: Template): FrameModel = when (t) {
    Template.EMPTY -> ModelEditor.empty("New project")
    Template.PORTAL -> Generators.portalFrame(listOf(6.0), listOf(4.0), Section.rect(0.3, 0.45), Section.rect(0.3, 0.5), Material.concrete(25.0), beamUdl = 20.0, lateral = 10.0)
    Template.BUILDING -> Benchmarks.building()
    Template.TRUSS -> com.structura.core.roof.RoofTruss.design(com.structura.core.roof.RoofTruss.Input()).model
}

/** Start screen: create a new project or open a saved one. */
@Composable
fun ProjectsScreen(onOpened: () -> Unit) {
    val context = LocalContext.current
    var projects by remember { mutableStateOf(ProjectStore.list(context)) }
    var name by remember { mutableStateOf("") }
    var client by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var engineer by remember { mutableStateOf(ProjectStore.lastEngineer(context)) }
    var template by remember { mutableStateOf(Template.EMPTY) }
    var confirmDelete by remember { mutableStateOf<ProjectInfo?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Cyan, unfocusedBorderColor = Line,
        focusedLabelColor = Cyan, unfocusedLabelColor = Muted, cursorColor = Cyan,
    )

    Column(
        Modifier.fillMaxSize().background(Ground).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(com.structura.app.R.drawable.structura_logo), "Structura AI logo", Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Structura AI", color = Ink, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Text("Projects", color = Muted, fontSize = 13.sp)
            }
        }

        ProjectStore.current?.let { cur ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Cyan).clickable(role = Role.Button) { onOpened() }.padding(16.dp)) {
                Text("Continue  ·  ${cur.name}", color = Dark, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }

        // new project
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).border(BorderStroke(1.dp, Line), RoundedCornerShape(16.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New project", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = colors)
            OutlinedTextField(client, { client = it }, label = { Text("Client / department") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = colors)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(location, { location = it }, label = { Text("Location") }, singleLine = true, modifier = Modifier.weight(1f), colors = colors)
                OutlinedTextField(engineer, { engineer = it }, label = { Text("Engineer") }, singleLine = true, modifier = Modifier.weight(1f), colors = colors)
            }
            Text("Start from", color = Muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Template.entries.forEach { t ->
                    val sel = t == template
                    Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (sel) Cyan.copy(alpha = 0.15f) else Ground)
                        .border(BorderStroke(1.dp, if (sel) Cyan else Line), RoundedCornerShape(18.dp)).clickable(role = Role.Button) { template = t }
                        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text(t.label, color = if (sel) Cyan else Muted, fontSize = 12.sp) }
                }
            }
            Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(if (name.isBlank()) Line else Orange)
                .clickable(enabled = name.isNotBlank(), role = Role.Button) {
                    ProjectStore.create(context, name.trim(), client.trim(), location.trim(), engineer.trim(), model(template))
                    onOpened()
                }, contentAlignment = Alignment.Center) { Text("Create project", color = Dark, fontWeight = FontWeight.SemiBold) }
        }

        // saved projects
        Text("Saved projects (${projects.size})", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        if (projects.isEmpty()) Text("No saved projects yet. Projects save automatically while you work.", color = Muted, fontSize = 13.sp)
        projects.forEach { p ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).border(BorderStroke(1.dp, if (p.id == ProjectStore.current?.id) Cyan else Line), RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClickLabel = "Open ${p.name}") { if (ProjectStore.open(context, p.id)) onOpened() }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(listOf(p.client, p.location).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" }, color = Muted, fontSize = 12.sp)
                    Text("${p.members} members · saved ${p.modifiedText}", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClickLabel = "Delete ${p.name}") { confirmDelete = p },
                    contentAlignment = Alignment.Center) { Text("✕", color = Muted, fontSize = 16.sp) }
            }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Delete project?") }, text = { Text("\"${p.name}\" and its saved designs will be removed from this phone.") },
            confirmButton = { TextButton({ ProjectStore.delete(context, p.id); projects = ProjectStore.list(context); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } })
    }
}
