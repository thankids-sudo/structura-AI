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

    /** Model handed from the template wizard to the 3D Studio editor. */
    var studioModel: com.structura.core.fem.FrameModel? = null

    fun takeStudioModel() = studioModel.also { studioModel = null }
}

/** Writes [content] to cache/exports/[fileName] and opens the Android share sheet. */
fun shareTextFile(context: Context, fileName: String, mime: String, content: String, subject: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeText(content, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export $fileName"))
}

/** Writes [bytes] to cache/exports/[fileName] and opens the Android share sheet. */
fun shareBytesFile(context: Context, fileName: String, mime: String, bytes: ByteArray, subject: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export $fileName"))
}

/** Imported section table (CSV) kept in app storage and reloaded at start-up. */
object SectionStore {
    private fun file(context: Context) = File(context.filesDir, "sections.csv")

    fun load(context: Context) {
        val f = file(context)
        if (f.exists()) runCatching { com.structura.core.steel.SteelLibrary.import(f.readText()) }
    }

    fun save(context: Context, csv: String): com.structura.core.steel.SteelLibrary.ImportResult {
        val r = com.structura.core.steel.SteelLibrary.import(csv)
        file(context).writeText(csv)
        return r
    }

    fun clear(context: Context) { file(context).delete(); com.structura.core.steel.SteelLibrary.clear() }
}

/** User's SOR item codes and rates for the BOQ hand-over (EstimateX / JAUEstimate). */
object SorStore {
    private const val P = "sor"
    fun load(context: Context): Map<com.structura.core.plan.BoqExport.Key, com.structura.core.plan.BoqExport.Sor> {
        val sp = context.getSharedPreferences(P, Context.MODE_PRIVATE)
        return com.structura.core.plan.BoqExport.Key.entries.associateWith { k ->
            com.structura.core.plan.BoqExport.Sor(sp.getString("code_${k.name}", "") ?: "", sp.getFloat("rate_${k.name}", 0f).toDouble(), sp.getString("desc_${k.name}", "") ?: "")
        }
    }
    fun save(context: Context, map: Map<com.structura.core.plan.BoqExport.Key, com.structura.core.plan.BoqExport.Sor>) {
        val e = context.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
        map.forEach { (k, s) -> e.putString("code_${k.name}", s.code).putFloat("rate_${k.name}", s.rate.toFloat()).putString("desc_${k.name}", s.description) }
        e.apply()
    }
}
