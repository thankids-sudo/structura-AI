package com.structura.app.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.structura.app.DesignSession
import com.structura.app.project.ProjectStore
import com.structura.app.report.PdfExport
import com.structura.core.fem.FrameModel
import com.structura.core.rcc.Status
import com.structura.core.report.ReportDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Generic screen for a [DesignModule]: inputs (saved with the project) → design → on-screen report → PDF / open model in Studio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignModuleScreen(id: String, onBack: () -> Unit, onStudio: () -> Unit) {
    val module = remember(id) { DesignModules.get(id) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val values = remember(id) {
        mutableStateMapOf<String, String>().apply {
            val saved = ProjectStore.designInputs[id]
            module.fields.forEach { put(it.key, saved?.get(it.key) ?: it.default) }
        }
    }
    var report by remember(id) { mutableStateOf<ReportDoc?>(null) }
    var model by remember(id) { mutableStateOf<FrameModel?>(null) }
    var dxfText by remember(id) { mutableStateOf<String?>(null) }
    var scene by remember(id) { mutableStateOf<com.structura.core.viz.Scene3D?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    var resultsY by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(report) { if (report != null) { kotlinx.coroutines.delay(80); scroll.animateScrollTo(resultsY) } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text(module.title, fontWeight = FontWeight.Bold); Text(module.codes, style = MaterialTheme.typography.labelMedium) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProjectStore.current?.let { Text("Project: ${it.name}", style = MaterialTheme.typography.labelMedium) }
            for ((group, fields) in module.fields.groupBy { it.group }) {
                if (group.isNotEmpty()) Text(group, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                for (f in fields.filter { it.options != null }) {
                    Text(f.label, style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.options!!.forEach { o -> FilterChip(values[f.key] == o, { values[f.key] = o }, { Text(o) }) }
                    }
                }
                fields.filter { it.options == null }.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { f ->
                            OutlinedTextField(values[f.key] ?: "", { values[f.key] = it }, label = { Text(f.label, maxLines = 1) }, singleLine = true,
                                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    busy = true; error = null
                    val snapshot = values.toMap()
                    scope.launch {
                        try {
                            val (r, m) = withContext(Dispatchers.Default) { module.run(snapshot) }
                            dxfText = module.dxf?.let { f -> withContext(Dispatchers.Default) { f(snapshot) } }
                            scene = module.scene?.let { f -> withContext(Dispatchers.Default) { runCatching { f(snapshot) }.getOrNull() } }
                            report = r; model = m
                            ProjectStore.saveInputs(context, id, snapshot)
                        } catch (e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("⚙ Analyse & design") }
                if (busy) CircularProgressIndicator(Modifier.padding(4.dp))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            report?.let { r ->
                Row(Modifier.onGloballyPositioned { resultsY = it.positionInParent().y.toInt() }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { PdfExport.share(context, r) }, modifier = Modifier.weight(1f)) { Text("↓ PDF report") }
                    dxfText?.let { d ->
                        OutlinedButton(onClick = { com.structura.app.shareTextFile(context, "${module.id}.dxf", "application/dxf", d, module.title + " — detail drawing") }, modifier = Modifier.weight(1f)) { Text("↓ DXF") }
                    }
                    model?.let { m ->
                        OutlinedButton(onClick = { DesignSession.studioModel = m; onStudio() }, modifier = Modifier.weight(1f)) { Text("◇ Open in 3D Studio") }
                    }
                }
                scene?.let { sc ->
                    com.structura.app.ui.viz.SceneCard(sc, Modifier.fillMaxWidth().height(460.dp))
                }
                ReportView(r)
            }
        }
    }
}

/** On-screen rendering of a [ReportDoc]. */
@Composable
fun ReportView(doc: ReportDoc) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(doc.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(doc.subtitle, style = MaterialTheme.typography.labelMedium)
            Text(if (doc.isSafe) "✔ All checks satisfied" else "✘ One or more checks not satisfied",
                color = if (doc.isSafe) Color(0xFF2E7D32) else Color(0xFFC62828), fontWeight = FontWeight.SemiBold)
            for (b in doc.blocks) when (b) {
                is ReportDoc.Block.Heading -> { HorizontalDivider(); Text(b.text, fontWeight = FontWeight.SemiBold) }
                is ReportDoc.Block.Para -> Text(b.text, style = MaterialTheme.typography.bodySmall)
                is ReportDoc.Block.KeyValues -> b.rows.forEach { (k, v) ->
                    Row { Text(k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); Text(v, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.6f)) }
                }
                is ReportDoc.Block.Steps -> b.lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                is ReportDoc.Block.Table -> {
                    val w = b.weights ?: List(b.header.size) { 1f }
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        (listOf(b.header) + b.rows).forEachIndexed { k, row ->
                            Row {
                                row.forEachIndexed { i, cell ->
                                    Text(cell, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (k == 0) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(end = 8.dp).width((w.getOrElse(i) { 1f } * 70).dp))
                                }
                            }
                        }
                    }
                }
                is ReportDoc.Block.Checks -> b.checks.forEach { c ->
                    val col = when (c.status) { Status.PASS -> Color(0xFF2E7D32); Status.FAIL -> Color(0xFFC62828); Status.INFO -> Color(0xFF1565C0) }
                    Text("${when (c.status) { Status.PASS -> "✔"; Status.FAIL -> "✘"; Status.INFO -> "ℹ" }} ${c.item}: ${c.value} — ${c.requirement}  [${c.clause}]",
                        color = col, style = MaterialTheme.typography.bodySmall)
                }
                is ReportDoc.Block.Figure -> FigureView(b)
            }
        }
    }
}

@Composable
private fun FigureView(f: ReportDoc.Block.Figure) {
    Column {
        Canvas(Modifier.fillMaxWidth().height((f.heightPt * 0.9f).dp).background(Color.White)) {
            val xs = f.lines.flatMap { listOf(it.x1, it.x2) } + f.texts.map { it.x }
            val ys = f.lines.flatMap { listOf(it.y1, it.y2) } + f.texts.map { it.y }
            if (xs.isEmpty()) return@Canvas
            val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
            val pad = 24f
            val sc = minOf((size.width - 2 * pad) / maxOf(x1 - x0, 1e-6), (size.height - 2 * pad) / maxOf(y1 - y0, 1e-6)).toFloat()
            val ox = (size.width - ((x1 - x0) * sc).toFloat()) / 2; val oy = (size.height - ((y1 - y0) * sc).toFloat()) / 2
            fun p(x: Double, y: Double) = Offset(ox + ((x - x0) * sc).toFloat(), oy + ((y1 - y) * sc).toFloat())
            for (l in f.lines) drawLine(Color.Black, p(l.x1, l.y1), p(l.x2, l.y2), when (l.weight) { 1 -> 1.2f; 2 -> 2.4f; else -> 4f },
                pathEffect = if (l.dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 7f)) else null)
            val paint = android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.BLACK; typeface = android.graphics.Typeface.SERIF }
            drawIntoCanvas { c -> for (t in f.texts) { paint.textSize = t.sizePt * 3.2f; val q = p(t.x, t.y); c.nativeCanvas.drawText(t.text, q.x, q.y, paint) } }
        }
        Text(f.caption, style = MaterialTheme.typography.labelSmall)
    }
}
