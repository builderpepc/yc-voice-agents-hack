package com.example.wearableai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.example.wearableai.shared.FORM_FIELD_DEFINITIONS
import com.example.wearableai.shared.FormField
import com.example.wearableai.shared.FormSection
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the FM Global Pre-Incident Plan as a structured PDF matching the
 * 3-page paper form layout.
 */
object PdfExporter {

    private const val PAGE_WIDTH = 612   // US Letter @ 72dpi
    private const val PAGE_HEIGHT = 792
    private const val MARGIN = 36f
    private const val COL_MID = PAGE_WIDTH / 2f
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2
    private const val FIELD_HEIGHT = 32f
    private const val SECTION_PAD = 8f
    private const val PHOTO_MAX = 200

    fun export(ctx: Context, fields: Map<String, FormField>): File {
        val doc = PdfDocument()

        val titlePaint = Paint().apply { textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
        val sectionPaint = Paint().apply { textSize = 12f; isFakeBoldText = true; isAntiAlias = true; color = 0xFF1a237e.toInt() }
        val labelPaint = Paint().apply { textSize = 9f; isFakeBoldText = true; isAntiAlias = true; color = 0xFF444444.toInt() }
        val valuePaint = Paint().apply { textSize = 10f; isAntiAlias = true }
        val linePaint = Paint().apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 0.5f; style = Paint.Style.STROKE }
        val boxPaint = Paint().apply { color = 0xFFE8EAF6.toInt(); style = Paint.Style.FILL }
        val headerPaint = Paint().apply { textSize = 8f; isAntiAlias = true; color = 0xFF999999.toInt() }

        // --- PAGE 1: Pre-Incident Plan Data Checklist ---
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN

        fun newPage(title: String? = null): Canvas {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
            y = MARGIN
            val c = page.canvas
            if (title != null) {
                c.drawText(title, MARGIN, y + 16f, titlePaint)
                c.drawText("FM Global", PAGE_WIDTH - MARGIN - titlePaint.measureText("FM Global"), y + 16f, headerPaint)
                y += 28f
            }
            return c
        }

        fun ensureSpace(needed: Float): Canvas {
            if (y + needed > PAGE_HEIGHT - MARGIN) {
                return newPage()
            }
            return canvas
        }

        fun drawSectionHeader(c: Canvas, text: String) {
            y += SECTION_PAD
            c.drawRect(MARGIN, y, MARGIN + CONTENT_WIDTH, y + 18f, boxPaint)
            c.drawText(text, MARGIN + 4f, y + 13f, sectionPaint)
            y += 22f
        }

        fun drawField(c: Canvas, label: String, fieldId: String, x: Float = MARGIN, width: Float = CONTENT_WIDTH) {
            val field = fields[fieldId]
            val value = field?.value ?: ""
            canvas = ensureSpace(FIELD_HEIGHT)
            canvas.drawText("$label:", x + 2f, y + 10f, labelPaint)
            canvas.drawText(value, x + 2f, y + 24f, valuePaint)
            canvas.drawLine(x, y + FIELD_HEIGHT, x + width, y + FIELD_HEIGHT, linePaint)
            y += FIELD_HEIGHT
        }

        fun drawFieldPair(c: Canvas, label1: String, id1: String, label2: String, id2: String) {
            val halfW = CONTENT_WIDTH / 2f - 4f
            val field1 = fields[id1]
            val field2 = fields[id2]
            canvas = ensureSpace(FIELD_HEIGHT)
            // Left
            canvas.drawText("$label1:", MARGIN + 2f, y + 10f, labelPaint)
            canvas.drawText(field1?.value ?: "", MARGIN + 2f, y + 24f, valuePaint)
            canvas.drawLine(MARGIN, y + FIELD_HEIGHT, MARGIN + halfW, y + FIELD_HEIGHT, linePaint)
            // Right
            val rx = COL_MID + 4f
            canvas.drawText("$label2:", rx + 2f, y + 10f, labelPaint)
            canvas.drawText(field2?.value ?: "", rx + 2f, y + 24f, valuePaint)
            canvas.drawLine(rx, y + FIELD_HEIGHT, rx + halfW, y + FIELD_HEIGHT, linePaint)
            y += FIELD_HEIGHT
        }

        fun drawTextBlock(c: Canvas, label: String, fieldId: String, lines: Int = 3) {
            val field = fields[fieldId]
            val value = field?.value ?: ""
            val blockH = FIELD_HEIGHT + (lines - 1) * 14f
            canvas = ensureSpace(blockH)
            canvas.drawText("$label:", MARGIN + 2f, y + 10f, labelPaint)
            // Wrap value text
            val wrapped = wrap(value, valuePaint, CONTENT_WIDTH - 8f)
            var ty = y + 24f
            for (line in wrapped.take(lines + 1)) {
                canvas.drawText(line, MARGIN + 2f, ty, valuePaint)
                ty += 14f
            }
            canvas.drawRect(RectF(MARGIN, y, MARGIN + CONTENT_WIDTH, y + blockH), linePaint)
            y += blockH

            // Embed photo if present
            field?.photoPath?.let { path ->
                val bmp = decodeScaled(path, PHOTO_MAX) ?: return@let
                canvas = ensureSpace(bmp.height.toFloat() + 8f)
                val dst = Rect(
                    (MARGIN + 4f).toInt(), y.toInt(),
                    (MARGIN + 4f).toInt() + bmp.width, y.toInt() + bmp.height,
                )
                canvas.drawBitmap(bmp, null, dst, null)
                y += bmp.height + 8f
                bmp.recycle()
            }
        }

        // Page 1 header
        canvas.drawText("Pre-Incident Plan Data Checklist", MARGIN, y + 16f, titlePaint)
        canvas.drawText("FM Global", PAGE_WIDTH - MARGIN - titlePaint.measureText("FM Global"), y + 16f, headerPaint)
        y += 28f

        drawField(canvas, "Address", "address")
        drawFieldPair(canvas, "Contact", "contact", "Phone", "phone")

        drawSectionHeader(canvas, "Business Name and Type of Occupancy")
        drawField(canvas, "Business Name and Type of Occupancy", "business_name_occupancy")

        // Building Access + Water Supply side by side headers
        y += SECTION_PAD
        canvas.drawRect(MARGIN, y, COL_MID - 4f, y + 18f, boxPaint)
        canvas.drawText("Building Access", MARGIN + 4f, y + 13f, sectionPaint)
        canvas.drawRect(COL_MID + 4f, y, MARGIN + CONTENT_WIDTH, y + 18f, boxPaint)
        canvas.drawText("Water Supply", COL_MID + 8f, y + 13f, sectionPaint)
        y += 22f

        drawFieldPair(canvas, "Primary entrance", "primary_entrance", "Available hydrants", "available_hydrants")
        drawFieldPair(canvas, "Key Box?", "key_box", "Available water sources", "available_water_sources")
        drawFieldPair(canvas, "Other entrances", "other_entrances", "Private water main?", "private_water_main")
        drawFieldPair(canvas, "\"A\" side of building", "a_side", "", "a_side") // a_side only on left

        drawSectionHeader(canvas, "Building")
        drawFieldPair(canvas, "Exterior wall construction", "exterior_wall_construction", "Number of floors", "number_of_floors")
        drawFieldPair(canvas, "Basement", "basement", "Sub-basement", "sub_basement")
        drawFieldPair(canvas, "Structural members", "structural_members", "Exposed/protected?", "structural_members_exposed")
        drawField(canvas, "Truss construction? (describe type and location)", "truss_construction")
        drawFieldPair(canvas, "Fire walls and openings", "fire_walls_openings", "Ceiling type", "ceiling_type")
        drawField(canvas, "Utilities (type and entrance point into building)", "utilities")
        drawField(canvas, "Hazards to firefighters (pits, fall hazards, etc.)", "hazards_to_firefighters")

        drawSectionHeader(canvas, "Storage Arrangements and Hazardous Material Located on Property")
        drawTextBlock(canvas, "Storage/Hazmat", "storage_hazmat", 4)

        // --- PAGE 2: Fire Protection ---
        canvas = newPage("Fire Protection")

        drawSectionHeader(canvas, "Fire Alarm System")
        drawFieldPair(canvas, "Location of FA annunciators", "fa_annunciators_location", "Central station/FA box", "central_station_fa_box")

        drawSectionHeader(canvas, "Fire Pumps")
        drawFieldPair(canvas, "Location", "fire_pumps_location", "Type", "fire_pumps_type")

        drawSectionHeader(canvas, "Standpipe and Automatic Sprinkler Systems")

        // Sprinkler table header
        canvas = ensureSpace(20f)
        val colW = CONTENT_WIDTH / 4f
        val headers = listOf("Type of System", "Location of Control Valves", "Area Protected", "Location of FDC/Size/Distance")
        for ((i, h) in headers.withIndex()) {
            canvas.drawText(h, MARGIN + i * colW + 2f, y + 10f, labelPaint)
        }
        canvas.drawLine(MARGIN, y + 14f, MARGIN + CONTENT_WIDTH, y + 14f, linePaint)
        y += 16f

        // 4 sprinkler rows
        for (row in 1..4) {
            canvas = ensureSpace(FIELD_HEIGHT)
            val ids = listOf("sprinkler_${row}_type", "sprinkler_${row}_valves", "sprinkler_${row}_area", "sprinkler_${row}_fdc")
            canvas.drawText("$row.", MARGIN - 12f, y + 14f, labelPaint)
            for ((i, id) in ids.withIndex()) {
                val v = fields[id]?.value ?: ""
                canvas.drawText(v, MARGIN + i * colW + 2f, y + 14f, valuePaint)
                canvas.drawLine(MARGIN + i * colW, y, MARGIN + i * colW, y + FIELD_HEIGHT, linePaint)
            }
            canvas.drawLine(MARGIN, y + FIELD_HEIGHT, MARGIN + CONTENT_WIDTH, y + FIELD_HEIGHT, linePaint)
            y += FIELD_HEIGHT
        }

        drawSectionHeader(canvas, "Special Suppression Systems")
        drawTextBlock(canvas, "Type, location, and coverage", "special_suppression", 3)

        drawSectionHeader(canvas, "System Impairments and Notes")
        drawTextBlock(canvas, "Impairments and Notes", "system_impairments", 4)

        // --- PAGE 3: Pre-Incident Plan Worksheet ---
        canvas = newPage("Pre-Incident Plan Worksheet")

        drawField(canvas, "Address", "address")

        drawSectionHeader(canvas, "Recommended First Alarm Assignments")
        drawTextBlock(canvas, "First Alarm Assignments", "first_alarm_assignments", 5)

        drawSectionHeader(canvas, "Key Considerations for Incident Command")
        drawTextBlock(canvas, "Incident Command Considerations", "incident_command", 5)

        drawSectionHeader(canvas, "Notes")
        drawTextBlock(canvas, "Notes", "worksheet_notes", 4)

        drawSectionHeader(canvas, "Site-Specific Resources Available")
        drawTextBlock(canvas, "Site Resources", "site_resources", 4)

        // Completion footer
        y += SECTION_PAD
        canvas = ensureSpace(FIELD_HEIGHT * 2)
        drawField(canvas, "Completed by", "completed_by")
        drawFieldPair(canvas, "Date", "completion_date", "Building plan attached", "building_plan_attached")

        // Footer
        canvas = ensureSpace(20f)
        canvas.drawText("Rev. FM Global Pre-Incident Plan", MARGIN, y + 14f, headerPaint)

        doc.finishPage(page)

        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(ctx.filesDir, "reports").apply { mkdirs() }
        dir.mkdirs()
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "pre_incident_plan_$fileStamp.pdf")
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
