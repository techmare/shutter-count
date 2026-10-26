package com.techmare.shuttercount.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.techmare.shuttercount.R
import com.techmare.shuttercount.core.CameraModels
import java.io.File
import kotlin.math.max

/** What goes on a shared report. */
data class ReportData(
    val camera: String,
    val serial: String?,
    val count: Long,
    /** Null hides the wear scale (setting "Include shutter life in reports" off, or unknown). */
    val ratedLife: Int?,
    val method: String,
    val time: Long = System.currentTimeMillis(),
    val experimental: Boolean = false,
    val includesElectronic: Boolean = false,
    /** True when the rating is the maker's published figure ("nominale"), false for an estimate ("estimée"). */
    val ratedOfficial: Boolean = false,
)

private const val W = 1080
private const val H = 1350
private const val PAD = 72f

private val BG = 0xFF23272C.toInt()
private val TEXT = 0xFFF2F3F4.toInt()
private val MUTED = 0xFFA9AFB6.toInt()
private val TICK = 0xFF4A5057.toInt()
private val DIM_DIGIT = 0xFF6B7178.toInt()
private val SIGNAL = 0xFFFFC21A.toInt()
private val OK = 0xFF3FA35E.toInt()
private val MID = 0xFFE5932A.toInt()
private val HIGH = 0xFFE0533A.toInt()
private val RULE = 0xFF353A40.toInt()

private fun wearFr(w: CameraModels.Wear) = when (w) {
    CameraModels.Wear.LIKE_NEW -> "Comme neuf"
    CameraModels.Wear.GOOD -> "Bon état"
    CameraModels.Wear.USED -> "Usure moyenne"
    CameraModels.Wear.HIGH -> "Usure élevée"
    CameraModels.Wear.BEYOND -> "Au-delà de la durée nominale"
}

private fun wearColor(w: CameraModels.Wear) = when (w) {
    CameraModels.Wear.LIKE_NEW, CameraModels.Wear.GOOD -> OK
    CameraModels.Wear.USED -> MID
    else -> HIGH
}

/** Draws the customer report card (French, no shop branding). */
fun renderReport(context: Context, r: ReportData): Bitmap {
    val bold = ResourcesCompat.getFont(context, R.font.barlow_condensed_bold) ?: Typeface.DEFAULT_BOLD
    val semi = ResourcesCompat.getFont(context, R.font.barlow_condensed_semibold) ?: Typeface.DEFAULT_BOLD
    val regular = ResourcesCompat.getFont(context, R.font.barlow_semicondensed_regular) ?: Typeface.DEFAULT
    val medium = ResourcesCompat.getFont(context, R.font.barlow_semicondensed_medium) ?: Typeface.DEFAULT

    fun paint(tf: Typeface, size: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf; textSize = size; this.color = color
    }

    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(BG)
    val inner = W - 2 * PAD
    var y = 128f

    // heading
    c.drawText("Rapport de déclenchements", PAD, y, paint(medium, 38f, MUTED))
    y += 92f
    val title = paint(bold, 84f, TEXT)
    while (title.measureText(r.camera) > inner && title.textSize > 48f) title.textSize -= 2f
    c.drawText(r.camera, PAD, y, title)
    if (!r.serial.isNullOrBlank()) {
        y += 56f
        c.drawText("N° de série ${r.serial}", PAD, y, paint(regular, 36f, MUTED))
    }

    // odometer
    y += 56f
    val digits = r.count.toString()
    val wheels = max(6, digits.length)
    val groups = (wheels - 1) / 3
    val gap = 12f
    val groupGap = 28f
    val wheelW = (inner - gap * (wheels - 1 - groups) - groupGap * groups) / wheels
    val wheelH = 220f
    val digitPaint = paint(bold, 176f, TEXT).apply { textAlign = Paint.Align.CENTER }
    val padded = digits.padStart(wheels, '0')
    var x = PAD
    for (i in 0 until wheels) {
        val rect = RectF(x, y, x + wheelW, y + wheelH)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(0xFF15181B.toInt(), 0xFF30353B.toInt(), 0xFF3B4046.toInt(), 0xFF30353B.toInt(), 0xFF15181B.toInt()),
                floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRoundRect(rect, 14f, 14f, fill)
        digitPaint.color = if (i < wheels - digits.length) DIM_DIGIT else TEXT
        val baseline = rect.centerY() - (digitPaint.descent() + digitPaint.ascent()) / 2
        c.drawText(padded[i].toString(), rect.centerX(), baseline, digitPaint)
        val remaining = wheels - 1 - i
        x += wheelW + if (remaining > 0 && remaining % 3 == 0) groupGap else gap
    }
    y += wheelH + 58f
    c.drawText("déclenchements", PAD, y, paint(regular, 38f, MUTED))

    // wear scale
    val rated = r.ratedLife
    if (rated != null) {
        val wear = CameraModels.wear(r.count, rated)
        val fraction = (r.count.toFloat() / rated).coerceIn(0f, 1f)
        y += 64f
        val ticks = 40
        val tickW = 4f
        val major = 44f
        val minor = 24f
        val base = y + major
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (t in 0..ticks) {
            val tx = PAD + t * (inner - tickW) / ticks
            val h = if (t % 10 == 0) major else minor
            tickPaint.color = if (r.count > 0 && t.toFloat() / ticks <= fraction) wearColor(wear) else TICK
            c.drawRoundRect(RectF(tx, base - h, tx + tickW, base), 2f, 2f, tickPaint)
        }
        val mx = PAD + fraction * (inner - tickW) + tickW / 2
        val tri = Path().apply {
            moveTo(mx, base + 8f); lineTo(mx - 16f, base + 30f); lineTo(mx + 16f, base + 30f); close()
        }
        c.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SIGNAL })
        y = base + 74f
        val small = paint(regular, 30f, MUTED)
        c.drawText("0", PAD, y, small)
        small.textAlign = Paint.Align.CENTER
        c.drawText(formatCount(rated / 2L), PAD + inner / 2, y, small)
        small.textAlign = Paint.Align.RIGHT
        c.drawText("${formatCount(rated.toLong())} " + if (r.ratedOfficial) "(nominale)" else "(estimée)", PAD + inner, y, small)

        y += 96f
        val verdict = paint(semi, 64f, TEXT)
        val label = wearFr(wear)
        c.drawText(label, PAD, y, verdict)
        c.drawText(
            (if (wearPercent(r.count, rated) == 0 && r.count > 0) "moins de 1 %" else "${wearPercent(r.count, rated)} %") + if (r.ratedOfficial) " de la durée de vie nominale" else " de la durée de vie estimée",
            PAD + verdict.measureText(label) + 24f, y - 6f, paint(regular, 36f, MUTED),
        )
    }

    // details, anchored to the bottom
    val rows = buildList {
        add("Méthode" to r.method)
        add("Date du contrôle" to formatDate(r.time))
    }
    val notes = buildList {
        if (r.includesElectronic) add("Le compteur inclut les déclenchements en obturateur électronique.")
        if (r.experimental) add("Lecture expérimentale.")
    }
    val labelPaint = paint(regular, 32f, MUTED)
    val valuePaint = paint(medium, 34f, TEXT)
    val notePaint = paint(regular, 30f, MUTED)
    val noteLayouts = notes.map {
        StaticLayout.Builder.obtain(it, 0, it.length, notePaint, inner.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }
    val rowH = 72f
    var by = H - PAD - noteLayouts.sumOf { it.height + 12 } - rows.size * rowH
    val rule = Paint().apply { color = RULE; strokeWidth = 2f }
    for ((k, v) in rows) {
        c.drawLine(PAD, by, PAD + inner, by, rule)
        c.drawText(k, PAD, by + 48f, labelPaint)
        valuePaint.textAlign = Paint.Align.RIGHT
        c.drawText(v, PAD + inner, by + 48f, valuePaint)
        by += rowH
    }
    by += 12f
    for (l in noteLayouts) {
        c.save(); c.translate(PAD, by); l.draw(c); c.restore()
        by += l.height + 12
    }
    return bmp
}

/** Renders the report and opens the share sheet with the image. */
fun shareReport(context: Context, r: ReportData) {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val safe = r.camera.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
    val file = File(dir, "rapport-$safe-${r.count}.png")
    file.outputStream().use { renderReport(context, r).compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Partager le rapport"))
}
