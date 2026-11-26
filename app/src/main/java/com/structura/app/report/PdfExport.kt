package com.structura.app.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.structura.app.project.ProjectStore
import com.structura.app.shareBytesFile
import com.structura.core.rcc.Status
import com.structura.core.report.ReportDoc
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A4 PDF of a [ReportDoc] in black and white with serif type: project header on every page, numbered footer,
 * bordered tables, check sheets with clauses, and line-drawn figures.
 */
object PdfExport {
    private const val W = 595; private const val H = 842; private const val M = 46f
    private val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private fun tp(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = if (bold) serifBold else serif; color = android.graphics.Color.BLACK }

    fun bytes(doc: ReportDoc): ByteArray {
        val pdf = PdfDocument()
        val p = ProjectStore.current
        val date = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        var y = 0f
        val cw = W - 2 * M
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; strokeWidth = 0.5f; style = Paint.Style.STROKE }

        fun newPage() {
            if (pageNo > 0) pdf.finishPage(page)
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            val hp = tp(7.5f)
            c.drawText(p?.name ?: "Structura AI", M, 30f, hp)
            val right = listOfNotNull(p?.client?.takeIf { it.isNotBlank() }, p?.location?.takeIf { it.isNotBlank() }).joinToString(" · ")
            c.drawText(right, W - M - hp.measureText(right), 30f, hp)
            c.drawLine(M, 35f, W - M, 35f, line)
            c.drawLine(M, H - 34f, W - M, H - 34f, line)
            val foot = "Structura AI · ${doc.title}" + (p?.engineer?.takeIf { it.isNotBlank() }?.let { " · Engineer: $it" } ?: "") + " · $date"
            c.drawText(foot, M, H - 22f, hp)
            val pg = "Page $pageNo"
            c.drawText(pg, W - M - hp.measureText(pg), H - 22f, hp)
            y = 50f
        }
        fun layout(text: String, paint: TextPaint, width: Float): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.08f).build()
        fun ensure(h: Float) { if (y + h > H - 46f) newPage() }
        fun para(text: String, paint: TextPaint, indent: Float = 0f, after: Float = 4f) {
            val l = layout(text, paint, cw - indent)
            ensure(l.height.toFloat())
            c.save(); c.translate(M + indent, y); l.draw(c); c.restore()
            y += l.height + after
        }
        fun table(header: List<String>, rows: List<List<String>>, weights: List<Float>?, size: Float = 7.8f) {
            val n = header.size
            val wts = weights ?: List(n) { 1f }
            val tot = wts.sum()
            val widths = wts.map { cw * it / tot }
            fun row(cells: List<String>, bold: Boolean) {
                val ls = cells.mapIndexed { i, s -> layout(s, tp(size, bold), widths[i] - 6) }
                val h = (ls.maxOfOrNull { it.height } ?: 10) + 6f
                ensure(h)
                var x = M
                for (i in 0 until n) {
                    c.drawRect(x, y, x + widths[i], y + h, line)
                    c.save(); c.translate(x + 3, y + 3); ls.getOrNull(i)?.draw(c); c.restore()
                    x += widths[i]
                }
                y += h
            }
            row(header, true)
            rows.forEach { row(it.take(n) + List(maxOf(0, n - it.size)) { "" }, false) }
            y += 6f
        }

        newPage()
        para(doc.title, tp(16f, true), after = 2f)
        para(doc.subtitle, tp(9.5f), after = 6f)
        p?.let { para(listOfNotNull("Project: ${it.name}", it.client.takeIf { s -> s.isNotBlank() }?.let { s -> "Client: $s" },
            it.location.takeIf { s -> s.isNotBlank() }?.let { s -> "Location: $s" }, it.engineer.takeIf { s -> s.isNotBlank() }?.let { s -> "Engineer: $s" }, "Date: $date").joinToString("   ·   "), tp(8.5f), after = 8f) }
        val verdict = if (doc.isSafe) "RESULT: ALL CHECKS SATISFIED" else "RESULT: ONE OR MORE CHECKS NOT SATISFIED — SEE CHECK SHEET"
        para(verdict, tp(9.5f, true), after = 8f)

        for (b in doc.blocks) when (b) {
            is ReportDoc.Block.Heading -> { ensure(40f); y += 4f; para(b.text, tp(11.5f, true), after = 3f); c.drawLine(M, y - 1, W - M, y - 1, line); y += 4f }
            is ReportDoc.Block.Para -> para(b.text, tp(9f))
            is ReportDoc.Block.KeyValues -> table(listOf("Item", "Value"), b.rows.map { listOf(it.first, it.second) }, listOf(1f, 2.2f), 8.2f)
            is ReportDoc.Block.Table -> table(b.header, b.rows, b.weights)
            is ReportDoc.Block.Steps -> b.lines.forEach { para("•  $it", tp(8.6f), indent = 6f, after = 2f) }.also { y += 4f }
            is ReportDoc.Block.Checks -> table(listOf("Check", "Value", "Requirement", "Status", "Clause"),
                b.checks.map { listOf(it.item, it.value, it.requirement, when (it.status) { Status.PASS -> "OK"; Status.FAIL -> "NOT OK"; Status.INFO -> "Note" }, it.clause) },
                listOf(1.3f, 1.3f, 2f, 0.6f, 1.2f))
            is ReportDoc.Block.Figure -> {
                val fh = b.heightPt
                ensure(fh + 24f)
                val xs = b.lines.flatMap { listOf(it.x1, it.x2) } + b.texts.map { it.x }
                val ys = b.lines.flatMap { listOf(it.y1, it.y2) } + b.texts.map { it.y }
                if (xs.isNotEmpty()) {
                    val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
                    val sc = minOf((cw - 40) / maxOf(x1 - x0, 1e-6), (fh - 20) / maxOf(y1 - y0, 1e-6)).toFloat()
                    val ox = M + (cw - (x1 - x0).toFloat() * sc) / 2
                    val oy = y + 10 + (fh - 20 - (y1 - y0).toFloat() * sc) / 2
                    fun px(x: Double) = ox + ((x - x0) * sc).toFloat()
                    fun py(v: Double) = oy + ((y1 - v) * sc).toFloat()
                    val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
                    for (l in b.lines) {
                        pen.strokeWidth = when (l.weight) { 1 -> 0.5f; 2 -> 1.0f; else -> 1.7f }
                        pen.pathEffect = if (l.dashed) DashPathEffect(floatArrayOf(6f, 3f, 1.5f, 3f), 0f) else null
                        c.drawLine(px(l.x1), py(l.y1), px(l.x2), py(l.y2), pen)
                    }
                    for (t in b.texts) c.drawText(t.text, px(t.x), py(t.y), tp(t.sizePt))
                }
                c.drawRect(M, y, W - M, y + fh, line)
                y += fh + 3f
                para("Figure — ${b.caption}", tp(8f), after = 8f)
            }
        }
        pdf.finishPage(page)
        val out = ByteArrayOutputStream()
        pdf.writeTo(out); pdf.close()
        return out.toByteArray()
    }

    fun share(context: Context, doc: ReportDoc) {
        val name = (ProjectStore.current?.name ?: "Structura") + "_" + doc.title
        val safe = name.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(80) + ".pdf"
        shareBytesFile(context, safe, "application/pdf", bytes(doc), doc.title)
    }
}
