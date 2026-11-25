package com.structura.app.ui.plan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlin.math.max

/** Loads a plan from a PDF (first page) or an image into a bitmap ≤ [MAX] px, and converts it to grey levels. */
internal object PlanBitmaps {
    private const val MAX = 2400

    fun pdfPage(context: Context, uri: Uri): Bitmap {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open PDF")
        pfd.use {
            PdfRenderer(it).use { r ->
                require(r.pageCount > 0) { "Empty PDF" }
                r.openPage(0).use { page ->
                    val s = MAX.toFloat() / max(page.width, page.height)
                    val bmp = Bitmap.createBitmap((page.width * s).toInt(), (page.height * s).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }

    fun image(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Not an image")
    }

    fun gray(b: Bitmap): Triple<IntArray, Int, Int> {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            val l = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            px[i] = if (a < 128) 255 else l
        }
        return Triple(px, w, h)
    }
}
