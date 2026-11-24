package com.structura.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.structura.core.rcc.BeamInput
import com.structura.core.rcc.BeamResult
import java.io.File

/** Holds the latest design so the drawing screen can pick it up (single-activity app). */
object DesignSession {
    var beamInput: BeamInput? = null
    var beamResult: BeamResult? = null

    /** Values handed over from the FEM screen to the beam designer (mm, m, kN·m, kN). */
    data class Prefill(val b: Double, val d: Double, val spanM: Double, val mu: Double, val vu: Double)

    var prefill: Prefill? = null

    fun takePrefill(): Prefill? = prefill.also { prefill = null }
}

/** Writes [content] to cache/exports/[fileName] and opens the Android share sheet. */
fun shareTextFile(context: Context, fileName: String, mime: String, content: String, subject: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeText(content, Charsets.US_ASCII)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export $fileName"))
}
