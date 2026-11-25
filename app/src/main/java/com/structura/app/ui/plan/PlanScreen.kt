package com.structura.app.ui.plan

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.structura.app.DesignSession
import com.structura.app.shareTextFile
import com.structura.app.shareBytesFile
import com.structura.app.SorStore
import com.structura.core.plan.BoqExport
import com.structura.core.plan.PlanImageReader
import com.structura.core.plan.BuildingInput
import com.structura.core.plan.DxfPlan
import com.structura.core.plan.DxfReader
import com.structura.core.plan.PlanEntity
import com.structura.core.plan.PlanLayout
import com.structura.core.plan.PlanRecognizer
import com.structura.core.plan.PlanToStructure
import com.structura.core.plan.PreliminaryDesign
import com.structura.core.plan.SamplePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Phase 4: DXF plan → grid / columns / beams → load take-down & preliminary sizes → 3D model in Studio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(onBack: () -> Unit, onStudio: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<DxfPlan?>(null) }
    var base by remember { mutableStateOf<PlanLayout?>(null) }
    var removed by remember { mutableStateOf(setOf<Int>()) }
    var design by remember { mutableStateOf<PreliminaryDesign?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf("") }
    var detection by remember { mutableStateOf<PlanImageReader.Detection?>(null) }
    var span12 by remember { mutableStateOf("4.0") }
    var showSor by remember { mutableStateOf(false) }
    var sor by remember { mutableStateOf(SorStore.load(context)) }

    var storeys by remember { mutableStateOf("3") }
    var height by remember { mutableStateOf("3.2") }
    var plinth by remember { mutableStateOf("1.5") }
    var finishes by remember { mutableStateOf("1.5") }
    var ll by remember { mutableStateOf("3.0") }
    var roofLl by remember { mutableStateOf("1.5") }
    var wall by remember { mutableStateOf("12") }
    var fck by remember { mutableStateOf("25") }
    var fy by remember { mutableStateOf("500") }
    var maxSpan by remember { mutableStateOf("10") }

    fun n(s: String, d: Double) = s.replace(',', '.').toDoubleOrNull() ?: d
    val layout = base?.without(removed, n(maxSpan, 10.0))

    fun load(text: String, name: String) {
        busy = true; message = null; design = null; removed = emptySet()
        scope.launch {
            try {
                val (p, l) = withContext(Dispatchers.Default) {
                    val p = DxfReader.read(text)
                    p to PlanRecognizer.recognise(p, maxSpan = n(maxSpan, 10.0))
                }
                plan = p; base = l; fileName = name
                message = "$name · units ${p.unitName} · ${p.entities.size} entities · layers: ${p.layers.take(12).joinToString()}"
            } catch (e: Exception) { message = "Could not read the plan: ${e.message}" } finally { busy = false }
        }
    }

    fun loadImage(uri: android.net.Uri, isPdf: Boolean) {
        busy = true; message = null; design = null; removed = emptySet(); plan = null; base = null
        scope.launch {
            try {
                val det = withContext(Dispatchers.Default) {
                    val bmp = if (isPdf) PlanBitmaps.pdfPage(context, uri) else PlanBitmaps.image(context, uri)
                    val (g, w, h) = PlanBitmaps.gray(bmp)
                    bmp.recycle()
                    PlanImageReader.detect(g, w, h)
                }
                detection = det; fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "plan"
                message = det.notes.joinToString(" · ") + " — enter the span between grid lines 1 and 2 and tap Apply scale"
            } catch (e: Exception) { message = "Could not read the image: ${e.message}" } finally { busy = false }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadImage(uri, true) }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadImage(uri, false) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
            load(text, uri.lastPathSegment?.substringAfterLast('/') ?: "plan.dxf")
        }.onFailure { message = "Open failed: ${it.message}" }
    }

    fun input() = BuildingInput(
        storeys = n(storeys, 3.0).toInt().coerceIn(1, 40), storeyHeight = n(height, 3.2), plinthHeight = n(plinth, 1.5),
        finishes = n(finishes, 1.5), liveLoad = n(ll, 3.0), roofLive = n(roofLl, 1.5), wallLoad = n(wall, 12.0),
        fck = n(fck, 25.0), fy = n(fy, 500.0),
    )

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Drawing → Structure AI", fontWeight = FontWeight.Bold); Text("DXF plan · grid · columns · beams · sizes", style = MaterialTheme.typography.labelMedium) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { openLauncher.launch(arrayOf("image/vnd.dxf", "application/dxf", "application/octet-stream", "text/plain", "*/*")) },
                    label = { Text("↑ Open DXF plan") })
                AssistChip(onClick = { pdfLauncher.launch(arrayOf("application/pdf")) }, label = { Text("↑ PDF plan") })
                AssistChip(onClick = { imageLauncher.launch(arrayOf("image/*")) }, label = { Text("↑ Photo / scan") })
                AssistChip(onClick = { detection = null; load(SamplePlan.dxf(), "Sample 3-bay plan") }, label = { Text("Try sample plan") })
                if (removed.isNotEmpty()) AssistChip(onClick = { removed = emptySet(); design = null }, label = { Text("Restore ${removed.size} column(s)") })
            }
            Text(
                "AutoCAD: draw columns as closed polylines on a layer named COLUMN (blocks are fine), grid lines on GRID; SAVEAS → DXF (ASCII). " +
                    "Tap a column to remove it (false detection or floating column); beams are re-proposed along the grid lines.",
                style = MaterialTheme.typography.labelSmall,
            )
            detection?.let { det ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Image plan: ${det.columns.size} column blobs on ${det.gridPixelsX().size} × ${det.gridPixelsY().size} lines (${det.width} × ${det.height} px)",
                            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            F("Span grid 1–2 (m)", span12, Modifier.weight(1f)) { span12 = it }
                            Button(onClick = {
                                runCatching {
                                    val p = PlanImageReader.toPlan(det, n(span12, 4.0))
                                    plan = p; base = PlanRecognizer.recognise(p, maxSpan = n(maxSpan, 10.0)); removed = emptySet(); design = null
                                    message = "Scale ${p.unitName}. Check the column sizes against the drawing."
                                }.onFailure { message = it.message }
                            }) { Text("Apply scale") }
                        }
                        Text("Grid 1 is the leftmost column line. Photos: shoot square-on in good light; PDF: first page is read.",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Card(Modifier.fillMaxWidth().height(380.dp)) {
                PlanCanvas(plan, layout, onTapColumn = { id -> removed = if (id in removed) removed - id else removed + id; design = null })
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            layout?.let { l ->
                Text(
                    "Grid ${l.gridX.size} × ${l.gridY.size} · ${l.columns.size} columns · ${l.beams.size} beams · ${PlanToStructure.panels(l).size} slab panels",
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                )
                l.notes.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall) }
            }

            Text("Building data", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                F("Storeys", storeys, Modifier.weight(1f)) { storeys = it }
                F("Storey h (m)", height, Modifier.weight(1f)) { height = it }
                F("Plinth (m)", plinth, Modifier.weight(1f)) { plinth = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                F("Finish kN/m²", finishes, Modifier.weight(1f)) { finishes = it }
                F("LL kN/m²", ll, Modifier.weight(1f)) { ll = it }
                F("Roof LL", roofLl, Modifier.weight(1f)) { roofLl = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                F("Wall kN/m", wall, Modifier.weight(1f)) { wall = it }
                F("fck", fck, Modifier.weight(1f)) { fck = it }
                F("fy", fy, Modifier.weight(1f)) { fy = it }
                F("Max span m", maxSpan, Modifier.weight(1f)) { maxSpan = it }
            }
            Text("IS 875 Part 2: residential 2.0, office 2.5–4.0, assembly 4.0–5.0 kN/m²; accessible roof 1.5. 230 mm brick wall ≈ 0.23 × 20 × (h − D) kN/m.",
                style = MaterialTheme.typography.labelSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val l = layout ?: run { message = "Open a DXF plan or the sample first"; return@Button }
                    runCatching { PlanToStructure.design(l, input()) }.onSuccess { design = it }.onFailure { message = it.message }
                }, enabled = layout != null && !busy, modifier = Modifier.weight(1f)) { Text("⚙ Take-down & sizes") }
                OutlinedButton(onClick = {
                    val d = design ?: return@OutlinedButton
                    busy = true
                    scope.launch {
                        try {
                            DesignSession.studioModel = withContext(Dispatchers.Default) { PlanToStructure.model(d) }
                            onStudio()
                        } catch (e: Exception) { message = "Model: ${e.message}" } finally { busy = false }
                    }
                }, enabled = design != null && !busy, modifier = Modifier.weight(1f)) { Text("◇ 3D model → Studio") }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))

            design?.let { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Preliminary design — slab ${(d.slabThickness * 1000).toInt()} mm", fontWeight = FontWeight.SemiBold)
                        d.steps.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall) }
                        Text("Columns (ground storey; full take-down in CSV)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(" Col  Position     Pu (kN)   b × D (mm)", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        d.columns.filter { it.storey == 1 }.forEach {
                            Text(String.format(Locale.US, "%4s  %-9s %9.0f   %d × %d", it.label, it.position, it.pu, (it.b * 1000).toInt(), (it.d * 1000).toInt()),
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Beam sizes", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        d.beams.groupBy { (it.b * 1000).toInt() to (it.d * 1000).toInt() }.forEach { (s, bs) ->
                            Text("${s.first} × ${s.second} mm: ${bs.size} beams, spans ${"%.2f".format(bs.minOf { it.span })}–${"%.2f".format(bs.maxOf { it.span })} m",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Quantities (superstructure, all storeys)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        d.quantities.forEach { (item, unit, q) -> Text(String.format(Locale.US, "%s: %.2f %s", item, q, unit), style = MaterialTheme.typography.bodySmall) }
                        Text("Preliminary only — analyse the 3D model in Studio and design members with the RCC modules (IS 456).",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                val proj = fileName.substringBeforeLast('.').ifBlank { "plan" }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        shareBytesFile(context, "${proj}_BOQ.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            BoqExport.xlsx(d, sor, proj), "Structura AI — measurement & abstract")
                    }, modifier = Modifier.weight(1f)) { Text("↓ BOQ Excel") }
                    OutlinedButton(onClick = {
                        shareTextFile(context, "${proj}_estimatex.json", "application/json", BoqExport.json(d, sor, proj), "Structura AI → EstimateX / JAUEstimate")
                    }, modifier = Modifier.weight(1f)) { Text("↓ EstimateX JSON") }
                    OutlinedButton(onClick = {
                        shareTextFile(context, "${proj}_takedown.csv", "text/csv", d.csv(), "Structura AI — take-down and quantities")
                    }, modifier = Modifier.weight(1f)) { Text("↓ CSV") }
                }
                OutlinedButton(onClick = { showSor = !showSor }, modifier = Modifier.fillMaxWidth()) { Text(if (showSor) "Hide SOR codes & rates" else "SOR codes & rates (your SOR)") }
                if (showSor) SorEditor(sor) { sor = it; SorStore.save(context, it) }
            }
        }
    }
}

@Composable
private fun F(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun PlanCanvas(plan: DxfPlan?, layout: PlanLayout?, onTapColumn: (Int) -> Unit) {
    var size by remember { mutableStateOf(Size(1f, 1f)) }
    // view box from the recognised grid (or all entities)
    val pts: List<Pair<Double, Double>> = when {
        layout != null && layout.columns.isNotEmpty() -> layout.columns.map { it.x to it.y }
        plan != null -> plan.entities.take(4000).flatMap { e ->
            when (e) {
                is PlanEntity.Line -> listOf(e.x1 to e.y1, e.x2 to e.y2)
                is PlanEntity.Poly -> e.pts
                is PlanEntity.Circle -> listOf(e.cx to e.cy)
            }
        }
        else -> emptyList()
    }
    // raw drawing is in DXF coordinates; the layout is measured from grid intersection A1
    val shift: Pair<Double, Double> = layout?.let { it.originX to it.originY } ?: (0.0 to 0.0)
    val minX = pts.minOfOrNull { it.first } ?: 0.0; val maxX = pts.maxOfOrNull { it.first } ?: 10.0
    val minY = pts.minOfOrNull { it.second } ?: 0.0; val maxY = pts.maxOfOrNull { it.second } ?: 10.0
    val margin = 70f
    val sc = min((size.width - 2 * margin) / max(maxX - minX, 1.0).toFloat(), (size.height - 2 * margin) / max(maxY - minY, 1.0).toFloat())
    fun sx(x: Double) = margin + ((x - minX) * sc).toFloat()
    fun sy(y: Double) = size.height - margin - ((y - minY) * sc).toFloat()

    Canvas(Modifier.fillMaxSize().pointerInput(layout, size) {
        detectTapGestures { o ->
            val l = layout ?: return@detectTapGestures
            val hit = l.columns.minByOrNull { hypot((sx(it.x) - o.x).toDouble(), (sy(it.y) - o.y).toDouble()) }
            if (hit != null && hypot((sx(hit.x) - o.x).toDouble(), (sy(hit.y) - o.y).toDouble()) < 48) onTapColumn(hit.id)
        }
    }) {
        size = this.size
        val ink = Color(0xFF263238)
        if (plan == null) {
            drawIntoCanvas { c ->
                c.nativeCanvas.drawText("Open a DXF plan or try the sample", 40f, this.size.height / 2,
                    Paint().apply { textSize = 34f; color = android.graphics.Color.GRAY; isAntiAlias = true })
            }
            return@Canvas
        }
        // raw drawing, faint, in layout coordinates
        val (dx, dy) = if (layout != null && layout.columns.isNotEmpty()) shift else 0.0 to 0.0
        val faint = Color(0x5578909C)
        for (e in plan.entities.take(6000)) when (e) {
            is PlanEntity.Line -> drawLine(faint, Offset(sx(e.x1 - dx), sy(e.y1 - dy)), Offset(sx(e.x2 - dx), sy(e.y2 - dy)), 1f)
            is PlanEntity.Poly -> {
                val p = e.pts.map { Offset(sx(it.first - dx), sy(it.second - dy)) }
                for (k in 1 until p.size) drawLine(faint, p[k - 1], p[k], 1f)
                if (e.closed && p.size > 2) drawLine(faint, p.last(), p.first(), 1f)
            }
            is PlanEntity.Circle -> drawCircle(faint, (e.r * sc).toFloat(), Offset(sx(e.cx - dx), sy(e.cy - dy)), style = Stroke(1f))
        }
        val l = layout ?: return@Canvas
        val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 8f, 4f, 8f))
        val gridC = Color(0xFFC62828)
        val paint = Paint().apply { textSize = 26f; isAntiAlias = true; textAlign = Paint.Align.CENTER; color = android.graphics.Color.rgb(198, 40, 40) }
        val y0 = l.gridY.minOrNull() ?: 0.0; val y1 = l.gridY.maxOrNull() ?: 0.0
        val x0 = l.gridX.minOrNull() ?: 0.0; val x1 = l.gridX.maxOrNull() ?: 0.0
        l.gridX.forEachIndexed { i, x ->
            drawLine(gridC, Offset(sx(x), sy(y0) + 30f), Offset(sx(x), sy(y1) - 30f), 1.5f, pathEffect = dash)
            drawCircle(gridC, 20f, Offset(sx(x), sy(y0) + 52f), style = Stroke(2f))
            drawIntoCanvas { it.nativeCanvas.drawText("${i + 1}", sx(x), sy(y0) + 61f, paint) }
        }
        l.gridY.forEachIndexed { j, y ->
            drawLine(gridC, Offset(sx(x0) - 30f, sy(y)), Offset(sx(x1) + 30f, sy(y)), 1.5f, pathEffect = dash)
            drawCircle(gridC, 20f, Offset(sx(x0) - 52f, sy(y)), style = Stroke(2f))
            drawIntoCanvas { it.nativeCanvas.drawText(PlanLayout.letter(j), sx(x0) - 52f, sy(y) + 9f, paint) }
        }
        for (b in l.beams) {
            val p = l.column(b.a); val q = l.column(b.b)
            drawLine(Color(0xFF1565C0), Offset(sx(p.x), sy(p.y)), Offset(sx(q.x), sy(q.y)), 5f)
        }
        for (c in l.columns) {
            val w = max((c.b * sc).toFloat(), 10f); val h = max((c.d * sc).toFloat(), 10f)
            if (c.circular) drawCircle(ink, w / 2, Offset(sx(c.x), sy(c.y)))
            else drawRect(ink, Offset(sx(c.x) - w / 2, sy(c.y) - h / 2), Size(w, h))
        }
    }
}

/** Editable SOR item code / rate per BOQ item (e.g. Gujarat R&B SOR); saved on the device. */
@Composable
private fun SorEditor(sor: Map<BoqExport.Key, BoqExport.Sor>, onChange: (Map<BoqExport.Key, BoqExport.Sor>) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Enter the item code and rate from your current SOR; they are saved and used in the Abstract and JSON.",
                style = MaterialTheme.typography.labelSmall)
            BoqExport.Key.entries.forEach { k ->
                val s = sor[k] ?: BoqExport.Sor()
                Text("${k.title} (${k.unit})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(s.code, { onChange(sor + (k to s.copy(code = it))) }, label = { Text("SOR item no.") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(if (s.rate == 0.0) "" else String.format(Locale.US, "%.2f", s.rate),
                        { v -> onChange(sor + (k to s.copy(rate = v.replace(',', '.').toDoubleOrNull() ?: 0.0))) },
                        label = { Text("Rate ₹/${k.unit}") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            }
        }
    }
}
