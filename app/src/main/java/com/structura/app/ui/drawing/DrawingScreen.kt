package com.structura.app.ui.drawing

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.structura.app.DesignSession
import com.structura.app.shareTextFile
import com.structura.core.drawing.BeamDetailer
import com.structura.core.drawing.Drawing
import com.structura.core.drawing.DxfWriter
import com.structura.core.drawing.Layer
import com.structura.core.drawing.Prim
import kotlin.math.min

fun layerColor(l: Layer): Color = when (l) {
    Layer.CONCRETE -> Color(0xFF111111)
    Layer.REBAR -> Color(0xFFC62828)
    Layer.STIRRUP -> Color(0xFF2E7D32)
    Layer.HATCH -> Color(0xFF9E9E9E)
    Layer.DIM -> Color(0xFF00838F)
    Layer.TEXT -> Color(0xFF1A237E)
    Layer.CENTER -> Color(0xFFAD1457)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawingScreen(onBack: () -> Unit) {
    val inp = DesignSession.beamInput
    val res = DesignSession.beamResult
    val context = LocalContext.current
    val drawing = remember(inp, res) { if (inp != null && res != null) BeamDetailer.detail(inp, res) else null }
    var fitKey by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detail drawing") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { fitKey++ }) { Icon(Icons.Filled.FitScreen, "Fit") }
                    if (drawing != null && inp != null) IconButton(onClick = {
                        shareTextFile(
                            context,
                            "Beam_${inp.widthB.toInt()}x${inp.overallDepthD.toInt()}.dxf",
                            "application/dxf",
                            DxfWriter.write(drawing),
                            "Structura AI — beam detail (DXF R12)",
                        )
                    }) { Icon(Icons.Filled.IosShare, "Export DXF") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (drawing == null) {
                Text("Design a beam first (RCC ▸ Beam design).", Modifier.padding(16.dp))
            } else {
                Text(
                    "Pinch to zoom, drag to pan. Export ▸ DXF (AutoCAD R12) keeps the named UCS of each view.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                DrawingCanvas(drawing, fitKey, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
fun DrawingCanvas(drawing: Drawing, fitKey: Int, modifier: Modifier = Modifier) {
    val prims = remember(drawing) { drawing.allWcs() }
    val bb = remember(drawing) { drawing.bounds() }
    var zoom by remember(fitKey) { mutableFloatStateOf(1f) }
    var pan by remember(fitKey) { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .background(Color.White)
            .pointerInput(fitKey) {
                detectTransformGestures { centroid, p, z, _ ->
                    val newZoom = (zoom * z).coerceIn(0.2f, 60f)
                    // keep the point under the fingers fixed while zooming
                    pan = (pan - centroid) * (newZoom / zoom) + centroid + p
                    zoom = newZoom
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = (bb[2] - bb[0]).toFloat().coerceAtLeast(1f)
            val h = (bb[3] - bb[1]).toFloat().coerceAtLeast(1f)
            val base = min(size.width * 0.94f / w, size.height * 0.94f / h)
            val s = base * zoom
            val ox = (size.width - w * base) / 2f
            val oy = (size.height + h * base) / 2f
            fun tx(x: Double) = ((x - bb[0]).toFloat() * s + ox * zoom + pan.x)
            fun ty(y: Double) = (-(y - bb[1]).toFloat() * s + oy * zoom + pan.y)
            drawPrims(prims, s, ::tx, ::ty)
        }
    }
}

private fun DrawScope.drawPrims(prims: List<Prim>, s: Float, tx: (Double) -> Float, ty: (Double) -> Float) {
    val paint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.SERIF
    }
    for (p in prims) {
        val c = layerColor(p.layer)
        when (p) {
            is Prim.Line -> drawLine(
                c, Offset(tx(p.a.x), ty(p.a.y)), Offset(tx(p.b.x), ty(p.b.y)),
                strokeWidth = if (p.layer == Layer.REBAR) 2.5f else if (p.layer == Layer.CONCRETE) 1.6f else 1f,
            )
            is Prim.Circle -> {
                val center = Offset(tx(p.c.x), ty(p.c.y))
                val r = (p.r * s).toFloat().coerceAtLeast(1.5f)
                if (p.filled) drawCircle(c, r, center) else drawCircle(c, r, center, style = Stroke(1f))
            }
            is Prim.Text -> {
                val px = (p.height * s).toFloat()
                if (px < 3f) continue
                paint.color = c.toArgb()
                paint.textSize = px
                paint.textAlign = if (p.centered) Paint.Align.CENTER else Paint.Align.LEFT
                drawIntoCanvas { cv ->
                    val nc = cv.nativeCanvas
                    nc.save()
                    nc.rotate(-p.rotationDeg.toFloat(), tx(p.at.x), ty(p.at.y))
                    nc.drawText(p.text, tx(p.at.x), ty(p.at.y), paint)
                    nc.restore()
                }
            }
        }
    }
}
