package com.example.wearableai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.example.wearableai.shared.Note
import com.example.wearableai.shared.NoteCategory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quick-and-dirty raw inspection report — not NFPA-formatted, just notes by
 * category with embedded photos when present. Writes to the app's external
 * Documents dir so the user can pull/share with any file manager.
 */
object PdfExporter {

    private const val PAGE_WIDTH = 612   // US Letter @ 72dpi
    private const val PAGE_HEIGHT = 792
    private const val MARGIN = 40f
    private const val LINE = 16f
    private const val PHOTO_MAX = 320    // px per side, scaled to fit

    fun export(ctx: Context, notes: List<Note>): File {
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
        val heading = Paint().apply { textSize = 14f; isFakeBoldText = true; isAntiAlias = true }
        val body = Paint().apply { textSize = 11f; isAntiAlias = true }
        val muted = Paint().apply { textSize = 9f; color = 0xFF666666.toInt(); isAntiAlias = true }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN + 24f

        canvas.drawText("Fire Inspection Notes", MARGIN, y, title)
        y += LINE * 1.3f
        canvas.drawText(stamp + "  •  ${notes.size} observation${if (notes.size == 1) "" else "s"}", MARGIN, y, muted)
        y += LINE * 1.6f

        fun newPage() {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
            canvas = page.canvas
            y = MARGIN + 16f
        }

        fun ensureSpace(needed: Float) { if (y + needed > PAGE_HEIGHT - MARGIN) newPage() }

        if (notes.isEmpty()) {
            ensureSpace(LINE)
            canvas.drawText("(no observations recorded)", MARGIN, y, body)
            y += LINE
        }

        val byCat = notes.groupBy { it.category }
        for (cat in NoteCategory.entries) {
            val items = byCat[cat] ?: continue
            ensureSpace(LINE * 2)
            y += LINE * 0.6f
            canvas.drawText(cat.heading, MARGIN, y, heading)
            y += LINE * 1.2f

            for (note in items) {
                val lines = wrap(note.markdown, body, PAGE_WIDTH - MARGIN * 2 - 10f)
                ensureSpace(LINE * (lines.size + 1))
                for ((i, line) in lines.withIndex()) {
                    val prefix = if (i == 0) "• " else "  "
                    canvas.drawText(prefix + line, MARGIN, y, body)
                    y += LINE
                }
                note.photoPath?.let { path ->
                    val bmp = decodeScaled(path, PHOTO_MAX) ?: return@let
                    ensureSpace(bmp.height.toFloat() + LINE)
                    val dst = Rect(
                        (MARGIN + 10f).toInt(),
                        y.toInt(),
                        (MARGIN + 10f).toInt() + bmp.width,
                        y.toInt() + bmp.height,
                    )
                    canvas.drawBitmap(bmp, null, dst, null)
                    y += bmp.height + LINE * 0.6f
                    bmp.recycle()
                }
                y += LINE * 0.3f
            }
        }

        doc.finishPage(page)

        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(ctx.filesDir, "reports").apply { mkdirs() }
        dir.mkdirs()
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "inspection_$fileStamp.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.replace("\n", " ").split(" ")
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (w in words) {
            val probe = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(probe) <= maxWidth) cur.clear().also { it.append(probe) }
            else {
                if (cur.isNotEmpty()) out.add(cur.toString())
                cur.clear().append(w)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun decodeScaled(path: String, targetMax: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth.takeIf { it > 0 } ?: return null
        val h = bounds.outHeight.takeIf { it > 0 } ?: return null
        var sample = 1
        while (w / sample > targetMax * 2 && h / sample > targetMax * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(path, opts) ?: return null
        val scale = (targetMax.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
        return if (scale == 1f) raw
        else Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true)
            .also { if (it != raw) raw.recycle() }
    }
}
